"""Fail-closed normalization for original-client entity and record evidence."""

from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from types import MappingProxyType
from typing import Any, Iterable, Mapping

from tools.exhaustive_trace.io import canonical_json, sha256_file
from tools.exhaustive_trace.model import (
    EvidenceState,
    ImplementationTarget,
    InventoryKind,
    InventoryRow,
    Reachability,
    RecoveryDisposition,
)
from tools.exhaustive_trace.source_manifest import CLIENT_SHA256, SourceManifest


class EntityStringEnum(str, Enum):
    pass


class EntityRowKind(EntityStringEnum):
    ENTITY_TYPE = "ENTITY_TYPE"
    RECORD_TYPE = "RECORD_TYPE"
    ENTITY_INSTANCE = "ENTITY_INSTANCE"
    CATALOG_ENTRY = "CATALOG_ENTRY"
    UNKNOWN = "UNKNOWN"


class EntitySectionStatus(EntityStringEnum):
    PROVEN = "PROVEN"
    CANDIDATE = "CANDIDATE"
    PROVEN_NONE = "PROVEN_NONE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    SOURCE_CONFLICT = "SOURCE_CONFLICT"
    UNKNOWN = "UNKNOWN"


class EntityFieldStatus(EntityStringEnum):
    PROVEN = "PROVEN"
    CANDIDATE = "CANDIDATE"
    SOURCE_CONFLICT = "SOURCE_CONFLICT"
    UNKNOWN = "UNKNOWN"


class ImplementationStatus(EntityStringEnum):
    REQUIRED = "REQUIRED"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


ENTITY_TYPES = frozenset(
    {
        "ACCOUNT",
        "SESSION",
        "FACTION",
        "CHARACTER",
        "CHARACTER_CREATION_CHARGE_SLOT",
        "CHARACTER_PARENTAGE",
        "ROLE_STATUS_CLASS",
        "RANK",
        "CARD",
        "OFFICE",
        "OFFICE_SEAT",
        "AUTHORITY_CARD",
        "CARD_COMMAND",
        "ADMINISTRATIVE_RECORD",
        "WORLD_TIME",
        "ORDER",
        "SUGGESTION",
        "REPLY",
        "BASE",
        "SYSTEM",
        "STAR_SYSTEM",
        "PLANET",
        "FORTRESS",
        "SPECIAL_BODY",
        "SPECIAL_CELESTIAL_BODY",
        "GRID_CELL",
        "GRID_TYPE",
        "ROUTE_EDGE",
        "INSTITUTION",
        "SPOT",
        "LOCATION_PRESENCE",
        "OUTFIT",
        "FORMATION",
        "FLEET",
        "CORPS",
        "TACTICAL_GROUP",
        "UNIT",
        "FLAGSHIP_ASSIGNMENT",
        "SHIP",
        "SHIP_UNIT_INSTANCE",
        "SHIP_TEMPLATE",
        "TROOP",
        "TROOP_UNIT",
        "TROOP_TEMPLATE",
        "CREW",
        "FIGHTER",
        "FIGHTER_TEMPLATE",
        "FIGHTER_FORMATION",
        "WEAPON",
        "PACKAGE",
        "WAREHOUSE",
        "WAREHOUSE_ITEM",
        "MAIL",
        "MAIL_ADDRESS",
        "MESSENGER",
        "CHAT_CONTEXT",
        "STRATEGY_PLAN",
        "STRATEGIC_MISSION",
        "BATTLE",
        "TACTICAL_FIELD",
        "TACTICS_GRID",
        "TACTICAL_OBSTACLE",
        "EQUIPMENT",
        "RESOURCE_STOCK",
        "RESOURCE_COMMODITY",
        "PRODUCTION_ORDER",
        "PRODUCTION_JOB",
        "REPAIR_SUPPLY_JOB",
        "RANKING",
        "SESSION_TERMINAL",
        "EVENT_RECORD",
    }
)

RELATION_NAMES = frozenset({"parent", "owner", "faction", "location", "visibility"})
PROVEN_RELATION_VERBS = {
    "parent": "PARENT_ID_JOIN",
    "owner": "OWNER_ID_JOIN",
    "faction": "FACTION_ID_JOIN",
    "location": "LOCATION_ID_JOIN",
    "visibility": "VISIBILITY_ID_JOIN",
}
LIFECYCLE_NAMES = frozenset(
    {"definition", "create", "select", "query", "update", "transfer", "destroy", "terminal"}
)
PROJECTION_NAMES = frozenset({"static", "dynamic", "notification"})
REPRESENTATION_NAMES = frozenset({"cache", "renderer"})
IMPLEMENTATION_TARGET_NAMES = frozenset(target.value for target in ImplementationTarget)

TOP_LEVEL_FIELDS = frozenset(
    {
        "schemaVersion",
        "source",
        "exporter",
        "surfaceSha256",
        "successMarker",
        "audit",
        "conservation",
        "entityTypeCandidates",
        "recordSchemaCandidates",
        "recordFieldCandidates",
        "recordParserCandidates",
        "recordRegistryCandidates",
        "strideCapCandidates",
        "idComparisonCandidates",
        "relationshipCandidates",
        "lifecycleCandidates",
        "wireProjectionCandidates",
        "cacheConsumerCandidates",
        "rendererConsumerCandidates",
        "catalogCandidates",
        "manualEntityCandidates",
        "labelCandidates",
    }
)

CANDIDATE_COLLECTIONS = (
    "entityTypeCandidates",
    "recordSchemaCandidates",
    "recordFieldCandidates",
    "recordParserCandidates",
    "recordRegistryCandidates",
    "strideCapCandidates",
    "idComparisonCandidates",
    "relationshipCandidates",
    "lifecycleCandidates",
    "wireProjectionCandidates",
    "cacheConsumerCandidates",
    "rendererConsumerCandidates",
    "catalogCandidates",
    "manualEntityCandidates",
    "labelCandidates",
)

DIRECT_ROW_COLLECTIONS = frozenset(
    {"entityTypeCandidates", "recordSchemaCandidates", "manualEntityCandidates"}
)

SHA256_PATTERN = re.compile(r"^[0-9A-Fa-f]{64}$")
FUNCTION_PATTERN = re.compile(r"^(?:FUN_)?[0-9A-Fa-f]{8}$")
FIELD_PLACEHOLDER_PATTERN = re.compile(r"^field[0-9]+$")
ENTITY_TOKEN_PATTERN = re.compile(r"^[A-Z][A-Z0-9_]*$")
RUNTIME_POINTER_PATTERN = re.compile(r"^0x[0-9A-Fa-f]{8}$")
EXPECTED_LANGUAGE = "x86:LE:32:default"
EXPECTED_COMPILER = "windows"
EXPECTED_IMAGE_BASE = "00400000"


@dataclass(frozen=True)
class EntitySection:
    status: EntityStringEnum
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class EntityLayout:
    status: EntitySectionStatus
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class EntityInventoryRow:
    row: InventoryRow
    row_kind: EntityRowKind
    entity_type: str
    record_type: str | None
    state_bearing: bool
    layout: EntityLayout | None
    id_namespace: EntitySection
    relations: Mapping[str, EntitySection]
    lifecycle: Mapping[str, EntitySection]
    wire_projections: Mapping[str, EntitySection]
    client_representation: Mapping[str, EntitySection]
    authority: EntitySection
    persistence: EntitySection
    reconnect_replay: EntitySection
    implementation_disposition: Mapping[str, EntitySection]
    catalog_cardinality: tuple[Mapping[str, Any], ...]
    recovery_disposition: RecoveryDisposition
    first_missing_boundary: str
    reachability_evidence: tuple[str, ...]
    evidence: tuple[str, ...]
    source_candidate_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "relations", MappingProxyType(dict(self.relations)))
        object.__setattr__(self, "lifecycle", MappingProxyType(dict(self.lifecycle)))
        object.__setattr__(self, "wire_projections", MappingProxyType(dict(self.wire_projections)))
        object.__setattr__(self, "client_representation", MappingProxyType(dict(self.client_representation)))
        object.__setattr__(self, "implementation_disposition", MappingProxyType(dict(self.implementation_disposition)))


@dataclass(frozen=True)
class RecordsEvidenceManifest:
    path: Path
    raw_path: Path
    raw_sha256: str
    exporter_path: Path
    exporter_sha256: str
    repository_sha256: str
    manual_text_path: Path
    manual_text_sha256: str
    manual_pdf_path: Path
    manual_pdf_sha256: str
    manual_page_xml_path: Path
    manual_page_xml_sha256: str
    catalog_candidate_path: Path
    catalog_candidate_sha256: str
    protocol_raw_path: Path
    protocol_raw_sha256: str


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


def _text_list(name: str, value: object, *, allow_empty: bool = True) -> tuple[str, ...]:
    if not isinstance(value, list) or any(not isinstance(item, str) or not item.strip() for item in value):
        raise ValueError(f"{name} must be a text list")
    if not allow_empty and not value:
        raise ValueError(f"{name} must not be empty")
    return tuple(item.strip() for item in value)


def _evidence(name: str, value: object) -> tuple[str, ...]:
    return _text_list(name, value, allow_empty=False)


def _sha256(name: str, value: object) -> str:
    result = _text(name, value).upper()
    if not SHA256_PATTERN.fullmatch(result):
        raise ValueError(f"{name} must be a SHA-256")
    return result


def _positive_int(name: str, value: object, *, allow_zero: bool = False) -> int:
    if not isinstance(value, int) or isinstance(value, bool):
        raise ValueError(f"{name} must be an integer")
    if value < 0 or (value == 0 and not allow_zero):
        raise ValueError(f"{name} is outside its allowed range")
    return value


def load_records_evidence_manifest(path: str | Path) -> RecordsEvidenceManifest:
    manifest_path = Path(path).resolve()
    payload = _mapping(
        "records evidence manifest",
        json.loads(manifest_path.read_text(encoding="utf-8")),
    )
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported records evidence manifest schemaVersion")
    if _sha256("clientSha256", payload.get("clientSha256")) != CLIENT_SHA256:
        raise ValueError("records evidence manifest is bound to a different client")
    repository_sha = _sha256(
        "ghidraRepositorySha256", payload.get("ghidraRepositorySha256")
    )

    def bound_file(label: str) -> tuple[Path, str]:
        record = _mapping(label, payload.get(label))
        file_path = Path(_text(f"{label}.path", record.get("path")))
        if not file_path.is_absolute():
            file_path = Path.cwd() / file_path
        file_path = file_path.resolve()
        expected = _sha256(f"{label}.sha256", record.get("sha256"))
        if not file_path.is_file():
            raise ValueError(f"{label} file is missing: {file_path}")
        actual = sha256_file(file_path)
        if actual != expected:
            raise ValueError(f"{label} hash mismatch: expected {expected}, got {actual}")
        return file_path, expected

    raw_path, raw_sha = bound_file("raw")
    exporter_path, exporter_sha = bound_file("exporter")
    manual_text_path, manual_text_sha = bound_file("manualText")
    manual_pdf_path, manual_pdf_sha = bound_file("manualPdf")
    manual_page_xml_path, manual_page_xml_sha = bound_file("manualPageXml")
    catalog_path, catalog_sha = bound_file("catalogCandidates")
    protocol_raw_path, protocol_raw_sha = bound_file("protocolRaw")
    return RecordsEvidenceManifest(
        path=manifest_path,
        raw_path=raw_path,
        raw_sha256=raw_sha,
        exporter_path=exporter_path,
        exporter_sha256=exporter_sha,
        repository_sha256=repository_sha,
        manual_text_path=manual_text_path,
        manual_text_sha256=manual_text_sha,
        manual_pdf_path=manual_pdf_path,
        manual_pdf_sha256=manual_pdf_sha,
        manual_page_xml_path=manual_page_xml_path,
        manual_page_xml_sha256=manual_page_xml_sha,
        catalog_candidate_path=catalog_path,
        catalog_candidate_sha256=catalog_sha,
        protocol_raw_path=protocol_raw_path,
        protocol_raw_sha256=protocol_raw_sha,
    )


def _validate_export(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_manual_text_sha256: str | None = None,
    expected_manual_pdf_sha256: str | None = None,
    expected_manual_page_xml_sha256: str | None = None,
    expected_catalog_candidate_sha256: str | None = None,
    expected_protocol_raw_sha256: str | None = None,
) -> None:
    unknown = set(raw) - TOP_LEVEL_FIELDS
    missing = TOP_LEVEL_FIELDS - set(raw)
    if unknown or missing:
        raise ValueError(
            f"records export top-level fields differ: unknown={sorted(unknown)} missing={sorted(missing)}"
        )
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported records export schemaVersion")
    if raw.get("successMarker") != "EXPORT_EXHAUSTIVE_RECORDS_OK":
        raise ValueError("records export success marker is missing")
    source = _mapping("source", raw.get("source"))
    if source.get("program") != "g7mtclient.exe":
        raise ValueError("records source program mismatch")
    if _sha256("source.executableSha256", source.get("executableSha256")) != CLIENT_SHA256:
        raise ValueError("records source executable hash mismatch")
    if source.get("language") != EXPECTED_LANGUAGE:
        raise ValueError("records source language mismatch")
    if source.get("compiler") != EXPECTED_COMPILER:
        raise ValueError("records source compiler mismatch")
    if source.get("imageBase") != EXPECTED_IMAGE_BASE:
        raise ValueError("records source image base mismatch")
    manual_sha = _sha256("source.manualTextSha256", source.get("manualTextSha256"))
    manual_pdf_sha = _sha256("source.manualPdfSha256", source.get("manualPdfSha256"))
    manual_page_xml_sha = _sha256(
        "source.manualPageXmlSha256", source.get("manualPageXmlSha256")
    )
    catalog_sha = _sha256(
        "source.catalogCandidateSha256", source.get("catalogCandidateSha256")
    )
    protocol_raw_sha = _sha256(
        "source.protocolRawSha256", source.get("protocolRawSha256")
    )
    if expected_manual_text_sha256 is not None and manual_sha != expected_manual_text_sha256.upper():
        raise ValueError("records source manual text hash mismatch")
    if expected_manual_pdf_sha256 is not None and manual_pdf_sha != expected_manual_pdf_sha256.upper():
        raise ValueError("records source manual PDF hash mismatch")
    if expected_manual_page_xml_sha256 is not None and manual_page_xml_sha != expected_manual_page_xml_sha256.upper():
        raise ValueError("records source manual page XML hash mismatch")
    if expected_catalog_candidate_sha256 is not None and catalog_sha != expected_catalog_candidate_sha256.upper():
        raise ValueError("records source catalog candidate hash mismatch")
    if expected_protocol_raw_sha256 is not None and protocol_raw_sha != expected_protocol_raw_sha256.upper():
        raise ValueError("records source protocol raw hash mismatch")
    audit = _mapping("audit", raw.get("audit"))
    if (
        audit.get("scope") != "COMPILED_RECORD_ANCHORS"
        or audit.get("capsArePopulationCounts") is not False
        or audit.get("catalogParentIsRuntimeJoin") is not False
        or audit.get("authorityPersistenceCovered") is not False
    ):
        raise ValueError("records audit overstates its bounded scope")
    _text_list("audit.limitations", audit.get("limitations"), allow_empty=False)
    conservation = _mapping("conservation", raw.get("conservation"))
    for name in ("streamContracts", "recordFamilies", "familyFields"):
        _positive_int(f"conservation.{name}", conservation.get(name))
    exporter = _mapping("exporter", raw.get("exporter"))
    if exporter.get("class") != "ExportExhaustiveRecords":
        raise ValueError("records exporter class mismatch")
    exporter_sha = _sha256("exporter.sha256", exporter.get("sha256"))
    repository_sha = _sha256(
        "exporter.ghidraRepositorySha256", exporter.get("ghidraRepositorySha256")
    )
    if expected_exporter_sha256 is not None and exporter_sha != expected_exporter_sha256.upper():
        raise ValueError("records raw exporter hash differs from evidence manifest")
    if expected_repository_sha256 is not None and repository_sha != expected_repository_sha256.upper():
        raise ValueError("records raw Ghidra repository hash differs from source manifest")
    _sha256("surfaceSha256", raw.get("surfaceSha256"))
    seen: set[str] = set()
    for collection in CANDIDATE_COLLECTIONS:
        items = raw.get(collection)
        if not isinstance(items, list):
            raise ValueError(f"{collection} must be a list")
        for index, item_value in enumerate(items):
            item = _mapping(f"{collection}[{index}]", item_value)
            candidate_id = _text(
                f"{collection}[{index}].candidateId", item.get("candidateId")
            )
            if candidate_id in seen:
                raise ValueError(f"duplicate records candidateId: {candidate_id}")
            seen.add(candidate_id)


def _plain_section(name: str, value: object) -> EntitySection:
    item = _mapping(name, value)
    status = EntitySectionStatus(_text(f"{name}.status", item.get("status")))
    evidence = _evidence(f"{name}.evidence", item.get("evidence"))
    claims = {key: value for key, value in item.items() if key not in {"status", "evidence"}}
    if status in {EntitySectionStatus.UNKNOWN, EntitySectionStatus.PROVEN_NONE}:
        nonempty = [key for key, claim in claims.items() if claim not in (None, [], {}, "UNKNOWN")]
        if nonempty:
            raise ValueError(f"{name} unknown section cannot claim {nonempty}")
    return EntitySection(status, {**claims, "evidence": evidence})


def _id_namespace(value: object, *, state_bearing: bool) -> EntitySection:
    item = _mapping("idNamespace", value)
    required = {
        "status",
        "name",
        "fields",
        "widthBits",
        "signedness",
        "uniquenessScope",
        "comparisonFunctions",
        "nullSemantics",
        "evidence",
    }
    if set(item) != required:
        raise ValueError("idNamespace fields differ")
    status = EntitySectionStatus(_text("idNamespace.status", item.get("status")))
    name = _optional_text("idNamespace.name", item.get("name"))
    fields = _text_list("idNamespace.fields", item.get("fields"))
    width = item.get("widthBits")
    if width is not None:
        width = _positive_int("idNamespace.widthBits", width)
    signedness = _text("idNamespace.signedness", item.get("signedness"))
    scope = _text("idNamespace.uniquenessScope", item.get("uniquenessScope"))
    comparisons = _text_list(
        "idNamespace.comparisonFunctions", item.get("comparisonFunctions")
    )
    null_semantics = _text("idNamespace.nullSemantics", item.get("nullSemantics"))
    evidence = _evidence("idNamespace.evidence", item.get("evidence"))
    if status is EntitySectionStatus.PROVEN and (
        name is None
        or not fields
        or width is None
        or signedness == "UNKNOWN"
        or scope == "UNKNOWN"
        or not comparisons
        or null_semantics == "UNKNOWN"
    ):
        raise ValueError("proven ID namespace requires fields, width, semantics, scope, and key consumer")
    if status is EntitySectionStatus.UNKNOWN and any(
        (name is not None, bool(fields), width is not None, signedness != "UNKNOWN", scope != "UNKNOWN", bool(comparisons), null_semantics != "UNKNOWN")
    ):
        raise ValueError("unknown ID namespace cannot claim identity semantics")
    if state_bearing and status is EntitySectionStatus.NOT_APPLICABLE:
        raise ValueError("state-bearing entity cannot omit its ID namespace")
    return EntitySection(
        status,
        {
            "name": name,
            "fields": fields,
            "widthBits": width,
            "signedness": signedness,
            "uniquenessScope": scope,
            "comparisonFunctions": comparisons,
            "nullSemantics": null_semantics,
            "evidence": evidence,
        },
    )


def _relation_section(name: str, value: object) -> EntitySection:
    item = _mapping(f"relations.{name}", value)
    if set(item) != {"status", "edges", "evidence"}:
        raise ValueError(f"relations.{name} fields differ")
    status = EntitySectionStatus(_text(f"relations.{name}.status", item.get("status")))
    edges_value = item.get("edges")
    if not isinstance(edges_value, list):
        raise ValueError(f"relations.{name}.edges must be a list")
    evidence = _evidence(f"relations.{name}.evidence", item.get("evidence"))
    edges: list[Mapping[str, Any]] = []
    for index, edge_value in enumerate(edges_value):
        edge = _mapping(f"relations.{name}.edges[{index}]", edge_value)
        relation = _text("relation", edge.get("relation"))
        target_type = _text("targetEntityType", edge.get("targetEntityType"))
        if target_type not in ENTITY_TYPES:
            raise ValueError("relation target entity type is unsupported")
        edge_evidence = _evidence("relation.evidence", edge.get("evidence"))
        source_field = _optional_text("relation.sourceField", edge.get("sourceField"))
        target_namespace = _optional_text(
            "relation.targetNamespace", edge.get("targetNamespace")
        )
        join_function = _optional_text("relation.joinFunction", edge.get("joinFunction"))
        if status is EntitySectionStatus.PROVEN and (
            source_field is None
            or target_namespace is None
            or join_function is None
            or not FUNCTION_PATTERN.fullmatch(join_function)
        ):
            raise ValueError("proven relation requires typed edge and join evidence")
        if status is EntitySectionStatus.CANDIDATE and relation not in {
            "CATALOG_PARENT",
            "NAME_MATCH",
            "CANDIDATE_PARENT",
            "CANDIDATE_LOCATION",
            "CANDIDATE_OWNER",
            "CANDIDATE_FACTION",
            "CANDIDATE_VISIBILITY",
        }:
            raise ValueError("candidate relation uses a proven relation verb")
        edges.append(
            {
                "relation": relation,
                "targetEntityType": target_type,
                "sourceField": source_field,
                "targetNamespace": target_namespace,
                "joinFunction": join_function,
                "evidence": edge_evidence,
            }
        )
    if status is EntitySectionStatus.UNKNOWN and edges:
        raise ValueError("unknown relation cannot claim edges")
    if status is EntitySectionStatus.PROVEN and not edges:
        raise ValueError("proven relation requires at least one typed edge")
    return EntitySection(status, {"edges": tuple(edges), "evidence": evidence})


def _operation_section(name: str, value: object) -> EntitySection:
    item = _mapping(name, value)
    if set(item) != {"status", "operations", "evidence"}:
        raise ValueError(f"{name} fields differ")
    status = EntitySectionStatus(_text(f"{name}.status", item.get("status")))
    operations = _text_list(f"{name}.operations", item.get("operations"))
    evidence = _evidence(f"{name}.evidence", item.get("evidence"))
    if status is EntitySectionStatus.PROVEN and not operations:
        raise ValueError(f"proven lifecycle or replay section {name} requires operations")
    if status in {EntitySectionStatus.UNKNOWN, EntitySectionStatus.PROVEN_NONE} and operations:
        raise ValueError(f"unknown lifecycle or replay section {name} cannot claim operations")
    return EntitySection(status, {"operations": operations, "evidence": evidence})


def _projection_section(name: str, value: object) -> EntitySection:
    item = _mapping(name, value)
    required = {"status", "protocolKeys", "fieldKeys", "evidence"}
    if set(item) != required:
        raise ValueError(f"{name} fields differ")
    status = EntitySectionStatus(_text(f"{name}.status", item.get("status")))
    protocol_keys = _text_list(f"{name}.protocolKeys", item.get("protocolKeys"))
    field_keys = _text_list(f"{name}.fieldKeys", item.get("fieldKeys"))
    evidence = _evidence(f"{name}.evidence", item.get("evidence"))
    if status is EntitySectionStatus.PROVEN and not protocol_keys:
        raise ValueError(f"proven projection {name} requires a protocol key")
    if status in {EntitySectionStatus.UNKNOWN, EntitySectionStatus.PROVEN_NONE} and (
        protocol_keys or field_keys
    ):
        raise ValueError(f"unknown projection {name} cannot claim protocol or fields")
    return EntitySection(
        status,
        {"protocolKeys": protocol_keys, "fieldKeys": field_keys, "evidence": evidence},
    )


def _representation_section(name: str, value: object) -> EntitySection:
    item = _mapping(name, value)
    if name.endswith("cache"):
        required = {"status", "writers", "readers", "evidence"}
        lists = ("writers", "readers")
    else:
        required = {"status", "consumers", "evidence"}
        lists = ("consumers",)
    if set(item) != required:
        raise ValueError(f"{name} fields differ")
    status = EntitySectionStatus(_text(f"{name}.status", item.get("status")))
    values = {field: _text_list(f"{name}.{field}", item.get(field)) for field in lists}
    evidence = _evidence(f"{name}.evidence", item.get("evidence"))
    if status is EntitySectionStatus.PROVEN and not any(values.values()):
        raise ValueError(f"proven client representation {name} requires a consumer")
    if status in {EntitySectionStatus.UNKNOWN, EntitySectionStatus.PROVEN_NONE} and any(values.values()):
        raise ValueError(f"unknown client representation {name} cannot claim consumers")
    return EntitySection(status, {**values, "evidence": evidence})


def _field(value: object, *, stride: int | None) -> Mapping[str, Any]:
    item = _mapping("field", value)
    status = EntityFieldStatus(_text("field.status", item.get("status")))
    key = _text("field.key", item.get("key"))
    ordinal = _positive_int("field.ordinal", item.get("ordinal"), allow_zero=True)
    name = _text("field.name", item.get("name"))
    semantic_status = _text(
        "field.semanticNameStatus", item.get("semanticNameStatus")
    )
    offset = item.get("offsetBytes")
    if offset is not None:
        offset = _positive_int("field.offsetBytes", offset, allow_zero=True)
    width = item.get("widthBits")
    if width is not None:
        width = _positive_int("field.widthBits", width)
    scalar_kind = _text("field.scalarKind", item.get("scalarKind"))
    signedness = _optional_text("field.signedness", item.get("signedness"))
    cap = item.get("arrayCap")
    if cap is not None:
        cap = _positive_int("field.arrayCap", cap, allow_zero=True)
    alias_group = _optional_text("field.aliasGroup", item.get("aliasGroup"))
    reads = _text_list("field.reads", item.get("reads"))
    writes = _text_list("field.writes", item.get("writes"))
    comparisons = _text_list("field.comparisons", item.get("comparisons"))
    evidence = _text_list("field.evidence", item.get("evidence"))
    if status is EntityFieldStatus.PROVEN and (
        offset is None or width is None or signedness in {None, "UNKNOWN"} or not evidence
    ):
        raise ValueError("proven field requires offset, width, signedness, and evidence")
    if status is EntityFieldStatus.UNKNOWN and (
        offset is not None
        or width is not None
        or semantic_status != "UNKNOWN"
        or not FIELD_PLACEHOLDER_PATTERN.fullmatch(name)
        or reads
        or writes
        or comparisons
    ):
        raise ValueError("unknown field cannot claim semantics or layout")
    if stride is not None and offset is not None and width is not None:
        if offset + math.ceil(width / 8) > stride:
            raise ValueError("field extends outside record stride")
    return {
        "key": key,
        "ordinal": ordinal,
        "name": name,
        "semanticNameStatus": semantic_status,
        "status": status,
        "offsetBytes": offset,
        "widthBits": width,
        "scalarKind": scalar_kind,
        "signedness": signedness,
        "arrayCap": cap,
        "aliasGroup": alias_group,
        "reads": reads,
        "writes": writes,
        "comparisons": comparisons,
        "evidence": evidence,
    }


def _layout(value: object) -> EntityLayout:
    item = _mapping("layout", value)
    status = EntitySectionStatus(_text("layout.status", item.get("status")))
    space = _text("layout.layoutSpace", item.get("layoutSpace"))
    stride = item.get("strideBytes")
    if stride is not None:
        stride = _positive_int("layout.strideBytes", stride)
    record_cap = item.get("recordCap")
    if record_cap is not None:
        record_cap = _positive_int("layout.recordCap", record_cap, allow_zero=True)
    evidence = _evidence("layout.evidence", item.get("evidence"))
    fields_value = item.get("fields")
    if not isinstance(fields_value, list):
        raise ValueError("layout.fields must be a list")
    fields = tuple(_field(value, stride=stride) for value in fields_value)
    if status is EntitySectionStatus.PROVEN and (stride is None or not fields):
        raise ValueError("proven layout requires stride and fields")
    keys = [str(field["key"]) for field in fields]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate field key")
    occupied: dict[int, str | None] = {}
    for field in fields:
        offset = field["offsetBytes"]
        width = field["widthBits"]
        if offset is None or width is None:
            continue
        for byte in range(int(offset), int(offset) + math.ceil(int(width) / 8)):
            if byte in occupied and (
                occupied[byte] is None
                or field["aliasGroup"] is None
                or occupied[byte] != field["aliasGroup"]
            ):
                raise ValueError("non-alias record fields overlap")
            occupied[byte] = field["aliasGroup"]
    return EntityLayout(
        status,
        {
            "layoutSpace": space,
            "strideBytes": stride,
            "recordCap": record_cap,
            "fields": fields,
            "evidence": evidence,
        },
    )


def _implementation(value: object) -> Mapping[str, EntitySection]:
    item = _mapping("implementationDisposition", value)
    if set(item) != IMPLEMENTATION_TARGET_NAMES:
        raise ValueError("implementation disposition target set differs")
    result: dict[str, EntitySection] = {}
    for target in sorted(IMPLEMENTATION_TARGET_NAMES):
        record = _mapping(f"implementationDisposition.{target}", item[target])
        try:
            status = ImplementationStatus(
                _text(f"implementationDisposition.{target}.status", record.get("status"))
            )
        except ValueError as error:
            raise ValueError("unsupported implementation disposition status") from error
        reason = _optional_text(
            f"implementationDisposition.{target}.reason", record.get("reason")
        )
        evidence = _evidence(
            f"implementationDisposition.{target}.evidence", record.get("evidence")
        )
        if status is ImplementationStatus.NOT_APPLICABLE and reason is None:
            raise ValueError("not-applicable implementation target requires a reason")
        result[target] = EntitySection(
            status, {"reason": reason, "evidence": evidence}
        )
    return result


def _catalog_cardinality(value: object) -> tuple[Mapping[str, Any], ...]:
    if not isinstance(value, list):
        raise ValueError("catalogCardinality must be a list")
    result: list[Mapping[str, Any]] = []
    seen: set[str] = set()
    allowed_status = {
        "ORIGINAL_OBSERVED",
        "ORIGINAL_MANUAL",
        "LEGACY_CANDIDATE",
        "NEW_DESIGN",
        "AUTHORED_PLACEHOLDER",
        "SOURCE_CONFLICT",
        "UNKNOWN",
    }
    for index, item_value in enumerate(value):
        item = _mapping(f"catalogCardinality[{index}]", item_value)
        source_id = _text("catalogCardinality.sourceId", item.get("sourceId"))
        if source_id in seen:
            raise ValueError("duplicate catalog cardinality source")
        seen.add(source_id)
        status = _text("catalogCardinality.status", item.get("status"))
        if status not in allowed_status:
            raise ValueError("unsupported catalog cardinality status")
        count = item.get("count")
        if count is not None:
            count = _positive_int("catalogCardinality.count", count, allow_zero=True)
        membership = _text(
            "catalogCardinality.membershipStatus", item.get("membershipStatus")
        )
        evidence = _evidence("catalogCardinality.evidence", item.get("evidence"))
        members_value = item.get("members", [])
        if not isinstance(members_value, list):
            raise ValueError("catalogCardinality.members must be a list")
        members: list[Mapping[str, Any]] = []
        for member_index, member_value in enumerate(members_value):
            member = _mapping(
                f"catalogCardinality.members[{member_index}]", member_value
            )
            if set(member) != {"term", "pdfPage", "pdfSha256", "pageXmlSha256"}:
                raise ValueError("manual catalog member fields differ")
            members.append(
                MappingProxyType(
                    {
                        "term": _text("manual member term", member.get("term")),
                        "pdfPage": _positive_int(
                            "manual member pdfPage", member.get("pdfPage")
                        ),
                        "pdfSha256": _sha256(
                            "manual member pdfSha256", member.get("pdfSha256")
                        ),
                        "pageXmlSha256": _sha256(
                            "manual member pageXmlSha256",
                            member.get("pageXmlSha256"),
                        ),
                    }
                )
            )
        if status == "ORIGINAL_MANUAL" and (count is None or len(members) != count):
            raise ValueError("original manual count must equal page-bound member count")
        if status != "ORIGINAL_MANUAL" and members:
            raise ValueError("only original manual cardinality may claim members")
        result.append(
            MappingProxyType(
                {
                    "sourceId": source_id,
                    "status": status,
                    "count": count,
                    "membershipStatus": membership,
                    "members": tuple(members),
                    "evidence": evidence,
                }
            )
        )
    return tuple(result)


def _build_row(candidate: Mapping[str, Any]) -> EntityInventoryRow:
    candidate_id = _text("candidateId", candidate.get("candidateId"))
    row_kind = EntityRowKind(_text("rowKind", candidate.get("rowKind")))
    entity_type = _text("entityType", candidate.get("entityType"))
    if (
        entity_type not in ENTITY_TYPES
        or not ENTITY_TOKEN_PATTERN.fullmatch(entity_type)
        or RUNTIME_POINTER_PATTERN.fullmatch(entity_type)
    ):
        raise ValueError("unsupported or unstable entity type")
    record_type = candidate.get("recordType")
    if row_kind is EntityRowKind.RECORD_TYPE:
        record_type = _text("recordType", record_type)
    elif record_type is not None:
        raise ValueError("recordType is only valid for RECORD_TYPE rows")
    state_bearing = candidate.get("stateBearing")
    if type(state_bearing) is not bool:
        raise ValueError("stateBearing must be boolean")
    name = _text("name", candidate.get("name"))
    provenance = _text("provenance", candidate.get("provenance"))
    reachability = Reachability(_text("reachability", candidate.get("reachability")))
    recovery = RecoveryDisposition(
        _text("recoveryDisposition", candidate.get("recoveryDisposition"))
    )
    id_namespace = _id_namespace(candidate.get("idNamespace"), state_bearing=state_bearing)
    relation_value = _mapping("relations", candidate.get("relations"))
    if set(relation_value) != RELATION_NAMES:
        raise ValueError("relations slot set differs")
    relations = {
        relation: _relation_section(relation, relation_value[relation])
        for relation in sorted(RELATION_NAMES)
    }
    lifecycle_value = _mapping("lifecycle", candidate.get("lifecycle"))
    if set(lifecycle_value) != LIFECYCLE_NAMES:
        raise ValueError("lifecycle slot set differs")
    lifecycle = {
        phase: _operation_section(f"lifecycle.{phase}", lifecycle_value[phase])
        for phase in sorted(LIFECYCLE_NAMES)
    }
    projection_value = _mapping("wireProjections", candidate.get("wireProjections"))
    if set(projection_value) != PROJECTION_NAMES:
        raise ValueError("wireProjections slot set differs")
    projections = {
        projection: _projection_section(
            f"wireProjections.{projection}", projection_value[projection]
        )
        for projection in sorted(PROJECTION_NAMES)
    }
    representation_value = _mapping(
        "clientRepresentation", candidate.get("clientRepresentation")
    )
    if set(representation_value) != REPRESENTATION_NAMES:
        raise ValueError("clientRepresentation slot set differs")
    representations = {
        name: _representation_section(
            f"clientRepresentation.{name}", representation_value[name]
        )
        for name in sorted(REPRESENTATION_NAMES)
    }
    authority = _plain_section("authority", candidate.get("authority"))
    persistence = _plain_section("persistence", candidate.get("persistence"))
    reconnect = _plain_section("reconnectReplay", candidate.get("reconnectReplay"))
    implementation = _implementation(candidate.get("implementationDisposition"))
    catalog = _catalog_cardinality(candidate.get("catalogCardinality"))
    first_missing = _text(
        "firstMissingBoundary", candidate.get("firstMissingBoundary")
    )
    reachability_evidence = tuple(
        _text_list(
            "reachabilityEvidence",
            candidate.get("reachabilityEvidence", candidate.get("evidence")),
            allow_empty=False,
        )
    )
    evidence = _evidence("evidence", candidate.get("evidence"))
    layout = None
    if row_kind is EntityRowKind.RECORD_TYPE:
        layout = _layout(candidate.get("layout"))
        field_keys = {field["key"] for field in layout.values["fields"]}
        unknown_id_fields = set(id_namespace.values["fields"]) - field_keys
        if unknown_id_fields:
            raise ValueError(f"ID namespace references unknown fields: {sorted(unknown_id_fields)}")
    elif "layout" in candidate:
        raise ValueError("layout is only valid for RECORD_TYPE rows")
    if reachability is Reachability.SHIPPED_REACHABLE:
        has_callpath = any(item.startswith("callpath:") for item in reachability_evidence)
        has_projection = any(
            section.status is EntitySectionStatus.PROVEN
            for section in projections.values()
        )
        if (
            not has_callpath
            or lifecycle["query"].status is not EntitySectionStatus.PROVEN
            or not has_projection
            or representations["cache"].status is not EntitySectionStatus.PROVEN
            or representations["renderer"].status is not EntitySectionStatus.PROVEN
        ):
            raise ValueError(
                "reachable entity requires callpath, query, projection, cache, and renderer proof"
            )
    states = {state: state is EvidenceState.ENUMERATED for state in EvidenceState}
    key = (
        f"ENTITY:RECORD:{record_type}"
        if row_kind is EntityRowKind.RECORD_TYPE
        else f"ENTITY:TYPE:{entity_type}"
    )
    row = InventoryRow(
        key=key,
        inventory=InventoryKind.ENTITY,
        name=name,
        provenance=provenance,
        reachability=reachability,
        states=states,
    )
    return EntityInventoryRow(
        row=row,
        row_kind=row_kind,
        entity_type=entity_type,
        record_type=record_type,
        state_bearing=state_bearing,
        layout=layout,
        id_namespace=id_namespace,
        relations=relations,
        lifecycle=lifecycle,
        wire_projections=projections,
        client_representation=representations,
        authority=authority,
        persistence=persistence,
        reconnect_replay=reconnect,
        implementation_disposition=implementation,
        catalog_cardinality=catalog,
        recovery_disposition=recovery,
        first_missing_boundary=first_missing,
        reachability_evidence=reachability_evidence,
        evidence=evidence,
        source_candidate_ids=tuple(
            dict.fromkeys(
                [candidate_id]
                + list(
                    _text_list(
                        "sourceCandidateIds",
                        candidate.get("sourceCandidateIds", []),
                    )
                )
            )
        ),
    )


def build_entity_inventory(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_manual_text_sha256: str | None = None,
    expected_manual_pdf_sha256: str | None = None,
    expected_manual_page_xml_sha256: str | None = None,
    expected_catalog_candidate_sha256: str | None = None,
    expected_protocol_raw_sha256: str | None = None,
    require_complete_entity_types: bool = False,
) -> list[EntityInventoryRow]:
    _validate_export(
        raw,
        expected_exporter_sha256=expected_exporter_sha256,
        expected_repository_sha256=expected_repository_sha256,
        expected_manual_text_sha256=expected_manual_text_sha256,
        expected_manual_pdf_sha256=expected_manual_pdf_sha256,
        expected_manual_page_xml_sha256=expected_manual_page_xml_sha256,
        expected_catalog_candidate_sha256=expected_catalog_candidate_sha256,
        expected_protocol_raw_sha256=expected_protocol_raw_sha256,
    )
    rows: list[EntityInventoryRow] = []
    for collection in DIRECT_ROW_COLLECTIONS:
        for candidate_value in raw[collection]:
            candidate = _mapping(collection, candidate_value)
            if candidate.get("status") != "EXCLUDED":
                rows.append(_build_row(candidate))
    rows.sort(key=lambda item: item.row.key)
    keys = [item.row.key for item in rows]
    if len(keys) != len(set(keys)):
        raise ValueError("duplicate stable entity key")
    known_candidate_ids = {
        str(candidate["candidateId"])
        for collection in CANDIDATE_COLLECTIONS
        for candidate in raw[collection]
    }
    dangling = {
        candidate_id
        for row in rows
        for candidate_id in row.source_candidate_ids
        if candidate_id not in known_candidate_ids
    }
    if dangling:
        raise ValueError(f"entity rows reference unknown candidates: {sorted(dangling)}")

    record_entity_types = {
        item.entity_type for item in rows if item.row_kind is EntityRowKind.RECORD_TYPE
    }
    for item in rows:
        if (
            item.row_kind is EntityRowKind.ENTITY_TYPE
            and item.row.reachability is Reachability.MANUAL_ONLY
            and item.entity_type in record_entity_types
        ):
            raise ValueError("MANUAL_ONLY entity conflicts with shipped record evidence")

    if require_complete_entity_types:
        actual_entity_types = {
            item.entity_type for item in rows if item.row_kind is EntityRowKind.ENTITY_TYPE
        }
        if actual_entity_types != ENTITY_TYPES:
            missing = sorted(ENTITY_TYPES - actual_entity_types)
            extra = sorted(actual_entity_types - ENTITY_TYPES)
            raise ValueError(
                f"entity type universe differs: missing={missing} extra={extra}"
            )

    protocol_keys = {
        _text("wireProjectionCandidates.candidateId", item.get("candidateId"))
        for item in (
            _mapping("wireProjectionCandidates item", value)
            for value in raw["wireProjectionCandidates"]
        )
    }
    source = _mapping("source", raw["source"])
    manual_pdf_sha = _sha256("source.manualPdfSha256", source.get("manualPdfSha256"))
    manual_page_xml_sha = _sha256(
        "source.manualPageXmlSha256", source.get("manualPageXmlSha256")
    )
    for item in rows:
        layout_fields = {
            str(field["key"])
            for field in (item.layout.values["fields"] if item.layout is not None else ())
        }
        for relation_name, section in item.relations.items():
            if section.status is not EntitySectionStatus.PROVEN:
                continue
            for edge in section.values["edges"]:
                if edge["relation"] != PROVEN_RELATION_VERBS[relation_name]:
                    raise ValueError(f"proven {relation_name} relation verb differs")
                if edge["sourceField"] not in layout_fields:
                    raise ValueError("proven relation sourceField is absent from layout")
        for section in item.wire_projections.values():
            for protocol_key in section.values["protocolKeys"]:
                if protocol_key not in protocol_keys:
                    raise ValueError("projection protocol key is dangling")
            for field_key in section.values["fieldKeys"]:
                if field_key not in layout_fields:
                    raise ValueError("projection field key is absent from layout")
        for cardinality in item.catalog_cardinality:
            for member in cardinality["members"]:
                if (
                    member["pdfSha256"] != manual_pdf_sha
                    or member["pageXmlSha256"] != manual_page_xml_sha
                ):
                    raise ValueError("manual catalog member hash differs from source")
    return rows


def _json_value(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in sorted(value.items())}
    if isinstance(value, tuple):
        return [_json_value(item) for item in value]
    return value


def entity_row_to_dict(item: EntityInventoryRow) -> dict[str, Any]:
    result: dict[str, Any] = {
        "schemaVersion": 1,
        "key": item.row.key,
        "inventory": item.row.inventory.value,
        "name": item.row.name,
        "rowKind": item.row_kind.value,
        "entityType": item.entity_type,
        "recordType": item.record_type,
        "stateBearing": item.state_bearing,
        "provenance": item.row.provenance,
        "reachability": item.row.reachability.value,
        "reachabilityEvidence": list(item.reachability_evidence),
        "recoveryDisposition": item.recovery_disposition.value,
        "states": {state.value: item.row.states[state] for state in EvidenceState},
        "idNamespace": {
            "status": item.id_namespace.status.value,
            **_json_value(item.id_namespace.values),
        },
        "relations": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in item.relations.items()
        },
        "lifecycle": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in item.lifecycle.items()
        },
        "wireProjections": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in item.wire_projections.items()
        },
        "clientRepresentation": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in item.client_representation.items()
        },
        "authority": {"status": item.authority.status.value, **_json_value(item.authority.values)},
        "persistence": {"status": item.persistence.status.value, **_json_value(item.persistence.values)},
        "reconnectReplay": {"status": item.reconnect_replay.status.value, **_json_value(item.reconnect_replay.values)},
        "implementationDisposition": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in item.implementation_disposition.items()
        },
        "catalogCardinality": _json_value(item.catalog_cardinality),
        "firstMissingBoundary": item.first_missing_boundary,
        "evidence": list(item.evidence),
        "sourceCandidateIds": list(item.source_candidate_ids),
    }
    result["layout"] = None if item.layout is None else {
        "status": item.layout.status.value,
        **_json_value(item.layout.values),
    }
    return result


def normalize_entity_inventory(rows: Iterable[EntityInventoryRow]) -> list[dict[str, Any]]:
    return [entity_row_to_dict(row) for row in sorted(rows, key=lambda item: item.row.key)]


def build_entity_reconciliation(
    raw: Mapping[str, Any], rows: Iterable[EntityInventoryRow]
) -> dict[str, Any]:
    _validate_export(raw)
    normalized = {candidate_id for row in rows for candidate_id in row.source_candidate_ids}
    records: list[dict[str, Any]] = []
    normalized_count = unresolved_count = excluded_count = 0
    for collection in CANDIDATE_COLLECTIONS:
        for index, candidate_value in enumerate(raw[collection]):
            candidate = _mapping(f"{collection}[{index}]", candidate_value)
            candidate_id = _text("candidateId", candidate.get("candidateId"))
            if candidate_id in normalized:
                status = "NORMALIZED"
                normalized_count += 1
            elif candidate.get("status") == "EXCLUDED":
                _text("exclusionReason", candidate.get("exclusionReason"))
                status = "EXCLUDED"
                excluded_count += 1
            else:
                status = "UNRESOLVED"
                unresolved_count += 1
            records.append(
                {
                    "candidateId": candidate_id,
                    "collection": collection,
                    "index": index,
                    "status": status,
                    "firstMissingBoundary": candidate.get(
                        "firstMissingBoundary", "SEMANTIC_JOIN"
                    ),
                }
            )
    candidate_count = len(records)
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
    args = parser.parse_args(argv)
    evidence_manifest = load_records_evidence_manifest(args.evidence_manifest)
    if args.input.resolve() != evidence_manifest.raw_path:
        raise ValueError("--input differs from the hash-bound records raw path")
    manifest_payload = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    source_manifest = SourceManifest.load(args.source_manifest)
    repository_hash = _mapping(
        "source manifest ghidra", manifest_payload.get("ghidra")
    ).get("repositorySha256")
    repository_sha = _sha256("source manifest repository hash", repository_hash)
    if evidence_manifest.repository_sha256 != repository_sha:
        raise ValueError("records and source manifests bind different Ghidra repositories")
    manual_source = next(
        (
            item
            for item in manifest_payload.get("sources", [])
            if item.get("id") == "official-web-manual-2004-10-07"
        ),
        None,
    )
    if not isinstance(manual_source, Mapping):
        raise ValueError("source manifest lacks the official manual PDF")
    if (
        _sha256("source manifest manual PDF", manual_source.get("sha256"))
        != evidence_manifest.manual_pdf_sha256
        or Path(_text("source manifest manual path", manual_source.get("path"))).resolve()
        != evidence_manifest.manual_pdf_path
    ):
        raise ValueError("records evidence and source manifest bind different manual PDFs")
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    rows = build_entity_inventory(
        raw,
        expected_exporter_sha256=evidence_manifest.exporter_sha256,
        expected_repository_sha256=repository_sha,
        expected_manual_text_sha256=evidence_manifest.manual_text_sha256,
        expected_manual_pdf_sha256=evidence_manifest.manual_pdf_sha256,
        expected_manual_page_xml_sha256=evidence_manifest.manual_page_xml_sha256,
        expected_catalog_candidate_sha256=evidence_manifest.catalog_candidate_sha256,
        expected_protocol_raw_sha256=evidence_manifest.protocol_raw_sha256,
        require_complete_entity_types=True,
    )
    normalized = normalize_entity_inventory(rows)
    reconciliation = build_entity_reconciliation(raw, rows)
    if reconciliation["unaccountedCount"] != 0:
        raise ValueError("entity reconciliation left unaccounted candidates")
    _write_jsonl(args.output, normalized)
    args.reconciliation.parent.mkdir(parents=True, exist_ok=True)
    args.reconciliation.write_text(
        json.dumps(reconciliation, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
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
