"""Checks for make_patch.py. Author: Peppone Choi."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_patch  # noqa: E402
from state_backup import sha256  # noqa: E402


class MakePatchTest(unittest.TestCase):
    def test_packs_new_and_changed_files_but_not_client_logs_or_settings(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            original, prepared, out = root / "original", root / "prepared", root / "patch"
            for base in (original, prepared):
                (base / "exe").mkdir(parents=True)
                (base / "exe" / "G7MTClient.exe").write_bytes(b"client")
                (base / "data").mkdir()
                (base / "data" / "same.tga").write_bytes(b"same")
            (original / "data" / "title.tga").write_bytes(b"japanese")
            (prepared / "data" / "title.tga").write_bytes(b"korean")
            (prepared / "exe" / "dinput8.dll").write_bytes(b"dll")
            (prepared / "exe" / "dxwrapper.log").write_text("log")
            (prepared / "GraphicConfig.txt").write_text("local")
            (prepared / "logh7-patch.lock").touch()
            make_patch.main(["--original", str(original), "--prepared", str(prepared), "--out", str(out)])
            lines = (out / "patch.manifest").read_text(encoding="utf-8").splitlines()
            self.assertEqual(["LOGH7-PATCH-1", f"client\t{sha256(original / 'exe' / 'G7MTClient.exe')}",
                              f"file\tdata/title.tga\t{sha256(prepared / 'data' / 'title.tga')}",
                              f"file\texe/dinput8.dll\t{sha256(prepared / 'exe' / 'dinput8.dll')}"], lines)
            (prepared / "exe" / "G7MTClient.exe").write_bytes(b"patched")
            with self.assertRaises(SystemExit):
                make_patch.main(["--original", str(original), "--prepared", str(prepared), "--out", str(root / "p2")])


if __name__ == "__main__":
    unittest.main()
