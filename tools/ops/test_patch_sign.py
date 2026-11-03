"""Checks for patch_sign.py. Author: Peppone Choi."""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

sys.path.insert(0, str(Path(__file__).resolve().parent))
import patch_sign  # noqa: E402


class PatchSignTest(unittest.TestCase):
    def test_signature_verifies_with_the_printed_public_key_only_for_the_signed_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                patch_sign.main(["keygen", "--out", str(root / "key.pem")])
            public = bytes.fromhex(output.getvalue().split()[-1])
            self.assertEqual(64, len(public))
            with self.assertRaises(SystemExit):
                patch_sign.main(["keygen", "--out", str(root / "key.pem")])
            (root / "patch").mkdir()
            manifest = b"LOGH7-PATCH-1\nclient\t00\nfile\texe/dinput8.dll\t11\n"
            (root / "patch" / "patch.manifest").write_bytes(manifest)
            with contextlib.redirect_stdout(io.StringIO()):
                patch_sign.main(["sign", "--key", str(root / "key.pem"), "--patch", str(root / "patch")])
            raw = bytes.fromhex((root / "patch" / patch_sign.SIGNATURE).read_text(encoding="ascii").strip())
            verifier = ec.EllipticCurvePublicNumbers(int.from_bytes(public[:32], "big"), int.from_bytes(public[32:], "big"),
                                                     ec.SECP256R1()).public_key()
            signature = encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
            verifier.verify(signature, manifest, ec.ECDSA(hashes.SHA256()))
            with self.assertRaises(Exception):
                verifier.verify(signature, manifest + b"file\tevil\t22\n", ec.ECDSA(hashes.SHA256()))


if __name__ == "__main__":
    unittest.main()
