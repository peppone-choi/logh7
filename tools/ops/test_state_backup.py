"""Checks for state_backup.py. Author: Peppone Choi."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import state_backup  # noqa: E402


class StateBackupTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.state = root / "state"
        self.world = self.state / "world"
        self.session = self.state / "session"
        self.characters = self.state / "characters.properties"
        (self.world / "slot-1").mkdir(parents=True)
        self.session.mkdir()
        (self.world / "CURRENT").write_text("LOGH7-WORLD-1\t1\t1\n")
        (self.world / "slot-1" / "stocks.tsv").write_text("stocks\n")
        (self.world / "slot-1" / "stocks.tsv.lock").write_text("")
        (self.session / "session.journal").write_bytes(b"\x00journal")
        (self.session / "session.lock").write_text("")
        self.characters.write_text("characters\n")
        self.owner = Path(f"{self.characters}.navigation.world-owner")
        self.owner.write_text(f"LOGH7-WORLD-OWNER-1\t{self.world.resolve()}\n")
        self.archive = root / "backup"

    def tearDown(self):
        self.temp.cleanup()

    def run_tool(self, *args):
        state_backup.main(["--health", "", *args])

    def take(self):
        self.run_tool("backup", "--characters", str(self.characters), "--world", str(self.world),
                      "--session", str(self.session), "--out", str(self.archive))

    def test_backup_skips_locks_and_restore_needs_force_and_keeps_replaced_state(self):
        self.take()
        names = sorted(p.relative_to(self.archive).as_posix() for p in self.archive.rglob("*") if p.is_file())
        self.assertEqual(["characters/characters.properties", "characters/characters.properties.navigation.world-owner",
                          "manifest.json", "session/session.journal", "world/CURRENT", "world/slot-1/stocks.tsv"], names)
        (self.world / "slot-1" / "stocks.tsv").write_text("changed\n")
        with self.assertRaises(SystemExit):
            self.run_tool("restore", str(self.archive))
        self.run_tool("restore", str(self.archive), "--force")
        self.assertEqual("stocks\n", (self.world / "slot-1" / "stocks.tsv").read_text())
        replaced = next(self.archive.parent.glob("backup.replaced-*"))
        self.assertEqual("changed\n", (replaced / "world" / "slot-1" / "stocks.tsv").read_text())

    def test_damaged_backup_and_foreign_world_owner_are_refused(self):
        self.take()
        (self.archive / "world" / "CURRENT").write_text("tampered\n")
        with self.assertRaises(SystemExit):
            self.run_tool("verify", str(self.archive))
        self.owner.write_text("LOGH7-WORLD-OWNER-1\tE:\\elsewhere\\world\n")
        with self.assertRaises(SystemExit):
            self.run_tool("backup", "--characters", str(self.characters), "--world", str(self.world),
                          "--session", str(self.session), "--out", str(self.archive) + "2")

    def test_export_verifies_the_copy_and_prune_keeps_the_newest(self):
        self.take()
        remote = self.archive.parent / "remote"
        remote.mkdir()
        self.run_tool("export", str(self.archive), "--dest", str(remote))
        self.run_tool("verify", str(remote / "backup"))
        with self.assertRaises(SystemExit):
            self.run_tool("export", str(self.archive), "--dest", str(remote))
        root = self.archive.parent / "daily"
        root.mkdir()
        for name in ("a", "b", "c"):
            self.run_tool("backup", "--characters", str(self.characters), "--world", str(self.world),
                          "--session", str(self.session), "--out", str(root / name))
        (root / "unrelated").mkdir()
        self.run_tool("prune", str(root), "--keep", "2")
        self.assertEqual(["b", "c", "unrelated"], sorted(p.name for p in root.iterdir()))

    def test_restore_to_a_clean_instance_rebinds_the_owner_and_leaves_the_original_alone(self):
        self.take()
        before = {p: p.read_bytes() for p in self.state.rglob("*") if p.is_file()}
        clean = self.archive.parent / "clean"
        self.run_tool("restore-to", str(self.archive), "--to", str(clean))
        self.assertEqual(before, {p: p.read_bytes() for p in self.state.rglob("*") if p.is_file()})
        self.assertEqual("stocks\n", (clean / "world" / "slot-1" / "stocks.tsv").read_text())
        self.assertEqual(b"\x00journal", (clean / "session" / "session.journal").read_bytes())
        self.assertFalse((clean / "world" / "slot-1" / "stocks.tsv.lock").exists())
        marker = clean / "characters" / "characters.properties.navigation.world-owner"
        # Exact bytes: the server trims only a trailing LF, so a CRLF would corrupt the path.
        self.assertEqual(f"LOGH7-WORLD-OWNER-1\t{(clean / 'world').resolve()}\n".encode("utf-8"), marker.read_bytes())
        # A non-empty target is refused (only a clean instance), and so is a damaged archive.
        with self.assertRaises(SystemExit):
            self.run_tool("restore-to", str(self.archive), "--to", str(clean))
        (self.archive / "session" / "session.journal").write_bytes(b"\x01journal")
        with self.assertRaises(SystemExit):
            self.run_tool("restore-to", str(self.archive), "--to", str(self.archive.parent / "clean2"))
        self.assertFalse((self.archive.parent / "clean2").exists())

    def test_accounts_file_is_backed_up_restored_in_place_and_into_a_clean_instance(self):
        accounts = self.state.parent / "accounts.properties"
        accounts.write_text("qa1=pbkdf2-sha256:1:aa:bb\n")
        self.run_tool("backup", "--characters", str(self.characters), "--world", str(self.world),
                      "--session", str(self.session), "--accounts", str(accounts), "--out", str(self.archive))
        self.assertTrue((self.archive / "accounts" / "accounts.properties").is_file())
        accounts.write_text("qa1=changed\n")
        self.run_tool("restore", str(self.archive), "--force")
        self.assertEqual("qa1=pbkdf2-sha256:1:aa:bb\n", accounts.read_text())
        clean = self.archive.parent / "clean-accounts"
        self.run_tool("restore-to", str(self.archive), "--to", str(clean))
        self.assertEqual("qa1=pbkdf2-sha256:1:aa:bb\n", (clean / "accounts" / "accounts.properties").read_text())

    def test_running_server_is_refused(self):
        original = state_backup.server_running
        state_backup.server_running = lambda url: True
        try:
            with self.assertRaises(SystemExit):
                self.take()
        finally:
            state_backup.server_running = original


if __name__ == "__main__":
    unittest.main()
