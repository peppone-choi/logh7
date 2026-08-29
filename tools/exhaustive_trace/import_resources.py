"""Fail-closed normalization for original resource files and loader candidates."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path, PurePosixPath
from types import MappingProxyType
from typing import Any, Mapping

from .io import canonical_json, sha256_file
from .model import (
    EvidenceState,
    ImplementationTarget,
    InventoryKind,
    InventoryRow,
    Reachability,
    RecoveryDisposition,
    StringEnum,
)
from .source_manifest import CLIENT_SHA256, SourceManifest


class ResourceRowKind(StringEnum):
    TREE_FILE = "TREE_FILE"
    EXTERNAL_DEPENDENCY = "EXTERNAL_DEPENDENCY"
    MANUAL_REQUIREMENT = "MANUAL_REQUIREMENT"


class ResourceSectionStatus(StringEnum):
    PROVEN = "PROVEN"
    CANDIDATE = "CANDIDATE"
    STATIC_MAPPED = "STATIC_MAPPED"
    RUNTIME_OBSERVED = "RUNTIME_OBSERVED"
    UNKNOWN = "UNKNOWN"
    NOT_APPLICABLE = "NOT_APPLICABLE"


class UsageDisposition(StringEnum):
    ENUMERATED_ONLY = "ENUMERATED_ONLY"
    ORPHAN = "ORPHAN"
    DORMANT_CANDIDATE = "DORMANT_CANDIDATE"
    INTEGRATED = "INTEGRATED"


class ImplementationStatus(StringEnum):
    REQUIRED = "REQUIRED"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


RESOURCE_CATEGORIES = frozenset(
    {
        "MODEL",
        "TEXTURE",
        "PORTRAIT",
        "BACKGROUND",
        "SPOT_BACKGROUND",
        "FONT",
        "MESSAGE",
        "SOUND",
        "MAP",
        "CURSOR",
        "CONFIGURATION",
        "EXECUTABLE",
        "DOCUMENTATION",
        "DATABASE",
        "OTHER",
        "UNKNOWN",
    }
)

CANDIDATE_COLLECTIONS = (
    "literalPathCandidates",
    "pathFormatterCandidates",
    "loaderCandidates",
    "decodeTransformCandidates",
    "runtimeKeyCandidates",
    "cacheRegistryCandidates",
    "ownerCandidates",
    "renderSubmissionCandidates",
    "audioSubmissionCandidates",
    "uiSubmissionCandidates",
    "presentationReceiptCandidates",
    "externalDependencyCandidates",
    "manualResourceCandidates",
)

TOP_LEVEL_FIELDS = frozenset(
    {
        "schemaVersion",
        "source",
        "exporter",
        "surfaceSha256",
        "successMarker",
        "audit",
        "conservation",
        *CANDIDATE_COLLECTIONS,
    }
)

SHA256_PATTERN = re.compile(r"^[0-9A-Fa-f]{64}$")
FUNCTION_PATTERN = re.compile(r"^(?:FUN_)?[0-9A-Fa-f]{8}$")
RUNTIME_POINTER_PATTERN = re.compile(r"^0x[0-9A-Fa-f]{8}$")
EXPECTED_LANGUAGE = "x86:LE:32:default"
EXPECTED_COMPILER = "windows"
EXPECTED_IMAGE_BASE = "00400000"


def _mapping(name: str, value: object) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _text(name: str, value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value.strip()


def _optional_text(name: str, value: object) -> str | None:
    if value is None:
        return None
    return _text(name, value)


def _sha256(name: str, value: object) -> str:
    result = _text(name, value).upper()
    if not SHA256_PATTERN.fullmatch(result):
        raise ValueError(f"{name} must be a SHA-256")
    return result


def _text_list(name: str, value: object, *, allow_empty: bool = True) -> tuple[str, ...]:
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item.strip() for item in value
    ):
        raise ValueError(f"{name} must be a text list")
    if not allow_empty and not value:
        raise ValueError(f"{name} must not be empty")
    return tuple(item.strip() for item in value)


def _evidence(name: str, value: object) -> tuple[str, ...]:
    return _text_list(name, value, allow_empty=False)


def _safe_resource_path(value: object) -> str:
    path = _text("resource path", value)
    if "\\" in path or "\x00" in path or path.startswith(("/", "./")):
        raise ValueError(f"unsafe resource path: {path}")
    pure = PurePosixPath(path)
    if pure.is_absolute() or any(part in {"", ".", ".."} for part in pure.parts):
        raise ValueError(f"unsafe resource path: {path}")
    return pure.as_posix()


@dataclass(frozen=True)
class TreeManifestEntry:
    relative_path: str
    content_sha256: str
    byte_size: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "relative_path", _safe_resource_path(self.relative_path))
        object.__setattr__(self, "content_sha256", _sha256("contentSha256", self.content_sha256))
        if not isinstance(self.byte_size, int) or isinstance(self.byte_size, bool) or self.byte_size < 0:
            raise ValueError("byteSize must be a non-negative integer")


@dataclass(frozen=True)
class ResourceSection:
    status: ResourceSectionStatus
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class ResourceImplementationSection:
    status: ImplementationStatus
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class ResourceInventoryRow:
    row: InventoryRow
    row_kind: ResourceRowKind
    source: Mapping[str, Any]
    format: ResourceSection
    category: ResourceSection
    path_resolution: ResourceSection
    loader: ResourceSection
    runtime_key: ResourceSection
    owner: ResourceSection
    decode_transform: ResourceSection
    cache_registry: ResourceSection
    submissions: Mapping[str, ResourceSection]
    presentation: ResourceSection
    usage_disposition: UsageDisposition
    distribution_disposition: str
    implementation_disposition: Mapping[str, ResourceImplementationSection]
    recovery_disposition: RecoveryDisposition
    first_missing_boundary: str
    reachability_evidence: tuple[str, ...]
    evidence: tuple[str, ...]
    source_candidate_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "source", MappingProxyType(dict(self.source)))
        object.__setattr__(self, "submissions", MappingProxyType(dict(self.submissions)))
        object.__setattr__(
            self,
            "implementation_disposition",
            MappingProxyType(dict(self.implementation_disposition)),
        )


@dataclass(frozen=True)
class ResourcesEvidenceManifest:
    path: Path
    raw_path: Path
    raw_sha256: str
    exporter_path: Path
    exporter_sha256: str
    repository_sha256: str
    source_manifest_path: Path
    source_manifest_sha256: str
    tree_manifest_path: Path
    tree_manifest_sha256: str
    pe_imports_path: Path
    pe_imports_sha256: str


def load_resource_adjudications(
    path: Path,
    *,
    expected_root_id: str,
    expected_source_manifest_sha256: str,
    expected_tree_manifest_sha256: str,
) -> dict[str, Mapping[str, Any]]:
    payload = _mapping("resource adjudications", json.loads(path.read_text(encoding="utf-8")))
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported resource adjudications schemaVersion")
    source = _mapping("resource adjudications source", payload.get("source"))
    if source.get("rootId") != _text("expected rootId", expected_root_id):
        raise ValueError("resource adjudications rootId differs")
    if _sha256("sourceManifestSha256", source.get("sourceManifestSha256")) != _sha256(
        "expected sourceManifestSha256", expected_source_manifest_sha256
    ):
        raise ValueError("resource adjudications source manifest differs")
    if _sha256("treeManifestSha256", source.get("treeManifestSha256")) != _sha256(
        "expected treeManifestSha256", expected_tree_manifest_sha256
    ):
        raise ValueError("resource adjudications tree manifest differs")
    items = payload.get("adjudications")
    if not isinstance(items, list):
        raise ValueError("resource adjudications must be an array")
    result: dict[str, Mapping[str, Any]] = {}
    for value in items:
        item = _mapping("resource adjudication", value)
        relative_path = _safe_resource_path(item.get("relativePosixPath"))
        if relative_path in result:
            raise ValueError(f"duplicate resource adjudication path: {relative_path}")
        if item.get("kind") == "PE_EXECUTABLE_BOOTSTRAP":
            analysis = _mapping("PE bootstrap analysis", item.get("analysis"))
            receipt_path = Path(_text("analysis.receiptPath", analysis.get("receiptPath")))
            if not receipt_path.is_absolute():
                receipt_path = Path.cwd() / receipt_path
            receipt_path = receipt_path.resolve()
            receipt_sha = _sha256("analysis.receiptSha256", analysis.get("receiptSha256"))
            if not receipt_path.is_file() or sha256_file(receipt_path) != receipt_sha:
                raise ValueError("PE bootstrap analysis receipt hash mismatch")
            receipt = _mapping(
                "PE bootstrap analysis receipt",
                json.loads(receipt_path.read_text(encoding="utf-8")),
            )
            if receipt.get("schemaVersion") != 1 or receipt.get("status") != "PROVEN_STATIC":
                raise ValueError("PE bootstrap analysis receipt contract differs")
            receipt_source = _mapping("PE bootstrap receipt source", receipt.get("source"))
            if (
                _sha256("receipt.source.sha256", receipt_source.get("sha256"))
                != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or receipt_source.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("PE bootstrap analysis receipt source differs")
            static_tools = _mapping("PE bootstrap receipt staticTools", receipt.get("staticTools"))
            bound_tool_count = 0
            for label, record_value in static_tools.items():
                if not isinstance(record_value, Mapping):
                    continue
                record = _mapping(f"staticTools.{label}", record_value)
                referenced_path = Path(_text(f"staticTools.{label}.path", record.get("path")))
                if not referenced_path.is_absolute():
                    referenced_path = Path.cwd() / referenced_path
                referenced_path = referenced_path.resolve()
                expected_sha = _sha256(
                    f"staticTools.{label}.sha256", record.get("sha256")
                )
                if not referenced_path.is_file() or sha256_file(referenced_path) != expected_sha:
                    raise ValueError("PE bootstrap referenced evidence hash mismatch")
                bound_tool_count += 1
            if bound_tool_count == 0:
                raise ValueError("PE bootstrap analysis receipt lacks bound static evidence")
        elif item.get("kind") == "PDF_OPERATION_MANUAL":
            analysis = _mapping("PDF operation manual analysis", item.get("analysis"))
            receipt_path = Path(_text("analysis.receiptPath", analysis.get("receiptPath")))
            if not receipt_path.is_absolute():
                receipt_path = Path.cwd() / receipt_path
            receipt_path = receipt_path.resolve()
            receipt_sha = _sha256("analysis.receiptSha256", analysis.get("receiptSha256"))
            if not receipt_path.is_file() or sha256_file(receipt_path) != receipt_sha:
                raise ValueError("PDF operation manual analysis receipt hash mismatch")
            receipt = _mapping(
                "PDF operation manual analysis receipt",
                json.loads(receipt_path.read_text(encoding="utf-8")),
            )
            if receipt.get("schemaVersion") != 1 or receipt.get("status") != "PROVEN_STATIC":
                raise ValueError("PDF operation manual analysis receipt contract differs")
            receipt_source = _mapping("PDF operation manual receipt source", receipt.get("source"))
            if (
                _sha256("receipt.source.sha256", receipt_source.get("sha256"))
                != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or receipt_source.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("PDF operation manual receipt source differs")
            receipt_analysis = _mapping(
                "PDF operation manual receipt analysis", receipt.get("analysis")
            )
            item_analysis = {
                key: value
                for key, value in analysis.items()
                if key not in {"status", "receiptPath", "receiptSha256", "evidence"}
            }
            if receipt_analysis != item_analysis:
                raise ValueError("PDF operation manual receipt analysis differs")
            if receipt_analysis.get("headerHex") != "255044462D312E340D25E2E3CFD30D0A":
                raise ValueError("PDF operation manual header differs")
            receipt_external = receipt.get("externalDocumentOpen")
            item_external = item.get("externalDocumentOpen")
            if receipt_external is not None or item_external is not None:
                if (
                    _mapping("PDF operation manual receipt externalDocumentOpen", receipt_external)
                    != _mapping("PDF operation manual adjudication externalDocumentOpen", item_external)
                ):
                    raise ValueError(
                        "PDF operation manual receipt external document open differs"
                    )
            static_tools = _mapping(
                "PDF operation manual receipt staticTools", receipt.get("staticTools")
            )
            bound_tool_count = 0
            for label, record_value in static_tools.items():
                if not isinstance(record_value, Mapping):
                    continue
                record = _mapping(f"staticTools.{label}", record_value)
                referenced_path = Path(_text(f"staticTools.{label}.path", record.get("path")))
                if not referenced_path.is_absolute():
                    referenced_path = Path.cwd() / referenced_path
                referenced_path = referenced_path.resolve()
                expected_sha = _sha256(f"staticTools.{label}.sha256", record.get("sha256"))
                if not referenced_path.is_file() or sha256_file(referenced_path) != expected_sha:
                    raise ValueError("PDF operation manual referenced evidence hash mismatch")
                bound_tool_count += 1
            if bound_tool_count == 0:
                raise ValueError("PDF operation manual analysis receipt lacks bound static evidence")
        elif item.get("kind") == "CP932_TERMS_DOCUMENT":
            analysis = _mapping("CP932 terms analysis", item.get("analysis"))
            receipt_path = Path(_text("analysis.receiptPath", analysis.get("receiptPath")))
            if not receipt_path.is_absolute():
                receipt_path = Path.cwd() / receipt_path
            receipt_path = receipt_path.resolve()
            receipt_sha = _sha256("analysis.receiptSha256", analysis.get("receiptSha256"))
            if not receipt_path.is_file() or sha256_file(receipt_path) != receipt_sha:
                raise ValueError("CP932 terms analysis receipt hash mismatch")
            receipt = _mapping(
                "CP932 terms analysis receipt",
                json.loads(receipt_path.read_text(encoding="utf-8")),
            )
            if receipt.get("schemaVersion") != 1 or receipt.get("status") != "PROVEN_STATIC":
                raise ValueError("CP932 terms analysis receipt contract differs")
            receipt_source = _mapping("CP932 terms receipt source", receipt.get("source"))
            if (
                _sha256("receipt.source.sha256", receipt_source.get("sha256"))
                != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or receipt_source.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("CP932 terms receipt source differs")
            receipt_analysis = _mapping("CP932 terms receipt analysis", receipt.get("analysis"))
            item_analysis = {
                key: value
                for key, value in analysis.items()
                if key not in {"status", "receiptPath", "receiptSha256", "evidence"}
            }
            if receipt_analysis != item_analysis:
                raise ValueError("CP932 terms receipt analysis differs")
            receipt_duplicate = _mapping(
                "CP932 terms receipt duplicateSource", receipt.get("duplicateSource")
            )
            item_duplicate = _mapping(
                "CP932 terms adjudication duplicateSource", item.get("duplicateSource")
            )
            if receipt_duplicate != item_duplicate:
                raise ValueError("CP932 terms receipt duplicate source differs")
            duplicate_path = Path(_text("duplicateSource.path", item_duplicate.get("path")))
            if not duplicate_path.is_absolute():
                duplicate_path = Path.cwd() / duplicate_path
            duplicate_path = duplicate_path.resolve()
            duplicate_sha = _sha256(
                "duplicateSource.contentSha256", item_duplicate.get("contentSha256")
            )
            if (
                not duplicate_path.is_file()
                or sha256_file(duplicate_path) != duplicate_sha
                or duplicate_path.stat().st_size != item_duplicate.get("byteSize")
            ):
                raise ValueError("CP932 terms duplicate source file differs")
            if (
                duplicate_sha != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or item_duplicate.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("CP932 terms duplicate is not byte-identical to source")
            static_tools = _mapping("CP932 terms receipt staticTools", receipt.get("staticTools"))
            bound_tool_count = 0
            for label, record_value in static_tools.items():
                if not isinstance(record_value, Mapping):
                    continue
                record = _mapping(f"staticTools.{label}", record_value)
                referenced_path = Path(_text(f"staticTools.{label}.path", record.get("path")))
                if not referenced_path.is_absolute():
                    referenced_path = Path.cwd() / referenced_path
                referenced_path = referenced_path.resolve()
                expected_sha = _sha256(f"staticTools.{label}.sha256", record.get("sha256"))
                if not referenced_path.is_file() or sha256_file(referenced_path) != expected_sha:
                    raise ValueError("CP932 terms referenced evidence hash mismatch")
                bound_tool_count += 1
            if bound_tool_count == 0:
                raise ValueError("CP932 terms analysis receipt lacks bound static evidence")
        elif item.get("kind") == "PE_GAME_UPDATER_EXECUTABLE":
            analysis = _mapping("game updater analysis", item.get("analysis"))
            receipt_path = Path(_text("analysis.receiptPath", analysis.get("receiptPath")))
            if not receipt_path.is_absolute():
                receipt_path = Path.cwd() / receipt_path
            receipt_path = receipt_path.resolve()
            receipt_sha = _sha256("analysis.receiptSha256", analysis.get("receiptSha256"))
            if not receipt_path.is_file() or sha256_file(receipt_path) != receipt_sha:
                raise ValueError("game updater analysis receipt hash mismatch")
            receipt = _mapping(
                "game updater analysis receipt",
                json.loads(receipt_path.read_text(encoding="utf-8")),
            )
            if set(receipt) != {
                "schemaVersion", "status", "scope", "source", "analysis",
                "processImage", "processLaunch", "peTriage", "installshieldEvidence",
                "launchLimitations", "staticTools", "toolEnvironment",
            }:
                raise ValueError("game updater receipt fields differ")
            if receipt.get("schemaVersion") != 1 or receipt.get("status") != "PROVEN_STATIC":
                raise ValueError("game updater analysis receipt contract differs")
            if receipt.get("scope") != "GIN7UPDATECLIENT_RESOURCE_LOADER_BOUNDARY":
                raise ValueError("game updater receipt scope differs")
            receipt_source = _mapping("game updater receipt source", receipt.get("source"))
            if (
                _sha256("receipt.source.sha256", receipt_source.get("sha256"))
                != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or receipt_source.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("game updater analysis receipt source differs")
            receipt_analysis = _mapping("game updater receipt analysis", receipt.get("analysis"))
            item_analysis = {
                key: value
                for key, value in analysis.items()
                if key not in {"status", "receiptPath", "receiptSha256", "evidence"}
            }
            if receipt_analysis != item_analysis:
                raise ValueError("game updater receipt analysis differs")
            for field, label in (
                ("processImage", "process image"),
                ("processLaunch", "process launch"),
            ):
                if _mapping(f"game updater receipt {label}", receipt.get(field)) != _mapping(
                    f"game updater adjudication {label}", item.get(field)
                ):
                    raise ValueError(f"game updater receipt {label} differs")
            static_tools = _mapping("game updater receipt staticTools", receipt.get("staticTools"))
            mandatory_tools = {
                "inspectorScript", "updater", "targetClient", "originalCdIso",
                "updaterPeImports", "bootfirstStaticAnalysis", "g7mtclientStaticAnalysis",
            }
            if set(static_tools) != mandatory_tools:
                raise ValueError("game updater mandatory static tools differ")
            for label, record_value in static_tools.items():
                record = _mapping(f"staticTools.{label}", record_value)
                if set(record) != {"path", "sha256"}:
                    raise ValueError("game updater static tool fields differ")
                referenced_path = Path(_text(f"staticTools.{label}.path", record.get("path")))
                if not referenced_path.is_absolute():
                    referenced_path = Path.cwd() / referenced_path
                referenced_path = referenced_path.resolve()
                expected_sha = _sha256(f"staticTools.{label}.sha256", record.get("sha256"))
                if not referenced_path.is_file() or sha256_file(referenced_path) != expected_sha:
                    raise ValueError("game updater referenced evidence hash mismatch")
            imports_record = _mapping("updater imports tool", static_tools["updaterPeImports"])
            imports_path = Path(_text("updater imports path", imports_record.get("path")))
            if not imports_path.is_absolute():
                imports_path = Path.cwd() / imports_path
            imports_receipt = _mapping(
                "updater imports receipt",
                json.loads(imports_path.resolve().read_text(encoding="utf-8")),
            )
            imports_source = _mapping("updater imports source", imports_receipt.get("source"))
            if (
                imports_receipt.get("status") != "PROVEN_STATIC"
                or imports_receipt.get("quality")
                != "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION"
                or imports_receipt.get("descriptorCount") != 11
                or imports_receipt.get("importCount") != 347
                or imports_source.get("sha256") != item.get("contentSha256")
                or imports_source.get("byteSize") != item.get("byteSize")
                or imports_receipt.get("dynamicResolutionSurface")
                != ["KERNEL32.DLL::LoadLibraryA", "KERNEL32.DLL::GetProcAddress"]
            ):
                raise ValueError("game updater imports receipt differs")
            pe_triage = _mapping("game updater peTriage", receipt.get("peTriage"))
            capabilities = _mapping(
                "game updater directImportCapabilities",
                pe_triage.get("directImportCapabilities"),
            )
            if (
                pe_triage.get("exports") != 0
                or pe_triage.get("overlayBytes") != 0
                or pe_triage.get("authenticode") is not False
                or capabilities.get("createProcessA") != "0x004402AC"
                or capabilities.get("winsockImports") != 21
                or capabilities.get("registryImports") != 5
                or capabilities.get("fileMutationSurface") is not True
            ):
                raise ValueError("game updater PE triage anchors differ")
            limitations = _mapping(
                "game updater launchLimitations", receipt.get("launchLimitations")
            )
            if limitations != {
                "defaultConfigurationMayBeOverridden": True,
                "downloadedPayloadIdentity": "UNRESOLVED",
                "gateSemantics": "UNRESOLVED",
                "networkSuccess": "UNSEEN",
                "playability": "NOT_CLAIMED",
                "remoteVersionComparison": "UNRESOLVED",
                "runtimeLaunch": "UNSEEN",
            }:
                raise ValueError("game updater launch limitations differ")
        elif item.get("kind") == "PE_PRIMARY_GAME_CLIENT_EXECUTABLE":
            analysis = _mapping("primary game client analysis", item.get("analysis"))
            receipt_path = Path(_text("analysis.receiptPath", analysis.get("receiptPath")))
            if not receipt_path.is_absolute():
                receipt_path = Path.cwd() / receipt_path
            receipt_path = receipt_path.resolve()
            receipt_sha = _sha256("analysis.receiptSha256", analysis.get("receiptSha256"))
            if not receipt_path.is_file() or sha256_file(receipt_path) != receipt_sha:
                raise ValueError("primary game client analysis receipt hash mismatch")
            receipt = _mapping(
                "primary game client analysis receipt",
                json.loads(receipt_path.read_text(encoding="utf-8")),
            )
            if receipt.get("schemaVersion") != 1 or receipt.get("status") != "PROVEN_STATIC":
                raise ValueError("primary game client analysis receipt contract differs")
            receipt_source = _mapping("primary game client receipt source", receipt.get("source"))
            if (
                _sha256("receipt.source.sha256", receipt_source.get("sha256"))
                != _sha256("adjudication.contentSha256", item.get("contentSha256"))
                or receipt_source.get("byteSize") != item.get("byteSize")
            ):
                raise ValueError("primary game client receipt source differs")
            receipt_analysis = _mapping(
                "primary game client receipt analysis", receipt.get("analysis")
            )
            item_analysis = {
                key: value
                for key, value in analysis.items()
                if key not in {"status", "receiptPath", "receiptSha256", "evidence"}
            }
            if receipt_analysis != item_analysis:
                raise ValueError("primary game client receipt analysis differs")
            receipt_process_image = _mapping(
                "primary game client receipt processImage", receipt.get("processImage")
            )
            item_process_image = _mapping(
                "primary game client adjudication processImage", item.get("processImage")
            )
            if receipt_process_image != item_process_image:
                raise ValueError("primary game client receipt process image differs")
            receipt_inbound = _mapping(
                "primary game client receipt inboundLaunch", receipt.get("inboundLaunch")
            )
            item_inbound = _mapping(
                "primary game client adjudication inboundLaunch", item.get("inboundLaunch")
            )
            if receipt_inbound != item_inbound:
                raise ValueError("primary game client receipt inbound launch differs")
            static_tools = _mapping(
                "primary game client receipt staticTools", receipt.get("staticTools")
            )
            bound_tool_count = 0
            for label, record_value in static_tools.items():
                if not isinstance(record_value, Mapping):
                    continue
                record = _mapping(f"staticTools.{label}", record_value)
                referenced_path = Path(_text(f"staticTools.{label}.path", record.get("path")))
                if not referenced_path.is_absolute():
                    referenced_path = Path.cwd() / referenced_path
                referenced_path = referenced_path.resolve()
                expected_sha = _sha256(f"staticTools.{label}.sha256", record.get("sha256"))
                if not referenced_path.is_file() or sha256_file(referenced_path) != expected_sha:
                    raise ValueError("primary game client referenced evidence hash mismatch")
                bound_tool_count += 1
            if bound_tool_count == 0:
                raise ValueError("primary game client analysis receipt lacks bound static evidence")
        result[relative_path] = item
    return result


def _unknown_section(**empty_values: object) -> ResourceSection:
    return ResourceSection(
        ResourceSectionStatus.UNKNOWN,
        {**empty_values, "evidence": ("resource-surface:unjoined",)},
    )


def _required_implementation() -> Mapping[str, ResourceImplementationSection]:
    return {
        target.value: ResourceImplementationSection(
            ImplementationStatus.REQUIRED,
            {
                "reason": None,
                "evidence": (f"goal:implementation-layer:{target.value}",),
            },
        )
        for target in ImplementationTarget
    }


def _category_for_path(path: str) -> str:
    lower = path.casefold()
    if lower.startswith("data/image/spot/"):
        return "SPOT_BACKGROUND"
    if lower.startswith("data/image/face/"):
        return "PORTRAIT"
    if "/cursor" in lower:
        return "CURSOR"
    if lower.startswith(("data/image/strategy/", "data/image/map_obj/", "data/model/strategy/", "data/model/space/")):
        return "MAP"
    if lower.startswith("data/image/"):
        return "TEXTURE"
    if lower.startswith("data/model/"):
        return "MODEL"
    if lower.startswith("data/msgdat/"):
        return "MESSAGE"
    if lower.startswith("data/sound/"):
        return "SOUND"
    extension = PurePosixPath(lower).suffix
    if extension == ".ini":
        return "CONFIGURATION"
    if extension == ".exe":
        return "EXECUTABLE"
    if extension in {".pdf", ".txt", ".url"}:
        return "DOCUMENTATION"
    if extension == ".db":
        return "DATABASE"
    return "OTHER"


def _format_for_path(path: str) -> str:
    extension = PurePosixPath(path).suffix.lower().lstrip(".")
    return extension.upper() if extension else "UNKNOWN"


def _validate_export(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_source_manifest_sha256: str | None = None,
    expected_tree_manifest_sha256: str | None = None,
    expected_pe_imports_sha256: str | None = None,
) -> None:
    unknown = set(raw) - TOP_LEVEL_FIELDS
    missing = TOP_LEVEL_FIELDS - set(raw)
    if unknown or missing:
        raise ValueError(
            f"resources export top-level fields differ: unknown={sorted(unknown)} missing={sorted(missing)}"
        )
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported resources export schemaVersion")
    if raw.get("successMarker") != "EXPORT_EXHAUSTIVE_RESOURCES_OK":
        raise ValueError("resources export success marker is missing")
    source = _mapping("source", raw.get("source"))
    if source.get("program") != "g7mtclient.exe":
        raise ValueError("resources source program mismatch")
    if _sha256("source.executableSha256", source.get("executableSha256")) != CLIENT_SHA256:
        raise ValueError("resources source executable hash mismatch")
    if source.get("language") != EXPECTED_LANGUAGE or source.get("compiler") != EXPECTED_COMPILER:
        raise ValueError("resources source language/compiler mismatch")
    if source.get("imageBase") != EXPECTED_IMAGE_BASE:
        raise ValueError("resources source image base mismatch")
    source_manifest_sha = _sha256(
        "source.sourceManifestSha256", source.get("sourceManifestSha256")
    )
    tree_manifest_sha = _sha256(
        "source.treeManifestSha256", source.get("treeManifestSha256")
    )
    pe_imports_sha = _sha256("source.peImportsSha256", source.get("peImportsSha256"))
    if expected_source_manifest_sha256 and source_manifest_sha != expected_source_manifest_sha256.upper():
        raise ValueError("resources source manifest hash mismatch")
    if expected_tree_manifest_sha256 and tree_manifest_sha != expected_tree_manifest_sha256.upper():
        raise ValueError("resources tree manifest hash mismatch")
    if expected_pe_imports_sha256 and pe_imports_sha != expected_pe_imports_sha256.upper():
        raise ValueError("resources PE imports hash mismatch")
    exporter = _mapping("exporter", raw.get("exporter"))
    if exporter.get("class") != "ExportExhaustiveResources":
        raise ValueError("resources exporter class mismatch")
    exporter_sha = _sha256("exporter.sha256", exporter.get("sha256"))
    repository_sha = _sha256(
        "exporter.ghidraRepositorySha256", exporter.get("ghidraRepositorySha256")
    )
    if expected_exporter_sha256 and exporter_sha != expected_exporter_sha256.upper():
        raise ValueError("resources exporter hash mismatch")
    if expected_repository_sha256 and repository_sha != expected_repository_sha256.upper():
        raise ValueError("resources repository hash mismatch")
    _sha256("surfaceSha256", raw.get("surfaceSha256"))
    audit = _mapping("audit", raw.get("audit"))
    if (
        audit.get("scope") != "COMPILED_RESOURCE_ANCHORS"
        or audit.get("filePresenceIsIntegration") is not False
        or audit.get("stringPresenceIsLoaderProof") is not False
        or audit.get("staticSubmissionIsPlayerVisible") is not False
    ):
        raise ValueError("resources audit overstates its bounded scope")
    _text_list("audit.limitations", audit.get("limitations"), allow_empty=False)
    conservation = _mapping("conservation", raw.get("conservation"))
    if not isinstance(conservation.get("treeFiles"), int) or conservation["treeFiles"] <= 0:
        raise ValueError("conservation.treeFiles must be positive")
    seen: set[str] = set()
    for collection in CANDIDATE_COLLECTIONS:
        items = raw.get(collection)
        if not isinstance(items, list):
            raise ValueError(f"{collection} must be a list")
        for index, value in enumerate(items):
            item = _mapping(f"{collection}[{index}]", value)
            candidate_id = _text(f"{collection}.candidateId", item.get("candidateId"))
            if candidate_id in seen:
                raise ValueError(f"duplicate resources candidateId: {candidate_id}")
            seen.add(candidate_id)


def _candidate_indexes(
    raw: Mapping[str, Any], entries: Mapping[str, TreeManifestEntry]
) -> tuple[dict[str, Mapping[str, Any]], dict[str, list[tuple[str, Mapping[str, Any]]]]]:
    by_id: dict[str, Mapping[str, Any]] = {}
    by_path: dict[str, list[tuple[str, Mapping[str, Any]]]] = {
        path: [] for path in entries
    }
    for collection in CANDIDATE_COLLECTIONS:
        for value in raw[collection]:
            item = _mapping(collection, value)
            candidate_id = _text("candidateId", item.get("candidateId"))
            by_id[candidate_id] = item
            paths: list[str] = []
            if item.get("resourcePath") is not None:
                paths.append(_safe_resource_path(item.get("resourcePath")))
            if item.get("matchedPaths") is not None:
                matched = item.get("matchedPaths")
                if not isinstance(matched, list):
                    raise ValueError("matchedPaths must be a list")
                paths.extend(_safe_resource_path(path) for path in matched)
            for path in dict.fromkeys(paths):
                if path not in entries:
                    raise ValueError(f"candidate references unknown resource path: {path}")
                by_path[path].append((collection, item))
    return by_id, by_path


def _section_from_candidates(
    items: list[Mapping[str, Any]],
    *,
    value_fields: tuple[str, ...],
    section_name: str,
) -> ResourceSection:
    if not items:
        return _unknown_section(**{field: () for field in value_fields})
    status_aliases = {
        "EXACT_PATH_MATCH": "CANDIDATE",
        "UNRESOLVED": "CANDIDATE",
        "PLAYER_VISIBLE": "RUNTIME_OBSERVED",
        "PLAYER_AUDIBLE": "RUNTIME_OBSERVED",
        "VISIBLE_AND_AUDIBLE": "RUNTIME_OBSERVED",
    }
    statuses = [
        status_aliases.get(
            _text(f"{section_name}.status", item.get("status")),
            _text(f"{section_name}.status", item.get("status")),
        )
        for item in items
    ]
    allowed = {status.value for status in ResourceSectionStatus}
    if any(status not in allowed for status in statuses):
        raise ValueError(f"unsupported {section_name} status")
    for item, status in zip(items, statuses):
        claimed = [field for field in value_fields if item.get(field) not in (None, [], "UNKNOWN")]
        if status == "UNKNOWN" and claimed:
            raise ValueError(f"unknown {section_name} cannot claim values")
    priority = {
        "UNKNOWN": 0,
        "CANDIDATE": 1,
        "STATIC_MAPPED": 2,
        "PROVEN": 3,
        "RUNTIME_OBSERVED": 4,
        "NOT_APPLICABLE": 0,
    }
    status = max(statuses, key=priority.__getitem__)
    values: dict[str, Any] = {}
    for field in value_fields:
        merged: list[str] = []
        for item in items:
            value = item.get(field)
            if isinstance(value, list):
                merged.extend(str(entry) for entry in value)
            elif value not in (None, "UNKNOWN"):
                merged.append(str(value))
        values[field] = tuple(dict.fromkeys(merged))
    evidence = tuple(
        dict.fromkeys(
            evidence
            for item in items
            for evidence in _evidence(f"{section_name}.evidence", item.get("evidence"))
        )
    )
    values["candidateIds"] = tuple(_text("candidateId", item.get("candidateId")) for item in items)
    values["evidence"] = evidence
    return ResourceSection(ResourceSectionStatus(status), values)


def build_resource_inventory(
    raw: Mapping[str, Any],
    tree_entries: list[TreeManifestEntry],
    *,
    root_id: str,
    adjudications: Mapping[str, Mapping[str, Any]] | None = None,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_source_manifest_sha256: str | None = None,
    expected_tree_manifest_sha256: str | None = None,
    expected_pe_imports_sha256: str | None = None,
) -> list[ResourceInventoryRow]:
    _validate_export(
        raw,
        expected_exporter_sha256=expected_exporter_sha256,
        expected_repository_sha256=expected_repository_sha256,
        expected_source_manifest_sha256=expected_source_manifest_sha256,
        expected_tree_manifest_sha256=expected_tree_manifest_sha256,
        expected_pe_imports_sha256=expected_pe_imports_sha256,
    )
    root_id = _text("rootId", root_id)
    entries: dict[str, TreeManifestEntry] = {}
    casefold_paths: set[str] = set()
    for entry in tree_entries:
        if not isinstance(entry, TreeManifestEntry):
            raise ValueError("tree entries must be TreeManifestEntry values")
        folded = entry.relative_path.casefold()
        if folded in casefold_paths:
            raise ValueError("resource paths must be case-insensitive unique")
        casefold_paths.add(folded)
        entries[entry.relative_path] = entry
    if len(entries) != len(tree_entries):
        raise ValueError("duplicate resource path")
    adjudications = adjudications or {}
    unknown_adjudication_paths = set(adjudications) - set(entries)
    if unknown_adjudication_paths:
        raise ValueError(
            f"resource adjudication path is absent from tree manifest: {sorted(unknown_adjudication_paths)}"
        )
    if raw["conservation"]["treeFiles"] != len(tree_entries):
        raise ValueError("raw/tree resource count differs")
    by_id, by_path = _candidate_indexes(raw, entries)
    path_candidate_ids = {
        _text("path candidateId", item.get("candidateId"))
        for collection in ("literalPathCandidates", "pathFormatterCandidates")
        for item in raw[collection]
    }
    for item in raw["loaderCandidates"]:
        item = _mapping("loader candidate", item)
        status = _text("loader.status", item.get("status"))
        refs = _text_list("loader.pathCandidateIds", item.get("pathCandidateIds", []))
        unknown_refs = set(refs) - path_candidate_ids
        if unknown_refs:
            raise ValueError(f"loader path candidate is dangling: {sorted(unknown_refs)}")
        claimed = any(item.get(field) not in (None, [], "UNKNOWN") for field in ("functions", "api", "acceptedFormats"))
        if status == "UNKNOWN" and claimed:
            raise ValueError("unknown loader cannot claim values")
        if status == "PROVEN" and (
            not refs
            or not _text_list("loader.functions", item.get("functions", []), allow_empty=False)
            or _optional_text("loader.api", item.get("api")) is None
        ):
            raise ValueError("proven loader requires path, function, and API")
    for item in raw["ownerCandidates"]:
        item = _mapping("owner candidate", item)
        keys = _text_list("owner.ownerKeys", item.get("ownerKeys", []))
        if any(RUNTIME_POINTER_PATTERN.fullmatch(key) for key in keys):
            raise ValueError("runtime pointer cannot be a stable resource owner")
        if item.get("status") == "PROVEN":
            functions = item.get("functions")
            if (
                _optional_text("owner.ownerKind", item.get("ownerKind")) is None
                or not keys
                or not isinstance(functions, list)
                or not functions
                or any(not isinstance(function, str) or not function.strip() for function in functions)
                or _optional_text("owner.joinKind", item.get("joinKind")) is None
            ):
                raise ValueError("proven owner requires kind, key, function, and join")
    for item in raw["runtimeKeyCandidates"]:
        item = _mapping("runtime key candidate", item)
        if item.get("status") == "PROVEN" and any(
            _optional_text(f"runtime key.{field}", item.get(field)) is None
            for field in ("namespace", "value", "derivationFunction")
        ):
            raise ValueError("proven runtime key requires namespace, value, and derivation")
    candidate_submission_ids = {
        _text("submission.candidateId", item.get("candidateId"))
        for collection in (
            "renderSubmissionCandidates",
            "audioSubmissionCandidates",
            "uiSubmissionCandidates",
        )
        for item in raw[collection]
    }
    rows: list[ResourceInventoryRow] = []
    for path in sorted(entries, key=str.casefold):
        entry = entries[path]
        attached = by_path[path]
        attached_ids = [
            _text("candidateId", item.get("candidateId")) for _, item in attached
        ]
        path_items = [
            item
            for collection, item in attached
            if collection in {"literalPathCandidates", "pathFormatterCandidates"}
        ]
        loader_items = [item for collection, item in attached if collection == "loaderCandidates"]
        runtime_items = [item for collection, item in attached if collection == "runtimeKeyCandidates"]
        owner_items = [item for collection, item in attached if collection == "ownerCandidates"]
        transform_items = [
            item for collection, item in attached if collection == "decodeTransformCandidates"
        ]
        cache_items = [item for collection, item in attached if collection == "cacheRegistryCandidates"]
        submission_items = {
            "render": [item for collection, item in attached if collection == "renderSubmissionCandidates"],
            "audio": [item for collection, item in attached if collection == "audioSubmissionCandidates"],
            "ui": [item for collection, item in attached if collection == "uiSubmissionCandidates"],
        }
        receipt_items = [
            item for collection, item in attached if collection == "presentationReceiptCandidates"
        ]
        path_resolution = _section_from_candidates(
            path_items,
            value_fields=("address", "value", "template", "function", "argumentDomain", "matchedPaths"),
            section_name="path resolution",
        )
        if path_items and path_resolution.status is ResourceSectionStatus.UNKNOWN:
            path_resolution = ResourceSection(ResourceSectionStatus.CANDIDATE, path_resolution.values)
        loader_section = _section_from_candidates(
            loader_items,
            value_fields=("functions", "api", "acceptedFormats", "pathCandidateIds"),
            section_name="loader",
        )
        adjudication = adjudications.get(path)
        adjudication_kind: str | None = None
        adjudication_evidence: tuple[str, ...] = ()
        pe_analysis: Mapping[str, Any] | None = None
        pdf_analysis: Mapping[str, Any] | None = None
        terms_analysis: Mapping[str, Any] | None = None
        primary_client_analysis: Mapping[str, Any] | None = None
        game_updater_analysis: Mapping[str, Any] | None = None
        duplicate_source: dict[str, Any] | None = None
        process_image: dict[str, Any] | None = None
        inbound_launch: dict[str, Any] | None = None
        process_launch: dict[str, Any] | None = None
        external_document_open: dict[str, Any] | None = None
        if adjudication is not None:
            adjudication = _mapping("resource adjudication", adjudication)
            adjudication_kind = _text("adjudication.kind", adjudication.get("kind"))
            common_adjudication_fields = {
                "schemaVersion",
                "relativePosixPath",
                "kind",
                "contentSha256",
                "byteSize",
                "loader",
                "evidence",
            }
            kind_fields = {
                "WINDOWS_INTERNET_SHORTCUT": {
                    "contentBytesHex", "originalName", "originalNameEncoding",
                    "originalNameBytesHex", "targetUrl",
                },
                "PE_EXECUTABLE_BOOTSTRAP": {"analysis", "processLaunch"},
                "PDF_OPERATION_MANUAL": {"analysis", "externalDocumentOpen"},
                "CP932_TERMS_DOCUMENT": {"analysis", "duplicateSource"},
                "PE_PRIMARY_GAME_CLIENT_EXECUTABLE": {
                    "analysis", "processImage", "inboundLaunch",
                },
                "PE_GAME_UPDATER_EXECUTABLE": {
                    "analysis", "processImage", "processLaunch",
                },
            }
            if adjudication_kind not in kind_fields:
                raise ValueError("unsupported resource adjudication kind")
            allowed_adjudication_fields = common_adjudication_fields | kind_fields[adjudication_kind]
            unknown_adjudication_fields = set(adjudication) - allowed_adjudication_fields
            if unknown_adjudication_fields:
                raise ValueError(
                    f"unknown resource adjudication fields: {sorted(unknown_adjudication_fields)}"
                )
            if loader_items:
                raise ValueError("resource NOT_APPLICABLE adjudication conflicts with loader candidates")
            if adjudication.get("schemaVersion") != 1:
                raise ValueError("unsupported resource adjudication schemaVersion")
            if _sha256("adjudication.contentSha256", adjudication.get("contentSha256")) != entry.content_sha256:
                raise ValueError("resource adjudication contentSha256 differs from tree manifest")
            if adjudication.get("byteSize") != entry.byte_size:
                raise ValueError("resource adjudication byteSize differs from tree manifest")
            adjudication_evidence = _evidence(
                "adjudication.evidence", adjudication.get("evidence")
            )
            if adjudication_kind == "WINDOWS_INTERNET_SHORTCUT":
                try:
                    content_bytes = bytes.fromhex(
                        _text("adjudication.contentBytesHex", adjudication.get("contentBytesHex"))
                    )
                except ValueError as error:
                    raise ValueError("resource adjudication contentBytesHex is invalid") from error
                if len(content_bytes) != entry.byte_size:
                    raise ValueError("resource adjudication content bytes differ from byteSize")
                if hashlib.sha256(content_bytes).hexdigest().upper() != entry.content_sha256:
                    raise ValueError("resource adjudication content bytes differ from contentSha256")
                try:
                    shortcut_text = content_bytes.decode("ascii")
                except UnicodeDecodeError as error:
                    raise ValueError("Internet Shortcut content must be ASCII") from error
                prefix = "[InternetShortcut]\r\nURL="
                suffix = "\r\n"
                if not shortcut_text.startswith(prefix) or not shortcut_text.endswith(suffix):
                    raise ValueError("Internet Shortcut content signature differs")
                content_url = shortcut_text[len(prefix) : -len(suffix)]
                target_url = _text("adjudication.targetUrl", adjudication.get("targetUrl"))
                if target_url != content_url:
                    raise ValueError("resource adjudication targetUrl differs from shortcut content")
                original_name = _text("adjudication.originalName", adjudication.get("originalName"))
                if adjudication.get("originalNameEncoding") != "CP932":
                    raise ValueError("resource adjudication originalNameEncoding must be CP932")
                try:
                    original_name_bytes = bytes.fromhex(
                        _text(
                            "adjudication.originalNameBytesHex",
                            adjudication.get("originalNameBytesHex"),
                        )
                    )
                    decoded_original_name = original_name_bytes.decode("cp932")
                except (ValueError, UnicodeDecodeError) as error:
                    raise ValueError("resource adjudication originalNameBytesHex is invalid CP932") from error
                if decoded_original_name != original_name:
                    raise ValueError("resource adjudication originalName differs from CP932 bytes")
            elif adjudication_kind == "PE_EXECUTABLE_BOOTSTRAP":
                pe_analysis = _mapping("adjudication.analysis", adjudication.get("analysis"))
                if set(pe_analysis) != {
                    "status", "format", "machine", "subsystem", "entryPointRva",
                    "role", "receiptPath", "receiptSha256", "evidence",
                }:
                    raise ValueError("PE bootstrap analysis fields differ")
                if pe_analysis.get("status") != "PROVEN":
                    raise ValueError("PE bootstrap analysis must be PROVEN")
                if pe_analysis.get("format") != "PE32_X86_GUI_EXECUTABLE":
                    raise ValueError("unsupported PE bootstrap format")
                if pe_analysis.get("machine") != "0x014C" or pe_analysis.get("subsystem") != 2:
                    raise ValueError("PE bootstrap architecture differs")
                if not RUNTIME_POINTER_PATTERN.fullmatch(
                    _text("analysis.entryPointRva", pe_analysis.get("entryPointRva"))
                ):
                    raise ValueError("PE bootstrap entryPointRva is invalid")
                _sha256("analysis.receiptSha256", pe_analysis.get("receiptSha256"))
                _evidence("analysis.evidence", pe_analysis.get("evidence"))
                process = _mapping("adjudication.processLaunch", adjudication.get("processLaunch"))
                if set(process) != {
                    "status", "api", "function", "callsite", "targetRelativePosixPath",
                    "targetSha256", "executableStringVa", "waitCallsite",
                    "exitCodeCallsite", "evidence",
                }:
                    raise ValueError("PE bootstrap processLaunch fields differ")
                if process.get("status") != "PROVEN":
                    raise ValueError("PE bootstrap processLaunch must be PROVEN")
                function = _text("processLaunch.function", process.get("function"))
                if not FUNCTION_PATTERN.fullmatch(function):
                    raise ValueError("PE bootstrap processLaunch function is invalid")
                for field in ("callsite", "executableStringVa", "waitCallsite", "exitCodeCallsite"):
                    if not RUNTIME_POINTER_PATTERN.fullmatch(
                        _text(f"processLaunch.{field}", process.get(field))
                    ):
                        raise ValueError(f"PE bootstrap {field} is invalid")
                target_path = _safe_resource_path(process.get("targetRelativePosixPath"))
                if target_path == path or target_path not in entries:
                    raise ValueError("PE bootstrap processLaunch target is missing or self-referential")
                target_sha = _sha256("processLaunch.targetSha256", process.get("targetSha256"))
                if target_sha != entries[target_path].content_sha256:
                    raise ValueError("PE bootstrap processLaunch targetSha256 differs")
                process_evidence = _evidence("processLaunch.evidence", process.get("evidence"))
                process_launch = {
                    "status": "PROVEN",
                    "api": _text("processLaunch.api", process.get("api")),
                    "function": function,
                    "callsite": _text("processLaunch.callsite", process.get("callsite")),
                    "targetRelativePosixPath": target_path,
                    "targetSha256": target_sha,
                    "targetRowKey": f"RESOURCE:FILE:{root_id}:{target_path}",
                    "executableStringVa": _text(
                        "processLaunch.executableStringVa", process.get("executableStringVa")
                    ),
                    "waitCallsite": _text("processLaunch.waitCallsite", process.get("waitCallsite")),
                    "exitCodeCallsite": _text(
                        "processLaunch.exitCodeCallsite", process.get("exitCodeCallsite")
                    ),
                    "evidence": process_evidence,
                }
            elif adjudication_kind == "PDF_OPERATION_MANUAL":
                pdf_analysis = _mapping("adjudication.analysis", adjudication.get("analysis"))
                expected_pdf_fields = {
                    "status", "format", "role", "headerHex", "pdfVersion", "pageCount",
                    "encrypted", "emptyPasswordAccess", "title", "author", "creator",
                    "producer", "creationDate", "modificationDate", "receiptPath",
                    "receiptSha256", "evidence",
                }
                if set(pdf_analysis) != expected_pdf_fields:
                    raise ValueError("PDF operation manual analysis fields differ")
                if pdf_analysis.get("status") != "PROVEN":
                    raise ValueError("PDF operation manual analysis must be PROVEN")
                if (
                    pdf_analysis.get("format") != "PDF_1_4"
                    or pdf_analysis.get("pdfVersion") != "1.4"
                    or pdf_analysis.get("headerHex") != "255044462D312E340D25E2E3CFD30D0A"
                ):
                    raise ValueError("PDF operation manual format differs")
                if (
                    pdf_analysis.get("role") != "ORIGINAL_OPERATION_MANUAL"
                    or pdf_analysis.get("title") != "銀河英雄伝説Ⅶ　操作説明書"
                    or pdf_analysis.get("author") != "BOTHTEC"
                ):
                    raise ValueError("PDF operation manual identity differs")
                if not isinstance(pdf_analysis.get("pageCount"), int) or pdf_analysis["pageCount"] <= 0:
                    raise ValueError("PDF operation manual pageCount is invalid")
                if pdf_analysis.get("encrypted") is not True or pdf_analysis.get("emptyPasswordAccess") is not True:
                    raise ValueError("PDF operation manual access contract differs")
                for field in (
                    "creator", "producer", "creationDate", "modificationDate", "receiptPath"
                ):
                    _text(f"analysis.{field}", pdf_analysis.get(field))
                _sha256("analysis.receiptSha256", pdf_analysis.get("receiptSha256"))
                _evidence("analysis.evidence", pdf_analysis.get("evidence"))
                external_value = adjudication.get("externalDocumentOpen")
                if external_value is not None:
                    external = _mapping("adjudication.externalDocumentOpen", external_value)
                    expected_external_fields = {
                        "status", "openerKey", "openerName", "openerSha256", "openerByteSize",
                        "api", "commandId", "handler", "callsite", "verb",
                        "targetOriginalName", "targetSha256", "evidence",
                    }
                    if set(external) != expected_external_fields:
                        raise ValueError("PDF externalDocumentOpen fields differ")
                    if external.get("status") != "PROVEN":
                        raise ValueError("PDF externalDocumentOpen must be PROVEN")
                    opener_key = _text("externalDocumentOpen.openerKey", external.get("openerKey"))
                    if not opener_key.startswith("ORIGINAL_CD_ARTIFACT:"):
                        raise ValueError("PDF externalDocumentOpen openerKey differs")
                    opener_sha = _sha256(
                        "externalDocumentOpen.openerSha256", external.get("openerSha256")
                    )
                    if not isinstance(external.get("openerByteSize"), int) or external["openerByteSize"] <= 0:
                        raise ValueError("PDF externalDocumentOpen openerByteSize is invalid")
                    if external.get("commandId") != 1001 or external.get("verb") != "open":
                        raise ValueError("PDF externalDocumentOpen command contract differs")
                    handler = _text("externalDocumentOpen.handler", external.get("handler"))
                    callsite = _text("externalDocumentOpen.callsite", external.get("callsite"))
                    if not FUNCTION_PATTERN.fullmatch(handler) or not RUNTIME_POINTER_PATTERN.fullmatch(callsite):
                        raise ValueError("PDF externalDocumentOpen code anchor differs")
                    target_sha = _sha256(
                        "externalDocumentOpen.targetSha256", external.get("targetSha256")
                    )
                    if target_sha != entry.content_sha256:
                        raise ValueError("PDF externalDocumentOpen targetSha256 differs")
                    external_document_open = {
                        "status": "PROVEN",
                        "openerKey": opener_key,
                        "openerName": _text(
                            "externalDocumentOpen.openerName", external.get("openerName")
                        ),
                        "openerSha256": opener_sha,
                        "openerByteSize": int(external["openerByteSize"]),
                        "api": _text("externalDocumentOpen.api", external.get("api")),
                        "commandId": 1001,
                        "handler": handler,
                        "callsite": callsite,
                        "verb": "open",
                        "targetOriginalName": _text(
                            "externalDocumentOpen.targetOriginalName",
                            external.get("targetOriginalName"),
                        ),
                        "targetSha256": target_sha,
                        "evidence": _evidence(
                            "externalDocumentOpen.evidence", external.get("evidence")
                        ),
                    }
            elif adjudication_kind == "CP932_TERMS_DOCUMENT":
                terms_analysis = _mapping("adjudication.analysis", adjudication.get("analysis"))
                expected_terms_fields = {
                    "status", "format", "role", "encoding", "title", "characterCount",
                    "lineEnding", "receiptPath", "receiptSha256", "evidence",
                }
                if set(terms_analysis) != expected_terms_fields:
                    raise ValueError("CP932 terms analysis fields differ")
                if terms_analysis.get("status") != "PROVEN":
                    raise ValueError("CP932 terms analysis must be PROVEN")
                if (
                    terms_analysis.get("format") != "CP932_TEXT"
                    or terms_analysis.get("encoding") != "CP932"
                    or terms_analysis.get("lineEnding") != "CRLF"
                ):
                    raise ValueError("CP932 terms format differs")
                if (
                    terms_analysis.get("role") != "ORIGINAL_SERVICE_TERMS"
                    or terms_analysis.get("title") != "銀河英雄伝説Ⅶ利用規約"
                ):
                    raise ValueError("CP932 terms identity differs")
                if (
                    not isinstance(terms_analysis.get("characterCount"), int)
                    or terms_analysis["characterCount"] <= 0
                ):
                    raise ValueError("CP932 terms characterCount is invalid")
                _text("analysis.receiptPath", terms_analysis.get("receiptPath"))
                _sha256("analysis.receiptSha256", terms_analysis.get("receiptSha256"))
                _evidence("analysis.evidence", terms_analysis.get("evidence"))
                duplicate = _mapping(
                    "adjudication.duplicateSource", adjudication.get("duplicateSource")
                )
                expected_duplicate_fields = {
                    "status", "path", "contentSha256", "byteSize", "relation", "evidence",
                }
                if set(duplicate) != expected_duplicate_fields:
                    raise ValueError("CP932 terms duplicateSource fields differ")
                duplicate_sha = _sha256(
                    "duplicateSource.contentSha256", duplicate.get("contentSha256")
                )
                if (
                    duplicate.get("status") != "PROVEN"
                    or duplicate.get("relation")
                    != "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY"
                    or duplicate_sha != entry.content_sha256
                    or duplicate.get("byteSize") != entry.byte_size
                ):
                    raise ValueError("CP932 terms duplicate source differs")
                duplicate_source = {
                    "status": "PROVEN",
                    "path": _text("duplicateSource.path", duplicate.get("path")),
                    "contentSha256": duplicate_sha,
                    "byteSize": int(duplicate["byteSize"]),
                    "relation": "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY",
                    "evidence": _evidence("duplicateSource.evidence", duplicate.get("evidence")),
                }
            elif adjudication_kind == "PE_GAME_UPDATER_EXECUTABLE":
                game_updater_analysis = _mapping(
                    "adjudication.analysis", adjudication.get("analysis")
                )
                expected_updater_fields = {
                    "status", "format", "machine", "subsystem", "imageBase",
                    "entryPointRva", "role", "sectionCount", "importDescriptorCount",
                    "importCount", "importQuality", "packingAssessment",
                    "originalFilename", "fileVersion", "receiptPath", "receiptSha256",
                    "evidence",
                }
                if set(game_updater_analysis) != expected_updater_fields:
                    raise ValueError("game updater analysis fields differ")
                exact_updater = {
                    "status": "PROVEN",
                    "format": "PE32_X86_GUI_EXECUTABLE",
                    "machine": "0x014C",
                    "subsystem": 2,
                    "imageBase": "0x00400000",
                    "entryPointRva": "0x00009A2E",
                    "role": "ORIGINAL_GAME_UPDATE_CLIENT",
                    "sectionCount": 4,
                    "importDescriptorCount": 11,
                    "importCount": 347,
                    "importQuality": "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION",
                    "packingAssessment": "NO_KNOWN_PACKER_SIGNATURE_STATIC_ONLY",
                    "originalFilename": "",
                    "fileVersion": "1, 0, 0, 0",
                }
                for field, expected in exact_updater.items():
                    if game_updater_analysis.get(field) != expected:
                        raise ValueError(f"game updater {field} differs")
                _text("analysis.receiptPath", game_updater_analysis.get("receiptPath"))
                _sha256("analysis.receiptSha256", game_updater_analysis.get("receiptSha256"))
                _evidence("analysis.evidence", game_updater_analysis.get("evidence"))

                process = _mapping("adjudication.processImage", adjudication.get("processImage"))
                if set(process) != {
                    "status", "osLoader", "target", "runtimeObservationStatus", "evidence",
                }:
                    raise ValueError("game updater processImage fields differ")
                if (
                    process.get("status") != "PROVEN_STATIC_FORMAT"
                    or process.get("osLoader") != "WINDOWS_PE_LOADER"
                    or process.get("target") != "SELF_PROCESS_IMAGE"
                    or process.get("runtimeObservationStatus") != "NOT_CLAIMED"
                ):
                    raise ValueError("game updater process image contract differs")
                process_image = {
                    "status": "PROVEN_STATIC_FORMAT",
                    "osLoader": "WINDOWS_PE_LOADER",
                    "target": "SELF_PROCESS_IMAGE",
                    "runtimeObservationStatus": "NOT_CLAIMED",
                    "evidence": _evidence("processImage.evidence", process.get("evidence")),
                }

                launch = _mapping("adjudication.processLaunch", adjudication.get("processLaunch"))
                expected_launch_fields = {
                    "status", "api", "function", "callsite", "triggerCallsite",
                    "targetCommand", "workingDirectory", "targetRelativePosixPath",
                    "targetSha256", "configOverrideStatus", "gateSemantics",
                    "runtimeObservationStatus", "evidence",
                }
                if set(launch) != expected_launch_fields:
                    raise ValueError("game updater processLaunch fields differ")
                target_path = _safe_resource_path(launch.get("targetRelativePosixPath"))
                if target_path == path or target_path not in entries:
                    raise ValueError("game updater processLaunch target is missing or self-referential")
                target_sha = _sha256("processLaunch.targetSha256", launch.get("targetSha256"))
                if target_sha != entries[target_path].content_sha256:
                    raise ValueError("game updater processLaunch targetSha256 differs")
                exact_launch = {
                    "status": "PROVEN_STATIC_DEFAULT",
                    "api": "KERNEL32.dll::CreateProcessA",
                    "function": "FUN_00407260",
                    "callsite": "0x004072C2",
                    "triggerCallsite": "0x004068A1",
                    "targetCommand": ".\\exe\\G7MTClient.exe",
                    "workingDirectory": ".\\exe\\",
                    "configOverrideStatus": "POSSIBLE",
                    "gateSemantics": "UNRESOLVED",
                    "runtimeObservationStatus": "UNSEEN",
                }
                for field, expected in exact_launch.items():
                    if launch.get(field) != expected:
                        raise ValueError(f"game updater processLaunch {field} differs")
                process_launch = {
                    **exact_launch,
                    "targetRelativePosixPath": target_path,
                    "targetSha256": target_sha,
                    "targetRowKey": f"RESOURCE:FILE:{root_id}:{target_path}",
                    "evidence": _evidence("processLaunch.evidence", launch.get("evidence")),
                }
            else:
                primary_client_analysis = _mapping(
                    "adjudication.analysis", adjudication.get("analysis")
                )
                expected_primary_fields = {
                    "status", "format", "machine", "subsystem", "imageBase",
                    "entryPointRva", "role", "sectionCount", "importDescriptorCount",
                    "importCount", "importQuality", "packingAssessment",
                    "originalFilename", "fileVersion", "receiptPath", "receiptSha256",
                    "evidence",
                }
                if set(primary_client_analysis) != expected_primary_fields:
                    raise ValueError("primary game client analysis fields differ")
                if primary_client_analysis.get("status") != "PROVEN":
                    raise ValueError("primary game client analysis must be PROVEN")
                exact_primary = {
                    "format": "PE32_X86_GUI_EXECUTABLE",
                    "machine": "0x014C",
                    "subsystem": 2,
                    "imageBase": "0x00400000",
                    "entryPointRva": "0x00201FBC",
                    "role": "ORIGINAL_PRIMARY_GAME_CLIENT",
                    "sectionCount": 5,
                    "importDescriptorCount": 19,
                    "importCount": 452,
                    "importQuality": "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION",
                    "packingAssessment": "NOT_PACKED_BY_STATIC_INDICATORS",
                    "originalFilename": "G7MTClient.EXE",
                    "fileVersion": "1, 0, 0, 1",
                }
                for field, expected in exact_primary.items():
                    if primary_client_analysis.get(field) != expected:
                        raise ValueError(f"primary game client {field} differs")
                _text("analysis.receiptPath", primary_client_analysis.get("receiptPath"))
                _sha256("analysis.receiptSha256", primary_client_analysis.get("receiptSha256"))
                _evidence("analysis.evidence", primary_client_analysis.get("evidence"))

                process = _mapping("adjudication.processImage", adjudication.get("processImage"))
                if set(process) != {
                    "status", "osLoader", "target", "runtimeObservationStatus", "evidence",
                }:
                    raise ValueError("primary game client processImage fields differ")
                if (
                    process.get("status") != "PROVEN_STATIC_FORMAT"
                    or process.get("osLoader") != "WINDOWS_PE_LOADER"
                    or process.get("target") != "SELF_PROCESS_IMAGE"
                    or process.get("runtimeObservationStatus") != "NOT_CLAIMED"
                ):
                    raise ValueError("primary game client process image contract differs")
                process_image = {
                    "status": "PROVEN_STATIC_FORMAT",
                    "osLoader": "WINDOWS_PE_LOADER",
                    "target": "SELF_PROCESS_IMAGE",
                    "runtimeObservationStatus": "NOT_CLAIMED",
                    "evidence": _evidence("processImage.evidence", process.get("evidence")),
                }

                inbound = _mapping("adjudication.inboundLaunch", adjudication.get("inboundLaunch"))
                expected_inbound_fields = {
                    "status", "launcherRowKey", "launcherRelativePosixPath", "launcherSha256",
                    "api", "callsite", "triggerCallsite", "targetCommand",
                    "workingDirectory", "targetRelativePosixPath", "targetSha256",
                    "configOverrideStatus", "gateSemantics", "runtimeObservationStatus",
                    "g7StartLaunchStatus", "evidence",
                }
                if set(inbound) != expected_inbound_fields:
                    raise ValueError("primary game client inboundLaunch fields differ")
                launcher_path = _safe_resource_path(inbound.get("launcherRelativePosixPath"))
                if launcher_path == path or launcher_path not in entries:
                    raise ValueError("primary game client inbound launcher is missing or self-referential")
                launcher_sha = _sha256("inboundLaunch.launcherSha256", inbound.get("launcherSha256"))
                if launcher_sha != entries[launcher_path].content_sha256:
                    raise ValueError("primary game client inbound launcher SHA-256 differs")
                launcher_row_key = _text("inboundLaunch.launcherRowKey", inbound.get("launcherRowKey"))
                if launcher_row_key != f"RESOURCE:FILE:{root_id}:{launcher_path}":
                    raise ValueError("primary game client inbound launcher row key differs")
                target_path = _safe_resource_path(inbound.get("targetRelativePosixPath"))
                target_sha = _sha256("inboundLaunch.targetSha256", inbound.get("targetSha256"))
                if target_path != path or target_sha != entry.content_sha256:
                    raise ValueError("primary game client inbound launch target differs")
                if (
                    inbound.get("status") != "PROVEN_STATIC_DEFAULT"
                    or inbound.get("api") != "KERNEL32.dll::CreateProcessA"
                    or inbound.get("callsite") != "0x004072C2"
                    or inbound.get("triggerCallsite") != "0x004068A1"
                    or inbound.get("targetCommand") != ".\\exe\\G7MTClient.exe"
                    or inbound.get("workingDirectory") != ".\\exe\\"
                    or inbound.get("configOverrideStatus") != "POSSIBLE"
                    or inbound.get("gateSemantics") != "UNRESOLVED"
                    or inbound.get("runtimeObservationStatus") != "UNSEEN"
                    or inbound.get("g7StartLaunchStatus") != "UNRESOLVED"
                ):
                    raise ValueError("primary game client inbound launch contract differs")
                inbound_launch = {
                    "status": "PROVEN_STATIC_DEFAULT",
                    "launcherRowKey": launcher_row_key,
                    "launcherRelativePosixPath": launcher_path,
                    "launcherSha256": launcher_sha,
                    "api": "KERNEL32.dll::CreateProcessA",
                    "callsite": "0x004072C2",
                    "triggerCallsite": "0x004068A1",
                    "targetCommand": ".\\exe\\G7MTClient.exe",
                    "workingDirectory": ".\\exe\\",
                    "targetRelativePosixPath": target_path,
                    "targetSha256": target_sha,
                    "configOverrideStatus": "POSSIBLE",
                    "gateSemantics": "UNRESOLVED",
                    "runtimeObservationStatus": "UNSEEN",
                    "g7StartLaunchStatus": "UNRESOLVED",
                    "evidence": _evidence("inboundLaunch.evidence", inbound.get("evidence")),
                }
            loader_adjudication = _mapping("adjudication.loader", adjudication.get("loader"))
            unknown_loader_fields = set(loader_adjudication) - {"status", "reason", "evidence"}
            if unknown_loader_fields:
                raise ValueError(
                    f"unknown resource loader adjudication fields: {sorted(unknown_loader_fields)}"
                )
            if loader_adjudication.get("status") != "NOT_APPLICABLE":
                raise ValueError("resource loader adjudication must be NOT_APPLICABLE")
            loader_reason = _text("adjudication.loader.reason", loader_adjudication.get("reason"))
            loader_evidence = _evidence("adjudication.loader.evidence", loader_adjudication.get("evidence"))
            loader_section = ResourceSection(
                ResourceSectionStatus.NOT_APPLICABLE,
                {"reason": loader_reason, "evidence": loader_evidence},
            )
        runtime_section = _section_from_candidates(
            runtime_items,
            value_fields=("namespace", "value", "derivationFunction"),
            section_name="runtime key",
        )
        owner_section = _section_from_candidates(
            owner_items,
            value_fields=("ownerKind", "ownerKeys", "functions", "joinKind"),
            section_name="owner",
        )
        transform_section = _section_from_candidates(
            transform_items,
            value_fields=("stages", "reason"),
            section_name="decode transform",
        )
        cache_section = _section_from_candidates(
            cache_items,
            value_fields=("registryKey", "runtimeKeyRefs", "insertFunctions", "readFunctions", "evictFunctions"),
            section_name="cache registry",
        )
        submissions = {
            kind: _section_from_candidates(
                items,
                value_fields=("function", "sink", "runtimeReceiptRefs"),
                section_name=f"{kind} submission",
            )
            for kind, items in submission_items.items()
        }
        presentation = _section_from_candidates(
            receipt_items,
            value_fields=("runId", "sourceSha256", "runtimeKey", "ownerKey", "submissionCandidateId"),
            section_name="presentation receipt",
        )
        loader_proven = loader_section.status is ResourceSectionStatus.PROVEN
        loader_not_applicable = loader_section.status is ResourceSectionStatus.NOT_APPLICABLE
        owner_proven = owner_section.status is ResourceSectionStatus.PROVEN
        runtime_submission_items = [
            item
            for items in submission_items.values()
            for item in items
            if item.get("status") == "RUNTIME_OBSERVED"
        ]
        valid_receipt = False
        for receipt_item in receipt_items:
            if receipt_item.get("status") not in {
                "PLAYER_VISIBLE",
                "PLAYER_AUDIBLE",
                "VISIBLE_AND_AUDIBLE",
            }:
                continue
            receipt_source = _sha256("receipt sourceSha256", receipt_item.get("sourceSha256"))
            if receipt_source != entry.content_sha256:
                raise ValueError("presentation receipt source hash differs")
            submission_id = _text(
                "receipt.submissionCandidateId", receipt_item.get("submissionCandidateId")
            )
            if submission_id not in candidate_submission_ids:
                raise ValueError("presentation receipt submission is dangling")
            runtime_key = _text("receipt.runtimeKey", receipt_item.get("runtimeKey"))
            owner_key = _text("receipt.ownerKey", receipt_item.get("ownerKey"))
            if runtime_key not in {
                str(item.get("value")) for item in runtime_items if item.get("status") == "PROVEN"
            }:
                raise ValueError("presentation receipt runtime key differs")
            if owner_key not in {
                str(key)
                for item in owner_items
                if item.get("status") == "PROVEN"
                for key in item.get("ownerKeys", [])
            }:
                raise ValueError("presentation receipt owner differs")
            run_id = _text("receipt.runId", receipt_item.get("runId"))
            matching_submission = next(
                (item for item in runtime_submission_items if item.get("candidateId") == submission_id),
                None,
            )
            if matching_submission is None or run_id not in matching_submission.get("runtimeReceiptRefs", []):
                raise ValueError("presentation receipt run differs from submission")
            valid_receipt = True
        if loader_not_applicable:
            usage = UsageDisposition.ENUMERATED_ONLY
            first_missing = "RUNTIME_OWNER"
        elif not loader_proven:
            usage = UsageDisposition.ENUMERATED_ONLY
            first_missing = "LOADER_JOIN"
        elif not owner_proven:
            usage = UsageDisposition.ORPHAN
            first_missing = "RUNTIME_OWNER"
        elif valid_receipt:
            usage = UsageDisposition.INTEGRATED
            first_missing = "NONE"
        else:
            usage = UsageDisposition.DORMANT_CANDIDATE
            first_missing = "RUNTIME_SUBMISSION_RECEIPT"
        reachability = (
            Reachability.SHIPPED_REACHABLE
            if usage is UsageDisposition.INTEGRATED
            else Reachability.UNKNOWN
        )
        states = {state: False for state in EvidenceState}
        states[EvidenceState.ENUMERATED] = True
        if loader_proven and owner_proven:
            states[EvidenceState.STATIC_MAPPED] = True
        if usage is UsageDisposition.INTEGRATED:
            states[EvidenceState.RUNTIME_OBSERVED] = True
            states[EvidenceState.PLAYER_VISIBLE] = True
        row = InventoryRow(
            key=f"RESOURCE:FILE:{root_id}:{path}",
            inventory=InventoryKind.RESOURCE,
            name=PurePosixPath(path).name,
            provenance="ORIGINAL_OBSERVED",
            reachability=reachability,
            states=states,
        )
        category = ResourceSection(
            ResourceSectionStatus.CANDIDATE,
            {
                "value": _category_for_path(path),
                "evidence": (f"path-rule:{path}",),
            },
        )
        format_section = ResourceSection(
            ResourceSectionStatus.PROVEN if adjudication is not None else ResourceSectionStatus.CANDIDATE,
            {
                "extension": PurePosixPath(path).suffix.lower(),
                "detectedFormat": (
                    "WINDOWS_INTERNET_SHORTCUT"
                    if adjudication_kind == "WINDOWS_INTERNET_SHORTCUT"
                    else str(pdf_analysis.get("format"))
                    if pdf_analysis is not None
                    else str(pe_analysis.get("format"))
                    if pe_analysis is not None
                    else str(terms_analysis.get("format"))
                    if terms_analysis is not None
                    else str(primary_client_analysis.get("format"))
                    if primary_client_analysis is not None
                    else _format_for_path(path)
                ),
                "detector": (
                    "CONTENT_SIGNATURE"
                    if adjudication_kind == "WINDOWS_INTERNET_SHORTCUT"
                    else "HASH_BOUND_PDF_ANALYSIS"
                    if pdf_analysis is not None
                    else "HASH_BOUND_STATIC_ANALYSIS"
                    if pe_analysis is not None
                    else "HASH_BOUND_TEXT_ANALYSIS"
                    if terms_analysis is not None
                    else "HASH_BOUND_STATIC_ANALYSIS"
                    if primary_client_analysis is not None
                    else "EXTENSION_ONLY"
                ),
                "evidence": (
                    adjudication_evidence
                    if adjudication is not None
                    else (f"manifest-path:{path}",)
                ),
            },
        )
        source = {
            "status": "PROVEN",
            "kind": "ORIGINAL_PAYLOAD_FILE",
            "rootId": root_id,
            "relativePosixPath": path,
            "contentSha256": entry.content_sha256,
            "byteSize": entry.byte_size,
            "evidence": (f"tree-manifest:{path}",),
        }
        if adjudication_kind == "WINDOWS_INTERNET_SHORTCUT":
            source.update(
                originalName=_text("adjudication.originalName", adjudication.get("originalName")),
                originalNameEncoding=_text(
                    "adjudication.originalNameEncoding", adjudication.get("originalNameEncoding")
                ),
                originalNameBytesHex=_text(
                    "adjudication.originalNameBytesHex", adjudication.get("originalNameBytesHex")
                ),
                targetUrl=_text("adjudication.targetUrl", adjudication.get("targetUrl")),
                adjudicationEvidence=_evidence(
                    "adjudication.evidence", adjudication.get("evidence")
                ),
            )
        elif pdf_analysis is not None:
            source.update(
                documentRole=_text("analysis.role", pdf_analysis.get("role")),
                originalName=(
                    external_document_open["targetOriginalName"]
                    if external_document_open is not None
                    else None
                ),
                pdfAnalysis={
                    key: value
                    for key, value in pdf_analysis.items()
                    if key not in {"role", "receiptPath", "evidence"}
                },
                externalDocumentOpen=external_document_open,
                adjudicationEvidence=adjudication_evidence,
            )
            source["pdfAnalysis"]["receiptPath"] = _text(
                "analysis.receiptPath", pdf_analysis.get("receiptPath")
            )
            source["pdfAnalysis"]["evidence"] = _evidence(
                "analysis.evidence", pdf_analysis.get("evidence")
            )
        elif pe_analysis is not None and process_launch is not None:
            source.update(
                staticRole=_text("analysis.role", pe_analysis.get("role")),
                peAnalysis={
                    "status": "PROVEN",
                    "format": str(pe_analysis["format"]),
                    "machine": str(pe_analysis["machine"]),
                    "subsystem": int(pe_analysis["subsystem"]),
                    "entryPointRva": str(pe_analysis["entryPointRva"]),
                    "receiptSha256": str(pe_analysis["receiptSha256"]),
                    "receiptPath": _text("analysis.receiptPath", pe_analysis.get("receiptPath")),
                    "evidence": _evidence("analysis.evidence", pe_analysis.get("evidence")),
                },
                processLaunch=process_launch,
                adjudicationEvidence=adjudication_evidence,
            )
        elif (
            game_updater_analysis is not None
            and process_image is not None
            and process_launch is not None
        ):
            source.update(
                staticRole=_text("analysis.role", game_updater_analysis.get("role")),
                peAnalysis={
                    key: value
                    for key, value in game_updater_analysis.items()
                    if key not in {"role", "receiptPath", "evidence"}
                },
                processImage=process_image,
                processLaunch=process_launch,
                adjudicationEvidence=adjudication_evidence,
            )
            source["peAnalysis"]["receiptPath"] = _text(
                "analysis.receiptPath", game_updater_analysis.get("receiptPath")
            )
            source["peAnalysis"]["evidence"] = _evidence(
                "analysis.evidence", game_updater_analysis.get("evidence")
            )
        elif terms_analysis is not None and duplicate_source is not None:
            source.update(
                documentRole=_text("analysis.role", terms_analysis.get("role")),
                textAnalysis={
                    key: value
                    for key, value in terms_analysis.items()
                    if key not in {"role", "receiptPath", "evidence"}
                },
                duplicateSource=duplicate_source,
                adjudicationEvidence=adjudication_evidence,
            )
            source["textAnalysis"]["receiptPath"] = _text(
                "analysis.receiptPath", terms_analysis.get("receiptPath")
            )
            source["textAnalysis"]["evidence"] = _evidence(
                "analysis.evidence", terms_analysis.get("evidence")
            )
        elif (
            primary_client_analysis is not None
            and process_image is not None
            and inbound_launch is not None
        ):
            source.update(
                staticRole=_text("analysis.role", primary_client_analysis.get("role")),
                peAnalysis={
                    key: value
                    for key, value in primary_client_analysis.items()
                    if key not in {"role", "receiptPath", "evidence"}
                },
                processImage=process_image,
                inboundLaunch=inbound_launch,
                adjudicationEvidence=adjudication_evidence,
            )
            source["peAnalysis"]["receiptPath"] = _text(
                "analysis.receiptPath", primary_client_analysis.get("receiptPath")
            )
            source["peAnalysis"]["evidence"] = _evidence(
                "analysis.evidence", primary_client_analysis.get("evidence")
            )
        file_candidate_id = f"TREE_FILE:{root_id}:{path}"
        rows.append(
            ResourceInventoryRow(
                row=row,
                row_kind=ResourceRowKind.TREE_FILE,
                source=source,
                format=format_section,
                category=category,
                path_resolution=path_resolution,
                loader=loader_section,
                runtime_key=runtime_section,
                owner=owner_section,
                decode_transform=transform_section,
                cache_registry=cache_section,
                submissions=submissions,
                presentation=presentation,
                usage_disposition=usage,
                distribution_disposition="USER_OWNED_LOCAL_ONLY",
                implementation_disposition=_required_implementation(),
                recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
                first_missing_boundary=first_missing,
                reachability_evidence=(f"resource-disposition:{usage.value}",),
                evidence=(f"tree-manifest:{path}:{entry.content_sha256}",),
                source_candidate_ids=tuple(dict.fromkeys([file_candidate_id, *attached_ids])),
            )
        )
    for value in raw["externalDependencyCandidates"]:
        item = _mapping("external dependency", value)
        candidate_id = _text("external dependency candidateId", item.get("candidateId"))
        status = _text("external dependency status", item.get("status"))
        if status not in {"CANDIDATE", "UNKNOWN"}:
            raise ValueError("external dependency cannot claim runtime proof")
        dependency_kind = _text("external dependency kind", item.get("dependencyKind"))
        name = _text("external dependency name", item.get("name"))
        category_value = _text("external dependency category", item.get("category"))
        if category_value not in RESOURCE_CATEGORIES:
            raise ValueError("unsupported external dependency category")
        evidence = _evidence("external dependency evidence", item.get("evidence"))
        states = {state: False for state in EvidenceState}
        states[EvidenceState.ENUMERATED] = True
        rows.append(
            ResourceInventoryRow(
                row=InventoryRow(
                    key=f"RESOURCE:EXTERNAL:{candidate_id}",
                    inventory=InventoryKind.RESOURCE,
                    name=name,
                    provenance="ORIGINAL_OBSERVED",
                    reachability=Reachability.UNKNOWN,
                    states=states,
                ),
                row_kind=ResourceRowKind.EXTERNAL_DEPENDENCY,
                source={
                    "status": "PROVEN",
                    "kind": "PE_IMPORT",
                    "dependencyKind": dependency_kind,
                    "evidence": evidence,
                },
                format=ResourceSection(
                    ResourceSectionStatus.NOT_APPLICABLE,
                    {"evidence": evidence},
                ),
                category=ResourceSection(
                    ResourceSectionStatus.CANDIDATE,
                    {"value": category_value, "evidence": evidence},
                ),
                path_resolution=ResourceSection(
                    ResourceSectionStatus.NOT_APPLICABLE,
                    {"evidence": evidence},
                ),
                loader=ResourceSection(
                    ResourceSectionStatus.CANDIDATE,
                    {"api": (name,), "candidateIds": (candidate_id,), "evidence": evidence},
                ),
                runtime_key=_unknown_section(namespace=(), value=(), derivationFunction=()),
                owner=_unknown_section(ownerKind=(), ownerKeys=(), functions=(), joinKind=()),
                decode_transform=_unknown_section(stages=(), reason=()),
                cache_registry=_unknown_section(
                    registryKey=(), runtimeKeyRefs=(), insertFunctions=(), readFunctions=(), evictFunctions=()
                ),
                submissions={
                    kind: _unknown_section(function=(), sink=(), runtimeReceiptRefs=())
                    for kind in ("render", "audio", "ui")
                },
                presentation=_unknown_section(
                    runId=(), sourceSha256=(), runtimeKey=(), ownerKey=(), submissionCandidateId=()
                ),
                usage_disposition=UsageDisposition.ENUMERATED_ONLY,
                distribution_disposition="OS_PROVIDED_EXTERNAL_DEPENDENCY",
                implementation_disposition=_required_implementation(),
                recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
                first_missing_boundary="RUNTIME_FONT_SELECTION",
                reachability_evidence=("resource-disposition:ENUMERATED_ONLY",),
                evidence=evidence,
                source_candidate_ids=(candidate_id,),
            )
        )
    return rows


def _json_value(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in sorted(value.items())}
    if isinstance(value, tuple):
        return [_json_value(item) for item in value]
    return value


def resource_row_to_dict(item: ResourceInventoryRow) -> dict[str, Any]:
    return {
        "schemaVersion": 1,
        "key": item.row.key,
        "inventory": item.row.inventory.value,
        "rowKind": item.row_kind.value,
        "name": item.row.name,
        "provenance": item.row.provenance,
        "reachability": item.row.reachability.value,
        "reachabilityEvidence": list(item.reachability_evidence),
        "recoveryDisposition": item.recovery_disposition.value,
        "distributionDisposition": item.distribution_disposition,
        "usageDisposition": item.usage_disposition.value,
        "states": {state.value: item.row.states[state] for state in EvidenceState},
        "source": _json_value(item.source),
        "format": {"status": item.format.status.value, **_json_value(item.format.values)},
        "category": {"status": item.category.status.value, **_json_value(item.category.values)},
        "pathResolution": {
            "status": item.path_resolution.status.value,
            **_json_value(item.path_resolution.values),
        },
        "loader": {"status": item.loader.status.value, **_json_value(item.loader.values)},
        "runtimeKey": {
            "status": item.runtime_key.status.value,
            **_json_value(item.runtime_key.values),
        },
        "owner": {"status": item.owner.status.value, **_json_value(item.owner.values)},
        "decodeTransform": {
            "status": item.decode_transform.status.value,
            **_json_value(item.decode_transform.values),
        },
        "cacheRegistry": {
            "status": item.cache_registry.status.value,
            **_json_value(item.cache_registry.values),
        },
        "submissions": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in sorted(item.submissions.items())
        },
        "presentation": {
            "status": item.presentation.status.value,
            **_json_value(item.presentation.values),
        },
        "implementationDisposition": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in sorted(item.implementation_disposition.items())
        },
        "firstMissingBoundary": item.first_missing_boundary,
        "evidence": list(item.evidence),
        "sourceCandidateIds": list(item.source_candidate_ids),
    }


def normalize_resource_inventory(rows: list[ResourceInventoryRow]) -> list[dict[str, Any]]:
    return [resource_row_to_dict(row) for row in sorted(rows, key=lambda item: item.row.key.casefold())]


def build_resource_reconciliation(
    raw: Mapping[str, Any],
    rows: list[ResourceInventoryRow],
    tree_entries: list[TreeManifestEntry],
) -> dict[str, Any]:
    file_ids = {
        candidate_id
        for row in rows
        for candidate_id in row.source_candidate_ids
        if candidate_id.startswith("TREE_FILE:")
    }
    if len(file_ids) != len(tree_entries):
        raise ValueError("tree candidate conservation differs")
    represented = {candidate_id for row in rows for candidate_id in row.source_candidate_ids}
    candidates: dict[str, Mapping[str, Any] | None] = {
        candidate_id: None for candidate_id in file_ids
    }
    for collection in CANDIDATE_COLLECTIONS:
        for value in raw[collection]:
            item = _mapping(collection, value)
            candidates[_text("candidateId", item.get("candidateId"))] = item
    records: list[dict[str, Any]] = []
    normalized_count = unresolved_count = excluded_count = 0
    for candidate_id, item in candidates.items():
        if candidate_id in represented:
            status = "NORMALIZED"
            first_missing = None
            normalized_count += 1
        elif item is not None and item.get("status") == "EXCLUDED":
            status = "EXCLUDED"
            first_missing = _text("excluded reason", item.get("reason"))
            excluded_count += 1
        else:
            status = "UNRESOLVED"
            first_missing = (
                str(item.get("firstMissingBoundary"))
                if item is not None and item.get("firstMissingBoundary")
                else "RESOURCE_PATH_JOIN"
            )
            unresolved_count += 1
        records.append(
            {
                "candidateId": candidate_id,
                "status": status,
                "firstMissingBoundary": first_missing,
            }
        )
    candidate_count = len(candidates)
    accounted = normalized_count + unresolved_count + excluded_count
    return {
        "schemaVersion": 1,
        "candidateCount": candidate_count,
        "normalizedCount": normalized_count,
        "unresolvedCount": unresolved_count,
        "excludedCount": excluded_count,
        "unaccountedCount": candidate_count - accounted,
        "records": sorted(records, key=lambda item: item["candidateId"]),
    }


def load_resources_evidence_manifest(path: str | Path) -> ResourcesEvidenceManifest:
    manifest_path = Path(path).resolve()
    payload = _mapping(
        "resources evidence manifest",
        json.loads(manifest_path.read_text(encoding="utf-8")),
    )
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported resources evidence manifest schemaVersion")
    if _sha256("clientSha256", payload.get("clientSha256")) != CLIENT_SHA256:
        raise ValueError("resources evidence manifest binds a different client")

    def bound_file(label: str) -> tuple[Path, str]:
        record = _mapping(label, payload.get(label))
        file_path = Path(_text(f"{label}.path", record.get("path")))
        if not file_path.is_absolute():
            file_path = Path.cwd() / file_path
        file_path = file_path.resolve()
        expected = _sha256(f"{label}.sha256", record.get("sha256"))
        if not file_path.is_file() or sha256_file(file_path) != expected:
            raise ValueError(f"{label} hash mismatch or file missing")
        return file_path, expected

    raw_path, raw_sha = bound_file("raw")
    exporter_path, exporter_sha = bound_file("exporter")
    source_manifest_path, source_manifest_sha = bound_file("sourceManifest")
    tree_manifest_path, tree_manifest_sha = bound_file("treeManifest")
    pe_imports_path, pe_imports_sha = bound_file("peImports")
    return ResourcesEvidenceManifest(
        path=manifest_path,
        raw_path=raw_path,
        raw_sha256=raw_sha,
        exporter_path=exporter_path,
        exporter_sha256=exporter_sha,
        repository_sha256=_sha256(
            "ghidraRepositorySha256", payload.get("ghidraRepositorySha256")
        ),
        source_manifest_path=source_manifest_path,
        source_manifest_sha256=source_manifest_sha,
        tree_manifest_path=tree_manifest_path,
        tree_manifest_sha256=tree_manifest_sha,
        pe_imports_path=pe_imports_path,
        pe_imports_sha256=pe_imports_sha,
    )


def _tree_entries_from_source_manifest(
    source_manifest_path: Path,
    expected_tree_manifest_path: Path,
) -> tuple[str, list[TreeManifestEntry]]:
    payload = json.loads(source_manifest_path.read_text(encoding="utf-8"))
    roots = payload.get("resourceRoots")
    if not isinstance(roots, list) or len(roots) != 1:
        raise ValueError("Task 6 requires exactly one original resource root")
    root = _mapping("resource root", roots[0])
    root_id = _text("resource root id", root.get("id"))
    root_path = Path(_text("resource root path", root.get("path"))).resolve()
    prefix = _text("resource root prefix", root.get("pathPrefix")).replace("\\", "/")
    if not prefix.endswith("/"):
        prefix += "/"
    tree_record = _mapping("treeManifest", root.get("treeManifest"))
    manifest_path = Path(_text("tree manifest path", tree_record.get("path"))).resolve()
    if manifest_path != expected_tree_manifest_path:
        raise ValueError("source and evidence manifests bind different tree manifests")
    entries: list[TreeManifestEntry] = []
    for line_number, line in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), 1):
        if len(line) < 67 or line[64:66] != " *":
            raise ValueError(f"invalid tree manifest line {line_number}")
        digest = _sha256(f"tree line {line_number}", line[:64])
        full_path = line[66:].replace("\\", "/")
        if not full_path.startswith(prefix):
            raise ValueError("tree manifest path is outside resource prefix")
        relative = _safe_resource_path(full_path[len(prefix) :])
        target = root_path / Path(relative)
        if not target.is_file():
            raise ValueError(f"resource file missing: {target}")
        entries.append(TreeManifestEntry(relative, digest, target.stat().st_size))
    return root_id, entries


def _write_jsonl(path: Path, items: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(
        json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        for item in items
    )
    path.write_text(text, encoding="utf-8", newline="\n")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reconciliation", type=Path, required=True)
    parser.add_argument("--evidence-manifest", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--adjudications", type=Path)
    args = parser.parse_args(argv)
    evidence = load_resources_evidence_manifest(args.evidence_manifest)
    if args.input.resolve() != evidence.raw_path:
        raise ValueError("--input differs from hash-bound resources raw path")
    if args.source_manifest.resolve() != evidence.source_manifest_path:
        raise ValueError("--source-manifest differs from evidence binding")
    source_manifest = SourceManifest.load(args.source_manifest)
    root_id, entries = _tree_entries_from_source_manifest(
        evidence.source_manifest_path, evidence.tree_manifest_path
    )
    adjudications = (
        load_resource_adjudications(
            args.adjudications,
            expected_root_id=root_id,
            expected_source_manifest_sha256=evidence.source_manifest_sha256,
            expected_tree_manifest_sha256=evidence.tree_manifest_sha256,
        )
        if args.adjudications is not None
        else {}
    )
    payload = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    repository_sha = _sha256(
        "source repository hash", _mapping("ghidra", payload.get("ghidra")).get("repositorySha256")
    )
    if repository_sha != evidence.repository_sha256:
        raise ValueError("resources and source manifests bind different Ghidra databases")
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    rows = build_resource_inventory(
        raw,
        entries,
        root_id=root_id,
        adjudications=adjudications,
        expected_exporter_sha256=evidence.exporter_sha256,
        expected_repository_sha256=repository_sha,
        expected_source_manifest_sha256=evidence.source_manifest_sha256,
        expected_tree_manifest_sha256=evidence.tree_manifest_sha256,
        expected_pe_imports_sha256=evidence.pe_imports_sha256,
    )
    normalized = normalize_resource_inventory(rows)
    reconciliation = build_resource_reconciliation(raw, rows, entries)
    if reconciliation["unaccountedCount"] != 0:
        raise ValueError("resource reconciliation left unaccounted candidates")
    _write_jsonl(args.output, normalized)
    args.reconciliation.parent.mkdir(parents=True, exist_ok=True)
    args.reconciliation.write_text(
        json.dumps(reconciliation, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        canonical_json(
            {
                "status": "PASS",
                "rowCount": len(rows),
                "candidateCount": reconciliation["candidateCount"],
                "unresolvedCount": reconciliation["unresolvedCount"],
                "unaccountedCount": reconciliation["unaccountedCount"],
                "verifiedSourcePathCount": len(source_manifest.verified_paths),
            }
        ),
        end="",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
