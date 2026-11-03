#!/usr/bin/env python3
"""Sign a launcher patch manifest with an ECDSA P-256 key (launcher/README.md).

``keygen`` writes a new private key (PEM, keep it off the patch server) and prints the public
key as 128 hex digits (X||Y) for the launcher's ``--trust-key``. ``sign`` writes
``patch.manifest.sig`` next to the manifest: the raw r||s signature over the manifest bytes as
128 hex digits. The launcher verifies it with Windows CNG before downloading any listed file.
Author: Peppone Choi.

    patch_sign.py keygen --out E:/secure/patch-key.pem
    patch_sign.py sign --key E:/secure/patch-key.pem --patch work/<case>/patch
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

SIGNATURE = "patch.manifest.sig"


def public_hex(key: ec.EllipticCurvePrivateKey) -> str:
    numbers = key.public_key().public_numbers()
    return numbers.x.to_bytes(32, "big").hex() + numbers.y.to_bytes(32, "big").hex()


def load(path: Path) -> ec.EllipticCurvePrivateKey:
    key = serialization.load_pem_private_key(path.read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or key.curve.name != "secp256r1":
        raise SystemExit(f"not a P-256 private key: {path}")
    return key


def sign(key: ec.EllipticCurvePrivateKey, data: bytes) -> str:
    r, s = decode_dss_signature(key.sign(data, ec.ECDSA(hashes.SHA256())))
    return r.to_bytes(32, "big").hex() + s.to_bytes(32, "big").hex()


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Sign launcher patch manifests with ECDSA P-256.")
    commands = parser.add_subparsers(dest="command", required=True)
    keygen = commands.add_parser("keygen")
    keygen.add_argument("--out", required=True)
    signer = commands.add_parser("sign")
    signer.add_argument("--key", required=True)
    signer.add_argument("--patch", required=True)
    args = parser.parse_args(argv)
    if args.command == "keygen":
        out = Path(args.out)
        if out.exists():
            raise SystemExit(f"key already exists: {out}")
        key = ec.generate_private_key(ec.SECP256R1())
        out.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
        print(f"public key {public_hex(key)}")
        return
    key = load(Path(args.key))
    manifest = Path(args.patch) / "patch.manifest"
    (manifest.parent / SIGNATURE).write_text(sign(key, manifest.read_bytes()) + "\n", encoding="ascii")
    print(f"signed {manifest} for public key {public_hex(key)}")


if __name__ == "__main__":
    sys.exit(main())
