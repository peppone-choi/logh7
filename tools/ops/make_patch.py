#!/usr/bin/env python3
"""Build a launcher patch (launcher/README.md) from an original install and a prepared copy.

Every file that is new or differs in the prepared copy becomes a ``file`` entry of
``patch.manifest``; the client line records the original ``exe/G7MTClient.exe`` hash. The
client executable itself, logs and per-machine settings are never packed, so the patch
carries only the revival's own changes. Output goes to a new directory under ``work/`` (game
assets are not committed). Author: Peppone Choi.

    make_patch.py --original INSTALL_ROOT --prepared COPY_ROOT --out work/<case>/patch
"""
from __future__ import annotations

import argparse
import fnmatch
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from state_backup import sha256  # noqa: E402

CLIENT = "exe/G7MTClient.exe"
# Machine-local or generated files that a fresh install must create for itself (the client writes
# its runtime log to exe/String.txt and window positions to exe/window*.dat).
EXCLUDE = ("*.log", "GraphicConfig.txt", "exe/window*.dat", "exe/String.txt", "logh7-patch.state", "logh7-patch.state.next", "logh7-patch-backup/*", CLIENT)


def changes(original: Path, prepared: Path) -> list[str]:
    found = []
    for path in sorted(prepared.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(prepared).as_posix()
        if any(fnmatch.fnmatch(relative, pattern) for pattern in EXCLUDE):
            continue
        source = original / relative
        if not source.is_file() or source.stat().st_size != path.stat().st_size or sha256(source) != sha256(path):
            found.append(relative)
    return found


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Build a launcher patch from an original install and a prepared copy.")
    parser.add_argument("--original", required=True)
    parser.add_argument("--prepared", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    original, prepared, out = Path(args.original), Path(args.prepared), Path(args.out)
    if out.exists():
        raise SystemExit(f"patch target already exists: {out}")
    client = original / CLIENT
    if not client.is_file() or sha256(client) != sha256(prepared / CLIENT):
        raise SystemExit("the prepared copy must keep the original client executable")
    files = changes(original, prepared)
    if not files:
        raise SystemExit("no changed files to pack")
    lines = ["LOGH7-PATCH-1", f"client\t{sha256(client)}"]
    for relative in files:
        target = out / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(prepared / relative, target)
        lines.append(f"file\t{relative}\t{sha256(target)}")
    (out / "patch.manifest").write_text("\n".join(lines) + "\n", encoding="utf-8")
    # Publish this value through a channel other than the patch server (launcher --expect-manifest).
    print(f"patch {out}: {len(files)} files, manifest sha256 {sha256(out / 'patch.manifest')}")


if __name__ == "__main__":
    sys.exit(main())
