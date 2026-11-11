"""Run the release launcher against a temporary HTTPS patch server. Author: Peppone Choi."""
import hashlib
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def certificates(root):
    openssl = shutil.which("openssl") or Path(os.environ["ProgramFiles"]) / "Git/usr/bin/openssl.exe"

    def run(*args):
        subprocess.run([openssl, *args], cwd=root, check=True, capture_output=True)

    run("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
        "-subj", "/CN=LOGH7 temporary test CA", "-addext", "basicConstraints=critical,CA:TRUE",
        "-keyout", "ca.key", "-out", "ca.pem")
    run("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
        "-keyout", "leaf.key", "-out", "leaf.csr")
    (root / "leaf.ext").write_text("subjectAltName=DNS:localhost\nextendedKeyUsage=serverAuth\n")
    run("x509", "-req", "-in", "leaf.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
        "-CAcreateserial", "-days", "1", "-extfile", "leaf.ext", "-out", "leaf.pem")
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(root / "leaf.pem", root / "leaf.key")
    return context


@contextmanager
def serve(reply, context=None):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            reply(self)

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
        if context is not None:
            server.socket = context.wrap_socket(server.socket, server_side=True)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            worker.join()


def exercise(launcher, root):
    payload = {"exe/a.dll": b"first", "data/b.dat": b"second"}

    def digest(data):
        return hashlib.sha256(data).hexdigest()

    manifest = ("LOGH7-PATCH-1\nclient\t" + digest(b"fixture") + "\n" + "".join(
        f"file\t{name}\t{digest(data)}\n" for name, data in payload.items())).encode()
    requests = []
    insecure = []
    context = certificates(root)
    interrupt = True
    paused = threading.Event()
    release = threading.Event()

    def response(request, status, data=b"", length=None, headers=()):
        request.send_response(status)
        request.send_header("Content-Length", str(len(data) if length is None else length))
        for name, value in headers:
            request.send_header(name, value)
        request.end_headers()
        request.wfile.write(data)

    def plaintext(request):
        insecure.append(request.path)
        response(request, 200, manifest)

    def secure(request):
        requests.append(request.path)
        if request.path == "/redirect/patch.manifest":
            response(request, 302, headers=(("Location", f"http://127.0.0.1:{http_port}/patch.manifest"),))
        elif request.path == "/paused/data/b.dat":
            paused.set()
            release.wait(30)
        elif request.path in ("/patch/patch.manifest", "/paused/patch.manifest"):
            response(request, 200, manifest)
        elif request.path == "/patch/data/b.dat" and interrupt:
            response(request, 200, b"sec", length=len(payload["data/b.dat"]))
        else:
            response(request, 200, payload[request.path.split("/", 2)[2]])

    env = {key: value for key, value in os.environ.items()
           if key.upper() not in {"SSL_CERT_FILE", "SSL_CERT_DIR", "ALL_PROXY", "HTTP_PROXY", "HTTPS_PROXY"}}
    env["NO_PROXY"] = "localhost,127.0.0.1"

    def download(url, output, trust=True):
        return subprocess.run([launcher, "--download-patch", url, output, "--expect-manifest", digest(manifest)],
                              env={**env, "SSL_CERT_FILE": str(root / "ca.pem")} if trust else env,
                              capture_output=True, encoding="utf-8", timeout=15)

    with serve(plaintext) as http_port, serve(secure, context) as https_port:
        base = f"https://localhost:{https_port}"
        out = root / "patch.1"
        other = root / "patch.2"
        legacy = root / "patch.partial"
        legacy.mkdir()
        (legacy / "keep").write_bytes(b"previous launcher data")
        assert download(base + "/patch", out, trust=False).returncode != 0
        mismatch = download(f"https://127.0.0.1:{https_port}/patch", out)
        assert mismatch.returncode != 0 and "HTTPS 인증 또는 암호화 연결 실패" in mismatch.stderr, mismatch
        assert "127.0.0.1" not in mismatch.stderr, mismatch
        assert not requests and not out.exists(), requests
        redirected = download(base + "/redirect", out)
        assert redirected.returncode != 0 and not insecure, redirected.stderr
        assert requests == ["/redirect/patch.manifest"] and not out.exists(), requests
        for suffix in (".PARTIAL", ".partial-next"):
            reserved = out.with_name(out.name + suffix)
            result = download(base + "/patch", reserved)
            assert result.returncode == 1 and "임시 디렉터리와 충돌" in result.stderr, result
            assert not reserved.exists()
        assert requests == ["/redirect/patch.manifest"], requests

        result = download(base + "/patch/", out)
        assert result.returncode != 0 and "패치 파일 받기: HTTPS 패치 응답 본문" in result.stderr, result
        partial = out.with_name(out.name + ".partial")
        assert (legacy / "keep").read_bytes() == b"previous launcher data"
        assert not out.exists() and (partial / "exe/a.dll").read_bytes() == payload["exe/a.dll"]
        assert not (partial / "data/b.dat").exists()
        interrupt = False
        requests.clear()
        result = download(base + "/patch", other)
        assert result.returncode == 0, result.stderr
        assert requests == ["/patch/patch.manifest", "/patch/exe/a.dll", "/patch/data/b.dat"], requests
        assert (partial / "exe/a.dll").read_bytes() == payload["exe/a.dll"]
        assert all((other / name).read_bytes() == data for name, data in payload.items())
        assert (legacy / "keep").read_bytes() == b"previous launcher data"
        requests.clear()
        result = download(base + "/patch", out)
        assert result.returncode == 0, result.stderr
        assert requests == ["/patch/patch.manifest", "/patch/data/b.dat"], requests
        assert all((out / name).read_bytes() == data for name, data in payload.items())
        assert not partial.exists()

        if os.name == "nt":
            out = root / "nested" / "concurrent"
            partial = out.with_name(out.name + ".partial")
            lock = out.with_name(out.name + ".partial-lock")
            args = [launcher, "--download-patch", base + "/paused", out, "--expect-manifest", digest(manifest)]
            with subprocess.Popen(args, env={**env, "SSL_CERT_FILE": str(root / "ca.pem")},
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE) as first:
                try:
                    assert paused.wait(15), "first download did not reach the held file"
                    requests.clear()
                    blocked = download(base + "/patch", out)
                    assert blocked.returncode == 1 and "다운로드가 진행 중" in blocked.stderr, blocked
                    assert requests == ["/patch/patch.manifest"], requests
                    assert not out.exists()
                    assert (partial / "exe/a.dll").read_bytes() == payload["exe/a.dll"]
                finally:
                    first.kill()
                    first.communicate(timeout=10)
                    release.set()
            assert not lock.exists()
            requests.clear()
            result = download(base + "/patch", out)
            assert result.returncode == 0, result.stderr
            assert requests == ["/patch/patch.manifest", "/patch/data/b.dat"], requests
            assert all((out / name).read_bytes() == data for name, data in payload.items())
            assert not partial.exists() and not lock.exists()


def safe_failure_output(launcher, root):
    secret = "sensitive-fixture"
    out = root / secret
    out.mkdir()

    def fail(args, reason):
        result = subprocess.run([launcher, *args], capture_output=True, encoding="utf-8", timeout=15)
        assert result.returncode == 1 and reason in result.stderr, result
        assert secret not in result.stdout + result.stderr, result

    fail(["--download-patch", f"https://localhost/{secret}", out], "받을 디렉터리가 이미 있습니다")

    def rejected(request):
        request.send_response(403, secret)
        request.send_header("Content-Length", "0")
        request.end_headers()

    with serve(rejected) as port:
        fail(["--download-patch", f"http://127.0.0.1:{port}/{secret}", root / "unused"],
             "패치 목록 받기: 패치 서버 응답 HTTP 403")

    client = root / secret
    config = root / "launcher.ini"
    config.write_text(f"host=127.0.0.1\nport=47900\naccount=fixture\nclient_root={client.name}\n", encoding="utf-8")
    (out / "patch.manifest").write_text(f"LOGH7-PATCH-1\ninvalid {secret}\n", encoding="utf-8")
    fail(["--config", config, "--apply-patch", out], "패치 적용: 패치 목록 항목 형식")
    (client / "logh7-patch.state").write_text(f"LOGH7-PATCH-STATE-1\ninvalid {secret}\n", encoding="utf-8")
    fail(["--config", config, "--revert-patch"], "패치 되돌리기: 패치 상태 항목 형식")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="logh7-https-") as folder:
        launcher = Path(sys.argv[1]).resolve()
        root = Path(folder)
        exercise(launcher, root)
        safe_failure_output(launcher, root)
    print("HTTPS trust, hostname, redirect refusal, versioned download resume, and safe failure output passed")
