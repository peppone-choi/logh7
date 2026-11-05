#!/usr/bin/env python3
"""Game-server alert delivery with real failure injection (checklist §14 장애 감지·알림).
작성: Peppone Choi

1. promtool checks and unit-tests the unchanged deploy/prometheus/game-alerts.yml.
2. A real game server (installDist) runs with its session store on a small dedicated volume
   (the CI mounts a tmpfs). Prometheus scrapes its ops /metrics and evaluates the same rules;
   Alertmanager routes every alert to a local webhook that stands in for the operator receiver.
3. Injections and the alerts the operator receiver must get, in order:
   - the volume is small from the start      -> LOGH7StateDiskCritical
   - the volume is filled until writes fail  -> LOGH7EngineStorageFailed (engine stops committing)
   - the server process is stopped           -> LOGH7GameServerUnreachable
The report (result.json) records which alerts arrived and when. Thresholds are the production rules.
"""
import argparse
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEPLOY = Path(__file__).resolve().parent
RULES = DEPLOY / "prometheus" / "game-alerts.yml"
RULE_TESTS = DEPLOY / "prometheus" / "game-alerts.test.yml"


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Receiver:
    """The operator receiver: records every Alertmanager webhook notification."""
    def __init__(self):
        self.events: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
                for alert in body.get("alerts", []):
                    outer.events.append({"at": time.time(), "status": alert.get("status"),
                                         "alertname": alert.get("labels", {}).get("alertname"),
                                         "severity": alert.get("labels", {}).get("severity")})
                self.send_response(200)
                self.end_headers()

            def log_message(self, format, *args):  # noqa: A002 - quiet test server
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def firing(self, name: str) -> bool:
        return any(e["alertname"] == name and e["status"] == "firing" for e in self.events)


def http(url: str, timeout: float = 2) -> str:
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode("utf-8")


def wait(what: str, condition, limit: float) -> float:
    started = time.monotonic()
    while time.monotonic() - started < limit:
        try:
            if condition():
                return round(time.monotonic() - started, 1)
        except OSError:
            pass
        time.sleep(1)
    raise SystemExit(f"timed out after {limit}s waiting for {what}")


def metric(ops: int, name: str) -> float | None:
    for line in http(f"http://127.0.0.1:{ops}/metrics").splitlines():
        if line.startswith(name + " "):
            return float(line.split()[1])
    return None


def fill(volume: Path) -> int:
    """Write a filler file until the volume refuses more bytes; returns bytes written."""
    written = 0
    block = b"\0" * (1 << 20)
    with (volume / "filler.bin").open("wb", buffering=0) as filler:
        try:
            while True:
                written += filler.write(block)
        except OSError:
            pass
        try:  # top up the last partial block
            while True:
                written += filler.write(b"\0" * 4096)
        except OSError:
            pass
    return written


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prometheus", required=True)
    parser.add_argument("--promtool", required=True)
    parser.add_argument("--alertmanager", required=True)
    parser.add_argument("--server-dist", required=True, help="server/app/build/install/app")
    parser.add_argument("--java", default="java")
    parser.add_argument("--volume", required=True, help="a small dedicated (tmpfs) directory for the session store")
    parser.add_argument("--report-dir", required=True)
    args = parser.parse_args()
    report_dir = Path(args.report_dir)
    report_dir.mkdir(parents=True, exist_ok=True)
    result: dict = {"rules": str(RULES.relative_to(DEPLOY.parent))}

    subprocess.run([args.promtool, "check", "rules", str(RULES)], check=True)
    subprocess.run([args.promtool, "test", "rules", RULE_TESTS.name], check=True, cwd=RULE_TESTS.parent)
    result["promtool"] = "check+test passed"

    work = Path(tempfile.mkdtemp(prefix="logh7-game-alerts-"))
    volume = Path(args.volume)
    session = volume / "session"
    session.mkdir(parents=True, exist_ok=True)
    receiver = Receiver()
    ports = {name: free_port() for name in ("game", "ops", "update", "session", "prometheus", "alertmanager")}
    (work / "alertmanager.yml").write_text(f"""route:
  receiver: operator
  group_by: [alertname]
  group_wait: 1s
  group_interval: 2s
  repeat_interval: 1h
receivers:
  - name: operator
    webhook_configs:
      - url: http://127.0.0.1:{receiver.port}/alert
        send_resolved: true
""", encoding="utf-8")
    (work / "prometheus.yml").write_text(f"""global:
  scrape_interval: 2s
  evaluation_interval: 2s
rule_files: [{RULES}]
alerting:
  alertmanagers:
    - static_configs:
        - targets: ["127.0.0.1:{ports['alertmanager']}"]
scrape_configs:
  - job_name: game-server
    metrics_path: /metrics
    static_configs:
      - targets: ["127.0.0.1:{ports['ops']}"]
""", encoding="utf-8")
    processes = []
    logs = []

    def start(name, command, env=None):
        log = (report_dir / f"{name}.log").open("w", encoding="utf-8")
        logs.append(log)
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env)
        processes.append(process)
        return process

    try:
        start("alertmanager", [args.alertmanager, f"--config.file={work / 'alertmanager.yml'}",
                               f"--storage.path={work / 'am'}", f"--web.listen-address=127.0.0.1:{ports['alertmanager']}",
                               "--cluster.listen-address="])
        start("prometheus", [args.prometheus, f"--config.file={work / 'prometheus.yml'}",
                             f"--storage.tsdb.path={work / 'tsdb'}", f"--web.listen-address=127.0.0.1:{ports['prometheus']}"])
        env = {**os.environ, "LOGH7_GAME_PORT": str(ports["game"]), "LOGH7_OPS_PORT": str(ports["ops"]),
               "LOGH7_UPDATE_PORT": str(ports["update"]), "LOGH7_SESSION_PORT": str(ports["session"]),
               "LOGH7_BIND_ADDRESS": "127.0.0.1", "LOGH7_OPS_BIND": "127.0.0.1",
               "LOGH7_SESSION_STORE_DIR": str(session)}
        server = start("game-server", [args.java, "-Xmx256m", "-cp", str(Path(args.server_dist) / "lib" / "*"),
                                       "org.logh7.app.MainKt"], env)
        ops = ports["ops"]
        result["server_up_seconds"] = wait("engine_up 1", lambda: metric(ops, "logh7_engine_up") == 1, 90)
        result["volume_free_bytes"] = metric(ops, "logh7_state_disk_free_bytes")

        result["disk_critical_seconds"] = wait("LOGH7StateDiskCritical at the operator receiver",
                                               lambda: receiver.firing("LOGH7StateDiskCritical"), 180)

        result["filled_bytes"] = fill(volume)
        result["engine_down_seconds"] = wait("engine_up 0 after the volume filled",
                                             lambda: metric(ops, "logh7_engine_up") == 0, 120)
        result["storage_failed_seconds"] = wait("LOGH7EngineStorageFailed at the operator receiver",
                                                lambda: receiver.firing("LOGH7EngineStorageFailed"), 180)

        server.terminate()
        server.wait(timeout=30)
        result["unreachable_seconds"] = wait("LOGH7GameServerUnreachable at the operator receiver",
                                             lambda: receiver.firing("LOGH7GameServerUnreachable"), 180)
        result["received"] = sorted({e["alertname"] for e in receiver.events if e["status"] == "firing"})
        result["ok"] = True
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
        for log in logs:
            log.close()
        filler = volume / "filler.bin"
        if filler.exists():
            filler.unlink()
        result["events"] = receiver.events
        (report_dir / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
        shutil.rmtree(work, ignore_errors=True)
    print(json.dumps({k: v for k, v in result.items() if k != "events"}, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    sys.exit(main())
