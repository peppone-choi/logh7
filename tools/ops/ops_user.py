#!/usr/bin/env python3
"""Add or replace an operator login in the ops users file (LOGH7_OPS_USERS_FILE). Author: Peppone Choi.

    ops_user.py USERS_FILE NAME          # prompts for the password twice, never echoes it

Entries are ``NAME=pbkdf2-sha256:<iterations>:<salt>:<hash>`` (PBKDF2-HMAC-SHA256, 600,000 iterations,
16-byte salt), the format server/ops-api OpsUsers verifies. The file is rewritten through a temp file.
"""
from __future__ import annotations

import base64
import getpass
import hashlib
import os
import re
import sys
from pathlib import Path

ITERATIONS = 600_000
NAME = re.compile(r"[A-Za-z0-9._-]{1,64}")


def entry(password: str, iterations: int = ITERATIONS, salt: bytes | None = None) -> str:
    salt = salt or os.urandom(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, iterations, 32)
    return f"pbkdf2-sha256:{iterations}:{base64.b64encode(salt).decode()}:{base64.b64encode(digest).decode()}"


def store(path: Path, name: str, value: str) -> None:
    if not NAME.fullmatch(name):
        raise SystemExit("operator names are 1-64 of [A-Za-z0-9._-]")
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    kept = [line for line in lines if not line.startswith(name + "=")]
    # Properties format: the colons of the hash are escaped so java.util.Properties reads them back unchanged.
    kept.append(f"{name}={value.replace(':', chr(92) + ':')}")
    temp = path.with_name(path.name + ".tmp")
    temp.write_text("\n".join(kept) + "\n", encoding="utf-8")
    os.replace(temp, path)


def main(argv: list[str] | None = None) -> None:
    args = argv if argv is not None else sys.argv[1:]
    if len(args) != 2:
        raise SystemExit(__doc__)
    password = getpass.getpass("password: ")
    if len(password) < 12 or password != getpass.getpass("again: "):
        raise SystemExit("passwords must match and be at least 12 characters")
    store(Path(args[0]), args[1], entry(password))
    print(f"stored operator {args[1]}")


if __name__ == "__main__":
    main()
