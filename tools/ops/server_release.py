#!/usr/bin/env python3
"""Versioned game-server releases with activate/rollback. Author: Peppone Choi.

A release is a copy of ``server/app/build/install/app`` (Gradle installDist) under
``RELEASES/<name>`` with a SHA-256 manifest. ``CURRENT``/``PREVIOUS`` text files name the
active and the prior release; start scripts read ``classpath`` instead of the build tree.
Switching is refused while the ops ``/health`` endpoint answers, and a release whose files
no longer match its manifest is never activated. Pair a switch with
``state_backup.py backup`` so a failed update can restore both code and state.

    server_release.py install  BUILD_DIR --releases DIR --name NAME
    server_release.py activate NAME --releases DIR
    server_release.py rollback --releases DIR
    server_release.py classpath --releases DIR
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from state_backup import server_running, sha256  # noqa: E402

MANIFEST = "release.json"
FORMAT = "LOGH7-SERVER-RELEASE-1"


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


def switch(releases: Path, name: str, health: str | None) -> None:
    if server_running(health):
        raise SystemExit(f"server is running ({health}); stop it before switching releases")
    checked(releases, name)
    current = pointer(releases, "CURRENT")
    if current == name:
        print(f"{name} is already active")
        return
    if current:
        (releases / "PREVIOUS").write_text(current + "\n", encoding="utf-8")
    (releases / "CURRENT").write_text(name + "\n", encoding="utf-8")
    print(f"active {name} (previous {current or '-'})")


def activate(args: argparse.Namespace) -> None:
    switch(Path(args.releases), args.name, args.health)


def rollback(args: argparse.Namespace) -> None:
    releases = Path(args.releases)
    previous = pointer(releases, "PREVIOUS")
    if not previous:
        raise SystemExit("no previous release to roll back to")
    switch(releases, previous, args.health)


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
    use.set_defaults(run=activate)
    back = commands.add_parser("rollback")
    back.add_argument("--releases", required=True)
    back.set_defaults(run=rollback)
    path = commands.add_parser("classpath")
    path.add_argument("--releases", required=True)
    path.set_defaults(run=classpath)
    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
