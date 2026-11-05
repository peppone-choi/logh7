#!/usr/bin/env python3
"""Offline backup/restore of the game server's file state. Author: Peppone Choi.

The server keeps three file stores (docs/server/session-persistence.md): the world slots
(LOGH7_WORLD_STATE_DIR), the session checkpoint/journal (LOGH7_SESSION_STORE_DIR) and the
character file with its sibling stores (LOGH7_CHARACTERS_FILE + ``.*``). The ``*.world-owner``
markers bind those siblings to the absolute world directory, so a backup is restored to the
same paths only. The server must be stopped: a running server is refused through its ops
``/health`` endpoint, because the stores are only consistent with each other between runs.

    state_backup.py backup  --characters F --world D --session D [--accounts F] --out ARCHIVE_DIR
    state_backup.py verify  ARCHIVE_DIR
    state_backup.py restore ARCHIVE_DIR [--force]
    state_backup.py restore-to ARCHIVE_DIR --to EMPTY_INSTANCE_ROOT
    state_backup.py export  ARCHIVE_DIR --dest OTHER_DISK_OR_SHARE
    state_backup.py prune   BACKUP_ROOT --keep N

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
import urllib.error
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
    """Require a refused connection before allowing offline mutation; HTTP errors still mean a live listener."""
    if not url:
        return False
    try:
        # Windows loopback refusal can arrive just after two seconds.
        with urllib.request.urlopen(url, timeout=5):
            return True
    except urllib.error.HTTPError as response:
        response.close()
        return True
    except urllib.error.URLError as failure:
        return not isinstance(failure.reason, ConnectionRefusedError)
    except OSError as failure:
        return not isinstance(failure, ConnectionRefusedError)


def state_files(characters: Path, world: Path, session: Path, accounts: Path | None = None) -> dict[str, list[tuple[Path, str]]]:
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
    groups = {"characters": [(p, p.name) for p in siblings], "world": tree(world), "session": tree(session)}
    if accounts is not None:  # Operator account edits (LOGH7_ACCOUNTS_FILE) are state too.
        if not accounts.is_file():
            raise SystemExit(f"accounts file not found: {accounts}")
        groups["accounts"] = [(accounts, accounts.name)]
    return groups


def surviving_files(characters: Path, world: Path, session: Path, accounts: Path | None) -> dict[str, list[tuple[Path, str]]]:
    """Like state_files, but only what still exists: a lost state volume must not block its own restore."""
    def tree(root: Path) -> list[tuple[Path, str]]:
        return sorted((p, p.relative_to(root).as_posix()) for p in root.rglob("*")
                      if p.is_file() and not p.name.endswith(".lock")) if root.is_dir() else []
    siblings = sorted(p for p in characters.parent.glob(characters.name + "*")
                      if p.is_file() and not p.name.endswith(".lock")
                      and (p.name == characters.name or p.name.startswith(characters.name + "."))) \
        if characters.parent.is_dir() else []
    groups = {"characters": [(p, p.name) for p in siblings], "world": tree(world), "session": tree(session)}
    if accounts is not None and accounts.is_file():
        groups["accounts"] = [(accounts, accounts.name)]
    return groups


def check_owner(characters: Path, world: Path) -> None:
    for marker in characters.parent.glob(characters.name + ".*.world-owner"):
        fields = marker.read_text(encoding="utf-8").strip().split("\t")
        if len(fields) != 2 or Path(fields[1]).resolve() != world.resolve():
            raise SystemExit(f"{marker.name} is bound to {fields[-1]}, not {world}")


def backup(args: argparse.Namespace) -> None:
    if server_running(args.health):
        raise SystemExit(f"server may be running ({args.health}); confirm it is stopped before a backup")
    characters, world, session = Path(args.characters).resolve(), Path(args.world).resolve(), Path(args.session).resolve()
    check_owner(characters, world)
    accounts = Path(args.accounts).resolve() if args.accounts else None
    out = Path(args.out)
    if out.exists():
        raise SystemExit(f"backup target already exists: {out}")
    roots = {"characters": characters.parent, "world": world, "session": session}
    if accounts is not None:
        roots["accounts"] = accounts.parent
    files = []
    for group, entries in state_files(characters, world, session, accounts).items():
        for source, name in entries:
            target = out / group / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            files.append({"group": group, "name": name, "size": target.stat().st_size, "sha256": sha256(target)})
    manifest = {"format": FORMAT, "created": time.strftime("%Y-%m-%dT%H:%M:%S") + f".{time.time_ns() % 1_000_000_000:09d}",
                "roots": {k: str(v) for k, v in roots.items()}, "characters": characters.name, "files": files}
    if accounts is not None:
        manifest["accounts"] = accounts.name
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


def export(args: argparse.Namespace) -> None:
    """Copy a verified backup to another place (a mounted remote share or disk) and verify the copy there."""
    source = Path(args.archive)
    load(source)
    target = Path(args.dest) / source.name
    if target.exists():
        raise SystemExit(f"export target already exists: {target}")
    partial = target.with_name(target.name + ".partial")
    shutil.rmtree(partial, ignore_errors=True)
    shutil.copytree(source, partial)
    load(partial)  # re-read every file from the destination medium before naming it complete
    partial.rename(target)
    print(f"exported {source} -> {target}")


def prune(args: argparse.Namespace) -> None:
    """Keep the newest ``--keep`` verified backups directly under ROOT (by manifest time); remove older ones."""
    root = Path(args.root)
    backups = []
    for child in root.iterdir() if root.is_dir() else []:
        if (child / MANIFEST).is_file():
            manifest = json.loads((child / MANIFEST).read_text(encoding="utf-8"))
            if manifest.get("format") == FORMAT:
                backups.append((manifest["created"], child.name, child))
    backups.sort(reverse=True)
    if args.keep < 1:
        raise SystemExit("--keep must be at least 1")
    for _, _, old in backups[args.keep:]:
        shutil.rmtree(old)
        print(f"removed {old}")
    print(f"kept {min(len(backups), args.keep)} of {len(backups)} backups")


def verify(args: argparse.Namespace) -> None:
    manifest = load(Path(args.archive))
    print(f"verified {args.archive}: {len(manifest['files'])} files")


def restore(args: argparse.Namespace) -> None:
    if server_running(args.health):
        raise SystemExit(f"server may be running ({args.health}); confirm it is stopped before a restore")
    archive = Path(args.archive)
    manifest = load(archive)
    roots = {k: Path(v) for k, v in manifest["roots"].items()}
    characters = roots["characters"] / manifest["characters"]
    accounts = roots["accounts"] / manifest["accounts"] if "accounts" in manifest else None
    present = characters.exists() or (accounts is not None and accounts.exists()) or \
        any(roots[g].is_dir() and any(roots[g].iterdir()) for g in ("world", "session"))
    if present:
        if not args.force:
            raise SystemExit("state already exists at the backup paths; rerun with --force to replace it")
        saved = Path(f"{archive}.replaced-{time.strftime('%Y%m%d-%H%M%S')}")
        for group, entries in surviving_files(characters, roots["world"], roots["session"], accounts).items():
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


OWNER = "LOGH7-WORLD-OWNER-1"


def restore_to(args: argparse.Namespace) -> None:
    """Restore into a clean, separate instance root without touching the backup's original paths.

    Layout: ROOT/characters/<file and siblings>, ROOT/world, ROOT/session. Each ``*.world-owner`` marker that
    was bound to the backup's world directory is rebound to ROOT/world (the same format the server writes), so
    the restored instance opens its own copy and never the original store. ROOT must not already hold files.
    """
    archive = Path(args.archive)
    manifest = load(archive)
    root = Path(args.to).resolve()
    if root.exists() and any(root.iterdir()):
        raise SystemExit(f"restore target is not empty: {root}")
    source_world = Path(manifest["roots"]["world"]).resolve()
    roots = {"characters": root / "characters", "world": root / "world", "session": root / "session"}
    if "accounts" in manifest:
        roots["accounts"] = root / "accounts"
    for path in roots.values():
        path.mkdir(parents=True, exist_ok=True)
    world = roots["world"].resolve()
    if any(c in str(world) for c in "\t\r\n"):
        raise SystemExit(f"unsupported restore path: {world}")
    rebound = 0
    for entry in manifest["files"]:
        source = archive / entry["group"] / entry["name"]
        target = roots[entry["group"]] / entry["name"]
        target.parent.mkdir(parents=True, exist_ok=True)
        if entry["group"] == "characters" and entry["name"].endswith(".world-owner"):
            fields = source.read_text(encoding="utf-8").strip().split("\t")
            if len(fields) != 2 or fields[0] != OWNER or Path(fields[1]).resolve() != source_world:
                raise SystemExit(f"{entry['name']} is not bound to the backup's world directory")
            # Bytes, not text mode: Windows text mode writes CRLF and the server trims only "\n".
            target.write_bytes(f"{OWNER}\t{world}\n".encode("utf-8"))
            rebound += 1
            continue
        shutil.copy2(source, target)
        if sha256(target) != entry["sha256"]:
            raise SystemExit(f"restore copy mismatch: {target}")
    characters = roots["characters"] / manifest["characters"]
    print(f"restored {len(manifest['files'])} files from {archive} into {root} ({rebound} world-owner markers rebound)")
    print(f"LOGH7_CHARACTERS_FILE={characters}")
    print(f"LOGH7_WORLD_STATE_DIR={world}")
    print(f"LOGH7_SESSION_STORE_DIR={roots['session'].resolve()}")
    if "accounts" in manifest:
        print(f"LOGH7_ACCOUNTS_FILE={(roots['accounts'] / manifest['accounts']).resolve()}")


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
    take.add_argument("--accounts", help="LOGH7_ACCOUNTS_FILE (operator account edits); optional")
    take.set_defaults(run=backup)
    check = commands.add_parser("verify")
    check.add_argument("archive")
    check.set_defaults(run=verify)
    out = commands.add_parser("export")
    out.add_argument("archive")
    out.add_argument("--dest", required=True)
    out.set_defaults(run=export)
    keep = commands.add_parser("prune")
    keep.add_argument("root")
    keep.add_argument("--keep", type=int, required=True)
    keep.set_defaults(run=prune)
    put = commands.add_parser("restore")
    put.add_argument("archive")
    put.add_argument("--force", action="store_true")
    put.set_defaults(run=restore)
    clean = commands.add_parser("restore-to", help="restore into a clean separate instance root")
    clean.add_argument("archive")
    clean.add_argument("--to", required=True)
    clean.set_defaults(run=restore_to)
    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
