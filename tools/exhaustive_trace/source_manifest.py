"""Validate the frozen source set for exhaustive original-client tracing."""

from __future__ import annotations

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from .io import canonical_json, sha256_file
from .model import ALLOWED_PROVENANCE


CLIENT_SHA256 = "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"
MESSAGE_DATA_SHA256 = "5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C"
FROZEN_SOURCE_SHA256 = {
    "original-cd-iso": "375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22",
    "cd-manual-v1": "1C4CF3DB13A172361277264C06ADA6E2499BE0969494C6557EB84BC4CC005399",
    "official-web-manual-2004-10-07": "FF9B7B638582FEBBA723413D9956F4166AECBC20746CB35BB4AFDDCEF9515080",
}
IMPORT_QUALITIES = frozenset({"readable", "packed", "parse_failed", "dynamic_only"})
REQUIRED_IMPORT_GROUPS = frozenset(
    {
        "direct3d8",
        "directinput8",
        "directsound",
        "winsock",
        "filesystem",
        "registry",
        "timing",
        "process_thread",
    }
)
FILESYSTEM_IMPORT_NAMES = frozenset(
    {
        "CreateDirectoryA", "CreateFileA", "CreateFileW", "DeleteFileA", "FindClose",
        "FindFirstFileA", "FindNextFileA", "FlushFileBuffers", "GetCurrentDirectoryA",
        "GetDiskFreeSpaceA", "GetDriveTypeA", "GetFileSize", "GetFileTime", "GetFileType",
        "GetFullPathNameA", "GetTempFileNameA", "GetTempPathA", "GetVolumeInformationA",
        "LockFile", "MoveFileA", "ReadFile", "RemoveDirectoryA", "SetCurrentDirectoryA",
        "SetEndOfFile", "SetFilePointer", "SetFileTime", "UnlockFile", "WriteFile",
    }
)
TIMING_IMPORT_NAMES = frozenset(
    {
        "CompareFileTime", "FileTimeToSystemTime", "GetLocalTime", "GetSystemTime",
        "GetTickCount", "GetTimeZoneInformation", "QueryPerformanceCounter",
        "QueryPerformanceFrequency", "Sleep", "SystemTimeToFileTime", "timeBeginPeriod",
        "timeEndPeriod", "timeGetDevCaps", "timeGetTime",
    }
)
PROCESS_THREAD_TERMS = (
    "Process", "Thread", "Tls", "Event", "Semaphore", "WaitFor", "CriticalSection",
    "Interlocked", "Mutex", "CloseHandle", "DuplicateHandle",
)


def _require_mapping(name: str, value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _require_text(name: str, value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _require_sha256(name: str, value: Any) -> str:
    digest = _require_text(name, value).upper()
    if len(digest) != 64 or any(character not in "0123456789ABCDEF" for character in digest):
        raise ValueError(f"{name} must be a SHA-256 digest")
    return digest


def _resolve(base: Path, value: Any) -> Path:
    path = Path(_require_text("path", value))
    return path if path.is_absolute() else base / path


def _verify_file(base: Path, record: Mapping[str, Any], label: str) -> tuple[Path, str]:
    path = _resolve(base, record.get("path"))
    expected = _require_sha256(f"{label}.sha256", record.get("sha256"))
    if not path.is_file():
        raise ValueError(f"{label} file is missing: {path}")
    actual = sha256_file(path)
    if actual != expected:
        raise ValueError(f"{label} hash mismatch: expected {expected}, got {actual}")
    return path, actual


def _validate_authority(record: Mapping[str, Any], label: str) -> None:
    authority = _require_text(f"{label}.authority", record.get("authority"))
    if authority not in ALLOWED_PROVENANCE:
        raise ValueError(f"unsupported authority for {label}: {authority}")


def sha256_tree(path: str | Path) -> str:
    """Hash a directory as sorted relative path, byte length, and file SHA facts."""

    root = Path(path)
    digest = hashlib.sha256()
    for file_path in sorted(
        (candidate for candidate in root.rglob("*") if candidate.is_file()),
        key=lambda candidate: candidate.relative_to(root).as_posix(),
    ):
        relative = file_path.relative_to(root).as_posix()
        fact = f"{relative}\0{file_path.stat().st_size}\0{sha256_file(file_path)}\n"
        digest.update(fact.encode("utf-8"))
    return digest.hexdigest().upper()


def ghidra_program_database_sha256(path: str | Path) -> str:
    """Hash the semantic program DB while excluding volatile project indexes."""

    repository = Path(path)
    databases = sorted(repository.rglob("db.*.gbf"))
    if len(databases) != 1:
        raise ValueError(
            f"Ghidra repository must contain exactly one current program database: {databases}"
        )
    return sha256_file(databases[0])


def _verify_tree_manifest(root: Path, manifest: Path, prefix: str) -> int:
    prefix = prefix.replace("\\", "/")
    if prefix and not prefix.endswith("/"):
        prefix += "/"
    count = 0
    expected_paths: set[Path] = set()
    for line_number, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        if len(line) < 67 or line[64:66] != " *":
            raise ValueError(f"invalid tree manifest line {line_number}")
        expected = _require_sha256(f"tree manifest line {line_number}", line[:64])
        relative_text = line[66:].replace("\\", "/")
        if prefix:
            if not relative_text.startswith(prefix):
                raise ValueError(f"tree manifest line {line_number} is outside prefix {prefix}")
            relative_text = relative_text[len(prefix):]
        relative = Path(relative_text)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"unsafe tree manifest path at line {line_number}")
        target = root / relative
        expected_paths.add(relative)
        if not target.is_file() or sha256_file(target) != expected:
            raise ValueError(f"resource tree mismatch at line {line_number}: {target}")
        count += 1
    if count == 0:
        raise ValueError("tree manifest must contain at least one file")
    actual_paths = {
        candidate.relative_to(root)
        for candidate in root.rglob("*")
        if candidate.is_file()
    }
    if actual_paths != expected_paths:
        missing = sorted(str(path) for path in expected_paths - actual_paths)
        unlisted = sorted(str(path) for path in actual_paths - expected_paths)
        raise ValueError(f"resource tree file set mismatch; missing={missing}, unlisted={unlisted}")
    return count


def _import_key(item: Mapping[str, Any]) -> str:
    symbol = item.get("name") or f"ordinal_{item['ordinal']}"
    return f"{str(item['dll']).upper()}::{symbol}"


def _semantic_import_name(item: Mapping[str, Any]) -> str:
    return str(item.get("name") or item.get("resolvedName") or f"ordinal_{item['ordinal']}")


def classify_import_groups(imports: Sequence[Mapping[str, Any]]) -> dict[str, list[str]]:
    """Classify static APIs by an explicit, gameplay-neutral import rule set."""

    return {
        "direct3d8": [_import_key(item) for item in imports if str(item["dll"]).upper() == "D3D8.DLL"],
        "directinput8": [
            _import_key(item) for item in imports if str(item["dll"]).upper() == "DINPUT8.DLL"
        ],
        "directsound": [
            _import_key(item) for item in imports if str(item["dll"]).upper() == "DSOUND.DLL"
        ],
        "winsock": [
            _import_key(item)
            for item in imports
            if str(item["dll"]).upper() in {"WS2_32.DLL", "WSOCK32.DLL"}
        ],
        "filesystem": [
            _import_key(item)
            for item in imports
            if _semantic_import_name(item) in FILESYSTEM_IMPORT_NAMES
        ],
        "registry": [
            _import_key(item)
            for item in imports
            if str(item["dll"]).upper() == "ADVAPI32.DLL"
            and _semantic_import_name(item).startswith("Reg")
        ],
        "timing": [
            _import_key(item)
            for item in imports
            if _semantic_import_name(item) in TIMING_IMPORT_NAMES
        ],
        "process_thread": [
            _import_key(item)
            for item in imports
            if any(term in _semantic_import_name(item) for term in PROCESS_THREAD_TERMS)
        ],
    }


def validate_import_gate(payload: Mapping[str, Any]) -> Mapping[str, Any]:
    """Fail closed unless the raw PE import gate has all required facts."""

    payload = _require_mapping("pe imports", payload)
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported PE import schemaVersion")
    if payload.get("format") != "PE32":
        raise ValueError("import gate format must be PE32")
    if payload.get("architecture") != "x86":
        raise ValueError("import gate architecture must be x86")
    quality = payload.get("quality")
    if quality not in IMPORT_QUALITIES:
        raise ValueError(f"unsupported or missing import quality: {quality!r}")

    imports = payload.get("imports")
    if not isinstance(imports, list):
        raise ValueError("imports must be a list")
    for index, item in enumerate(imports):
        item = _require_mapping(f"imports[{index}]", item)
        _require_text(f"imports[{index}].dll", item.get("dll"))
        if not isinstance(item.get("name"), str) and not isinstance(item.get("ordinal"), int):
            raise ValueError(f"imports[{index}] needs a name or ordinal")
    if quality == "readable" and not imports:
        raise ValueError("readable PE import evidence must contain imports")
    if payload.get("importCount") != len(imports):
        raise ValueError("importCount does not match imports")
    descriptor_count = payload.get("descriptorCount")
    if not isinstance(descriptor_count, int) or descriptor_count <= 0:
        raise ValueError("descriptorCount must be a positive integer")

    groups = _require_mapping("groups", payload.get("groups"))
    if set(groups) != REQUIRED_IMPORT_GROUPS:
        missing = sorted(REQUIRED_IMPORT_GROUPS - set(groups))
        extra = sorted(set(groups) - REQUIRED_IMPORT_GROUPS)
        raise ValueError(f"import groups mismatch; missing={missing}, extra={extra}")
    known = {
        f"{item['dll'].upper()}::{item.get('name') or 'ordinal_' + str(item['ordinal'])}"
        for item in imports
    }
    if len(known) != len(imports):
        raise ValueError("imports must have unique DLL/symbol keys")
    for group_name, entries in groups.items():
        if not isinstance(entries, list) or any(not isinstance(item, str) for item in entries):
            raise ValueError(f"groups.{group_name} must be a list of import keys")
        unknown = sorted(set(entries) - known)
        if unknown:
            raise ValueError(f"groups.{group_name} references unknown imports: {unknown}")
        if quality == "readable" and not entries:
            raise ValueError(f"groups.{group_name} must not be empty for readable imports")
    expected_groups = classify_import_groups(imports)
    if dict(groups) != expected_groups:
        raise ValueError("import groups do not match the explicit classification rules")

    source = _require_mapping("source", payload.get("source"))
    if source.get("machine") != "0x014C" or source.get("optionalHeaderMagic") != "0x010B":
        raise ValueError("PE source machine/magic must identify x86 PE32")
    _require_sha256("source.executableSha256", source.get("executableSha256"))
    generator = _require_mapping("generator", payload.get("generator"))
    for name in ("builder", "rawPeParser", "ghidraCrossCheck"):
        _require_mapping(f"generator.{name}", generator.get(name))
    audit = _require_mapping("audit", payload.get("audit"))
    if audit.get("runtimeDynamicResolutionNotCovered") is not True:
        raise ValueError("dynamic import limitation must be explicit")
    _require_text("audit.limitation", audit.get("limitation"))
    return payload


@dataclass(frozen=True)
class SourceManifest:
    path: Path
    client_sha256: str
    message_data_sha256: str | None
    import_quality: str
    verified_paths: tuple[Path, ...]

    @classmethod
    def load(cls, path: str | Path) -> "SourceManifest":
        """Load only the production frozen source set."""

        return cls._load(
            path,
            expected_client_sha256=CLIENT_SHA256,
            expected_message_data_sha256=MESSAGE_DATA_SHA256,
            expected_source_sha256=FROZEN_SOURCE_SHA256,
        )

    @classmethod
    def _load(
        cls,
        path: str | Path,
        *,
        expected_client_sha256: str = CLIENT_SHA256,
        expected_message_data_sha256: str = MESSAGE_DATA_SHA256,
        expected_source_sha256: Mapping[str, str] = FROZEN_SOURCE_SHA256,
    ) -> "SourceManifest":
        manifest_path = Path(path).resolve()
        payload = _require_mapping(
            "source manifest", json.loads(manifest_path.read_text(encoding="utf-8"))
        )
        if payload.get("schemaVersion") != 1:
            raise ValueError("unsupported source manifest schemaVersion")
        base = manifest_path.parent
        verified: list[Path] = []

        client = _require_mapping("client", payload.get("client"))
        _validate_authority(client, "client")
        client_path, client_hash = _verify_file(base, client, "client")
        expected_client_hash = _require_sha256("expected client hash", expected_client_sha256)
        if client_hash != expected_client_hash:
            raise ValueError(
                f"client hash mismatch: expected {expected_client_hash}, got {client_hash}"
            )
        verified.append(client_path)

        message_data = _require_mapping("messageData", payload.get("messageData"))
        _validate_authority(message_data, "messageData")
        message_path, message_hash = _verify_file(base, message_data, "messageData")
        expected_message_hash = _require_sha256(
            "expected message data hash", expected_message_data_sha256
        )
        if message_hash != expected_message_hash:
            raise ValueError(
                "messageData hash mismatch: "
                f"expected {expected_message_hash}, got {message_hash}"
            )
        verified.append(message_path)

        sources = payload.get("sources")
        if not isinstance(sources, list):
            raise ValueError("sources must be a list")
        identifiers: set[str] = set()
        for index, raw_source in enumerate(sources):
            source = _require_mapping(f"sources[{index}]", raw_source)
            identifier = _require_text(f"sources[{index}].id", source.get("id"))
            if identifier in identifiers:
                raise ValueError(f"duplicate source id: {identifier}")
            identifiers.add(identifier)
            _validate_authority(source, f"sources[{index}]")
            source_path, _ = _verify_file(base, source, f"sources[{index}]")
            verified.append(source_path)
        expected_sources = {
            identifier: _require_sha256(f"expected source {identifier}", digest)
            for identifier, digest in expected_source_sha256.items()
        }
        missing_sources = sorted(set(expected_sources) - identifiers)
        if missing_sources:
            raise ValueError(f"missing frozen sources: {missing_sources}")
        source_by_id = {str(source["id"]): source for source in sources}
        for identifier, expected in expected_sources.items():
            actual = _require_sha256(
                f"source {identifier} hash", source_by_id[identifier].get("sha256")
            )
            if actual != expected:
                raise ValueError(
                    f"frozen source hash mismatch for {identifier}: expected {expected}, got {actual}"
                )
        required_derived_sources = {
            "ghidra-import-exporter", "ghidra-import-export", "pe-import-generator"
        }
        missing_derived_sources = sorted(required_derived_sources - identifiers)
        if missing_derived_sources:
            raise ValueError(f"missing derived provenance sources: {missing_derived_sources}")

        manual_policy = _require_mapping("manualPolicy", payload.get("manualPolicy"))
        if manual_policy.get("editionsRemainDistinct") is not True:
            raise ValueError("manual editions must remain distinct")
        if source_by_id["cd-manual-v1"]["path"] == source_by_id["official-web-manual-2004-10-07"]["path"]:
            raise ValueError("manual editions must use distinct source files")

        resource_roots = payload.get("resourceRoots")
        if not isinstance(resource_roots, list):
            raise ValueError("resourceRoots must be a list")
        for index, raw_root in enumerate(resource_roots):
            resource_root = _require_mapping(f"resourceRoots[{index}]", raw_root)
            _validate_authority(resource_root, f"resourceRoots[{index}]")
            root_path = _resolve(base, resource_root.get("path"))
            if not root_path.is_dir():
                raise ValueError(f"resource root is missing: {root_path}")
            tree_manifest = _require_mapping(
                f"resourceRoots[{index}].treeManifest", resource_root.get("treeManifest")
            )
            tree_manifest_path, _ = _verify_file(
                base, tree_manifest, f"resourceRoots[{index}].treeManifest"
            )
            _verify_tree_manifest(
                root_path,
                tree_manifest_path,
                _require_text(
                    f"resourceRoots[{index}].pathPrefix", resource_root.get("pathPrefix")
                ),
            )
            verified.append(tree_manifest_path)

        tools = _require_mapping("tools", payload.get("tools"))
        if not tools:
            raise ValueError("tools must contain at least one hash-bound tool")
        tool_paths: dict[str, Path] = {}
        for name, raw_tool in tools.items():
            _require_text("tool name", name)
            tool = _require_mapping(f"tools.{name}", raw_tool)
            tool_path, _ = _verify_file(base, tool, f"tools.{name}")
            tool_paths[name] = tool_path.resolve()
            verified.append(tool_path)
        if "pefile" not in tool_paths:
            raise ValueError("tools.pefile is required for PE import provenance")

        ghidra = _require_mapping("ghidra", payload.get("ghidra"))
        project_dir = _resolve(base, ghidra.get("projectDir"))
        project_name = _require_text("ghidra.projectName", ghidra.get("projectName"))
        _require_text("ghidra.programName", ghidra.get("programName"))
        if not project_dir.is_dir() or not (project_dir / f"{project_name}.gpr").is_file():
            raise ValueError(f"Ghidra project is missing: {project_dir / (project_name + '.gpr')}")
        repository = project_dir / f"{project_name}.rep"
        if not repository.is_dir():
            raise ValueError(f"Ghidra repository is missing: {repository}")
        expected_repository_hash = _require_sha256(
            "ghidra.repositorySha256", ghidra.get("repositorySha256")
        )
        repository_algorithm = ghidra.get(
            "repositoryHashAlgorithm",
            "sorted(relative-posix-path NUL byte-length NUL uppercase-file-sha256 LF)",
        )
        if repository_algorithm == "program-database-sha256":
            actual_repository_hash = ghidra_program_database_sha256(repository)
        else:
            actual_repository_hash = sha256_tree(repository)
        if actual_repository_hash != expected_repository_hash:
            raise ValueError(
                "Ghidra repository hash mismatch: "
                f"expected {expected_repository_hash}, got {actual_repository_hash}"
            )
        if _require_sha256(
            "ghidra.programExecutableSha256", ghidra.get("programExecutableSha256")
        ) != client_hash:
            raise ValueError("Ghidra program is bound to a different client hash")

        pe_record = _require_mapping("peImports", payload.get("peImports"))
        pe_path, _ = _verify_file(base, pe_record, "peImports")
        verified.append(pe_path)
        pe_payload = _require_mapping(
            "pe imports", json.loads(pe_path.read_text(encoding="utf-8"))
        )
        validate_import_gate(pe_payload)
        pe_source = _require_mapping("pe imports source", pe_payload.get("source"))
        evidence_client_hash = _require_sha256(
            "pe imports source executableSha256", pe_source.get("executableSha256")
        )
        if evidence_client_hash != client_hash:
            raise ValueError("pe import evidence is bound to a different client hash")
        embedded_client_path = Path(_require_text("pe imports source.path", pe_source.get("path")))
        if not embedded_client_path.is_file() or sha256_file(embedded_client_path) != client_hash:
            raise ValueError("PE import source path is missing or has a different client hash")
        if pe_source.get("size") != client_path.stat().st_size:
            raise ValueError("PE import source size does not match the frozen client")

        generator = _require_mapping("pe imports generator", pe_payload["generator"])
        builder = _require_mapping("generator.builder", generator["builder"])
        builder_path, _ = _verify_file(Path.cwd(), builder, "generator.builder")
        verified.append(builder_path)
        parser_record = _require_mapping("generator.rawPeParser", generator["rawPeParser"])
        parser_path, _ = _verify_file(Path.cwd(), parser_record, "generator.rawPeParser")
        verified.append(parser_path)
        if parser_path.resolve() != tool_paths["pefile"]:
            raise ValueError("raw PE parser differs from tools.pefile")
        cross_check = _require_mapping(
            "generator.ghidraCrossCheck", generator["ghidraCrossCheck"]
        )
        exporter_path, _ = _verify_file(
            Path.cwd(),
            {"path": cross_check.get("exporterPath"), "sha256": cross_check.get("exporterSha256")},
            "generator.ghidraCrossCheck.exporter",
        )
        output_path, _ = _verify_file(
            Path.cwd(),
            {"path": cross_check.get("outputPath"), "sha256": cross_check.get("outputSha256")},
            "generator.ghidraCrossCheck.output",
        )
        verified.extend((exporter_path, output_path))
        provenance_paths = {
            "pe-import-generator": builder_path,
            "ghidra-import-exporter": exporter_path,
            "ghidra-import-export": output_path,
        }
        for identifier, actual_path in provenance_paths.items():
            declared_path = _resolve(base, source_by_id[identifier].get("path")).resolve()
            if declared_path != actual_path.resolve():
                raise ValueError(f"embedded provenance path differs from source {identifier}")
        headers = _require_mapping("generator.ghidraCrossCheck.headers", cross_check.get("headers"))
        normalized_headers = {key: str(value) for key, value in headers.items()}
        if (
            normalized_headers.get("PROGRAM") != ghidra.get("programName")
            or normalized_headers.get("LANGUAGE") != ghidra.get("language")
            or normalized_headers.get("COMPILER") != ghidra.get("compiler")
            or int(normalized_headers.get("IMAGE_BASE", "-1"), 16)
            != int(str(ghidra.get("imageBase", "-1")), 16)
            or normalized_headers.get("EXECUTABLE_SHA256", "").upper() != client_hash
        ):
            raise ValueError("Ghidra cross-check headers do not match the frozen program")
        actual_headers: dict[str, str] = {}
        for line in output_path.read_text(encoding="utf-8").splitlines():
            if line == "===== IMPORT SUMMARY =====":
                break
            if "=" in line:
                key, value = line.split("=", 1)
                actual_headers[key] = value
        if actual_headers != normalized_headers:
            raise ValueError("embedded Ghidra headers do not match the hash-bound output")
        if int(str(pe_source.get("imageBase", "-1")), 16) != int(
            str(ghidra.get("imageBase", "-1")), 16
        ):
            raise ValueError("PE and Ghidra image bases differ")

        return cls(
            path=manifest_path,
            client_sha256=client_hash,
            message_data_sha256=message_hash,
            import_quality=str(pe_payload["quality"]),
            verified_paths=tuple(verified),
        )


def _main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args(argv)
    manifest = SourceManifest.load(args.manifest)
    print(
        canonical_json(
            {
                "status": "PASS",
                "manifest": str(manifest.path),
                "clientSha256": manifest.client_sha256,
                "messageDataSha256": manifest.message_data_sha256,
                "importQuality": manifest.import_quality,
                "verifiedPathCount": len(manifest.verified_paths),
            }
        ),
        end="",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
