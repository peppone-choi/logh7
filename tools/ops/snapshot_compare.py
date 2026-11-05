#!/usr/bin/env python3
"""Compare two ops ``/snapshot`` dumps after masking clock-driven fields. Author: Peppone Choi.

A restored instance keeps running between being healthy and being sampled, so revision, game time and tick
counters drift; every other token (characters, flagships, bases and their owners, outfits, treasuries, mail,
session generation) must match. Exit 0 when equal, 1 with a short diff otherwise.

    snapshot_compare.py A.snapshot.txt B.snapshot.txt
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


def compare(a: str, b: str) -> list[str]:
    if "navigators={}" in a or "planetStocks=null" in a or "navigators={}" in b or "planetStocks=null" in b:
        return ["a snapshot has no world state yet (sampled before it was published)"]
    return [line for line in difflib.unified_diff(tokens(a), tokens(b), lineterm="", n=0)
            if line[:1] in "+-" and line[:3] not in ("+++", "---")]


def main(argv: list[str] | None = None) -> int:
    first, second = (Path(p).read_text(encoding="utf-8") for p in (argv or sys.argv[1:])[:2])
    diff = compare(first, second)
    print(f"tokens={len(tokens(first))}/{len(tokens(second))} differing={len(diff)}")
    for line in diff[:40]:
        print(line[:200])
    return 0 if not diff else 1


if __name__ == "__main__":
    sys.exit(main())
