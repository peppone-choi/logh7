"""Checks for ops_user.py. Author: Peppone Choi."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ops_user  # noqa: E402


class OpsUserTest(unittest.TestCase):
    def test_entry_is_replaced_and_properties_escaped(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "ops-users.properties"
            ops_user.store(path, "alice", ops_user.entry("first-password", 1000, b"0" * 16))
            ops_user.store(path, "bob", ops_user.entry("second-password", 1000, b"1" * 16))
            ops_user.store(path, "alice", ops_user.entry("third-password", 1000, b"2" * 16))
            lines = path.read_text(encoding="utf-8").splitlines()
            self.assertEqual(["bob", "alice"], [line.split("=")[0] for line in lines])
            self.assertTrue(lines[1].startswith("alice=pbkdf2-sha256\:1000\:"))
            with self.assertRaises(SystemExit):
                ops_user.store(path, "bad name", "x")


if __name__ == "__main__":
    unittest.main()
