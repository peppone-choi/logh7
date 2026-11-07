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
        elif request.path == "/patch/patch.manifest":
            response(request, 200, manifest)
        elif request.path == "/patch/data/b.dat" and interrupt:
            response(request, 200, b"sec", length=len(payload["data/b.dat"]))
        else:
            response(request, 200, payload[request.path.removeprefix("/patch/")])

    env = {key: value for key, value in os.environ.items()
           if key.upper() not in {"SSL_CERT_FILE", "SSL_CERT_DIR", "ALL_PROXY", "HTTP_PROXY", "HTTPS_PROXY"}}
    env["NO_PROXY"] = "localhost,127.0.0.1"

    def download(url, output, trust=True):
        return subprocess.run([launcher, "--download-patch", url, output, "--expect-manifest", digest(manifest)],
                              env={**env, "SSL_CERT_FILE": str(root / "ca.pem")} if trust else env,
                              capture_output=True, encoding="utf-8", timeout=15)

    with serve(plaintext) as http_port, serve(secure, context) as https_port:
        base = f"https://localhost:{https_port}"
        out = root / "patch"
        assert download(base + "/patch", out, trust=False).returncode != 0
        assert download(f"https://127.0.0.1:{https_port}/patch", out).returncode != 0
        assert not requests and not out.exists(), requests
        redirected = download(base + "/redirect", out)
        assert redirected.returncode != 0 and not insecure, redirected.stderr
        assert requests == ["/redirect/patch.manifest"] and not out.exists(), requests

        result = download(base + "/patch/", out)
        assert result.returncode != 0 and "data/b.dat" in result.stderr, result
        partial = out.with_suffix(".partial")
        assert not out.exists() and (partial / "exe/a.dll").read_bytes() == payload["exe/a.dll"]
        assert not (partial / "data/b.dat").exists()
        interrupt = False
        requests.clear()
        result = download(base + "/patch", out)
        assert result.returncode == 0, result.stderr
        assert requests == ["/patch/patch.manifest", "/patch/data/b.dat"], requests
        assert all((out / name).read_bytes() == data for name, data in payload.items())
        assert not partial.exists()


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="logh7-https-") as folder:
        exercise(Path(sys.argv[1]).resolve(), Path(folder))
    print("HTTPS trust, hostname, redirect refusal and interrupted download resume passed")
