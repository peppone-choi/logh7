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
exact string constant of the matching store class in the target release. ``--definitions-db``
(full definitions published in PostgreSQL, not only colors) requires the PostgreSQL country definition reader.
``--world-definition`` (``LOGH7_WORLD_FILE``) requires a reader for its ``schema=`` (2: country starts).
``--session`` checks checkpoint/journal framing and schema (repeat for every session directory).
``--accounts`` and ``--account-fame`` check the stored login and shared-fame formats.
    server_release.py classpath --releases DIR
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import shutil
import struct
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from state_backup import server_running, sha256  # noqa: E402

MANIFEST = "release.json"
FORMAT = "LOGH7-SERVER-RELEASE-1"
# Country definitions in PostgreSQL (LOGH7_DEFINITIONS_JDBC_URL): a release without this reader starts
# on its built-in catalog and silently ignores the published definitions.
DEFINITIONS_READER = "org/logh7/persistence/PostgresCountryDefinitions.class"
# Saved-record formats and the class a release needs to read them (PR #290: character profile v2).
RECORD_FORMATS = (("character profile v2", "v2:", "org/logh7/gateway/CharacterProfile.class"),)
SESSION_CODEC = "org/logh7/persistence/SessionCodec.class"
ACCOUNT_PASSWORD = "org/logh7/gateway/AccountPassword.class"
FILE_ACCOUNTS = "org/logh7/gateway/FileAccounts.class"
FAME_STORAGE = "org/logh7/gateway/FameLedger$Storage.class"


# Versioned state files: the first tab-separated header field (e.g. ``LOGH7-STOCK-42``) must be a
# string constant of the store class in the target release, or that release rejects the file.
HEADER_READERS = (("LOGH7-STOCK-", "org/logh7/persistence/FilePlanetStockStore.class"),
                  ("LOGH7-NAV-", "org/logh7/persistence/FileNavigationStore.class"),
                  ("LOGH7-WORLD-", "org/logh7/persistence/FileWorldStateStore.class"))


# World definition (LOGH7_WORLD_FILE) ``schema=N``: schema 1 is read by every release; a later schema
# needs a release whose reader holds the listed exact string constant (codex/live-country-identity).
WORLD_SCHEMAS = {
    "2": ("org/logh7/engine/Galaxy$Companion.class", "Country starts require world schema 2"),
    "3": ("org/logh7/engine/Galaxy$Companion.class", "Initial owners require world schema 3"),
}
SCHEMA_READERS_CLASSES = {reader for reader, _ in WORLD_SCHEMAS.values()}


def java_properties(text: str) -> dict[str, str]:
    """java.util.Properties.load semantics (Galaxy.load): ``=``, ``:`` or whitespace separators,
    ``#``/``!`` comments, backslash line continuation and escapes, and the last duplicate wins."""
    logical, pending = [], ""
    for raw in text.splitlines():
        line = (pending + raw.lstrip()) if pending else raw.lstrip()
        if not pending and (not line or line[0] in "#!"):
            continue
        trailing = len(line) - len(line.rstrip("\\"))
        if trailing % 2 == 1:
            pending = line[:-1]
            continue
        pending = ""
        logical.append(line)
    if pending:
        logical.append(pending)

    def unescape(value: str) -> str:
        out, i = [], 0
        while i < len(value):
            ch = value[i]
            if ch == "\\" and i + 1 < len(value):
                nxt = value[i + 1]
                if nxt == "u" and i + 5 < len(value):
                    out.append(chr(int(value[i + 2:i + 6], 16)))
                    i += 6
                    continue
                out.append({"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(nxt, nxt))
                i += 2
                continue
            out.append(ch)
            i += 1
        return "".join(out)

    result: dict[str, str] = {}
    for line in logical:
        i, end = 0, len(line)
        while i < end:
            if line[i] == "\\":
                i += 2
                continue
            if line[i] in "=: \t\f":
                break
            i += 1
        key = line[:min(i, end)]
        j = i
        while j < end and line[j] in " \t\f":
            j += 1
        if j < end and line[j] in "=:":
            j += 1
            while j < end and line[j] in " \t\f":
                j += 1
        result[unescape(key)] = unescape(line[j:])
    return result


def unreadable_world_definition(classes: dict[str, bytes], definition: Path | None) -> list[str]:
    if definition is None:
        return []
    schema = java_properties(definition.read_text(encoding="utf-8", errors="replace")).get("schema")
    if schema is None:
        return [f"no world schema in {definition.name}"]
    if schema == "1":
        return []
    reader = WORLD_SCHEMAS.get(schema)
    if reader is None:
        return [f"unknown world schema {schema} in {definition.name}"]
    if not has_constant(classes.get(reader[0], b""), reader[1]):
        return [f"world schema {schema} in {definition.name}"]
    return []


def release_classes(root: Path) -> dict[str, bytes]:
    classes: dict[str, bytes] = {}
    readers = ({DEFINITIONS_READER, SESSION_CODEC, ACCOUNT_PASSWORD, FILE_ACCOUNTS, FAME_STORAGE,
                "org/logh7/persistence/FileCommunicationStore.class"} | SCHEMA_READERS_CLASSES |
               {reader for _, reader in HEADER_READERS} | {reader for _, _, reader in RECORD_FORMATS})
    for jar in (root / "lib").glob("*.jar"):
        try:
            with zipfile.ZipFile(jar) as archive:
                for name in readers.intersection(archive.namelist()):
                    classes[name] = archive.read(name)
        except zipfile.BadZipFile:
            continue
    return classes


def has_constant(code: bytes, text: str) -> bool:
    """True when the class file holds ``text`` as an exact CONSTANT_Utf8 entry (not a prefix)."""
    data = text.encode("utf-8")
    return b"\x01" + len(data).to_bytes(2, "big") + data in code


def session_schema_limit(code: bytes) -> int:
    """Read SessionCodec.SCHEMA's JVM ConstantValue without starting the target release."""
    stream = io.BytesIO(code)

    def u2():
        return struct.unpack(">H", stream.read(2))[0]

    def u4():
        return struct.unpack(">I", stream.read(4))[0]

    if stream.read(4) != b"\xca\xfe\xba\xbe":
        return 0
    stream.read(4)
    constants: dict[int, bytes | int] = {}
    index = 1
    try:
        count = u2()
        sizes = {4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4,
                 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
        while index < count:
            tag = stream.read(1)[0]
            if tag == 1:
                constants[index] = stream.read(u2())
            elif tag == 3:
                constants[index] = u4()
            else:
                stream.seek(sizes[tag], io.SEEK_CUR)
            index += 2 if tag in (5, 6) else 1
        stream.read(6)  # access flags, this class, super class
        stream.seek(2 * u2(), io.SEEK_CUR)  # interfaces
        for _ in range(u2()):
            stream.read(2)  # access flags
            name, descriptor = constants[u2()], constants[u2()]
            for _ in range(u2()):
                attribute, length = constants[u2()], u4()
                if name == b"SCHEMA" and descriptor == b"I" and attribute == b"ConstantValue" and length == 2:
                    value = constants[u2()]
                    return value if isinstance(value, int) else 0
                stream.seek(length, io.SEEK_CUR)
    except (IndexError, KeyError, struct.error):
        return 0
    return 0


def session_problems(classes: dict[str, bytes], sessions: list[Path]) -> list[str]:
    if not sessions:
        return []
    limit = session_schema_limit(classes.get(SESSION_CODEC, b""))
    problems = []
    for directory in sessions:
        if not directory.is_dir():
            problems.append(f"session directory not found: {directory}")
            continue
        checkpoint, journal = directory / "session.checkpoint", directory / "session.journal"
        if not checkpoint.is_file():
            problems.append(f"session checkpoint missing: {directory}")
            continue
        for path in (checkpoint, journal):
            if path == journal and not path.exists():
                continue  # A fresh checkpoint can exist before the journal is created.
            try:
                with path.open("rb") as stream:
                    frames = 0
                    for header in iter(lambda: stream.read(4), b""):
                        size = int.from_bytes(header, "big")
                        if len(header) != 4 or not 4 <= size <= 8 * 1024 * 1024:
                            raise ValueError("invalid session frame length")
                        payload, checksum = stream.read(size), stream.read(32)
                        if len(payload) != size or hashlib.sha256(payload).digest() != checksum:
                            raise ValueError("invalid session frame checksum")
                        schema = int.from_bytes(payload[:4], "big")
                        if not 1 <= schema <= limit:
                            raise ValueError(f"unsupported session schema {schema}")
                        frames += 1
                        if path == checkpoint and frames > 1:
                            break
                    if path == checkpoint and frames != 1:
                        raise ValueError("invalid session checkpoint frame count")
            except (ValueError, OSError) as error:
                problems.append(f"{error} in {path}")
    return problems


def player_state_problems(classes: dict[str, bytes], accounts: Path | None, account_fame: Path | None) -> list[str]:
    problems = []
    for path in (accounts, account_fame):
        if path is None:
            continue
        try:
            values = java_properties(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            problems.append(f"unreadable player state file: {path}")
            continue
        if path == accounts:
            if (any(value.startswith("pbkdf2-sha256:") for value in values.values()) and
                    not has_constant(classes.get(ACCOUNT_PASSWORD, b""), "pbkdf2-sha256")):
                problems.append(f"hashed account passwords in {path.name}")
            if ("pbkdf2-sha256:removed" in values.values() and
                    not has_constant(classes.get(FILE_ACCOUNTS, b""), "pbkdf2-sha256:removed")):
                problems.append(f"reserved account names in {path.name}")
        else:
            format_name = "LOGH7-ACCOUNT-FAME-1"
            if values.get("@format") != format_name or not has_constant(classes.get(FAME_STORAGE, b""), format_name):
                problems.append(f"unsupported shared account fame format in {path.name}")
    return problems


def unreadable_headers(classes: dict[str, bytes], world: Path | None, characters: Path | None) -> list[str]:
    files: list[Path] = []
    if world is not None and (world / "CURRENT").is_file():
        # FileWorldStateStore loads only the slot named by CURRENT; an inactive slot left by a failed
        # update is overwritten by the next save and must not block the rollback.
        files.append(world / "CURRENT")
        fields = (world / "CURRENT").read_text(encoding="latin-1").strip().split("\t")
        if len(fields) == 3 and fields[1] in ("0", "1"):
            slot = world / f"slot-{fields[1]}"
            files += [p for p in slot.glob("*.tsv") if p.is_file()]
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


def unreadable(classes: dict[str, bytes], characters: Path | None, world: Path | None = None) -> list[str]:
    """Character and world formats that the target release has no reader for."""
    missing = unreadable_headers(classes, world, characters)
    if characters is None:
        return missing
    # Mail format 4 stores actual country IDs (0 is unattached), not native display codes.
    # Every saved generation is addressable, so an older reader must not be activated over it.
    reader = classes.get("org/logh7/persistence/FileCommunicationStore.class", b"")
    for mail in characters.parent.glob(f"{characters.name}.mail.session-*"):
        if not mail.is_file() or not mail.name.rsplit("-", 1)[1].isdigit():
            continue
        with mail.open("rb") as stream:
            header = stream.read(4)
        if header == bytes.fromhex("4c374d34") and b"\x03" + header not in reader:
            missing.append(f"mail country format 4 in {mail.name}")
    # Generation 1 uses the file itself; later session generations use `<file>.session-<n>`
    # (CharacterStores.path). Every generation on disk may be loaded, so check them all.
    stores = [characters] + [p for p in characters.parent.glob(f"{characters.name}.session-*")
                             if p.name.rsplit("-", 1)[1].isdigit()]
    values = []
    for store in stores:
        if not store.is_file():
            continue
        values.extend(java_properties(store.read_text(encoding="utf-8")).values())
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
    if (not args.name or "/" in args.name or "\\" in args.name or
            args.name.rstrip(" .").upper() in ("", "CURRENT", "PREVIOUS")):
        raise SystemExit(f"bad release name: {args.name}")
    target = releases / args.name
    if target.exists():
        raise SystemExit(f"release already exists: {target}")
    shutil.copytree(source, target)
    files = [{"path": p.relative_to(target).as_posix(), "sha256": sha256(p)} for p in sorted(target.rglob("*")) if p.is_file()]
    (target / MANIFEST).write_text(json.dumps({"format": FORMAT, "name": args.name, "files": files}, indent=1) + "\n", encoding="utf-8")
    print(f"installed {args.name}: {len(files)} files")


def switch(releases: Path, name: str, health: str | None, characters: Path | None = None,
           world: Path | None = None, definitions_db: bool = False, world_definition: Path | None = None,
           *, sessions: list[Path] | None = None, accounts: Path | None = None,
           account_fame: Path | None = None) -> None:
    if server_running(health):
        raise SystemExit(f"server may be running ({health}); confirm it is stopped before switching releases")
    root = checked(releases, name)
    classes = release_classes(root)
    missing = (unreadable(classes, characters, world) + unreadable_world_definition(classes, world_definition)
               + session_problems(classes, sessions or []) + player_state_problems(classes, accounts, account_fame))
    if definitions_db and DEFINITIONS_READER not in classes:
        missing.append("country definitions database")
    if missing:
        raise SystemExit(f"release {name} cannot read the saved state ({', '.join(missing)}); "
                         "restore the pre-upgrade backup (state_backup.py restore, and the definitions database dump "
                         "when one is used) before switching, or choose a newer release")
    current = pointer(releases, "CURRENT")
    if current == name:
        print(f"{name} is already active")
        return
    if current:
        (releases / "PREVIOUS").write_text(current + "\n", encoding="utf-8")
    (releases / "CURRENT").write_text(name + "\n", encoding="utf-8")
    print(f"active {name} (previous {current or '-'})")


def change_release(args: argparse.Namespace) -> None:
    releases = Path(args.releases)
    name = args.name if args.command == "activate" else pointer(releases, "PREVIOUS")
    if not name:
        raise SystemExit("no previous release to roll back to")
    switch(releases, name, args.health, args.characters, args.world, args.definitions_db, args.world_definition,
           sessions=args.session, accounts=args.accounts, account_fame=args.account_fame)


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
    back = commands.add_parser("rollback")
    for change in (use, back):
        change.add_argument("--releases", required=True)
        change.add_argument("--characters", type=Path, help="LOGH7_CHARACTERS_FILE")
        change.add_argument("--world", type=Path, help="LOGH7_WORLD_STATE_DIR")
        change.add_argument("--world-definition", type=Path, help="LOGH7_WORLD_FILE")
        change.add_argument("--session", action="append", type=Path, help="LOGH7_SESSION_STORE_DIR (repeat for each session)")
        change.add_argument("--accounts", type=Path, help="LOGH7_ACCOUNTS_FILE")
        change.add_argument("--account-fame", type=Path, help="LOGH7_ACCOUNT_FAME_FILE")
        change.add_argument("--definitions-db", action="store_true",
                            help="full country definitions are published in PostgreSQL (not only colors)")
        change.set_defaults(run=change_release)
    path = commands.add_parser("classpath")
    path.add_argument("--releases", required=True)
    path.set_defaults(run=classpath)
    args = parser.parse_args(argv)
    args.run(args)


if __name__ == "__main__":
    sys.exit(main())
