#!/usr/bin/env python3
"""Versioned game-server releases with activate/rollback. Author: Peppone Choi.

A release is a copy of ``server/app/build/install/app`` (Gradle installDist) under
``RELEASES/<name>`` with a SHA-256 manifest. ``CURRENT``/``PREVIOUS`` text files name the
active and the prior release; start scripts read ``classpath`` instead of the build tree.
Switching is refused while the ops ``/health`` endpoint answers, and a release whose files
no longer match its manifest is never activated. Pair a switch with
``state_backup.py backup`` so a failed update can restore both code and state.

    server_release.py install  BUILD_DIR --releases DIR --name NAME
    server_release.py activate NAME --releases DIR [--characters FILE] [--world DIR]
    server_release.py rollback --releases DIR [--characters FILE] [--world DIR]

With ``--characters`` (the live ``LOGH7_CHARACTERS_FILE``) a switch is refused when the saved state
uses a record format the target release cannot read. An older release still starts and answers
``/health`` with such state; it fails only when the character store is first opened (a login), so
the operator must restore the pre-upgrade backup first. ``--world`` (``LOGH7_WORLD_STATE_DIR``) adds
the versioned store headers (``LOGH7-STOCK-N``, ``LOGH7-NAV-N``, ``LOGH7-WORLD-N``): each must be an
exact string constant of the matching store class in the target release.
    server_release.py classpath --releases DIR
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from state_backup import server_running, sha256  # noqa: E402

MANIFEST = "release.json"
FORMAT = "LOGH7-SERVER-RELEASE-1"
# Saved-record formats and the class a release needs to read them (PR #290: character profile v2).
RECORD_FORMATS = (("character profile v2", "v2:", "org/logh7/gateway/CharacterProfile.class"),)


# Versioned state files: the first tab-separated header field (e.g. ``LOGH7-STOCK-42``) must be a
# string constant of the store class in the target release, or that release rejects the file.
HEADER_READERS = (("LOGH7-STOCK-", "org/logh7/persistence/FilePlanetStockStore.class"),
                  ("LOGH7-NAV-", "org/logh7/persistence/FileNavigationStore.class"),
                  ("LOGH7-WORLD-", "org/logh7/persistence/FileWorldStateStore.class"))


def release_classes(root: Path) -> dict[str, bytes]:
    classes: dict[str, bytes] = {}
    for jar in (root / "lib").glob("*.jar"):
        try:
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    classes[name] = archive.read(name) if name.endswith(".class") and name.startswith("org/logh7/persistence/") else b""
        except zipfile.BadZipFile:
            continue
    return classes


def has_constant(code: bytes, text: str) -> bool:
    """True when the class file holds ``text`` as an exact CONSTANT_Utf8 entry (not a prefix)."""
    data = text.encode("utf-8")
    return b"\x01" + len(data).to_bytes(2, "big") + data in code


def unreadable_headers(classes: dict[str, bytes], world: Path | None, characters: Path | None) -> list[str]:
    files: list[Path] = []
    if world is not None and world.is_dir():
        files += [p for p in world.rglob("*") if p.is_file() and p.suffix in (".tsv", "") and p.name != "writer.lock"]
    if characters is not None:
        files += [characters.with_name(characters.name + suffix) for suffix in (".navigation", ".planet-stocks")]
    missing = []
    for path in files:
        if not path.is_file():
            continue
        with path.open("rb") as stream:
            head = stream.readline(256).decode("latin-1").split("\t", 1)[0].strip()
        for prefix, reader in HEADER_READERS:
            if head.startswith(prefix) and not has_constant(classes.get(reader, b""), head):
                missing.append(f"{head} in {path.name}")
    return missing


def unreadable(root: Path, characters: Path | None, world: Path | None = None) -> list[str]:
    """Record formats in the saved state that the release at ``root`` has no reader for."""
    missing = unreadable_headers(release_classes(root), world, characters)
    if characters is None:
        return missing
    # Generation 1 uses the file itself; later session generations use `<file>.session-<n>`
    # (CharacterStores.path). Every generation on disk may be loaded, so check them all.
    stores = [characters] + [p for p in characters.parent.glob(f"{characters.name}.session-*")
                             if p.name.rsplit("-", 1)[1].isdigit()]
    values = []
    for store in stores:
        if not store.is_file():
            continue
        for line in store.read_text(encoding="latin-1").splitlines():
            if line and not line.startswith(("#", "!")) and "=" in line:
                values.append(line.split("=", 1)[1].replace("\\", ""))
    classes = release_classes(root)
    return missing + [name for name, prefix, reader in RECORD_FORMATS
                      if reader not in classes and any(value.startswith(prefix) for value in values)]


def pointer(releases: Path, name: str) -> str | None:
    path = releases / name
    return path.read_text(encoding="utf-8").strip() if path.is_file() else None


def checked(releases: Path, name: str) -> Path:
    root = releases / name
    manifest = json.loads((root / MANIFEST).read_text(encoding="utf-8"))
    if manifest.get("format") != FORMAT or manifest.get("name") != name:
        raise SystemExit(f"not a release: {root}")
    bad = [f["path"] for f in manifest["files"] if not (root / f["path"]).is_file() or sha256(root / f["path"]) != f["sha256"]]
    if bad:
        raise SystemExit(f"release {name} damaged: " + ", ".join(bad))
    if not (root / "lib").is_dir():
        raise SystemExit(f"release {name} has no lib directory")
    return root


def install(args: argparse.Namespace) -> None:
    source, releases = Path(args.build), Path(args.releases)
    if not (source / "lib").is_dir():
        raise SystemExit(f"not an installDist tree: {source}")
    if not args.name or "/" in args.name or "\\" in args.name or args.name in ("CURRENT", "PREVIOUS"):
        raise SystemExit(f"bad release name: {args.name}")
    target = releases / args.name
    if target.exists():
        raise SystemExit(f"release already exists: {target}")
    shutil.copytree(source, target)
    files = [{"path": p.relative_to(target).as_posix(), "sha256": sha256(p)} for p in sorted(target.rglob("*")) if p.is_file()]
    (target / MANIFEST).write_text(json.dumps({"format": FORMAT, "name": args.name, "files": files}, indent=1) + "\n", encoding="utf-8")
    print(f"installed {args.name}: {len(files)} files")


def switch(releases: Path, name: str, health: str | None, characters: Path | None = None,
           world: Path | None = None) -> None:
    if server_running(health):
        raise SystemExit(f"server may be running ({health}); confirm it is stopped before switching releases")
    missing = unreadable(checked(releases, name), characters, world)
    if missing:
        raise SystemExit(f"release {name} cannot read the saved state ({', '.join(missing)}); "
                         "restore the pre-upgrade backup (state_backup.py restore) before switching")
    current = pointer(releases, "CURRENT")
    if current == name:
        print(f"{name} is already active")
        return
    if current:
        (releases / "PREVIOUS").write_text(current + "\n", encoding="utf-8")
    (releases / "CURRENT").write_text(name + "\n", encoding="utf-8")
    print(f"active {name} (previous {current or '-'})")


def activate(args: argparse.Namespace) -> None:
    switch(Path(args.releases), args.name, args.health, args.characters and Path(args.characters),
           args.world and Path(args.world))


def rollback(args: argparse.Namespace) -> None:
    releases = Path(args.releases)
    previous = pointer(releases, "PREVIOUS")
    if not previous:
        raise SystemExit("no previous release to roll back to")
    switch(releases, previous, args.health, args.characters and Path(args.characters), args.world and Path(args.world))


def classpath(args: argparse.Namespace) -> None:
    releases = Path(args.releases)
    current = pointer(releases, "CURRENT")
    if not current:
        raise SystemExit("no active release")
    print((checked(releases, current) / "lib" / "*").as_posix())


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Versioned game-server releases with activate/rollback.")
    parser.add_argument("--health", default="http://127.0.0.1:47951/health",
                        help="ops health URL used to refuse a running server ('' to skip)")
    commands = parser.add_subparsers(dest="command", required=True)
    put = commands.add_parser("install")
    put.add_argument("build")
    put.add_argument("--releases", required=True)
    put.add_argument("--name", required=True)
    put.set_defaults(run=install)
    use = commands.add_parser("activate")
    use.add_argument("name")
    use.add_argument("--releases", required=True)
    use.add_argument("--characters", help="live characters file; refuse a release that cannot read its records")
    use.add_argument("--world", help="LOGH7_WORLD_STATE_DIR; refuse a release that cannot read its file headers")
    use.set_defaults(run=activate)
    back = commands.add_parser("rollback")
    back.add_argument("--releases", required=True)
    back.add_argument("--characters", help="live characters file; refuse a release that cannot read its records")
    back.add_argument("--world", help="LOGH7_WORLD_STATE_DIR; refuse a release that cannot read its file headers")
    back.set_defaults(run=rollback)
    path = commands.add_parser("classpath")
    path.add_argument("--releases", required=True)
    path.set_defaults(run=classpath)
    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
