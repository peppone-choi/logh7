"""Readiness errors must never authorize offline state replacement. Author: Peppone Choi."""
import argparse
import sys
import tempfile
import threading
import unittest
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state_backup
import server_release


class ServerLivenessTest(unittest.TestCase):
    def test_http_errors_still_block_backup_restore_and_release_before_any_file_is_read(self):
        class Health(BaseHTTPRequestHandler):
            status = 503

            def do_GET(self):
                self.send_response(self.status)
                self.end_headers()

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Health) as server:
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                url = f"http://127.0.0.1:{server.server_port}/health"
                for status in (200, 204, 401, 503):
                    Health.status = status
                    with self.subTest(status=status):
                        self.assertTrue(state_backup.server_running(url))
                with tempfile.TemporaryDirectory() as folder:
                    root = Path(folder)
                    args = argparse.Namespace(health=url)
                    for action in (state_backup.backup, state_backup.restore):
                        with self.assertRaisesRegex(SystemExit, "confirm it is stopped"):
                            action(args)
                    with self.assertRaisesRegex(SystemExit, "confirm it is stopped"):
                        server_release.switch(root, "candidate", url)
                    self.assertEqual([], list(root.iterdir()))
            finally:
                server.shutdown()
                worker.join()
        self.assertFalse(state_backup.server_running(url))

    def test_only_explicit_connection_refusal_allows_offline_operations(self):
        for reason, running in ((ConnectionRefusedError(), False), (TimeoutError(), True),
                                (OSError("name lookup failed"), True)):
            with self.subTest(reason=type(reason).__name__):
                with patch.object(state_backup.urllib.request, "urlopen", side_effect=urllib.error.URLError(reason)):
                    self.assertEqual(running, state_backup.server_running("http://127.0.0.1:1/health"))
        self.assertFalse(state_backup.server_running(None))
        self.assertFalse(state_backup.server_running(""))


if __name__ == "__main__":
    unittest.main()
