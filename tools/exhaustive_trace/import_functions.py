"""Fail-closed normalization for the original client's complete function universe."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
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


class FunctionRowKind(StringEnum):
    INDIVIDUAL_FUNCTION = "INDIVIDUAL_FUNCTION"
    FUNCTION_GROUP = "FUNCTION_GROUP"


class FunctionGroupKind(StringEnum):
    EXTERNAL_IMPORT = "EXTERNAL_IMPORT"
    THUNK = "THUNK"


CANONICAL_GROUPS = {
    FunctionGroupKind.EXTERNAL_IMPORT: (
        "FUNCTION_GROUP:EXTERNAL_IMPORT",
        "namespace=EXTERNAL and source=raw-pe-imports",
    ),
    FunctionGroupKind.THUNK: ("FUNCTION_GROUP:THUNK", "isThunk=true"),
}


class FunctionStatus(StringEnum):
    TECHNICAL_ID_ONLY = "TECHNICAL_ID_ONLY"
    UNKNOWN = "UNKNOWN"
    DIRECT_ENUMERATED = "DIRECT_ENUMERATED"
    CANDIDATE = "CANDIDATE"
    MECHANICAL_ENUMERATION = "MECHANICAL_ENUMERATION"
    EVIDENCE_LINKED = "EVIDENCE_LINKED"
    UNADJUDICATED_INTERNAL = "UNADJUDICATED_INTERNAL"
    GROUPED_BY_RULE = "GROUPED_BY_RULE"


class ImplementationStatus(StringEnum):
    REQUIRED = "REQUIRED"
    NOT_APPLICABLE = "NOT_APPLICABLE"


@dataclass(frozen=True)
class FunctionSection:
    status: FunctionStatus
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class FunctionImplementationSection:
    status: ImplementationStatus
    values: Mapping[str, Any]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", MappingProxyType(dict(self.values)))


@dataclass(frozen=True)
class FunctionInventoryRow:
    row: InventoryRow
    row_kind: FunctionRowKind
    address: str | None
    group_kind: FunctionGroupKind | None
    grouping_rule: str | None
    member_addresses: tuple[str, ...]
    identity: Mapping[str, Any]
    proposed_name: FunctionSection
    classification: FunctionSection
    inputs_outputs: FunctionSection
    callers: FunctionSection
    callees: FunctionSection
    global_structure_fields: FunctionSection
    side_effects: tuple[str, ...]
    confidence: FunctionSection
    recovery_disposition: RecoveryDisposition
    implementation_disposition: Mapping[str, FunctionImplementationSection]
    first_missing_boundary: str
    reachability_evidence: tuple[str, ...]
    evidence: tuple[str, ...]
    source_candidate_ids: tuple[str, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "identity", MappingProxyType(dict(self.identity)))
        object.__setattr__(
            self,
            "implementation_disposition",
            MappingProxyType(dict(self.implementation_disposition)),
        )


@dataclass(frozen=True)
class FunctionsEvidenceManifest:
    path: Path
    raw_path: Path
    raw_sha256: str
    exporter_path: Path
    exporter_sha256: str
    repository_sha256: str
    source_manifest_path: Path
    source_manifest_sha256: str
    input_paths: Mapping[str, Path]
    input_sha256: Mapping[str, str]


TOP_LEVEL_FIELDS = frozenset(
    {
        "schemaVersion",
        "source",
        "exporter",
        "surfaceSha256",
        "successMarker",
        "audit",
        "conservation",
        "functionCandidates",
        "functionGroupCandidates",
        "upstreamReferenceCandidates",
        "unresolvedTargetCandidates",
    }
)

SHA256_PATTERN = re.compile(r"^[0-9A-Fa-f]{64}$")
ADDRESS_PATTERN = re.compile(r"^(?:0x)?[0-9A-Fa-f]{8}$")
FUNCTION_ID_PATTERN = re.compile(r"^FUNCTION:([0-9A-F]{8})$")
EXPECTED_LANGUAGE = "x86:LE:32:default"
EXPECTED_COMPILER = "windows"
EXPECTED_IMAGE_BASE = "00400000"
SIDE_EFFECTS = frozenset(
    {
        "READS_GLOBAL",
        "WRITES_GLOBAL",
        "CALLS_INTERNAL",
        "CALLS_EXTERNAL",
        "CALLS_INDIRECT",
        "RETURNS",
        "THROWS_OR_TERMINATES",
    }
)
INPUT_HASH_FIELDS = {
    "peImports": "peImportsSha256",
    "protocolRaw": "protocolRawSha256",
    "uiRaw": "uiRawSha256",
    "recordsRaw": "recordsRawSha256",
    "resourcesRaw": "resourcesRawSha256",
}


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
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item.strip() for item in value
    ):
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


def _address(name: str, value: object) -> str:
    result = _text(name, value).removeprefix("0x").removeprefix("0X").upper()
    if not ADDRESS_PATTERN.fullmatch(result):
        raise ValueError(f"{name} must be an eight-digit address")
    return result


def _implementation_for(
    row_kind: FunctionRowKind,
    group_kind: FunctionGroupKind | None,
) -> Mapping[str, FunctionImplementationSection]:
    result: dict[str, FunctionImplementationSection] = {}
    for target in ImplementationTarget:
        required = row_kind is FunctionRowKind.INDIVIDUAL_FUNCTION or target in {
            ImplementationTarget.CONTRACT,
            ImplementationTarget.LEGACY_GATEWAY,
            ImplementationTarget.NEW_CLIENT,
            ImplementationTarget.QA,
            ImplementationTarget.INDEPENDENT_REVIEW,
        }
        if required:
            status = ImplementationStatus.REQUIRED
            reason = (
                "internal function remains an individual compatibility and gameplay adjudication unit"
                if row_kind is FunctionRowKind.INDIVIDUAL_FUNCTION
                else f"{group_kind.value} boundary requires an explicit compatibility disposition"
            )
        else:
            status = ImplementationStatus.NOT_APPLICABLE
            reason = f"{group_kind.value} member is not itself a server, database, or authored-content implementation"
        result[target.value] = FunctionImplementationSection(
            status,
            {"reason": reason, "evidence": (f"goal:implementation-layer:{target.value}",)},
        )
    return result


def _validate_export(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_source_manifest_sha256: str | None = None,
    expected_input_sha256: Mapping[str, str] | None = None,
) -> None:
    unknown = set(raw) - TOP_LEVEL_FIELDS
    missing = TOP_LEVEL_FIELDS - set(raw)
    if unknown or missing:
        raise ValueError(
            f"functions export top-level fields differ: unknown={sorted(unknown)} missing={sorted(missing)}"
        )
    if raw.get("schemaVersion") != 1:
        raise ValueError("unsupported functions schemaVersion")
    if raw.get("successMarker") != "EXPORT_EXHAUSTIVE_FUNCTIONS_OK":
        raise ValueError("functions success marker is missing")
    source = _mapping("source", raw.get("source"))
    if source.get("program") != "g7mtclient.exe":
        raise ValueError("functions source program mismatch")
    if _sha256("source.executableSha256", source.get("executableSha256")) != CLIENT_SHA256:
        raise ValueError("functions source executable hash mismatch")
    if source.get("language") != EXPECTED_LANGUAGE or source.get("compiler") != EXPECTED_COMPILER:
        raise ValueError("functions source language/compiler mismatch")
    if source.get("imageBase") != EXPECTED_IMAGE_BASE:
        raise ValueError("functions source image base mismatch")
    source_manifest_sha = _sha256(
        "source.sourceManifestSha256", source.get("sourceManifestSha256")
    )
    if expected_source_manifest_sha256 and source_manifest_sha != expected_source_manifest_sha256:
        raise ValueError("functions source manifest hash mismatch")
    for label, field in INPUT_HASH_FIELDS.items():
        actual = _sha256(f"source.{field}", source.get(field))
        if expected_input_sha256 and actual != expected_input_sha256[label]:
            raise ValueError(f"functions {label} hash mismatch")
    exporter = _mapping("exporter", raw.get("exporter"))
    if exporter.get("class") != "ExportExhaustiveFunctions":
        raise ValueError("functions exporter class mismatch")
    exporter_sha = _sha256("exporter.sha256", exporter.get("sha256"))
    repository_sha = _sha256(
        "exporter.ghidraRepositorySha256", exporter.get("ghidraRepositorySha256")
    )
    if expected_exporter_sha256 and exporter_sha != expected_exporter_sha256:
        raise ValueError("functions exporter hash mismatch")
    if expected_repository_sha256 and repository_sha != expected_repository_sha256:
        raise ValueError("functions repository hash mismatch")
    _sha256("surfaceSha256", raw.get("surfaceSha256"))
    audit = _mapping("audit", raw.get("audit"))
    if (
        audit.get("scope") != "FUNCTION_SURFACE_UNIVERSE"
        or audit.get("sizeAloneClassifiesPlumbing") is not False
        or audit.get("upstreamMentionIsSemanticIdentity") is not False
        or audit.get("staticCallgraphIsRuntimeReachability") is not False
        or audit.get("groupedTargetReciprocity")
        != "INDIVIDUAL_ONLY_GROUP_INBOUND_RETAINED_IN_CALLER"
    ):
        raise ValueError("functions audit overstates its bounded scope")
    _text_list("audit.limitations", audit.get("limitations"), allow_empty=False)
    for collection in (
        "functionCandidates",
        "functionGroupCandidates",
        "upstreamReferenceCandidates",
        "unresolvedTargetCandidates",
    ):
        if not isinstance(raw.get(collection), list):
            raise ValueError(f"{collection} must be a list")


def _ref_records(
    name: str,
    values: object,
    *,
    address_field: str,
) -> tuple[Mapping[str, Any], ...]:
    if not isinstance(values, list):
        raise ValueError(f"{name} must be a list")
    result: list[Mapping[str, Any]] = []
    for index, value in enumerate(values):
        item = _mapping(f"{name}[{index}]", value)
        _address(f"{name}.{address_field}", item.get(address_field))
        _address(f"{name}.callsite", item.get("callsite"))
        if item.get("kind") != "DIRECT_CALL":
            raise ValueError(f"{name} must contain direct calls")
        _evidence(f"{name}.evidence", item.get("evidence"))
        result.append(item)
    return tuple(result)


def _data_records(name: str, values: object) -> tuple[Mapping[str, Any], ...]:
    if not isinstance(values, list):
        raise ValueError(f"{name} must be a list")
    result: list[Mapping[str, Any]] = []
    for index, value in enumerate(values):
        item = _mapping(f"{name}[{index}]", value)
        _address(f"{name}.targetAddress", item.get("targetAddress"))
        symbol = _text(f"{name}.targetSymbol", item.get("targetSymbol"))
        if re.fullmatch(r"0x[0-9A-Fa-f]{8}", symbol):
            raise ValueError("runtime pointer cannot be a stable structure field")
        _text(f"{name}.refType", item.get("refType"))
        _evidence(f"{name}.evidence", item.get("evidence"))
        result.append(item)
    return tuple(result)


def _normalize_records(values: tuple[Mapping[str, Any], ...]) -> tuple[Mapping[str, Any], ...]:
    return tuple(
        MappingProxyType(dict(item))
        for item in sorted(
            values,
            key=lambda item: (
                str(item.get("callsite", "")),
                str(item.get("targetAddress", item.get("sourceAddress", ""))),
            ),
        )
    )


def build_function_inventory(
    raw: Mapping[str, Any],
    *,
    expected_exporter_sha256: str | None = None,
    expected_repository_sha256: str | None = None,
    expected_source_manifest_sha256: str | None = None,
    expected_input_sha256: Mapping[str, str] | None = None,
) -> list[FunctionInventoryRow]:
    _validate_export(
        raw,
        expected_exporter_sha256=expected_exporter_sha256,
        expected_repository_sha256=expected_repository_sha256,
        expected_source_manifest_sha256=expected_source_manifest_sha256,
        expected_input_sha256=expected_input_sha256,
    )
    seen_candidate_ids: dict[str, str] = {}
    for collection in (
        "functionCandidates",
        "functionGroupCandidates",
        "upstreamReferenceCandidates",
        "unresolvedTargetCandidates",
    ):
        for index, value in enumerate(raw[collection]):
            item = _mapping(f"{collection}[{index}]", value)
            candidate_id = _text(f"{collection}[{index}].candidateId", item.get("candidateId"))
            folded = candidate_id.casefold()
            if folded in seen_candidate_ids:
                raise ValueError(
                    f"candidateId must be globally unique case-insensitively: "
                    f"{seen_candidate_ids[folded]} and {candidate_id}"
                )
            seen_candidate_ids[folded] = candidate_id
    function_items: dict[str, Mapping[str, Any]] = {}
    address_membership: dict[str, str] = {}
    for value in raw["functionCandidates"]:
        item = _mapping("function candidate", value)
        candidate_id = _text("function candidateId", item.get("candidateId"))
        match = FUNCTION_ID_PATTERN.fullmatch(candidate_id)
        address = _address("function address", item.get("address"))
        if match is None or match.group(1) != address:
            raise ValueError("function candidateId/address mismatch")
        if candidate_id in function_items or address in address_membership:
            raise ValueError("function membership must occur exactly once")
        if item.get("namespace") != "INTERNAL":
            raise ValueError("individual functions must be internal")
        function_items[candidate_id] = item
        address_membership[address] = candidate_id

    groups: list[tuple[Mapping[str, Any], FunctionGroupKind, tuple[Mapping[str, Any], ...]]] = []
    group_candidate_ids: set[str] = set()
    external_count = thunk_count = 0
    for value in raw["functionGroupCandidates"]:
        item = _mapping("function group", value)
        candidate_id = _text("function group candidateId", item.get("candidateId"))
        if candidate_id in group_candidate_ids:
            raise ValueError("duplicate function group candidateId")
        group_candidate_ids.add(candidate_id)
        try:
            kind = FunctionGroupKind(_text("function group kind", item.get("groupKind")))
        except ValueError as error:
            raise ValueError("internal non-thunk functions cannot be grouped as plumbing") from error
        rule = _text("function grouping rule", item.get("groupingRule"))
        canonical_id, canonical_rule = CANONICAL_GROUPS[kind]
        if candidate_id != canonical_id or rule != canonical_rule:
            raise ValueError("function group must use its canonical candidateId and grouping rule")
        members_value = item.get("members")
        if not isinstance(members_value, list) or not members_value:
            raise ValueError("function group members must not be empty")
        members: list[Mapping[str, Any]] = []
        for member_value in members_value:
            member = _mapping("function group member", member_value)
            address = _address("function group member address", member.get("address"))
            namespace = _text("function group member namespace", member.get("namespace"))
            if address in address_membership:
                raise ValueError("function membership must occur exactly once")
            if kind is FunctionGroupKind.EXTERNAL_IMPORT and namespace != "EXTERNAL":
                raise ValueError("internal non-thunk function cannot join external group")
            if kind is FunctionGroupKind.THUNK and (
                namespace != "INTERNAL" or _optional_text("thunk target", member.get("thunkTarget")) is None
            ):
                raise ValueError("internal non-thunk function cannot join thunk group")
            _text("function group member name", member.get("name"))
            _evidence("function group member evidence", member.get("evidence"))
            address_membership[address] = candidate_id
            external_count += int(namespace == "EXTERNAL")
            thunk_count += int(kind is FunctionGroupKind.THUNK)
            members.append(member)
        _evidence("function group evidence", item.get("evidence"))
        groups.append((item, kind, tuple(members)))

    conservation = _mapping("conservation", raw.get("conservation"))
    expected_conservation_fields = {
        "functionSurfaceMembers",
        "ghidraDefinedFunctions",
        "ghidraInternalFunctions",
        "individualFunctions",
        "groupedMembers",
        "externalFunctions",
        "thunkFunctions",
        "ghidraExternalFunctions",
        "rawPeImports",
        "upstreamReferences",
        "unresolvedTargets",
    }
    if set(conservation) != expected_conservation_fields:
        raise ValueError("function conservation fields differ")
    if any(type(value) is not int or value < 0 for value in conservation.values()):
        raise ValueError("function conservation counters must be non-negative integers")
    ghidra_external = conservation.get("ghidraExternalFunctions")
    expected_counts = {
        "functionSurfaceMembers": len(address_membership),
        "ghidraDefinedFunctions": len(function_items) + thunk_count + ghidra_external,
        "ghidraInternalFunctions": len(function_items) + thunk_count,
        "individualFunctions": len(function_items),
        "groupedMembers": sum(len(members) for _, _, members in groups),
        "externalFunctions": external_count,
        "thunkFunctions": thunk_count,
        "rawPeImports": external_count,
        "upstreamReferences": len(raw["upstreamReferenceCandidates"]),
        "unresolvedTargets": len(raw["unresolvedTargetCandidates"]),
    }
    if (
        any(conservation.get(key) != value for key, value in expected_counts.items())
        or external_count - ghidra_external not in {0, 1}
    ):
        raise ValueError("function conservation differs")

    unresolved_targets: dict[tuple[str, str], Mapping[str, Any]] = {}
    for value in raw["unresolvedTargetCandidates"]:
        item = _mapping("unresolved target", value)
        _text("unresolved target candidateId", item.get("candidateId"))
        target = _address("unresolved target address", item.get("targetAddress"))
        callsites = _text_list("unresolved target callsites", item.get("callsites"), allow_empty=False)
        if item.get("status") != "UNRESOLVED":
            raise ValueError("unresolved target status differs")
        _text("unresolved target boundary", item.get("firstMissingBoundary"))
        _evidence("unresolved target evidence", item.get("evidence"))
        if target in address_membership:
            raise ValueError("unresolved target is already a known function member")
        for callsite in callsites:
            key = (target, _address("unresolved callsite", callsite))
            if key in unresolved_targets:
                raise ValueError("duplicate unresolved target address/callsite")
            unresolved_targets[key] = item

    covered_unresolved: set[tuple[str, str]] = set()
    parsed_calls: dict[str, tuple[tuple[Mapping[str, Any], ...], tuple[Mapping[str, Any], ...]]] = {}
    parsed_data: dict[str, tuple[tuple[Mapping[str, Any], ...], tuple[Mapping[str, Any], ...]]] = {}
    for candidate_id, item in function_items.items():
        callers = _ref_records("callers", item.get("callers"), address_field="sourceAddress")
        callees = _ref_records("callees", item.get("callees"), address_field="targetAddress")
        parsed_calls[candidate_id] = (callers, callees)
        data = _mapping("dataReferences", item.get("dataReferences"))
        parsed_data[candidate_id] = (
            _data_records("data reads", data.get("reads")),
            _data_records("data writes", data.get("writes")),
        )
        for callee in callees:
            target = _address("callee target", callee.get("targetAddress"))
            callsite = _address("callee callsite", callee.get("callsite"))
            target_id = f"FUNCTION:{target}"
            if target not in address_membership and (target, callsite) not in unresolved_targets:
                raise ValueError("direct call has no unresolved target candidate")
            if target not in address_membership:
                covered_unresolved.add((target, callsite))
            if target_id in function_items:
                reciprocal = any(
                    _address("caller source", caller.get("sourceAddress"))
                    == FUNCTION_ID_PATTERN.fullmatch(candidate_id).group(1)
                    and _address("caller callsite", caller.get("callsite")) == callsite
                    for caller in parsed_calls.get(target_id, (
                        _ref_records("callers", function_items[target_id].get("callers"), address_field="sourceAddress"),
                        (),
                    ))[0]
                )
                if not reciprocal:
                    raise ValueError("direct call edge must be reciprocal")

    # Complete reciprocity after every row has been parsed.
    for candidate_id, (callers, _) in parsed_calls.items():
        target = FUNCTION_ID_PATTERN.fullmatch(candidate_id).group(1)
        for caller in callers:
            source = _address("caller source", caller.get("sourceAddress"))
            callsite = _address("caller callsite", caller.get("callsite"))
            source_id = f"FUNCTION:{source}"
            if source_id in parsed_calls:
                if not any(
                    _address("callee target", callee.get("targetAddress")) == target
                    and _address("callee callsite", callee.get("callsite")) == callsite
                    for callee in parsed_calls[source_id][1]
                ):
                    raise ValueError("direct call edge must be reciprocal")
            elif source in address_membership:
                # Grouped source members do not have member-level caller/callee rows.
                continue
            elif (source, callsite) in unresolved_targets:
                covered_unresolved.add((source, callsite))
            else:
                raise ValueError("caller source has no known or unresolved function member")

    if set(unresolved_targets) != covered_unresolved:
        raise ValueError("unresolved target candidate is not referenced by a direct edge")

    upstream_by_function: dict[str, list[Mapping[str, Any]]] = {
        candidate_id: [] for candidate_id in function_items
    }
    seen_upstream: set[str] = set()
    for value in raw["upstreamReferenceCandidates"]:
        item = _mapping("upstream reference", value)
        candidate_id = _text("upstream candidateId", item.get("candidateId"))
        if candidate_id in seen_upstream:
            raise ValueError("duplicate upstream reference candidateId")
        seen_upstream.add(candidate_id)
        _text("upstream artifact", item.get("artifact"))
        _sha256("upstream artifactSha256", item.get("artifactSha256"))
        _text("upstream jsonPointer", item.get("jsonPointer"))
        _text("upstream token", item.get("token"))
        _evidence("upstream evidence", item.get("evidence"))
        resolved = item.get("resolvedFunctionCandidateId")
        if item.get("status") == "MENTION":
            resolved_id = _text("upstream resolvedFunctionCandidateId", resolved)
            if resolved_id not in function_items:
                raise ValueError("upstream mention resolves outside individual functions")
            upstream_by_function[resolved_id].append(item)
        elif item.get("status") == "UNRESOLVED":
            if resolved is not None:
                raise ValueError("unresolved upstream reference cannot resolve")
            _text("upstream missing boundary", item.get("firstMissingBoundary"))
        else:
            raise ValueError("unsupported upstream reference status")

    rows: list[FunctionInventoryRow] = []
    for candidate_id in sorted(function_items):
        item = function_items[candidate_id]
        address = _address("function address", item.get("address"))
        ghidra_name = _text("function ghidraName", item.get("ghidraName"))
        body = _mapping("function body", item.get("body"))
        min_address = _address("function minAddress", body.get("minAddress"))
        max_address = _address("function maxAddress", body.get("maxAddress"))
        instruction_count = body.get("instructionCount")
        if min_address != address or type(instruction_count) is not int or instruction_count <= 0:
            raise ValueError("function body metadata differs")
        signature = _mapping("function signature", item.get("signature"))
        if set(signature) != {
            "status",
            "callingConvention",
            "returnType",
            "parameters",
            "evidence",
        }:
            raise ValueError("function signature fields differ")
        signature_status = _text("signature status", signature.get("status"))
        if signature_status != "UNKNOWN":
            raise ValueError("function signature status must remain UNKNOWN")
        calling_convention = _text(
            "signature callingConvention", signature.get("callingConvention")
        )
        return_type = _text("signature returnType", signature.get("returnType"))
        raw_parameters = signature.get("parameters")
        if not isinstance(raw_parameters, list):
            raise ValueError("signature parameters must be a list of objects")
        normalized_parameters: list[dict[str, Any]] = []
        ordinals: list[int] = []
        for index, raw_parameter in enumerate(raw_parameters):
            parameter = _mapping(f"signature parameters[{index}]", raw_parameter)
            if set(parameter) != {"ordinal", "name", "dataType", "storage"}:
                raise ValueError("signature parameters fields differ")
            ordinal = parameter.get("ordinal")
            if type(ordinal) is not int or ordinal < 0:
                raise ValueError("signature parameters ordinal must be a non-negative integer")
            ordinals.append(ordinal)
            normalized_parameters.append(
                {
                    "ordinal": ordinal,
                    "name": _text("signature parameter name", parameter.get("name")),
                    "dataType": _text("signature parameter dataType", parameter.get("dataType")),
                    "storage": _text("signature parameter storage", parameter.get("storage")),
                }
            )
        if ordinals != list(range(len(ordinals))):
            raise ValueError("signature parameters ordinals must be unique and contiguous")
        signature_evidence = _evidence("signature evidence", signature.get("evidence"))
        callers, callees = parsed_calls[candidate_id]
        reads, writes = parsed_data[candidate_id]
        strings = item.get("stringReferences")
        indirect = item.get("indirectCallsites")
        if not isinstance(strings, list) or not isinstance(indirect, list):
            raise ValueError("function string/indirect references must be lists")
        for record in strings:
            value = _mapping("string reference", record)
            _address("string address", value.get("stringAddress"))
            _address("string callsite", value.get("referenceAddress"))
            if not isinstance(value.get("value"), str):
                raise ValueError("string value must be text")
            _evidence("string evidence", value.get("evidence"))
        for record in indirect:
            value = _mapping("indirect callsite", record)
            _address("indirect callsite", value.get("callsite"))
            _text("indirect operand", value.get("operand"))
            if value.get("status") != "UNRESOLVED":
                raise ValueError("indirect callsite must remain unresolved")
            _evidence("indirect evidence", value.get("evidence"))
        side_effects = _text_list("sideEffects", item.get("sideEffects"))
        if len(set(side_effects)) != len(side_effects) or set(side_effects) - SIDE_EFFECTS:
            raise ValueError("unsupported or duplicate function side effect")
        if indirect and "CALLS_INDIRECT" not in side_effects:
            raise ValueError("indirect callsite requires CALLS_INDIRECT side effect")
        raw_classification = _mapping("function classification", item.get("classification"))
        if raw_classification.get("status") != "UNADJUDICATED_INTERNAL":
            raise ValueError("raw internal function classification must be unadjudicated")
        _text_list("classification reasons", raw_classification.get("reasons"))
        upstream = upstream_by_function[candidate_id]
        classification_status = (
            FunctionStatus.EVIDENCE_LINKED if upstream else FunctionStatus.UNADJUDICATED_INTERNAL
        )
        states = {state: False for state in EvidenceState}
        states[EvidenceState.ENUMERATED] = True
        evidence = _evidence("function evidence", item.get("evidence"))
        source_ids = (candidate_id, *(_text("upstream candidateId", link.get("candidateId")) for link in upstream))
        rows.append(
            FunctionInventoryRow(
                row=InventoryRow(
                    key=f"FUNCTION:INTERNAL:{address}",
                    inventory=InventoryKind.FUNCTION,
                    name=ghidra_name,
                    provenance="ORIGINAL_OBSERVED",
                    reachability=Reachability.UNKNOWN,
                    states=states,
                ),
                row_kind=FunctionRowKind.INDIVIDUAL_FUNCTION,
                address=address,
                group_kind=None,
                grouping_rule=None,
                member_addresses=(),
                identity={
                    "ghidraName": ghidra_name,
                    "namespace": "INTERNAL",
                    "body": {
                        "minAddress": min_address,
                        "maxAddress": max_address,
                        "instructionCount": instruction_count,
                    },
                },
                proposed_name=FunctionSection(
                    FunctionStatus.TECHNICAL_ID_ONLY,
                    {
                        "value": ghidra_name,
                        "semanticName": None,
                        "evidence": evidence,
                    },
                ),
                classification=FunctionSection(
                    classification_status,
                    {
                        "reasons": tuple(
                            f"{link['artifact']}:{link['jsonPointer']}" for link in upstream
                        ),
                        "upstreamReferences": tuple(dict(link) for link in upstream),
                        "evidence": tuple(
                            evidence_ref
                            for link in upstream
                            for evidence_ref in _evidence("upstream evidence", link.get("evidence"))
                        )
                        or evidence,
                    },
                ),
                inputs_outputs=FunctionSection(
                    FunctionStatus.UNKNOWN if signature_status == "UNKNOWN" else FunctionStatus.CANDIDATE,
                    {
                        "callingConvention": calling_convention,
                        "returnType": return_type,
                        "parameters": tuple(normalized_parameters),
                        "evidence": signature_evidence,
                    },
                ),
                callers=FunctionSection(
                    FunctionStatus.DIRECT_ENUMERATED,
                    {"direct": _normalize_records(callers), "evidence": evidence},
                ),
                callees=FunctionSection(
                    FunctionStatus.DIRECT_ENUMERATED,
                    {
                        "direct": _normalize_records(callees),
                        "indirectCallsites": tuple(dict(record) for record in indirect),
                        "evidence": evidence,
                    },
                ),
                global_structure_fields=FunctionSection(
                    FunctionStatus.CANDIDATE if reads or writes else FunctionStatus.UNKNOWN,
                    {
                        "reads": _normalize_records(reads),
                        "writes": _normalize_records(writes),
                        "stringReferences": tuple(dict(record) for record in strings),
                        "evidence": evidence,
                    },
                ),
                side_effects=tuple(sorted(side_effects)),
                confidence=FunctionSection(
                    FunctionStatus.MECHANICAL_ENUMERATION,
                    {
                        "addressBody": "HIGH",
                        "semanticClassification": "LOW" if not upstream else "CANDIDATE",
                        "evidence": evidence,
                    },
                ),
                recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
                implementation_disposition=_implementation_for(
                    FunctionRowKind.INDIVIDUAL_FUNCTION, None
                ),
                first_missing_boundary=(
                    "SEMANTIC_ROLE" if not upstream else "SEMANTIC_IDENTITY_AND_RUNTIME_REACHABILITY"
                ),
                reachability_evidence=("static-function-enumeration-only",),
                evidence=evidence,
                source_candidate_ids=tuple(source_ids),
            )
        )

    for item, kind, members in sorted(groups, key=lambda group: str(group[0]["candidateId"])):
        candidate_id = _text("function group candidateId", item.get("candidateId"))
        rule = _text("function grouping rule", item.get("groupingRule"))
        evidence = _evidence("function group evidence", item.get("evidence"))
        member_addresses = tuple(sorted(_address("member address", member.get("address")) for member in members))
        states = {state: False for state in EvidenceState}
        states[EvidenceState.ENUMERATED] = True
        rows.append(
            FunctionInventoryRow(
                row=InventoryRow(
                    key=f"FUNCTION:GROUP:{kind.value}",
                    inventory=InventoryKind.FUNCTION,
                    name=kind.value.lower(),
                    provenance="ORIGINAL_OBSERVED",
                    reachability=Reachability.UNKNOWN,
                    states=states,
                ),
                row_kind=FunctionRowKind.FUNCTION_GROUP,
                address=None,
                group_kind=kind,
                grouping_rule=rule,
                member_addresses=member_addresses,
                identity={
                    "members": tuple(
                        dict(member)
                        for member in sorted(members, key=lambda member: str(member["address"]))
                    )
                },
                proposed_name=FunctionSection(
                    FunctionStatus.TECHNICAL_ID_ONLY,
                    {"value": kind.value, "semanticName": None, "evidence": evidence},
                ),
                classification=FunctionSection(
                    FunctionStatus.GROUPED_BY_RULE,
                    {"reasons": (rule,), "upstreamReferences": (), "evidence": evidence},
                ),
                inputs_outputs=FunctionSection(
                    FunctionStatus.UNKNOWN,
                    {"callingConvention": None, "returnType": None, "parameters": (), "evidence": evidence},
                ),
                callers=FunctionSection(
                    FunctionStatus.UNKNOWN, {"direct": (), "evidence": evidence}
                ),
                callees=FunctionSection(
                    FunctionStatus.UNKNOWN,
                    {"direct": (), "indirectCallsites": (), "evidence": evidence},
                ),
                global_structure_fields=FunctionSection(
                    FunctionStatus.UNKNOWN,
                    {"reads": (), "writes": (), "stringReferences": (), "evidence": evidence},
                ),
                side_effects=(),
                confidence=FunctionSection(
                    FunctionStatus.MECHANICAL_ENUMERATION,
                    {"addressBody": "HIGH", "semanticClassification": "UNKNOWN", "evidence": evidence},
                ),
                recovery_disposition=RecoveryDisposition.RECOVERABLE_STATIC,
                implementation_disposition=_implementation_for(
                    FunctionRowKind.FUNCTION_GROUP, kind
                ),
                first_missing_boundary="MEMBER_LEVEL_SEMANTICS",
                reachability_evidence=("static-function-grouping-only",),
                evidence=evidence,
                source_candidate_ids=(
                    candidate_id,
                    *(f"FUNCTION:{address}" for address in member_addresses),
                ),
            )
        )
    return sorted(
        rows,
        key=lambda row: (
            0 if row.row_kind is FunctionRowKind.INDIVIDUAL_FUNCTION else 1,
            row.address or row.row.key,
        ),
    )


def _json_value(value: Any) -> Any:
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in sorted(value.items())}
    if isinstance(value, tuple):
        return [_json_value(item) for item in value]
    return value


def function_row_to_dict(item: FunctionInventoryRow) -> dict[str, Any]:
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
        "states": {state.value: item.row.states[state] for state in EvidenceState},
        "address": item.address,
        "groupKind": item.group_kind.value if item.group_kind else None,
        "groupingRule": item.grouping_rule,
        "memberAddresses": list(item.member_addresses),
        "identity": _json_value(item.identity),
        "proposedName": {
            "status": item.proposed_name.status.value,
            **_json_value(item.proposed_name.values),
        },
        "classification": {
            "status": item.classification.status.value,
            **_json_value(item.classification.values),
        },
        "inputsOutputs": {
            "status": item.inputs_outputs.status.value,
            **_json_value(item.inputs_outputs.values),
        },
        "callers": {"status": item.callers.status.value, **_json_value(item.callers.values)},
        "callees": {"status": item.callees.status.value, **_json_value(item.callees.values)},
        "globalStructureFields": {
            "status": item.global_structure_fields.status.value,
            **_json_value(item.global_structure_fields.values),
        },
        "sideEffects": list(item.side_effects),
        "confidence": {
            "status": item.confidence.status.value,
            **_json_value(item.confidence.values),
        },
        "implementationDisposition": {
            name: {"status": section.status.value, **_json_value(section.values)}
            for name, section in sorted(item.implementation_disposition.items())
        },
        "firstMissingBoundary": item.first_missing_boundary,
        "evidence": list(item.evidence),
        "sourceCandidateIds": list(item.source_candidate_ids),
    }


def normalize_function_inventory(rows: list[FunctionInventoryRow]) -> list[dict[str, Any]]:
    return [function_row_to_dict(row) for row in sorted(rows, key=lambda row: row.row.key)]


def build_function_reconciliation(
    raw: Mapping[str, Any],
    rows: list[FunctionInventoryRow],
    *,
    _raw_validated: bool = False,
) -> dict[str, Any]:
    if not _raw_validated:
        validated_rows = build_function_inventory(raw)
        if normalize_function_inventory(validated_rows) != normalize_function_inventory(rows):
            raise ValueError("reconciliation rows differ from the validated raw inventory")
    represented = {candidate_id for row in rows for candidate_id in row.source_candidate_ids}
    candidates: dict[str, tuple[str, str | None]] = {}
    for item in raw["functionCandidates"]:
        candidate_id = _text("function candidateId", item.get("candidateId"))
        candidates[candidate_id] = ("FUNCTION", None)
    for group in raw["functionGroupCandidates"]:
        group_candidate_id = _text("function group candidateId", group.get("candidateId"))
        candidates[group_candidate_id] = ("FUNCTION_GROUP", None)
        for member in group["members"]:
            address = _address("group member address", member.get("address"))
            candidates[f"FUNCTION:{address}"] = ("FUNCTION", None)
    for collection in ("upstreamReferenceCandidates", "unresolvedTargetCandidates"):
        for item in raw[collection]:
            candidate_id = _text(f"{collection} candidateId", item.get("candidateId"))
            first_missing = (
                str(item.get("firstMissingBoundary"))
                if item.get("firstMissingBoundary")
                else None
            )
            candidates[candidate_id] = (collection, first_missing)
    records: list[dict[str, Any]] = []
    normalized = unresolved = 0
    for candidate_id, (collection, first_missing) in candidates.items():
        if candidate_id in represented:
            status = "NORMALIZED"
            boundary = None
            normalized += 1
        else:
            status = "UNRESOLVED"
            boundary = first_missing or "FUNCTION_ROW_JOIN"
            unresolved += 1
        records.append(
            {
                "candidateId": candidate_id,
                "collection": collection,
                "status": status,
                "firstMissingBoundary": boundary,
            }
        )
    candidate_count = len(candidates)
    represented_functions = sum(
        1
        for candidate_id, (collection, _) in candidates.items()
        if collection == "FUNCTION" and candidate_id in represented
    )
    return {
        "schemaVersion": 1,
        "functionSurfaceMemberCount": raw["conservation"]["functionSurfaceMembers"],
        "representedFunctionCount": represented_functions,
        "candidateCount": candidate_count,
        "normalizedCount": normalized,
        "unresolvedCount": unresolved,
        "unaccountedCount": candidate_count - normalized - unresolved,
        "records": sorted(records, key=lambda record: record["candidateId"]),
    }


def load_functions_evidence_manifest(path: str | Path) -> FunctionsEvidenceManifest:
    manifest_path = Path(path).resolve()
    payload = _mapping(
        "functions evidence manifest",
        json.loads(manifest_path.read_text(encoding="utf-8")),
    )
    if payload.get("schemaVersion") != 1:
        raise ValueError("unsupported functions evidence manifest schemaVersion")
    if _sha256("clientSha256", payload.get("clientSha256")) != CLIENT_SHA256:
        raise ValueError("functions evidence manifest binds a different client")

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
    source_path, source_sha = bound_file("sourceManifest")
    input_paths: dict[str, Path] = {}
    input_sha: dict[str, str] = {}
    for label in INPUT_HASH_FIELDS:
        input_paths[label], input_sha[label] = bound_file(label)
    return FunctionsEvidenceManifest(
        path=manifest_path,
        raw_path=raw_path,
        raw_sha256=raw_sha,
        exporter_path=exporter_path,
        exporter_sha256=exporter_sha,
        repository_sha256=_sha256(
            "ghidraRepositorySha256", payload.get("ghidraRepositorySha256")
        ),
        source_manifest_path=source_path,
        source_manifest_sha256=source_sha,
        input_paths=MappingProxyType(input_paths),
        input_sha256=MappingProxyType(input_sha),
    )


def _write_jsonl(path: Path, items: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(
            json.dumps(item, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
            for item in items
        ),
        encoding="utf-8",
        newline="\n",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reconciliation", type=Path, required=True)
    parser.add_argument("--evidence-manifest", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    args = parser.parse_args(argv)
    evidence = load_functions_evidence_manifest(args.evidence_manifest)
    if args.input.resolve() != evidence.raw_path:
        raise ValueError("--input differs from hash-bound functions raw path")
    if args.source_manifest.resolve() != evidence.source_manifest_path:
        raise ValueError("--source-manifest differs from functions evidence binding")
    source_manifest = SourceManifest.load(args.source_manifest)
    source_payload = json.loads(args.source_manifest.read_text(encoding="utf-8"))
    repository_sha = _sha256(
        "source repository hash", _mapping("ghidra", source_payload.get("ghidra")).get("repositorySha256")
    )
    if repository_sha != evidence.repository_sha256:
        raise ValueError("functions and source manifests bind different Ghidra databases")
    raw = json.loads(args.input.read_text(encoding="utf-8"))
    rows = build_function_inventory(
        raw,
        expected_exporter_sha256=evidence.exporter_sha256,
        expected_repository_sha256=repository_sha,
        expected_source_manifest_sha256=evidence.source_manifest_sha256,
        expected_input_sha256=evidence.input_sha256,
    )
    normalized = normalize_function_inventory(rows)
    reconciliation = build_function_reconciliation(raw, rows, _raw_validated=True)
    if reconciliation["unaccountedCount"] != 0:
        raise ValueError("function reconciliation left unaccounted candidates")
    if reconciliation["representedFunctionCount"] != reconciliation["functionSurfaceMemberCount"]:
        raise ValueError("function inventory omitted defined functions")
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
                "functionSurfaceMemberCount": reconciliation["functionSurfaceMemberCount"],
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
