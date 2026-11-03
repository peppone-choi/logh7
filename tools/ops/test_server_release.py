"""Checks for server_release.py. Author: Peppone Choi."""
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import server_release  # noqa: E402


class ServerReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.build = root / "install" / "app"
        (self.build / "lib").mkdir(parents=True)
        (self.build / "bin").mkdir()
        (self.build / "lib" / "app.jar").write_bytes(b"v1")
        self.releases = root / "releases"

    def tearDown(self):
        self.temp.cleanup()

    def tool(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            server_release.main(["--health", "", *args])
        return out.getvalue().strip()

    def test_activate_rollback_and_classpath(self):
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        (self.build / "lib" / "app.jar").write_bytes(b"v2")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "b")
        self.tool("activate", "a", "--releases", str(self.releases))
        self.tool("activate", "b", "--releases", str(self.releases))
        self.assertTrue(self.tool("classpath", "--releases", str(self.releases)).endswith("/b/lib/*"))
        self.tool("rollback", "--releases", str(self.releases))
        self.assertTrue(self.tool("classpath", "--releases", str(self.releases)).endswith("/a/lib/*"))
        self.assertEqual("b", (self.releases / "PREVIOUS").read_text().strip())

    def test_damaged_release_duplicate_name_and_running_server_are_refused(self):
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        with self.assertRaises(SystemExit):
            self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "a")
        (self.releases / "a" / "lib" / "app.jar").write_bytes(b"tampered")
        with self.assertRaises(SystemExit):
            self.tool("activate", "a", "--releases", str(self.releases))
        self.assertFalse((self.releases / "CURRENT").exists())
        original = server_release.server_running
        server_release.server_running = lambda _: True
        try:
            with self.assertRaises(SystemExit):
                self.tool("rollback", "--releases", str(self.releases))
        finally:
            server_release.server_running = original


if __name__ == "__main__":
    unittest.main()
