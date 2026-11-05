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

    def test_rollback_to_a_release_that_cannot_read_v2_characters_is_refused(self):
        import zipfile
        with zipfile.ZipFile(self.build / "lib" / "gateway.jar", "w") as jar:
            jar.writestr("org/logh7/gateway/CharacterCreation.class", b"")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "gateway.jar", "w") as jar:
            jar.writestr("org/logh7/gateway/CharacterProfile.class", b"")
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        characters = Path(self.temp.name) / "characters.properties"
        characters.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        self.tool("activate", "old", "--releases", str(self.releases), "--characters", str(characters))
        self.tool("activate", "new", "--releases", str(self.releases), "--characters", str(characters))
        characters.write_text("#x\nmulti0=v2\\:generated\\:3\\:alliance\\:1008\n", encoding="latin-1")
        with self.assertRaises(SystemExit) as refused:
            self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        self.assertIn("character profile v2", str(refused.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())
        # A later session generation is stored beside the base file and is checked too.
        characters.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        later = characters.with_name(characters.name + ".session-2")
        later.write_text("#x\nmulti0=v2\\:generated\\:3\\:alliance\\:1008\n", encoding="latin-1")
        characters.with_name(characters.name + ".session-generation").write_text("2\n", encoding="latin-1")
        with self.assertRaises(SystemExit):
            self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        # After restoring the pre-upgrade characters the same rollback goes through.
        later.write_text("#x\nmulti0=1008040000\n", encoding="latin-1")
        self.tool("rollback", "--releases", str(self.releases), "--characters", str(characters))
        self.assertEqual("old", (self.releases / "CURRENT").read_text().strip())

    def test_store_header_must_be_an_exact_constant_of_the_target_reader(self):
        import zipfile

        def constant(text):
            data = text.encode()
            return b"\x01" + len(data).to_bytes(2, "big") + data

        reader = "org/logh7/persistence/FilePlanetStockStore.class"
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("LOGH7-STOCK-4") + constant("LOGH7-STOCK-41"))
            jar.writestr("org/logh7/persistence/FileWorldStateStore.class", b"\xca\xfe" + constant("LOGH7-WORLD-1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "old")
        with zipfile.ZipFile(self.build / "lib" / "persistence.jar", "w") as jar:
            jar.writestr(reader, b"\xca\xfe" + constant("LOGH7-STOCK-41") + constant("LOGH7-STOCK-42"))
            jar.writestr("org/logh7/persistence/FileWorldStateStore.class", b"\xca\xfe" + constant("LOGH7-WORLD-1"))
        self.tool("install", str(self.build), "--releases", str(self.releases), "--name", "new")
        world = Path(self.temp.name) / "world"
        (world / "slot-0").mkdir(parents=True)
        (world / "slot-1").mkdir()
        (world / "CURRENT").write_text("LOGH7-WORLD-1\t0\t1\n", encoding="latin-1")
        # A newer file in the inactive slot (a failed update before CURRENT moved) does not block.
        (world / "slot-1" / "stocks.tsv").write_text("LOGH7-STOCK-99\t1\t2\n", encoding="latin-1")
        stocks = world / "slot-0" / "stocks.tsv"
        stocks.write_text("LOGH7-STOCK-41\t1\t2\n", encoding="latin-1")
        self.tool("activate", "old", "--releases", str(self.releases), "--world", str(world))
        stocks.write_text("LOGH7-STOCK-42\t1\t2\n", encoding="latin-1")
        self.tool("activate", "new", "--releases", str(self.releases), "--world", str(world))
        # "LOGH7-STOCK-4" is a prefix of the new header, but only an exact constant counts.
        with self.assertRaises(SystemExit) as refused:
            self.tool("rollback", "--releases", str(self.releases), "--world", str(world))
        self.assertIn("LOGH7-STOCK-42", str(refused.exception))
        self.assertEqual("new", (self.releases / "CURRENT").read_text().strip())

if __name__ == "__main__":
    unittest.main()
