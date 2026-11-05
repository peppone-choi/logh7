#!/usr/bin/env python3
"""Compare two ops ``/snapshot`` dumps after masking clock-driven fields. Author: Peppone Choi.

A restored instance keeps running between being healthy and being sampled, so revision, game time and tick
counters drift; every other token (flagships, bases and their owners, outfits, treasuries, session generation)
must match. /snapshot does not carry mail or other character-file sibling stores: ``--stores`` also compares
their hashes between two stopped instance roots. Exit 0 when equal, 1 with a short diff otherwise.

    snapshot_compare.py A.snapshot.txt B.snapshot.txt [--stores ROOT_A ROOT_B]
"""
from __future__ import annotations

import difflib
import re
import sys
from pathlib import Path

CLOCK = re.compile(r"\b(revision|gameSeconds|strategyTicks|gameMillis|cpRecoveryEvents|elapsed)=\d+")
SPLIT = re.compile(r"(?<=[,(\[{])\s*")


def tokens(text: str) -> list[str]:
    return SPLIT.split(CLOCK.sub(lambda m: m.group(1) + "=*", text))


def published(text: str) -> bool:
    """A complete world snapshot: not an error page, an empty body or the startup placeholder."""
    return (text.startswith("WorldSnapshot(") and "session=SessionSnapshot(" in text
            and "navigators={" in text and "navigators={}" not in text
            and "planetStocks=PlanetStocks(" in text)


def compare(a: str, b: str) -> list[str]:
    if not published(a) or not published(b):
        return ["an input is not a published world snapshot (error page, empty, or sampled before startup finished)"]
    return [line for line in difflib.unified_diff(tokens(a), tokens(b), lineterm="", n=0)
            if line[:1] in "+-" and line[:3] not in ("+++", "---")]


def stores(root: Path) -> dict[str, str]:
    """Hashes of the persisted stores /snapshot does not carry: mail and other character-file siblings."""
    import hashlib
    base = root / "characters"
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(base.glob("*.properties.*"))
            if p.is_file() and not p.name.endswith((".lock", ".world-owner"))}


def main(argv: list[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])
    roots = None
    if "--stores" in args:  # also compare the instance roots' persisted sibling stores (mail, fame, ...)
        at = args.index("--stores")
        roots = (Path(args[at + 1]), Path(args[at + 2]))
        del args[at:at + 3]
    first, second = (Path(p).read_text(encoding="utf-8") for p in args[:2])
    diff = compare(first, second)
    print(f"tokens={len(tokens(first))}/{len(tokens(second))} differing={len(diff)}")
    for line in diff[:40]:
        print(line[:200])
    if roots is not None:
        a, b = stores(roots[0]), stores(roots[1])
        mail = sorted(name for name in a if ".mail." in name)
        print(f"stores={len(a)}/{len(b)} equal={a == b} mail_files={len(mail)}")
        if a != b:
            diff.append("persisted stores differ")
    return 0 if not diff else 1


if __name__ == "__main__":
    sys.exit(main())
