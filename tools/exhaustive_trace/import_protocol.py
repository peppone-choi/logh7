"""Normalize hash-bound Ghidra protocol evidence into the closed inventory."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .io import canonical_json
from .io import sha256_file
from .source_manifest import SourceManifest
from .model import (
    EvidenceState,
    ImplementationTarget,
    InventoryKind,
    InventoryRow,
    Reachability,
    RecoveryDisposition,
)
from .source_manifest import CLIENT_SHA256


class StringEnum(str, Enum):
    pass


class ProtocolDirection(StringEnum):
    CLIENT_TO_SERVER = "CLIENT_TO_SERVER"
    SERVER_TO_CLIENT = "SERVER_TO_CLIENT"
    BIDIRECTIONAL = "BIDIRECTIONAL"
    UNKNOWN = "UNKNOWN"


class ProtocolCodeSpace(StringEnum):
    MESSAGE16 = "MESSAGE16"
    MESSAGE32 = "MESSAGE32"


class BodySizeStatus(StringEnum):
    EMPTY = "EMPTY"
    FIXED = "FIXED"
    VARIABLE = "VARIABLE"
    BOUNDED_VARIABLE = "BOUNDED_VARIABLE"
    UNKNOWN = "UNKNOWN"


class BodyMeasurementKind(StringEnum):
    WIRE_BODY = "WIRE_BODY"
    SERIALIZED_LENGTH = "SERIALIZED_LENGTH"
    STRUCT_SIZE = "STRUCT_SIZE"
    ALLOCATION_SIZE = "ALLOCATION_SIZE"
    ARRAY_CAP = "ARRAY_CAP"
    UNKNOWN = "UNKNOWN"


class OwnershipStatus(StringEnum):
    PROVEN = "PROVEN"
    PROVEN_ABSENT = "PROVEN_ABSENT"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


class SiblingDisposition(StringEnum):
    OBSERVED = "OBSERVED"
    ABSENT_IN_EXPORTED_SURFACE = "ABSENT_IN_EXPORTED_SURFACE"
    NOT_APPLICABLE = "NOT_APPLICABLE"
    UNKNOWN = "UNKNOWN"


class ProtocolRelationType(StringEnum):
    SERIALIZES = "SERIALIZES"
    PARSES = "PARSES"
    DISPATCHES = "DISPATCHES"


SEMANTIC_NAME_STATUSES = frozenset({"DIRECT", "CANDIDATE", "UNKNOWN"})
SIBLING_KINDS = ("request", "response", "notify")
SHA256_PATTERN = re.compile(r"^[0-9A-F]{64}$")
EXPECTED_SOURCE = {
    "program": "g7mtclient.exe",
    "language": "x86:LE:32:default",
    "compiler": "windows",
    "imageBase": "00400000",
}
EXPECTED_EXPORT_FIELDS = frozenset(
    {
        "schemaVersion",
        "source",
        "exporter",
        "surfaceSha256",
        "functions",
        "parserCases",
        "parserConditionCodes",
        "dispatcherCases",
        "dispatcherConditionCodes",
        "outboundCases",
        "message32Framework",
        "message32HandlerFamilies",
        "message32HandlerCodes",
        "protocolStrings",
        "streamContracts",
        "functionGraphs",
        "functionInstructions",
        "successMarker",
    }
)


def _text(name: str, value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _mapping(name: str, value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{name} must be an object")
    return value


def _evidence(name: str, value: Any) -> tuple[str, ...]:
    if not isinstance(value, (list, tuple)):
        raise ValueError(f"{name} must contain evidence references")
    result = tuple(value)
    if not result or any(not isinstance(item, str) or not item.strip() for item in result):
        raise ValueError(f"{name} must contain evidence references")
    return result


def normalize_code(value: Any) -> str:
    if isinstance(value, int) and not isinstance(value, bool):
        code = value
    elif isinstance(value, str):
        try:
            code = int(value, 0)
        except ValueError as error:
            raise ValueError(f"invalid protocol code: {value!r}") from error
    else:
        raise ValueError("protocol code must be an integer or numeric string")
    if not 0 <= code <= 0xFFFF:
        raise ValueError(f"protocol code out of range: {code}")
    return f"0x{code:04X}"


def _sha256(name: str, value: Any) -> str:
    digest = _text(name, value).upper()
    if not SHA256_PATTERN.fullmatch(digest):
        raise ValueError(f"{name} must be a SHA-256 digest")
    return digest


def validate_protocol_export(
    raw_value: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
) -> Mapping[str, Any]:
    raw = _mapping("protocol export", raw_value)
    if set(raw) != EXPECTED_EXPORT_FIELDS:
        missing = sorted(EXPECTED_EXPORT_FIELDS - set(raw))
        extra = sorted(set(raw) - EXPECTED_EXPORT_FIELDS)
        raise ValueError(f"protocol export top-level fields mismatch; missing={missing}, extra={extra}")
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported protocol export schemaVersion")
    if raw.get("successMarker") != "EXPORT_EXHAUSTIVE_PROTOCOL_OK":
        raise ValueError("protocol export success marker is missing")
    source = _mapping("protocol export source", raw.get("source"))
    for field, expected in EXPECTED_SOURCE.items():
        actual = _text(f"source.{field}", source.get(field))
        if actual.lower() != expected.lower():
            raise ValueError(f"source.{field} differs from the frozen Ghidra program")
    if _sha256("source.executableSha256", source.get("executableSha256")) != CLIENT_SHA256:
        raise ValueError("protocol export is bound to a different client hash")
    exporter = _mapping("protocol export exporter", raw.get("exporter"))
    if exporter.get("class") != "ExportExhaustiveProtocol":
        raise ValueError("unexpected protocol exporter class")
    exporter_hash = _sha256("exporter.sha256", exporter.get("sha256"))
    repository_hash = _sha256(
        "exporter.ghidraRepositorySha256", exporter.get("ghidraRepositorySha256")
    )
    if expected_exporter_sha256 is not None and exporter_hash != _sha256(
        "expected exporter hash", expected_exporter_sha256
    ):
        raise ValueError("protocol exporter hash mismatch")
    if expected_repository_sha256 is not None and repository_hash != _sha256(
        "expected repository hash", expected_repository_sha256
    ):
        raise ValueError("Ghidra repository hash mismatch")
    _sha256("surfaceSha256", raw.get("surfaceSha256"))
    return raw


@dataclass(frozen=True)
class BodySizeEvidence:
    status: BodySizeStatus
    measurement_kind: BodyMeasurementKind
    bytes: int | None
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class DirectionEvidence:
    status: ProtocolDirection
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class ProtocolSibling:
    disposition: SiblingDisposition
    codes: tuple[str, ...]
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class ProtocolRelation:
    type: ProtocolRelationType
    function: str
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class ProtocolFact:
    kind: str
    value: str
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class ProtocolOwnership:
    status: OwnershipStatus
    functions: tuple[str, ...]
    evidence: tuple[str, ...]


@dataclass(frozen=True)
class ProtocolInventoryRow:
    row: InventoryRow
    code_space: ProtocolCodeSpace
    code: str
    semantic_name_status: str
    direction: DirectionEvidence
    body_size: BodySizeEvidence
    siblings: Mapping[str, ProtocolSibling]
    relations: tuple[ProtocolRelation, ...]
    ownership: Mapping[str, ProtocolOwnership]
    facts: tuple[ProtocolFact, ...]
    evidence: tuple[str, ...]
    recovery_disposition: RecoveryDisposition

    def __post_init__(self) -> None:
        if set(self.siblings) != set(SIBLING_KINDS):
            raise ValueError("siblings must contain request, response, and notify")
        if set(self.ownership) != {"parser", "serializer", "dispatcher"}:
            raise ValueError("ownership must contain parser, serializer, and dispatcher")
        object.__setattr__(self, "siblings", MappingProxyType(dict(self.siblings)))
        object.__setattr__(self, "ownership", MappingProxyType(dict(self.ownership)))


@dataclass(frozen=True)
class ProtocolEvidenceManifest:
    path: Path
    raw_path: Path
    raw_sha256: str
    exporter_path: Path
    exporter_sha256: str
    repository_sha256: str


def load_protocol_evidence_manifest(path: str | Path) -> ProtocolEvidenceManifest:
    manifest_path = Path(path).resolve()
    payload = _mapping(
        "protocol evidence manifest",
        json.loads(manifest_path.read_text(encoding="utf-8")),
    )
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported protocol evidence manifest schemaVersion")
    if _sha256("clientSha256", payload.get("clientSha256")) != CLIENT_SHA256:
        raise ValueError("protocol evidence manifest is bound to a different client")
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
    return ProtocolEvidenceManifest(
        manifest_path,
        raw_path,
        raw_sha,
        exporter_path,
        exporter_sha,
        repository_sha,
    )


def _normalize_body_size(value: Any) -> BodySizeEvidence:
    raw = _mapping("bodySize", value)
    try:
        status = BodySizeStatus(_text("bodySize.status", raw.get("status")))
    except ValueError as error:
        raise ValueError(f"bodySize.status is invalid: {raw.get('status')!r}") from error
    try:
        measurement_kind = BodyMeasurementKind(
            _text("bodySize.measurementKind", raw.get("measurementKind"))
        )
    except ValueError as error:
        raise ValueError("bodySize.measurementKind is invalid") from error
    byte_count = raw.get("bytes")
    if status is BodySizeStatus.UNKNOWN:
        if byte_count is not None or measurement_kind is not BodyMeasurementKind.UNKNOWN:
            raise ValueError("UNKNOWN body size cannot assert bytes or a measurement kind")
    elif status is BodySizeStatus.EMPTY:
        if byte_count != 0 or measurement_kind is not BodyMeasurementKind.WIRE_BODY:
            raise ValueError("EMPTY body size requires zero WIRE_BODY bytes")
    elif status is BodySizeStatus.FIXED:
        if not isinstance(byte_count, int) or isinstance(byte_count, bool) or byte_count < 0:
            raise ValueError("FIXED bodySize.bytes must be a non-negative integer")
        if measurement_kind not in {
            BodyMeasurementKind.WIRE_BODY,
            BodyMeasurementKind.SERIALIZED_LENGTH,
        }:
            raise ValueError("FIXED body size requires wire or serialized-length evidence")
    elif byte_count is not None:
        raise ValueError("variable body size must not assert one exact byte count")
    return BodySizeEvidence(
        status,
        measurement_kind,
        byte_count,
        _evidence("bodySize.evidence", raw.get("evidence")),
    )


def _normalize_direction(value: Any) -> DirectionEvidence:
    raw = _mapping("direction", value)
    try:
        status = ProtocolDirection(_text("direction.status", raw.get("status")))
    except ValueError as error:
        raise ValueError(f"direction is invalid: {raw.get('status')!r}") from error
    return DirectionEvidence(status, _evidence("direction.evidence", raw.get("evidence")))


def _normalize_ownership(value: Any) -> Mapping[str, ProtocolOwnership]:
    raw = _mapping("ownership", value)
    expected = {"parser", "serializer", "dispatcher"}
    if set(raw) != expected:
        raise ValueError("ownership must contain exactly parser, serializer, and dispatcher")
    result: dict[str, ProtocolOwnership] = {}
    for kind in sorted(expected):
        item = _mapping(f"ownership.{kind}", raw[kind])
        try:
            status = OwnershipStatus(_text(f"ownership.{kind}.status", item.get("status")))
        except ValueError as error:
            raise ValueError(f"ownership.{kind}.status is invalid") from error
        raw_functions = item.get("functions")
        if not isinstance(raw_functions, (list, tuple)):
            raise ValueError(f"ownership.{kind}.functions must be a list")
        functions = tuple(_text(f"ownership.{kind}.function", item) for item in raw_functions)
        if len(functions) != len(set(functions)):
            raise ValueError(f"ownership.{kind}.functions must be unique")
        if status is OwnershipStatus.PROVEN and not functions:
            raise ValueError(f"PROVEN ownership.{kind} needs functions")
        if status is not OwnershipStatus.PROVEN and functions:
            raise ValueError(f"non-PROVEN ownership.{kind} cannot list functions")
        result[kind] = ProtocolOwnership(
            status,
            functions,
            _evidence(f"ownership.{kind}.evidence", item.get("evidence")),
        )
    return result


def _normalize_siblings(value: Any) -> Mapping[str, ProtocolSibling]:
    raw = _mapping("siblings", value)
    if set(raw) != set(SIBLING_KINDS):
        raise ValueError("siblings must contain exactly request, response, and notify")
    result: dict[str, ProtocolSibling] = {}
    for kind in SIBLING_KINDS:
        item = _mapping(f"siblings.{kind}", raw[kind])
        try:
            disposition = SiblingDisposition(
                _text(f"siblings.{kind}.disposition", item.get("disposition"))
            )
        except ValueError as error:
            raise ValueError(f"invalid siblings.{kind}.disposition") from error
        raw_codes = item.get("codes")
        if not isinstance(raw_codes, (list, tuple)):
            raise ValueError(f"siblings.{kind}.codes must be a list")
        codes = tuple(normalize_code(code) for code in raw_codes)
        if len(codes) != len(set(codes)):
            raise ValueError(f"siblings.{kind}.codes must be unique")
        if disposition is SiblingDisposition.OBSERVED and not codes:
            raise ValueError(f"observed sibling {kind} needs at least one code")
        if disposition is not SiblingDisposition.OBSERVED and codes:
            raise ValueError(f"non-observed sibling {kind} cannot list codes")
        result[kind] = ProtocolSibling(
            disposition, codes, _evidence(f"siblings.{kind}.evidence", item.get("evidence"))
        )
    return result


def _normalize_relations(value: Any) -> tuple[ProtocolRelation, ...]:
    if not isinstance(value, (list, tuple)):
        raise ValueError("relations must be a list")
    result: list[ProtocolRelation] = []
    for index, raw_value in enumerate(value):
        raw = _mapping(f"relations[{index}]", raw_value)
        try:
            relation_type = ProtocolRelationType(_text("relation type", raw.get("type")))
        except ValueError as error:
            raise ValueError(f"unsupported relation type: {raw.get('type')!r}") from error
        result.append(
            ProtocolRelation(
                relation_type,
                _text(f"relations[{index}].function", raw.get("function")),
                _evidence(f"relations[{index}].evidence", raw.get("evidence")),
            )
        )
    return tuple(result)


def _normalize_facts(value: Any) -> tuple[ProtocolFact, ...]:
    if value is None:
        return ()
    if not isinstance(value, (list, tuple)):
        raise ValueError("facts must be a list")
    return tuple(
        ProtocolFact(
            _text(f"facts[{index}].kind", _mapping("fact", item).get("kind")),
            _text(f"facts[{index}].value", _mapping("fact", item).get("value")),
            _evidence(f"facts[{index}].evidence", _mapping("fact", item).get("evidence")),
        )
        for index, item in enumerate(value)
    )


def normalize_protocol_row(raw_value: Mapping[str, Any]) -> ProtocolInventoryRow:
    raw = _mapping("protocol row", raw_value)
    try:
        code_space = ProtocolCodeSpace(_text("codeSpace", raw.get("codeSpace")))
    except ValueError as error:
        raise ValueError(f"codeSpace is invalid: {raw.get('codeSpace')!r}") from error
    code = normalize_code(raw.get("code"))
    semantic_name_status = _text("semanticNameStatus", raw.get("semanticNameStatus"))
    if semantic_name_status not in SEMANTIC_NAME_STATUSES:
        raise ValueError(f"unsupported semanticNameStatus: {semantic_name_status}")
    name_value = raw.get("name")
    if semantic_name_status == "UNKNOWN":
        if name_value not in (None, ""):
            raise ValueError("UNKNOWN semantic name must not contain an asserted name")
        name = f"UnknownProtocol_{code}"
    else:
        name = _text("name", name_value)
    try:
        reachability = Reachability(_text("reachability", raw.get("reachability")))
    except ValueError as error:
        raise ValueError("reachability is invalid") from error
    try:
        recovery = RecoveryDisposition(
            _text("recoveryDisposition", raw.get("recoveryDisposition"))
        )
    except ValueError as error:
        raise ValueError("recoveryDisposition is invalid") from error
    evidence = _evidence("evidence", raw.get("evidence"))
    row = InventoryRow(
        key=f"PROTOCOL:{code_space.value}:{code}",
        inventory=InventoryKind.PROTOCOL,
        name=name,
        provenance=_text("provenance", raw.get("provenance")),
        reachability=reachability,
        states={EvidenceState.ENUMERATED: True},
    )
    relations = _normalize_relations(raw.get("relations"))
    ownership = _normalize_ownership(raw.get("ownership"))
    relation_functions = {
        "parser": {relation.function for relation in relations if relation.type is ProtocolRelationType.PARSES},
        "serializer": {
            relation.function for relation in relations if relation.type is ProtocolRelationType.SERIALIZES
        },
        "dispatcher": {
            relation.function for relation in relations if relation.type is ProtocolRelationType.DISPATCHES
        },
    }
    for kind, functions in relation_functions.items():
        owner = ownership[kind]
        if owner.status is OwnershipStatus.PROVEN and set(owner.functions) != functions:
            raise ValueError(f"ownership.{kind} does not match typed relations")
        if owner.status is not OwnershipStatus.PROVEN and functions:
            raise ValueError(f"typed relations require PROVEN ownership.{kind}")
    return ProtocolInventoryRow(
        row=row,
        code_space=code_space,
        code=code,
        semantic_name_status=semantic_name_status,
        direction=_normalize_direction(raw.get("direction")),
        body_size=_normalize_body_size(raw.get("bodySize")),
        siblings=_normalize_siblings(raw.get("siblings")),
        relations=relations,
        ownership=ownership,
        facts=_normalize_facts(raw.get("facts")),
        evidence=evidence,
        recovery_disposition=recovery,
    )


def normalize_protocol_inventory(
    rows: Sequence[Mapping[str, Any]],
) -> tuple[ProtocolInventoryRow, ...]:
    normalized = [normalize_protocol_row(row) for row in rows]
    codes = [(row.code_space, row.code) for row in normalized]
    if len(codes) != len(set(codes)):
        raise ValueError("duplicate protocol code")
    return tuple(
        sorted(normalized, key=lambda row: (row.code_space.value, int(row.code, 16)))
    )


def _add_relation(state: dict[str, Any], relation_type: str, function: str, evidence: str) -> None:
    relation = {"type": relation_type, "function": function, "evidence": [evidence]}
    if relation not in state["relations"]:
        state["relations"].append(relation)


def _add_fact(state: dict[str, Any], kind: str, value: Any, evidence: str) -> None:
    text_value = str(value)
    fact = {"kind": kind, "value": text_value, "evidence": [evidence]}
    if fact not in state["facts"]:
        state["facts"].append(fact)


def build_protocol_inventory(
    raw_value: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
) -> tuple[ProtocolInventoryRow, ...]:
    raw = validate_protocol_export(
        raw_value,
        expected_exporter_sha256=expected_exporter_sha256,
        expected_repository_sha256=expected_repository_sha256,
    )
    functions = _mapping("functions", raw.get("functions"))
    parser_function = _text("functions.parser", functions.get("parser"))
    dispatcher_function = _text("functions.dispatcher", functions.get("dispatcher"))
    outbound_function = _text("functions.outbound", functions.get("outbound"))
    surface_ref = f"raw:surface:{_text('surfaceSha256', raw.get('surfaceSha256'))}"

    states: dict[str, dict[str, Any]] = {}

    def state_for(code_value: Any) -> dict[str, Any]:
        code = normalize_code(code_value)
        return states.setdefault(
            code,
            {
                "codeSpace": ProtocolCodeSpace.MESSAGE16.value,
                "code": code,
                "name": None,
                "semanticNameStatus": "UNKNOWN",
                "nameCandidates": set(),
                "recoveryDisposition": RecoveryDisposition.RECOVERABLE_STATIC.value,
                "directionSignals": set(),
                "directionEvidence": set(),
                "bodySize": None,
                "requestSiblings": set(),
                "responseSiblings": set(),
                "relations": [],
                "facts": [],
                "evidence": set(),
            },
        )

    parser_cases = raw.get("parserCases")
    if not isinstance(parser_cases, list):
        raise ValueError("parserCases must be a list")
    for index, raw_case in enumerate(parser_cases):
        case = _mapping(f"parserCases[{index}]", raw_case)
        codes = case.get("codes")
        if not isinstance(codes, list) or not codes:
            raise ValueError(f"parserCases[{index}].codes must not be empty")
        allocation = _mapping("allocationSize", case.get("allocationSize"))
        allocation_status = _text("allocationSize.status", allocation.get("status"))
        if allocation_status not in {"FIXED", "SHARED_CASE", "DYNAMIC", "UNKNOWN"}:
            raise ValueError(f"unsupported parser allocation status: {allocation_status}")
        allocation_bytes = allocation.get("bytes")
        evidence = f"raw:parserCases:{index}"
        for code_value in codes:
            state = state_for(code_value)
            state["directionSignals"].add(ProtocolDirection.SERVER_TO_CLIENT.value)
            state["directionEvidence"].add(evidence)
            state["evidence"].add(evidence)
            _add_relation(state, ProtocolRelationType.PARSES.value, parser_function, evidence)
            state["bodySize"] = {
                "status": BodySizeStatus.UNKNOWN.value,
                "measurementKind": BodyMeasurementKind.UNKNOWN.value,
                "bytes": None,
                "evidence": [evidence],
            }
            if allocation_bytes is not None:
                _add_fact(state, "PARSER_ALLOCATION_SIZE", allocation_bytes, evidence)
            _add_fact(state, "PARSER_ALLOCATION_STATUS", allocation_status, evidence)
            for helper in case.get("helperCalls", []):
                _add_fact(state, "PARSER_HELPER", helper, evidence)
            if case.get("messageName"):
                state["nameCandidates"].add(str(case["messageName"]))

    parser_conditions = raw.get("parserConditionCodes")
    if not isinstance(parser_conditions, list):
        raise ValueError("parserConditionCodes must be a list")
    for index, raw_condition in enumerate(parser_conditions):
        condition = _mapping(f"parserConditionCodes[{index}]", raw_condition)
        evidence = f"raw:parserConditionCodes:{index}"
        state = state_for(condition.get("code"))
        state["directionSignals"].add(ProtocolDirection.SERVER_TO_CLIENT.value)
        state["directionEvidence"].add(evidence)
        state["evidence"].add(evidence)
        _add_relation(state, ProtocolRelationType.PARSES.value, parser_function, evidence)
        _add_fact(state, "PARSER_CONDITION", condition.get("condition"), evidence)
        _add_fact(state, "PARSER_CONDITION_STATUS", condition.get("status"), evidence)

    dispatcher_cases = raw.get("dispatcherCases")
    if not isinstance(dispatcher_cases, list):
        raise ValueError("dispatcherCases must be a list")
    for index, raw_case in enumerate(dispatcher_cases):
        case = _mapping(f"dispatcherCases[{index}]", raw_case)
        codes = case.get("codes")
        if not isinstance(codes, list) or not codes:
            raise ValueError(f"dispatcherCases[{index}].codes must not be empty")
        evidence = f"raw:dispatcherCases:{index}"
        for code_value in codes:
            state = state_for(code_value)
            state["directionSignals"].add(ProtocolDirection.SERVER_TO_CLIENT.value)
            state["directionEvidence"].add(evidence)
            state["evidence"].add(evidence)
            _add_relation(state, ProtocolRelationType.DISPATCHES.value, dispatcher_function, evidence)
            if case.get("messageName"):
                state["nameCandidates"].add(str(case["messageName"]))
            for destination in case.get("destinationExpressions", []):
                _add_fact(state, "DESTINATION_EXPRESSION", destination, evidence)
            for helper in case.get("helperCalls", []):
                _add_fact(state, "DISPATCH_HELPER", helper, evidence)

    dispatcher_conditions = raw.get("dispatcherConditionCodes")
    if not isinstance(dispatcher_conditions, list):
        raise ValueError("dispatcherConditionCodes must be a list")
    for index, raw_condition in enumerate(dispatcher_conditions):
        condition = _mapping(f"dispatcherConditionCodes[{index}]", raw_condition)
        evidence = f"raw:dispatcherConditionCodes:{index}"
        state = state_for(condition.get("code"))
        state["directionSignals"].add(ProtocolDirection.SERVER_TO_CLIENT.value)
        state["directionEvidence"].add(evidence)
        state["evidence"].add(evidence)
        _add_relation(state, ProtocolRelationType.DISPATCHES.value, dispatcher_function, evidence)
        _add_fact(state, "DISPATCHER_CONDITION", condition.get("condition"), evidence)
        _add_fact(state, "DISPATCHER_CONDITION_STATUS", condition.get("status"), evidence)

    outbound_cases = raw.get("outboundCases")
    if not isinstance(outbound_cases, list):
        raise ValueError("outboundCases must be a list")
    for index, raw_case in enumerate(outbound_cases):
        case = _mapping(f"outboundCases[{index}]", raw_case)
        evidence = f"raw:outboundCases:{index}"
        if case.get("requestCode") is None:
            continue
        request = state_for(case.get("requestCode"))
        request_code = request["code"]
        request["directionSignals"].add(ProtocolDirection.CLIENT_TO_SERVER.value)
        request["directionEvidence"].add(evidence)
        request["requestSiblings"].add(request_code)
        request["evidence"].add(evidence)
        _add_fact(request, "OUTBOUND_BINDER", outbound_function, evidence)
        _add_fact(request, "OUTBOUND_LOCAL_KIND", case.get("localKind"), evidence)
        if request["bodySize"] is None:
            request["bodySize"] = {
                "status": BodySizeStatus.UNKNOWN.value,
                "measurementKind": BodyMeasurementKind.UNKNOWN.value,
                "bytes": None,
                "evidence": [evidence],
            }
        response_value = case.get("expectedResponseCode")
        if response_value is not None:
            response = state_for(response_value)
            response_code = response["code"]
            request["responseSiblings"].add(response_code)
            response["requestSiblings"].add(request_code)
            response["responseSiblings"].add(response_code)
            response["directionSignals"].add(ProtocolDirection.SERVER_TO_CLIENT.value)
            response["directionEvidence"].add(evidence)
            response["evidence"].add(evidence)

    for state in states.values():
        candidates = sorted(state["nameCandidates"])
        if len(candidates) == 1:
            state["name"] = candidates[0]
            state["semanticNameStatus"] = "DIRECT"
        elif len(candidates) > 1:
            state["name"] = " | ".join(candidates)
            state["semanticNameStatus"] = "CANDIDATE"
            state["recoveryDisposition"] = RecoveryDisposition.SOURCE_CONFLICT.value
            _add_fact(
                state,
                "SEMANTIC_NAME_CONFLICT",
                " | ".join(candidates),
                surface_ref,
            )

    states_by_direct_name: dict[str, list[dict[str, Any]]] = {}
    for state in states.values():
        if state["semanticNameStatus"] == "DIRECT" and state["name"]:
            states_by_direct_name.setdefault(state["name"], []).append(state)

    protocol_strings = raw.get("protocolStrings")
    if not isinstance(protocol_strings, list):
        raise ValueError("protocolStrings must be a list")
    for index, raw_string in enumerate(protocol_strings):
        item = _mapping(f"protocolStrings[{index}]", raw_string)
        value = _text(f"protocolStrings[{index}].value", item.get("value"))
        lookup = value[:-3] if value.endswith(" OK") else value
        evidence = f"raw:protocolStrings:{index}"
        for state in states_by_direct_name.get(lookup, []):
            state["evidence"].add(evidence)
            _add_fact(state, "PROTOCOL_STRING", value, evidence)
            if item.get("address") is not None:
                _add_fact(state, "PROTOCOL_STRING_ADDRESS", item.get("address"), evidence)

    stream_contracts = raw.get("streamContracts")
    if not isinstance(stream_contracts, list):
        raise ValueError("streamContracts must be a list")
    for index, raw_contract in enumerate(stream_contracts):
        item = _mapping(f"streamContracts[{index}]", raw_contract)
        message = _text(f"streamContracts[{index}].message", item.get("message"))
        method = _text(f"streamContracts[{index}].method", item.get("method"))
        evidence = f"raw:streamContracts:{index}"
        cap = item.get("maxExpression", item.get("maxCountOrBytes"))
        fact_value = "|".join(
            (
                method,
                _text(f"streamContracts[{index}].field", item.get("field")),
                str(cap),
                _text(
                    f"streamContracts[{index}].measurementKind",
                    item.get("measurementKind"),
                ),
            )
        )
        xrefs = item.get("xrefs")
        if not isinstance(xrefs, list):
            raise ValueError(f"streamContracts[{index}].xrefs must be a list")
        for state in states_by_direct_name.get(message, []):
            state["evidence"].add(evidence)
            _add_fact(state, "STREAM_ARRAY_CAP", fact_value, evidence)
            for xref_index, raw_xref in enumerate(xrefs):
                xref = _mapping(f"streamContracts[{index}].xrefs[{xref_index}]", raw_xref)
                if xref.get("from") is not None:
                    _add_fact(
                        state,
                        "STREAM_XREF_CANDIDATE",
                        f"{method}|{xref.get('from')}|{xref.get('function')}|{xref.get('nearestPriorFunction')}",
                        evidence,
                    )

    result_rows: list[dict[str, Any]] = []
    for code, state in states.items():
        signals = state["directionSignals"]
        if signals == {ProtocolDirection.CLIENT_TO_SERVER.value}:
            direction = ProtocolDirection.CLIENT_TO_SERVER.value
        elif signals == {ProtocolDirection.SERVER_TO_CLIENT.value}:
            direction = ProtocolDirection.SERVER_TO_CLIENT.value
        elif len(signals) > 1:
            direction = ProtocolDirection.BIDIRECTIONAL.value
        else:
            direction = ProtocolDirection.UNKNOWN.value
        body_size = state["bodySize"] or {
            "status": BodySizeStatus.UNKNOWN.value,
            "measurementKind": BodyMeasurementKind.UNKNOWN.value,
            "bytes": None,
            "evidence": [surface_ref],
        }

        def sibling(kind: str, codes: set[str]) -> dict[str, Any]:
            if codes:
                return {
                    "disposition": SiblingDisposition.OBSERVED.value,
                    "codes": sorted(codes, key=lambda value: int(value, 16)),
                    "evidence": sorted(state["evidence"]),
                }
            return {
                "disposition": (
                    SiblingDisposition.UNKNOWN.value
                    if state["semanticNameStatus"] == "UNKNOWN"
                    else SiblingDisposition.ABSENT_IN_EXPORTED_SURFACE.value
                ),
                "codes": [],
                "evidence": [surface_ref],
            }

        owner_functions = {
            "parser": sorted(
                {
                    relation["function"]
                    for relation in state["relations"]
                    if relation["type"] == ProtocolRelationType.PARSES.value
                }
            ),
            "serializer": sorted(
                {
                    relation["function"]
                    for relation in state["relations"]
                    if relation["type"] == ProtocolRelationType.SERIALIZES.value
                }
            ),
            "dispatcher": sorted(
                {
                    relation["function"]
                    for relation in state["relations"]
                    if relation["type"] == ProtocolRelationType.DISPATCHES.value
                }
            ),
        }
        ownership = {
            kind: {
                "status": OwnershipStatus.PROVEN.value if functions else OwnershipStatus.UNKNOWN.value,
                "functions": functions,
                "evidence": sorted(state["evidence"]) if functions else [surface_ref],
            }
            for kind, functions in owner_functions.items()
        }
        result_rows.append(
            {
                "codeSpace": ProtocolCodeSpace.MESSAGE16.value,
                "code": code,
                "name": state["name"],
                "semanticNameStatus": state["semanticNameStatus"],
                "direction": {
                    "status": direction,
                    "evidence": sorted(state["directionEvidence"]) or [surface_ref],
                },
                "bodySize": body_size,
                "siblings": {
                    "request": sibling("request", state["requestSiblings"]),
                    "response": sibling("response", state["responseSiblings"]),
                    "notify": {
                        "disposition": SiblingDisposition.UNKNOWN.value,
                        "codes": [],
                        "evidence": [surface_ref],
                    },
                },
                "relations": state["relations"],
                "ownership": ownership,
                "facts": state["facts"],
                "evidence": sorted(state["evidence"]),
                "provenance": "ORIGINAL_OBSERVED",
                "reachability": Reachability.UNKNOWN.value,
                "recoveryDisposition": state["recoveryDisposition"],
            }
        )
    framework = _mapping("message32Framework", raw.get("message32Framework"))
    if framework.get("status") != "REGISTERED_AT_SHIPPED_STARTUP_PATH":
        raise ValueError("Message32 framework startup registration is not proven")
    if framework.get("messageTypeWidthBits") != 16:
        raise ValueError("Message32 container message type must remain uint16")
    message32_send = _text("message32Framework.sendFunction", framework.get("sendFunction"))
    message32_parse = _text("message32Framework.parseFunction", framework.get("parseFunction"))
    families = raw.get("message32HandlerFamilies")
    if not isinstance(families, list):
        raise ValueError("message32HandlerFamilies must be a list")
    handler_codes = raw.get("message32HandlerCodes")
    if not isinstance(handler_codes, list):
        raise ValueError("message32HandlerCodes must be a list")
    registration_callsites: set[str] = set()
    registry_callsites: set[str] = set()
    for family_index, raw_family in enumerate(families):
        family = _mapping(f"message32HandlerFamilies[{family_index}]", raw_family)
        registration_callsite = _text(
            "Message32 startup registration evidence", family.get("registrationCallsite")
        )
        registry_callsite = _text(
            "Message32 startup registration evidence", family.get("registryCallsite")
        )
        if registration_callsite in registration_callsites or registry_callsite in registry_callsites:
            raise ValueError("Message32 startup registration evidence must be unique")
        registration_callsites.add(registration_callsite)
        registry_callsites.add(registry_callsite)
    for index, raw_code in enumerate(handler_codes):
        item = _mapping(f"message32HandlerCodes[{index}]", raw_code)
        family_index = item.get("familyIndex")
        if not isinstance(family_index, int) or isinstance(family_index, bool) or not 0 <= family_index < len(families):
            raise ValueError(f"message32HandlerCodes[{index}] has invalid familyIndex")
        family = _mapping(f"message32HandlerFamilies[{family_index}]", families[family_index])
        base = int(normalize_code(family.get("baseCode")), 16)
        count = family.get("count")
        offset = item.get("offset")
        if not isinstance(count, int) or count <= 0:
            raise ValueError("Message32 family count must be positive")
        if not isinstance(offset, int) or isinstance(offset, bool) or not 0 <= offset < count:
            raise ValueError("Message32 handler offset is outside its family")
        code = normalize_code(item.get("code"))
        if int(code, 16) != base + offset:
            raise ValueError("Message32 handler code differs from family base plus offset")
        client_to_server = item.get("clientToServerRegistered")
        server_to_client = item.get("serverToClientRegistered")
        if not isinstance(client_to_server, bool) or not isinstance(server_to_client, bool):
            raise ValueError("Message32 direction registration flags must be boolean")
        for field in ("factory", "constructor", "vtable"):
            if _text(f"message32HandlerCodes[{index}].{field}", item.get(field)) != _text(
                f"message32HandlerFamilies[{family_index}].{field}", family.get(field)
            ):
                raise ValueError("Message32 registration chain differs from its family")
        direction_slots = (
            (
                "clientToServer",
                client_to_server,
                offset + 1,
            ),
            (
                "serverToClient",
                server_to_client,
                count + offset + 1,
            ),
        )
        for prefix, registered, expected_slot in direction_slots:
            assignment = item.get(f"{prefix}Assignment")
            slot = item.get(f"{prefix}Slot")
            if registered:
                _text(f"{prefix} assignment evidence", assignment)
                if slot != expected_slot:
                    raise ValueError(f"Message32 {prefix} slot differs from family offset")
            elif assignment is not None or slot is not None:
                raise ValueError(f"unregistered Message32 {prefix} cannot assert assignment evidence")
        expected_direction = (
            ProtocolDirection.BIDIRECTIONAL
            if client_to_server and server_to_client
            else ProtocolDirection.CLIENT_TO_SERVER
            if client_to_server
            else ProtocolDirection.SERVER_TO_CLIENT
            if server_to_client
            else ProtocolDirection.UNKNOWN
        )
        if ProtocolDirection(_text("message32 direction", item.get("direction"))) is not expected_direction:
            raise ValueError("Message32 direction differs from registration flags")
        evidence = f"raw:message32HandlerCodes:{index}"
        family_evidence = f"raw:message32HandlerFamilies:{family_index}"
        relations: list[dict[str, Any]] = []
        if client_to_server:
            relations.append(
                {
                    "type": ProtocolRelationType.SERIALIZES.value,
                    "function": message32_send,
                    "evidence": [evidence, family_evidence],
                }
            )
        if server_to_client:
            relations.extend(
                [
                    {
                        "type": ProtocolRelationType.PARSES.value,
                        "function": message32_parse,
                        "evidence": [evidence, family_evidence],
                    },
                    {
                        "type": ProtocolRelationType.DISPATCHES.value,
                        "function": message32_parse,
                        "evidence": [evidence, family_evidence],
                    },
                ]
            )
        functions = {
            "serializer": [message32_send] if client_to_server else [],
            "parser": [message32_parse] if server_to_client else [],
            "dispatcher": [message32_parse] if server_to_client else [],
        }
        result_rows.append(
            {
                "codeSpace": ProtocolCodeSpace.MESSAGE32.value,
                "code": code,
                "name": None,
                "semanticNameStatus": "UNKNOWN",
                "direction": {"status": expected_direction.value, "evidence": [evidence]},
                "bodySize": {
                    "status": BodySizeStatus.UNKNOWN.value,
                    "measurementKind": BodyMeasurementKind.UNKNOWN.value,
                    "bytes": None,
                    "evidence": [family_evidence],
                },
                "siblings": {
                    kind: {
                        "disposition": SiblingDisposition.UNKNOWN.value,
                        "codes": [],
                        "evidence": [evidence],
                    }
                    for kind in SIBLING_KINDS
                },
                "relations": relations,
                "ownership": {
                    kind: {
                        "status": OwnershipStatus.PROVEN.value if owned else OwnershipStatus.UNKNOWN.value,
                        "functions": owned,
                        "evidence": [evidence, family_evidence],
                    }
                    for kind, owned in functions.items()
                },
                "facts": [
                    {"kind": "MESSAGE32_FAMILY_BASE", "value": normalize_code(family.get("baseCode")), "evidence": [family_evidence]},
                    {"kind": "HANDLER_FACTORY", "value": _text("factory", item.get("factory")), "evidence": [evidence]},
                    {"kind": "HANDLER_CONSTRUCTOR", "value": _text("constructor", item.get("constructor")), "evidence": [evidence]},
                    {"kind": "HANDLER_VTABLE", "value": _text("vtable", item.get("vtable")), "evidence": [evidence]},
                ],
                "evidence": [evidence, family_evidence, "raw:message32Framework"],
                "provenance": "ORIGINAL_OBSERVED",
                "reachability": Reachability.UNKNOWN.value,
                "recoveryDisposition": RecoveryDisposition.RECOVERABLE_STATIC.value,
            }
        )
    return normalize_protocol_inventory(result_rows)


def build_protocol_reconciliation(
    raw_value: Mapping[str, Any], rows: Sequence[ProtocolInventoryRow]
) -> dict[str, Any]:
    """Account for every exported protocol candidate without semantic guessing."""

    raw = validate_protocol_export(raw_value)
    by_code = {(row.code_space, row.code): row for row in rows}
    by_direct_name: dict[str, list[str]] = {}
    for row in rows:
        if row.semantic_name_status == "DIRECT":
            by_direct_name.setdefault(row.row.name, []).append(row.row.key)

    candidates: list[dict[str, Any]] = []

    def add_code_collection(
        collection: str,
        field: str = "codes",
        code_space: ProtocolCodeSpace = ProtocolCodeSpace.MESSAGE16,
    ) -> None:
        values = raw.get(collection)
        if not isinstance(values, list):
            raise ValueError(f"{collection} must be a list")
        for index, raw_item in enumerate(values):
            item = _mapping(f"{collection}[{index}]", raw_item)
            raw_codes = item.get(field)
            codes = raw_codes if isinstance(raw_codes, list) else [raw_codes]
            for code_index, raw_code in enumerate(codes):
                if raw_code is None:
                    continue
                code = normalize_code(raw_code)
                row = by_code.get((code_space, code))
                key = row.row.key if row is not None else None
                candidates.append(
                    {
                        "candidateId": f"{collection}:{index}:{code_index}",
                        "kind": "OPCODE",
                        "source": collection,
                        "codeSpace": code_space.value,
                        "value": code,
                        "status": "NORMALIZED" if key else "UNRESOLVED",
                        "inventoryKeys": [key] if key else [],
                    }
                )

    add_code_collection("parserCases")
    add_code_collection("parserConditionCodes", "code")
    add_code_collection("dispatcherCases")
    add_code_collection("dispatcherConditionCodes", "code")
    add_code_collection("message32HandlerCodes", "code", ProtocolCodeSpace.MESSAGE32)
    outbound = raw.get("outboundCases")
    if not isinstance(outbound, list):
        raise ValueError("outboundCases must be a list")
    for index, raw_item in enumerate(outbound):
        item = _mapping(f"outboundCases[{index}]", raw_item)
        for field in ("requestCode", "expectedResponseCode"):
            value = item.get(field)
            if value is None:
                candidates.append(
                    {
                        "candidateId": f"outboundCases:{index}:{field}",
                        "kind": "OPCODE",
                        "source": "outboundCases",
                        "value": None,
                        "status": "UNRESOLVED",
                        "inventoryKeys": [],
                    }
                )
                continue
            code = normalize_code(value)
            row = by_code.get((ProtocolCodeSpace.MESSAGE16, code))
            key = row.row.key if row is not None else None
            candidates.append(
                {
                    "candidateId": f"outboundCases:{index}:{field}",
                    "kind": "OPCODE",
                    "source": "outboundCases",
                    "codeSpace": ProtocolCodeSpace.MESSAGE16.value,
                    "value": code,
                    "status": "NORMALIZED" if key else "UNRESOLVED",
                    "inventoryKeys": [key] if key else [],
                }
            )

    for collection, kind, value_field in (
        ("protocolStrings", "PROTOCOL_STRING", "value"),
        ("streamContracts", "STREAM_CONTRACT", "message"),
    ):
        values = raw.get(collection)
        if not isinstance(values, list):
            raise ValueError(f"{collection} must be a list")
        for index, raw_item in enumerate(values):
            item = _mapping(f"{collection}[{index}]", raw_item)
            value = _text(f"{collection}[{index}].{value_field}", item.get(value_field))
            lookup = value[:-3] if collection == "protocolStrings" and value.endswith(" OK") else value
            keys = sorted(by_direct_name.get(lookup, []))
            candidates.append(
                {
                    "candidateId": f"{collection}:{index}",
                    "kind": kind,
                    "source": collection,
                    "value": value,
                    "status": "NORMALIZED" if keys else "UNRESOLVED",
                    "inventoryKeys": keys,
                }
            )

    candidates.sort(key=lambda item: item["candidateId"])
    return {
        "schemaVersion": 1,
        "surfaceSha256": _sha256("surfaceSha256", raw.get("surfaceSha256")),
        "candidateCount": len(candidates),
        "accountedCount": len(candidates),
        "unaccountedCount": 0,
        "normalizedCount": sum(item["status"] == "NORMALIZED" for item in candidates),
        "unresolvedCount": sum(item["status"] == "UNRESOLVED" for item in candidates),
        "candidates": candidates,
    }


def protocol_row_to_dict(value: ProtocolInventoryRow) -> dict[str, Any]:
    return {
        "key": value.row.key,
        "inventory": value.row.inventory.value,
        "name": value.row.name,
        "provenance": value.row.provenance,
        "reachability": value.row.reachability.value,
        "states": {state.value: value.row.states[state] for state in EvidenceState},
        "codeSpace": value.code_space.value,
        "code": value.code,
        "semanticNameStatus": value.semantic_name_status,
        "direction": {
            "status": value.direction.status.value,
            "evidence": list(value.direction.evidence),
        },
        "bodySize": {
            "status": value.body_size.status.value,
            "measurementKind": value.body_size.measurement_kind.value,
            "bytes": value.body_size.bytes,
            "evidence": list(value.body_size.evidence),
        },
        "siblings": {
            kind: {
                "disposition": sibling.disposition.value,
                "codes": list(sibling.codes),
                "evidence": list(sibling.evidence),
            }
            for kind, sibling in value.siblings.items()
        },
        "relations": [
            {
                "type": relation.type.value,
                "function": relation.function,
                "evidence": list(relation.evidence),
            }
            for relation in value.relations
        ],
        "ownership": {
            kind: {
                "status": owner.status.value,
                "functions": list(owner.functions),
                "evidence": list(owner.evidence),
            }
            for kind, owner in value.ownership.items()
        },
        "facts": [
            {"kind": fact.kind, "value": fact.value, "evidence": list(fact.evidence)}
            for fact in value.facts
        ],
        "evidence": list(value.evidence),
        "recoveryDisposition": value.recovery_disposition.value,
        "implementationDisposition": {
            target.value: {
                "status": "REQUIRED",
                "reason": (
                    f"{target.value} must implement protocol row {value.row.key}"
                ),
                "evidence": [f"goal:implementation-layer:{target.value}"],
            }
            for target in ImplementationTarget
        },
    }


def _main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--reconciliation", required=True, type=Path)
    parser.add_argument("--evidence-manifest", required=True, type=Path)
    parser.add_argument("--source-manifest", required=True, type=Path)
    args = parser.parse_args(argv)
    evidence_manifest = load_protocol_evidence_manifest(args.evidence_manifest)
    if args.input.resolve() != evidence_manifest.raw_path:
        raise ValueError("--input differs from the hash-bound protocol raw path")
    manifest_payload = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    manifest = SourceManifest.load(args.source_manifest)
    repository_hash = _mapping("source manifest ghidra", manifest_payload.get("ghidra")).get(
        "repositorySha256"
    )
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    rows = build_protocol_inventory(
        raw,
        expected_exporter_sha256=evidence_manifest.exporter_sha256,
        expected_repository_sha256=_text("ghidra.repositorySha256", repository_hash),
    )
    if evidence_manifest.repository_sha256 != _sha256(
        "source manifest repository hash", repository_hash
    ):
        raise ValueError("protocol and source manifests bind different Ghidra repositories")
    reconciliation = build_protocol_reconciliation(raw, rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        "".join(canonical_json(protocol_row_to_dict(row)) for row in rows),
        encoding="utf-8",
        newline="",
    )
    args.reconciliation.parent.mkdir(parents=True, exist_ok=True)
    args.reconciliation.write_text(
        canonical_json(reconciliation), encoding="utf-8", newline=""
    )
    print(
        canonical_json(
            {
                "status": "PASS",
                "rowCount": len(rows),
                "candidateCount": reconciliation["candidateCount"],
                "unresolvedCount": reconciliation["unresolvedCount"],
                "verifiedSourcePathCount": len(manifest.verified_paths),
            }
        ),
        end="",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
