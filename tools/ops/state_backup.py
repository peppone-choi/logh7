#!/usr/bin/env python3
"""Offline backup/restore of the game server's file state. Author: Peppone Choi.

The server keeps three file stores (docs/server/session-persistence.md): the world slots
(LOGH7_WORLD_STATE_DIR), the session checkpoint/journal (LOGH7_SESSION_STORE_DIR) and the
character file with its sibling stores (LOGH7_CHARACTERS_FILE + ``.*``). The ``*.world-owner``
markers bind those siblings to the absolute world directory, so a backup is restored to the
same paths only. The server must be stopped: a running server is refused through its ops
``/health`` endpoint, because the stores are only consistent with each other between runs.

    state_backup.py backup  --characters F --world D --session D --out ARCHIVE_DIR
    state_backup.py verify  ARCHIVE_DIR
    state_backup.py restore ARCHIVE_DIR [--force]

``restore --force`` first moves the current files into ``ARCHIVE_DIR.replaced-<time>`` so the
replaced state can be put back. Lock files (``*.lock``) are never copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import time
import urllib.request
from pathlib import Path

MANIFEST = "manifest.json"
FORMAT = "LOGH7-STATE-BACKUP-1"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def server_running(url: str | None) -> bool:
    if not url:
        return False
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            return response.status == 200
    except OSError:
        return False


def state_files(characters: Path, world: Path, session: Path) -> dict[str, list[tuple[Path, str]]]:
    """Group -> [(absolute file, relative name)] for every non-lock state file."""
    def tree(root: Path) -> list[tuple[Path, str]]:
        if not root.is_dir():
            raise SystemExit(f"state directory not found: {root}")
        return sorted((p, p.relative_to(root).as_posix()) for p in root.rglob("*")
                      if p.is_file() and not p.name.endswith(".lock"))
    if not characters.is_file():
        raise SystemExit(f"characters file not found: {characters}")
    siblings = [characters] + sorted(p for p in characters.parent.glob(characters.name + ".*")
                                     if p.is_file() and not p.name.endswith(".lock"))
    return {"characters": [(p, p.name) for p in siblings], "world": tree(world), "session": tree(session)}


def check_owner(characters: Path, world: Path) -> None:
    for marker in characters.parent.glob(characters.name + ".*.world-owner"):
        fields = marker.read_text(encoding="utf-8").strip().split("\t")
        if len(fields) != 2 or Path(fields[1]).resolve() != world.resolve():
            raise SystemExit(f"{marker.name} is bound to {fields[-1]}, not {world}")


def backup(args: argparse.Namespace) -> None:
    if server_running(args.health):
        raise SystemExit(f"server is running ({args.health}); stop it before a backup")
    characters, world, session = Path(args.characters).resolve(), Path(args.world).resolve(), Path(args.session).resolve()
    check_owner(characters, world)
    out = Path(args.out)
    if out.exists():
        raise SystemExit(f"backup target already exists: {out}")
    roots = {"characters": characters.parent, "world": world, "session": session}
    files = []
    for group, entries in state_files(characters, world, session).items():
        for source, name in entries:
            target = out / group / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            files.append({"group": group, "name": name, "size": target.stat().st_size, "sha256": sha256(target)})
    manifest = {"format": FORMAT, "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                "roots": {k: str(v) for k, v in roots.items()}, "characters": characters.name, "files": files}
    (out / MANIFEST).write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"backup {out}: {len(files)} files")


def load(archive: Path) -> dict:
    manifest = json.loads((archive / MANIFEST).read_text(encoding="utf-8"))
    if manifest.get("format") != FORMAT:
        raise SystemExit(f"unsupported backup format: {manifest.get('format')}")
    bad = [f"{f['group']}/{f['name']}" for f in manifest["files"]
           if not (archive / f["group"] / f["name"]).is_file()
           or sha256(archive / f["group"] / f["name"]) != f["sha256"]]
    if bad:
        raise SystemExit("backup damaged: " + ", ".join(bad))
    return manifest


def verify(args: argparse.Namespace) -> None:
    manifest = load(Path(args.archive))
    print(f"verified {args.archive}: {len(manifest['files'])} files")


def restore(args: argparse.Namespace) -> None:
    if server_running(args.health):
        raise SystemExit(f"server is running ({args.health}); stop it before a restore")
    archive = Path(args.archive)
    manifest = load(archive)
    roots = {k: Path(v) for k, v in manifest["roots"].items()}
    characters = roots["characters"] / manifest["characters"]
    present = characters.exists() or any(roots[g].is_dir() and any(roots[g].iterdir()) for g in ("world", "session"))
    if present:
        if not args.force:
            raise SystemExit("state already exists at the backup paths; rerun with --force to replace it")
        saved = Path(f"{archive}.replaced-{time.strftime('%Y%m%d-%H%M%S')}")
        for group, entries in state_files(characters, roots["world"], roots["session"]).items():
            for source, name in entries:
                target = saved / group / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(source), target)
        print(f"replaced state moved to {saved}")
    for entry in manifest["files"]:
        target = roots[entry["group"]] / entry["name"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(archive / entry["group"] / entry["name"], target)
        if sha256(target) != entry["sha256"]:
            raise SystemExit(f"restore copy mismatch: {target}")
    print(f"restored {len(manifest['files'])} files from {archive}")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Offline backup/restore of the game server file state.")
    parser.add_argument("--health", default="http://127.0.0.1:47951/health",
                        help="ops health URL used to refuse a running server ('' to skip)")
    commands = parser.add_subparsers(dest="command", required=True)
    take = commands.add_parser("backup")
    take.add_argument("--characters", required=True)
    take.add_argument("--world", required=True)
    take.add_argument("--session", required=True)
    take.add_argument("--out", required=True)
    take.set_defaults(run=backup)
    check = commands.add_parser("verify")
    check.add_argument("archive")
    check.set_defaults(run=verify)
    put = commands.add_parser("restore")
    put.add_argument("archive")
    put.add_argument("--force", action="store_true")
    put.set_defaults(run=restore)
    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
