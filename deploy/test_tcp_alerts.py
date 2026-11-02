#!/usr/bin/env python3
"""Run real loopback TCP probes and unchanged alert rules; no game protocol or delivery claim.
작성: Peppone Choi
"""
import argparse
import hashlib
import json
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


class TcpListener:
    def __init__(self):
        self.port = 0
        self.accepted = 0
        self.running = threading.Event()
        self.start()

    def start(self):
        self.socket = socket.socket()
        self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.socket.bind(("127.0.0.1", self.port))
        self.port = self.socket.getsockname()[1]
        self.socket.listen(16)
        self.socket.settimeout(.25)
        self.running.set()
        self.thread = threading.Thread(target=self.accept, daemon=True)
        self.thread.start()

    def accept(self):
        while self.running.is_set():
            try:
                connection, _ = self.socket.accept()
                connection.close()
                self.accepted += 1
            except socket.timeout:
                pass
            except OSError:
                if self.running.is_set():
                    raise

    def close(self):
        if not self.running.is_set():
            return
        self.running.clear()
        self.socket.close()
        self.thread.join(3)
        if self.thread.is_alive():
            raise RuntimeError("Owned TCP fixture did not stop")


def unused_port():
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        return reservation.getsockname()[1]


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(5)


def binary(value):
    resolved = shutil.which(value)
    if not resolved:
        raise ValueError(f"Executable not found: {value}")
    return str(Path(resolved).resolve())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prometheus", default="prometheus")
    parser.add_argument("--promtool", default="promtool")
    parser.add_argument("--blackbox-exporter", default="blackbox_exporter")
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    prometheus, promtool, exporter = map(binary, (args.prometheus, args.promtool, args.blackbox_exporter))
    deploy = Path(__file__).resolve().parent
    # Validate source files before starting any owned process.
    subprocess.run([promtool, "check", "config", str(deploy / "prometheus/prometheus-tcp.yml")], check=True)
    subprocess.run([promtool, "test", "rules", "tcp-alerts.test.yml"], cwd=deploy / "prometheus", check=True)
    if args.report_dir:
        root = args.report_dir.resolve()
        root.mkdir(parents=True, exist_ok=False)
    else:
        root = Path(tempfile.mkdtemp(prefix="logh7-tcp-alerts."))
    report = {"status": "RUNNING", "scope": "LOOPBACK_TCP_ALERT_EVALUATION",
              "phases": [], "protocolCompatibility": "NOT_TESTED", "notificationDelivery": "NOT_CONFIGURED"}
    processes, logs, listeners = [], [], {}
    start = time.monotonic()
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def spawn(command, name):
        log = (root / f"{name}-{len(processes)}.log").open("wb")
        logs.append(log)
        process = subprocess.Popen(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        return process

    def request(base, path):
        with http.open(base + path, timeout=3) as response:
            return json.load(response)

    def check_alive():
        if prom.poll() is not None or blackbox.poll() is not None:
            raise RuntimeError("Owned monitor exited; inspect private logs in the report directory")

    def snapshot():
        state = {"alerts": [], "up": {}, "probe": {}}
        for key, expression in [("up", 'up{job="logh7-tcp"}'), ("probe", 'probe_success{job="logh7-tcp"}')]:
            result = request(prom_url, "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
            if result["status"] != "success" or result["data"]["resultType"] != "vector":
                raise RuntimeError("Unexpected Prometheus query response")
            for series in result["data"]["result"]:
                endpoint = series["metric"]["endpoint"]
                if endpoint not in listeners or series["metric"]["instance"] != f"127.0.0.1:{listeners[endpoint].port}":
                    raise RuntimeError("Unexpected probe target/labels")
                state[key][endpoint] = float(series["value"][1])
        result = request(prom_url, "/api/v1/alerts")
        if result["status"] != "success":
            raise RuntimeError("Unexpected Prometheus alerts response")
        state["alerts"] = [a for a in result["data"]["alerts"] if a["labels"].get("job") == "logh7-tcp"]
        return state

    def wait_phase(name, predicate, timeout=105, monitor_required=True):
        deadline = time.monotonic() + timeout
        latest = None
        while time.monotonic() < deadline:
            if monitor_required:
                check_alive()
            elif prom.poll() is not None:
                raise RuntimeError("Owned Prometheus exited")
            latest = snapshot()
            if predicate(latest):
                report["phases"].append({"name": name, "elapsedSeconds": round(time.monotonic() - start, 3), **latest})
                print(f"{name}: PASS", flush=True)
                return
            time.sleep(.5)
        report["lastObservation"] = latest
        raise TimeoutError(f"Timed out waiting for {name}")

    def matches(state, alert, phase, endpoints):
        observed = {(a["labels"]["alertname"], a["state"], a["labels"]["endpoint"]) for a in state["alerts"]}
        return observed == {(alert, phase, endpoint) for endpoint in endpoints}

    def healthy(state):
        return state["up"] == state["probe"] == {endpoint: 1.0 for endpoint in listeners} and not state["alerts"]

    try:
        for endpoint in ("login", "update", "session"):
            listeners[endpoint] = TcpListener()
        exporter_port, prometheus_port = unused_port(), unused_port()
        if exporter_port == prometheus_port:
            raise RuntimeError("Port reservation raced; rerun with a fresh report directory")
        rules = (deploy / "prometheus/tcp-alerts.yml").read_bytes()
        (root / "tcp-alerts.yml").write_bytes(rules)
        report["rulesSha256"] = hashlib.sha256(rules).hexdigest()
        config = (deploy / "prometheus/prometheus-tcp.yml").read_text()
        for endpoint, original in [("login", 47900), ("update", 47902), ("session", 47903)]:
            old = f"127.0.0.1:{original}"
            if config.count(old) != 1:
                raise RuntimeError("Source target contract changed")
            config = config.replace(old, f"127.0.0.1:{listeners[endpoint].port}")
        if config.count("127.0.0.1:9115") != 1:
            raise RuntimeError("Source exporter contract changed")
        config = config.replace("127.0.0.1:9115", f"127.0.0.1:{exporter_port}")
        (root / "prometheus.yml").write_text(config)
        subprocess.run([promtool, "check", "config", str(root / "prometheus.yml")], check=True)
        exporter_command = [exporter, "--config.file=" + str(deploy / "blackbox/tcp.yml"),
                            f"--web.listen-address=127.0.0.1:{exporter_port}"]
        blackbox = spawn(exporter_command, "blackbox")
        prom = spawn([prometheus, "--config.file=" + str(root / "prometheus.yml"),
                      f"--web.listen-address=127.0.0.1:{prometheus_port}",
                      "--storage.tsdb.path=" + str(root / "data"),
                      "--storage.tsdb.retention.time=1h", "--storage.tsdb.retention.size=32MB",
                      "--log.level=warn"], "prometheus")
        prom_url = f"http://127.0.0.1:{prometheus_port}"
        # Bound startup retries; no reload/admin API or existing daemon is used.
        deadline = time.monotonic() + 15
        while True:
            check_alive()
            try:
                snapshot()
                break
            except (urllib.error.URLError, OSError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.25)
        wait_phase("healthy", healthy)
        listeners["session"].close()
        wait_phase("listener_pending", lambda s: s["up"] == {k: 1.0 for k in listeners}
                   and s["probe"].get("session") == 0
                   and all(s["probe"].get(k) == 1 for k in ("login", "update"))
                   and matches(s, "LOGH7TcpListenerUnavailable", "pending", {"session"}))
        wait_phase("listener_firing", lambda s: s["up"] == {k: 1.0 for k in listeners}
                   and s["probe"].get("session") == 0
                   and all(s["probe"].get(k) == 1 for k in ("login", "update"))
                   and matches(s, "LOGH7TcpListenerUnavailable", "firing", {"session"}))
        listeners["session"].start()
        wait_phase("listener_recovered", healthy)
        stop(blackbox)
        wait_phase("probe_pending", lambda s: s["up"] == {k: 0.0 for k in listeners}
                   and not s["probe"] and matches(s, "LOGH7TcpProbeUnavailable", "pending", set(listeners)),
                   monitor_required=False)
        wait_phase("probe_firing", lambda s: s["up"] == {k: 0.0 for k in listeners}
                   and not s["probe"] and matches(s, "LOGH7TcpProbeUnavailable", "firing", set(listeners)),
                   monitor_required=False)
        blackbox = spawn(exporter_command, "blackbox")
        wait_phase("probe_recovered", healthy)
        report["acceptedConnections"] = {k: fixture.accepted for k, fixture in listeners.items()}
        if not all(report["acceptedConnections"].values()):
            raise RuntimeError("Each fixture must have received real TCP connections")
        report["status"] = "COMPLETED"
    except BaseException as failure:
        report["status"] = "FAILED"
        report["error"] = type(failure).__name__ + ": " + str(failure)
        raise
    finally:
        cleanup_errors = []
        for process in reversed(processes):
            try:
                stop(process)
            except Exception as failure:
                cleanup_errors.append(type(failure).__name__)
        for fixture in listeners.values():
            try:
                fixture.close()
            except Exception as failure:
                cleanup_errors.append(type(failure).__name__)
        for log in logs:
            log.close()
        report["ownedProcessesStopped"] = all(p.poll() is not None for p in processes)
        if cleanup_errors or not report["ownedProcessesStopped"]:
            report["status"] = "FAILED"
            report["cleanupErrors"] = cleanup_errors
        (root / "result.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"Report: {root / 'result.json'}", flush=True)
        if cleanup_errors or not report["ownedProcessesStopped"]:
            raise RuntimeError("Owned test resources did not stop; see result.json")


if __name__ == "__main__":
    main()
