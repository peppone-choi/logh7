"""Strict, hash-bound loading for the six exhaustive-trace inventories."""

from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping

from .io import canonical_json
from .model import ALLOWED_PROVENANCE, freeze_json
from .source_manifest import SourceManifest


STATE_NAMES = frozenset(
    {
        "ENUMERATED", "STATIC_MAPPED", "CODEC_PROVEN", "RUNTIME_OBSERVED",
        "PLAYER_VISIBLE", "AUTHORITY_PROVEN", "PERSISTENCE_PROVEN",
        "BOTH_FACTIONS", "INDEPENDENTLY_REVIEWED",
    }
)
REACHABILITY_VALUES = frozenset(
    {"SHIPPED_REACHABLE", "SHIPPED_DORMANT", "MANUAL_ONLY", "UNKNOWN"}
)
RECOVERY_VALUES = frozenset(
    {
        "RECOVERED_ORIGINAL", "RECOVERABLE_STATIC", "RECOVERABLE_LIVE",
        "SOURCE_CONFLICT", "ORIGINAL_SERVER_LOST", "ORIGINAL_UNIMPLEMENTED",
        "AUTHORING_REQUIRED", "RIGHTS_REVIEW_REQUIRED",
    }
)


@dataclass(frozen=True)
class InventorySpec:
    logical_name: str
    inventory: str
    filename: str
    reconciliation_filename: str


INVENTORY_SPECS = MappingProxyType(
    {
        name: InventorySpec(name, inventory, f"{name}.jsonl", f"{name}-reconciliation.json")
        for name, inventory in (
            ("protocol", "PROTOCOL"), ("ui", "UI"), ("entities", "ENTITY"),
            ("resources", "RESOURCE"), ("functions", "FUNCTION"),
            ("authority", "AUTHORITY"),
        )
    }
)


@dataclass(frozen=True)
class InventorySource:
    logical_name: str
    inventory: str
    filename: str
    file_sha256: str
    canonical_rows_sha256: str
    row_count: int
    reconciliation_filename: str
    reconciliation_sha256: str
    candidate_count: int
    normalized_count: int
    unresolved_count: int
    excluded_count: int
    unaccounted_count: int


@dataclass(frozen=True)
class InventoryBundle:
    root: Path
    rows: tuple[Mapping[str, Any], ...]
    sources: Mapping[str, InventorySource]
    source_manifest_path: Path
    source_manifest_sha256: str
    client_sha256: str
    message_data_sha256: str
    bundle_sha256: str

    @property
    def row_count(self) -> int:
        return len(self.rows)


def _sha_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest().upper()


def _sha_value(value: Any) -> str:
    return _sha_bytes(canonical_json(value).encode("utf-8"))


def _is_reparse(path: Path) -> bool:
    try:
        stat = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def _safe_path(path: str | Path, label: str, *, directory: bool) -> Path:
    declared = Path(os.path.abspath(path))
    for component in [*reversed(declared.parents), declared]:
        if component == Path(component.anchor):
            continue
        if (component.exists() or component.is_symlink()) and _is_reparse(component):
            raise ValueError(f"{label} contains a link or reparse point: {component}")
    exists = declared.is_dir() if directory else declared.is_file()
    if not exists:
        raise ValueError(f"{label} is missing: {declared}")
    return declared.resolve()


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _parse_json_bytes(data: bytes, label: str) -> Mapping[str, Any]:
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError(f"{label} must be canonical UTF-8 LF JSON")
    try:
        text = data.decode("utf-8")
        value = json.loads(text, object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not valid UTF-8 JSON") from error
    if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != data:
        raise ValueError(f"{label} is not canonical JSON")
    return value


def _parse_jsonl_bytes(data: bytes, label: str) -> list[dict[str, Any]]:
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError(f"{label} must use canonical UTF-8 LF JSONL")
    rows: list[dict[str, Any]] = []
    for number, raw_line in enumerate(data.splitlines(keepends=True), 1):
        if raw_line == b"\n":
            raise ValueError(f"{label}:{number} contains a blank line")
        try:
            text = raw_line.decode("utf-8")
            value = json.loads(text, object_pairs_hook=_object_no_duplicates)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"{label}:{number} is invalid JSON") from error
        if not isinstance(value, dict) or canonical_json(value).encode("utf-8") != raw_line:
            raise ValueError(f"{label}:{number} is not canonical JSONL")
        rows.append(value)
    if not rows:
        raise ValueError(f"{label} inventory is empty")
    return rows


def _text(name: str, value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _validate_envelope(row: Mapping[str, Any], spec: InventorySpec, number: int) -> None:
    prefix = f"{spec.filename}:{number}"
    if row.get("inventory") != spec.inventory:
        raise ValueError(f"{prefix} inventory mismatch")
    key = _text(f"{prefix}.key", row.get("key"))
    expected_prefix = {
        "PROTOCOL": "PROTOCOL:", "UI": "UI:", "ENTITY": "ENTITY:",
        "RESOURCE": "RESOURCE:", "FUNCTION": "FUNCTION:", "AUTHORITY": "AUTHORITY:",
    }[spec.inventory]
    if not key.startswith(expected_prefix):
        raise ValueError(f"{prefix} key prefix differs from inventory")
    _text(f"{prefix}.name", row.get("name"))
    if row.get("provenance") not in ALLOWED_PROVENANCE:
        raise ValueError(f"{prefix} provenance is unsupported")
    if row.get("reachability") not in REACHABILITY_VALUES:
        raise ValueError(f"{prefix} reachability is unsupported")
    if row.get("recoveryDisposition") not in RECOVERY_VALUES:
        raise ValueError(f"{prefix} recovery disposition is unsupported")
    states = row.get("states")
    if not isinstance(states, Mapping) or set(states) != STATE_NAMES or any(type(v) is not bool for v in states.values()):
        raise ValueError(f"{prefix} states contract mismatch")
    evidence = row.get("evidence")
    if not isinstance(evidence, list) or not evidence or any(not isinstance(item, str) or not item.strip() for item in evidence):
        raise ValueError(f"{prefix} evidence must contain text references")


def load_inventory_bundle(
    directory: str | Path,
    *,
    source_manifest: str | Path,
) -> InventoryBundle:
    root = _safe_path(directory, "inventory directory", directory=True)
    expected_names = {
        item for spec in INVENTORY_SPECS.values()
        for item in (spec.filename, spec.reconciliation_filename)
    }
    actual_files = [path for path in root.iterdir() if path.is_file() or path.is_symlink()]
    folded: dict[str, str] = {}
    for path in actual_files:
        name = path.name
        prior = folded.setdefault(name.casefold(), name)
        if prior != name:
            raise ValueError(f"casefold artifact collision: {prior} / {name}")
    actual_names = {path.name for path in actual_files}
    missing = sorted(expected_names - actual_names)
    extra = sorted(actual_names - expected_names)
    if missing or extra:
        raise ValueError(f"inventory artifact set mismatch; missing={missing}, extra={extra}")

    manifest_path = _safe_path(source_manifest, "source manifest", directory=False)
    manifest_bytes = manifest_path.read_bytes()
    manifest = SourceManifest.load(manifest_path)
    manifest_sha = _sha_bytes(manifest_bytes)

    parsed_inventories: dict[str, tuple[bytes, list[dict[str, Any]]]] = {}
    global_keys: dict[str, str] = {}
    for logical_name, spec in INVENTORY_SPECS.items():
        inventory_path = _safe_path(root / spec.filename, spec.filename, directory=False)
        inventory_bytes = inventory_path.read_bytes()
        rows = _parse_jsonl_bytes(inventory_bytes, spec.filename)
        parsed_inventories[logical_name] = (inventory_bytes, rows)
        for row in rows:
            key = _text(f"{spec.filename}.key", row.get("key"))
            folded_key = key.casefold()
            if folded_key in global_keys:
                raise ValueError(
                    f"global duplicate/collision inventory key: {global_keys[folded_key]} / {key}"
                )
            global_keys[folded_key] = key

    all_rows: list[dict[str, Any]] = []
    sources: dict[str, InventorySource] = {}
    for logical_name, spec in INVENTORY_SPECS.items():
        reconciliation_path = _safe_path(
            root / spec.reconciliation_filename, spec.reconciliation_filename, directory=False
        )
        inventory_bytes, rows = parsed_inventories[logical_name]
        reconciliation_bytes = reconciliation_path.read_bytes()
        for number, row in enumerate(rows, 1):
            _validate_envelope(row, spec, number)
            all_rows.append(row)
        reconciliation = _parse_json_bytes(reconciliation_bytes, spec.reconciliation_filename)
        if reconciliation.get("schemaVersion") != 1:
            raise ValueError(f"{spec.reconciliation_filename} schemaVersion mismatch")
        unaccounted = reconciliation.get("unaccountedCount")
        if type(unaccounted) is not int or unaccounted != 0:
            raise ValueError(f"{spec.reconciliation_filename} has unaccounted candidates")
        normalized = reconciliation.get("normalizedCount", 0)
        unresolved = reconciliation.get("unresolvedCount", 0)
        excluded = reconciliation.get("excludedCount", 0)
        for field_name, value in (
            ("normalizedCount", normalized), ("unresolvedCount", unresolved),
            ("excludedCount", excluded),
        ):
            if type(value) is not int or value < 0:
                raise ValueError(f"{spec.reconciliation_filename}.{field_name} must be non-negative int")
        candidate = reconciliation.get("candidateCount")
        if candidate is None:
            records = reconciliation.get("records") or reconciliation.get("candidates")
            candidate = len(records) if isinstance(records, list) else normalized + unresolved + excluded
        if type(candidate) is not int or candidate != normalized + unresolved + excluded:
            raise ValueError(f"{spec.reconciliation_filename} conservation mismatch")
        canonical_rows_sha = _sha_value(sorted(rows, key=lambda row: (row["key"].casefold(), row["key"])))
        sources[logical_name] = InventorySource(
            logical_name=logical_name,
            inventory=spec.inventory,
            filename=spec.filename,
            file_sha256=_sha_bytes(inventory_bytes),
            canonical_rows_sha256=canonical_rows_sha,
            row_count=len(rows),
            reconciliation_filename=spec.reconciliation_filename,
            reconciliation_sha256=_sha_bytes(reconciliation_bytes),
            candidate_count=candidate,
            normalized_count=normalized,
            unresolved_count=unresolved,
            excluded_count=excluded,
            unaccounted_count=unaccounted,
        )
    ordered_rows = tuple(
        freeze_json(row)
        for row in sorted(all_rows, key=lambda row: (row["key"].casefold(), row["key"]))
    )
    source_payload = {
        name: {
            "inventory": source.inventory,
            "filename": source.filename,
            "fileSha256": source.file_sha256,
            "canonicalRowsSha256": source.canonical_rows_sha256,
            "rowCount": source.row_count,
            "reconciliationFilename": source.reconciliation_filename,
            "reconciliationSha256": source.reconciliation_sha256,
            "candidateCount": source.candidate_count,
            "normalizedCount": source.normalized_count,
            "unresolvedCount": source.unresolved_count,
            "excludedCount": source.excluded_count,
            "unaccountedCount": source.unaccounted_count,
        }
        for name, source in sorted(sources.items())
    }
    bundle_sha = _sha_value(
        {
            "schemaVersion": 1,
            "sourceManifestSha256": manifest_sha,
            "clientSha256": manifest.client_sha256,
            "messageDataSha256": manifest.message_data_sha256,
            "sources": source_payload,
        }
    )
    return InventoryBundle(
        root=root,
        rows=ordered_rows,
        sources=MappingProxyType(sources),
        source_manifest_path=manifest_path,
        source_manifest_sha256=manifest_sha,
        client_sha256=manifest.client_sha256,
        message_data_sha256=manifest.message_data_sha256,
        bundle_sha256=bundle_sha,
    )


__all__ = [
    "INVENTORY_SPECS", "InventoryBundle", "InventorySource", "InventorySpec",
    "load_inventory_bundle",
]
