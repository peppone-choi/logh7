"""Fail-closed orphan and vertical-trace coverage audits."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Iterable, Mapping, Sequence

from .io import canonical_json
from .model import EvidenceState, ImplementationTarget, RecoveryDisposition, freeze_json


SCHEMA_VERSION = 1
POLICY_VERSION = "TASK10-1"
IMPLEMENTATION_TARGETS = tuple(target.value for target in ImplementationTarget)
STATE_NAMES = tuple(state.value for state in EvidenceState)
RECOVERY_DISPOSITIONS = frozenset(item.value for item in RecoveryDisposition)
REACHABILITY_VALUES = frozenset(
    {"SHIPPED_REACHABLE", "SHIPPED_DORMANT", "MANUAL_ONLY", "UNKNOWN"}
)
VERDICTS = frozenset({"PASS", "PARTIAL", "UNSEEN", "BLOCKED", "UNKNOWN"})
IMPLEMENTATION_STATUSES = frozenset({"REQUIRED", "NOT_APPLICABLE"})
FEATURE_TRACE_BOUNDARIES = (
    "PLAYER_VISIBLE_CONTROL", "ENABLE_AUTHORITY_PRECONDITIONS", "INPUT_EVENT",
    "COMMAND_CONSTRUCTION", "SERIALIZATION_SEND", "SERVER_PARSE_VALIDATION",
    "AUTHORITY_RESULT", "STATE_MUTATION_EVENT", "RESPONSE_NOTIFICATION",
    "CLIENT_PARSE_STATE_WRITE", "PLAYER_VISIBLE_CONSUMER", "PERSISTENCE",
    "RECONNECT_REPLAY",
)
ENTITY_TRACE_BOUNDARIES = (
    "DEFINITION_PROVENANCE", "STABLE_IDENTITY", "PARENT_OWNER_LOCATION",
    "CREATION_SPAWN", "QUERY_VISIBILITY", "MUTATION_COMMANDS",
    "TRANSFER_TERMINAL_STATE", "WIRE_SNAPSHOT_NOTIFICATION",
    "CLIENT_REPRESENTATION", "PERSISTENCE_RESTORATION",
)
CONTENT_TRACE_BOUNDARIES = (
    "SOURCE_VALUE_PROVENANCE", "LOADER_PARSER", "STABLE_ID",
    "REFERENCING_OWNER", "RUNTIME_USE", "LOCALIZATION",
    "PLAYER_VISIBLE_RECEIPT",
)
VERTICAL_PATHS = MappingProxyType(
    {"FEATURE": FEATURE_TRACE_BOUNDARIES, "ENTITY": ENTITY_TRACE_BOUNDARIES, "CONTENT": CONTENT_TRACE_BOUNDARIES}
)
BOUNDARY_ORDER = (
    "PROTOCOL_DIRECTION", "SEMANTIC_IDENTITY", "CLIENT_SERIALIZER", "CLIENT_PARSER",
    "CLIENT_DISPATCHER", "BODY_LAYOUT", "UI_INTERACTION_DISPOSITION",
    "BUILDER_CONSTRUCTION", "UI_HANDLER", "UI_ENABLEMENT",
    *FEATURE_TRACE_BOUNDARIES,
    "ENTITY_ID_NAMESPACE", "ENTITY_PARENT", *ENTITY_TRACE_BOUNDARIES,
    "RESOURCE_LOADER", "RESOURCE_OWNER", *CONTENT_TRACE_BOUNDARIES,
    "WRITER_INPUT_SOURCE", "AUTHORITY_OWNER", "COMMAND_HANDLER",
    "AUTHORITY_MUTATION", "AUTHORITY_EVENT", "NOTIFICATION_FANOUT",
    "EMISSION_IDENTITY", "AUTHORITY_COUNTERPART_CLASSIFICATION",
    "FEATURE_REACHABILITY_CLASSIFICATION", "FIELD_OFFSET_WIDTH", "ID_NAMESPACE",
    "ORIGINAL_ENTITY_EXISTENCE", "LOADER_JOIN", "RUNTIME_FONT_SELECTION",
    "SEMANTIC_ROLE", "SEMANTIC_IDENTITY_AND_RUNTIME_REACHABILITY",
    "MEMBER_LEVEL_SEMANTICS", *STATE_NAMES,
)

COVERAGE_RULES = (
    "AUTHORITY_EVENT",
    "AUTHORITY_MUTATION",
    "ENTITY_ID_NAMESPACE",
    "ENTITY_PARENT",
    "ENTITY_VERTICAL_TRACE",
    "FEATURE_REACHABILITY_CLASSIFICATION",
    "FEATURE_REACHABILITY_LEDGER_ABSENT",
    "FEATURE_VERTICAL_TRACE",
    "FEATURE_VERTICAL_TRACE_CONTRACT",
    "FIELD_RECOVERY_DISPOSITION",
    "IMPLEMENTATION_TARGET_SET",
    "IMPLEMENTATION_TARGET_STATUS",
    "POPULATION_RECOVERY_DISPOSITION",
    "PROTOCOL_DIRECTION",
    "PROTOCOL_DIRECTION_DISPOSITION_MISSING",
    "PROTOCOL_OWNERSHIP_DISPOSITION_MISSING",
    "RECOVERY_DISPOSITION",
    "RESOURCE_LOADER",
    "RESOURCE_OWNER",
    "CONTENT_VERTICAL_TRACE",
    "SOURCE_ROW_CONSERVATION",
    "STATE_CONTRACT",
    "UI_HANDLER",
    "UI_HANDLER_DISPOSITION_MISSING",
    "WRITER_INPUT_SOURCE",
)
POLICY = MappingProxyType(
    {
        "version": POLICY_VERSION,
        "rules": COVERAGE_RULES,
        "rulesSha256": hashlib.sha256(
            canonical_json(COVERAGE_RULES).encode("utf-8")
        ).hexdigest().upper(),
        "candidateEvidenceClosesBoundary": False,
        "laterStatePromotesEarlierState": False,
        "verticalPaths": VERTICAL_PATHS,
        "boundaryOrder": BOUNDARY_ORDER,
    }
)


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


def _evidence(value: Any, fallback: Iterable[str]) -> tuple[str, ...]:
    if isinstance(value, (list, tuple)):
        items = tuple(sorted({item for item in value if isinstance(item, str) and item.strip()}))
        if items:
            return items
    items = tuple(sorted({item for item in fallback if isinstance(item, str) and item.strip()}))
    return items or ("coverage:audit:no-row-evidence",)


def _status(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, Mapping) and isinstance(value.get("status"), str):
        return str(value["status"])
    return None


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


@dataclass(frozen=True)
class CoverageGap:
    rule_id: str
    verdict: str
    first_missing_boundary: str
    evidence: tuple[str, ...]
    detail: str

    def __post_init__(self) -> None:
        if self.verdict not in VERDICTS - {"PASS"}:
            raise ValueError("coverage gap verdict mismatch")
        for name, value in (
            ("rule_id", self.rule_id),
            ("first_missing_boundary", self.first_missing_boundary),
            ("detail", self.detail),
        ):
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"coverage gap {name} must be text")
        object.__setattr__(self, "evidence", _evidence(self.evidence, ()))


@dataclass(frozen=True)
class CoverageFatal:
    rule_id: str
    row_key: str | None
    path: str
    evidence: tuple[str, ...]
    detail: str

    def __post_init__(self) -> None:
        for name, value in (("rule_id", self.rule_id), ("path", self.path), ("detail", self.detail)):
            if not isinstance(value, str) or not value.strip():
                raise ValueError(f"coverage fatal {name} must be text")
        object.__setattr__(self, "evidence", _evidence(self.evidence, ()))


@dataclass(frozen=True)
class CoverageRow:
    row_key: str
    inventory: str
    reachability: str
    recovery_disposition: str | None
    implementation_disposition: Mapping[str, Any]
    states: Mapping[str, bool]
    verdict: str
    first_missing_boundary: str | None
    all_missing_boundaries: tuple[str, ...]
    gaps: tuple[CoverageGap, ...]
    fatals: tuple[CoverageFatal, ...]

    def __post_init__(self) -> None:
        if self.verdict not in VERDICTS:
            raise ValueError("coverage row verdict mismatch")
        object.__setattr__(self, "implementation_disposition", freeze_json(self.implementation_disposition))
        object.__setattr__(self, "states", freeze_json(self.states))
        object.__setattr__(self, "all_missing_boundaries", tuple(self.all_missing_boundaries))
        object.__setattr__(self, "gaps", tuple(self.gaps))
        object.__setattr__(self, "fatals", tuple(self.fatals))


@dataclass(frozen=True)
class CoverageReport:
    graph_binding: Mapping[str, Any]
    rows: tuple[CoverageRow, ...]
    global_fatals: tuple[CoverageFatal, ...]
    conservation: Mapping[str, Any]
    row_results_sha256: str = field(init=False)
    fatal_surface_sha256: str = field(init=False)
    coverage_surface_sha256: str = field(init=False)

    def __post_init__(self) -> None:
        rows = tuple(sorted(self.rows, key=lambda item: (item.row_key.casefold(), item.row_key)))
        global_fatals = tuple(
            sorted(self.global_fatals, key=lambda item: (item.rule_id, item.path, item.detail))
        )
        object.__setattr__(self, "graph_binding", freeze_json(self.graph_binding))
        object.__setattr__(self, "rows", rows)
        object.__setattr__(self, "global_fatals", global_fatals)
        object.__setattr__(self, "conservation", freeze_json(self.conservation))
        row_hash = _sha([_row_payload(item) for item in rows])
        fatal_hash = _sha([_fatal_payload(item) for item in global_fatals])
        object.__setattr__(self, "row_results_sha256", row_hash)
        object.__setattr__(self, "fatal_surface_sha256", fatal_hash)
        surface = {
            "schemaVersion": SCHEMA_VERSION,
            "policy": _plain(POLICY),
            "graphBinding": _plain(self.graph_binding),
            "conservation": _plain(self.conservation),
            "rowResultsSha256": row_hash,
            "fatalSurfaceSha256": fatal_hash,
        }
        object.__setattr__(self, "coverage_surface_sha256", _sha(surface))

    @property
    def fatals(self) -> tuple[CoverageFatal, ...]:
        """Return global and row-scoped structural failures as one stable surface."""

        return tuple(
            sorted(
                (*self.global_fatals, *(fatal for row in self.rows for fatal in row.fatals)),
                key=lambda item: (item.rule_id, item.row_key or "", item.path, item.detail),
            )
        )

    @property
    def gaps(self) -> tuple[CoverageGap, ...]:
        """Return every evidence gap without changing its owning row or verdict."""

        return tuple(
            gap
            for row in self.rows
            for gap in row.gaps
        )


def _gap_payload(item: CoverageGap) -> dict[str, Any]:
    return {
        "ruleId": item.rule_id,
        "verdict": item.verdict,
        "firstMissingBoundary": item.first_missing_boundary,
        "evidence": list(item.evidence),
        "detail": item.detail,
    }


def _fatal_payload(item: CoverageFatal) -> dict[str, Any]:
    return {
        "ruleId": item.rule_id,
        "rowKey": item.row_key,
        "path": item.path,
        "evidence": list(item.evidence),
        "detail": item.detail,
    }


def _row_payload(item: CoverageRow) -> dict[str, Any]:
    return {
        "rowKey": item.row_key,
        "inventory": item.inventory,
        "reachability": item.reachability,
        "recoveryDisposition": item.recovery_disposition,
        "implementationDisposition": _plain(item.implementation_disposition),
        "states": _plain(item.states),
        "verdict": item.verdict,
        "firstMissingBoundary": item.first_missing_boundary,
        "allMissingBoundaries": list(item.all_missing_boundaries),
        "gaps": [_gap_payload(value) for value in item.gaps],
        "fatals": [_fatal_payload(value) for value in item.fatals],
    }


def _graph_binding(graph: Any, bundle: Any, rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    return {
        "bundleSha256": getattr(bundle, "bundle_sha256", None),
        "sourceManifestSha256": getattr(bundle, "source_manifest_sha256", None),
        "clientSha256": getattr(bundle, "client_sha256", None),
        "messageDataSha256": getattr(bundle, "message_data_sha256", None),
        "nodesSha256": getattr(graph, "nodes_sha256", None),
        "edgesSha256": getattr(graph, "edges_sha256", None),
        "graphSurfaceSha256": getattr(graph, "graph_surface_sha256", None),
        "sourceRowCount": len(rows),
        "sourceRowsSha256": _sha(
            sorted((_plain(row) for row in rows), key=lambda row: (row["key"].casefold(), row["key"]))
        ),
    }


def _source_rows(graph: Any) -> tuple[Mapping[str, Any], ...]:
    supplied = getattr(graph, "source_rows", None)
    if supplied is not None:
        return tuple(supplied)
    rows: list[Mapping[str, Any]] = []
    for node in getattr(graph, "nodes", ()):
        if getattr(node, "kind", None) != "INVENTORY_ROW":
            continue
        attributes = _plain(getattr(node, "attributes", {}))
        rows.append(
            {
                "key": node.key,
                "name": node.label,
                "inventory": attributes.get("inventory"),
                "rowKind": attributes.get("rowKind"),
                "provenance": node.provenance,
                "reachability": attributes.get("reachability"),
                "recoveryDisposition": attributes.get("recoveryDisposition"),
                "implementationDisposition": attributes.get("implementationDisposition"),
                "states": attributes.get("states", {}),
                "firstMissingBoundary": attributes.get("firstMissingBoundary"),
                "evidence": list(node.evidence),
            }
        )
    return tuple(rows)


def _is_proven(section: Any, *, functions: bool = False) -> bool:
    if not isinstance(section, Mapping):
        return False
    status = _status(section)
    if status not in {"PROVEN", "OBSERVED", "BOUND_CONSUMER", "WRITER_PROVEN"}:
        return False
    evidence = section.get("evidence")
    if not isinstance(evidence, (list, tuple)) or not any(
        isinstance(item, str) and item.strip() for item in evidence
    ):
        return False
    if functions and not section.get("functions"):
        return False
    return True


def _input_sources_resolve(value: Any, known_source_ids: frozenset[str]) -> bool:
    if not isinstance(value, (list, tuple)) or not value:
        return False
    identities: list[str] = []
    for item in value:
        if isinstance(item, str) and item.strip():
            identities.append(item)
        elif (
            isinstance(item, Mapping)
            and isinstance(item.get("identity"), str)
            and item["identity"].strip()
        ):
            identities.append(item["identity"])
        else:
            return False
    return bool(identities) and all(identity in known_source_ids for identity in identities)


def _is_not_applicable(section: Any) -> bool:
    status = _status(section)
    return bool(
        isinstance(section, Mapping)
        and status
        and status.startswith("NOT_APPLICABLE")
        and isinstance(section.get("reason"), str)
        and section["reason"].strip()
    )


def _has_evidence(section: Any) -> bool:
    evidence = section.get("evidence") if isinstance(section, Mapping) else None
    return bool(
        isinstance(evidence, (list, tuple))
        and any(isinstance(item, str) and item.strip() for item in evidence)
    )


def _is_not_applicable_status(section: Any) -> bool:
    status = _status(section)
    return bool(status and status.startswith("NOT_APPLICABLE"))


def _audit_vertical_path(
    row: Mapping[str, Any],
    *,
    path_name: str,
    field_name: str,
    boundaries: Sequence[str],
    required_contract: bool,
    fatal: Any,
    gap: Any,
) -> None:
    trace = row.get(field_name)
    if required_contract and (
        not isinstance(trace, Mapping) or set(trace) != set(boundaries)
    ):
        fatal(
            "FEATURE_VERTICAL_TRACE_CONTRACT",
            field_name,
            "feature vertical trace must contain every ordered boundary exactly once",
            row.get("evidence"),
        )
    trace_map = trace if isinstance(trace, Mapping) else {}
    for boundary in boundaries:
        section = trace_map.get(boundary)
        if _is_proven(section) or _is_not_applicable(section):
            continue
        if _is_not_applicable_status(section):
            fatal(
                f"{path_name}_VERTICAL_TRACE_CONTRACT",
                f"{field_name}.{boundary}",
                "NOT_APPLICABLE vertical boundary requires reason and evidence",
                section.get("evidence") if isinstance(section, Mapping) else None,
            )
        gap(
            f"{path_name}_VERTICAL_TRACE",
            boundary,
            f"{path_name.lower()} vertical boundary is not proven",
            section.get("evidence") if isinstance(section, Mapping) else row.get("evidence"),
            "UNKNOWN",
        )


def _audit_row(row: Mapping[str, Any], *, known_source_ids: frozenset[str]) -> CoverageRow:
    key = str(row.get("key", ""))
    inventory = str(row.get("inventory", ""))
    row_evidence = _evidence(row.get("evidence"), (f"inventory:{key}",))
    fatals: list[CoverageFatal] = []
    gaps: list[CoverageGap] = []

    def fatal(rule: str, path: str, detail: str, evidence: Any = None) -> None:
        fatals.append(CoverageFatal(rule, key or None, path, _evidence(evidence, row_evidence), detail))

    def gap(rule: str, boundary: str, detail: str, evidence: Any = None, verdict: str = "PARTIAL") -> None:
        gaps.append(CoverageGap(rule, verdict, boundary, _evidence(evidence, row_evidence), detail))

    reachability = row.get("reachability")
    if reachability not in REACHABILITY_VALUES:
        fatal("FEATURE_REACHABILITY_CLASSIFICATION", "reachability", "row lacks a supported reachability disposition")
        reachability_text = "UNKNOWN"
    else:
        reachability_text = str(reachability)
    if reachability_text == "UNKNOWN" and (
        row.get("rowKind") == "FEATURE" or row.get("provenance") == "ORIGINAL_MANUAL"
    ):
        gap(
            "FEATURE_REACHABILITY_CLASSIFICATION",
            "FEATURE_REACHABILITY_CLASSIFICATION",
            "feature reachability remains explicitly unknown",
            row.get("reachabilityEvidence"),
            "UNKNOWN",
        )

    recovery = row.get("recoveryDisposition")
    if recovery not in RECOVERY_DISPOSITIONS:
        fatal("RECOVERY_DISPOSITION", "recoveryDisposition", "row lacks exactly one supported recovery disposition")
        recovery_text: str | None = None
    else:
        recovery_text = str(recovery)

    implementation = row.get("implementationDisposition")
    implementation_map = _plain(implementation) if isinstance(implementation, Mapping) else {}
    if set(implementation_map) != set(IMPLEMENTATION_TARGETS):
        fatal(
            "IMPLEMENTATION_TARGET_SET",
            "implementationDisposition",
            "implementation disposition must contain every target exactly once",
        )
    for target, section in implementation_map.items():
        if not isinstance(section, Mapping) or section.get("status") not in IMPLEMENTATION_STATUSES:
            fatal(
                "IMPLEMENTATION_TARGET_STATUS",
                f"implementationDisposition.{target}",
                "implementation target status must be REQUIRED or NOT_APPLICABLE",
            )
        elif section.get("status") == "NOT_APPLICABLE" and not (
            isinstance(section.get("reason"), str) and section["reason"].strip()
            and isinstance(section.get("evidence"), (list, tuple))
            and any(isinstance(item, str) and item.strip() for item in section["evidence"])
        ):
            fatal(
                "IMPLEMENTATION_TARGET_STATUS",
                f"implementationDisposition.{target}.reason",
                "NOT_APPLICABLE requires a non-empty reason and evidence",
            )

    if row.get("rowKind") == "FEATURE":
        _audit_vertical_path(
            row,
            path_name="FEATURE",
            field_name="featureTrace",
            boundaries=FEATURE_TRACE_BOUNDARIES,
            required_contract=True,
            fatal=fatal,
            gap=gap,
        )

    supplied_states = row.get("states")
    states = dict(supplied_states) if isinstance(supplied_states, Mapping) else {}
    if set(states) != set(STATE_NAMES) or any(type(value) is not bool for value in states.values()):
        fatal("STATE_CONTRACT", "states", "row must carry all nine independent boolean evidence states")
    report_states = {name: states.get(name, False) for name in STATE_NAMES}

    if inventory == "PROTOCOL":
        direction_section = row.get("direction")
        direction = _status(direction_section)
        if direction is None:
            fatal(
                "PROTOCOL_DIRECTION_DISPOSITION_MISSING",
                "direction",
                "protocol row lacks an explicit direction disposition",
            )
        elif direction == "UNKNOWN":
            gap("PROTOCOL_DIRECTION", "PROTOCOL_DIRECTION", "protocol direction remains unknown", direction_section.get("evidence") if isinstance(direction_section, Mapping) else None, "UNKNOWN")
        elif direction not in {"CLIENT_TO_SERVER", "SERVER_TO_CLIENT", "BIDIRECTIONAL"}:
            fatal("PROTOCOL_DIRECTION_DISPOSITION_MISSING", "direction.status", "protocol direction is unsupported")
        ownership = row.get("ownership")
        required_roles = {
            "CLIENT_TO_SERVER": ("serializer",),
            "SERVER_TO_CLIENT": ("parser", "dispatcher"),
            "BIDIRECTIONAL": ("serializer", "parser", "dispatcher"),
        }.get(direction, ())
        for role in required_roles:
            section = ownership.get(role) if isinstance(ownership, Mapping) else None
            if section is None:
                fatal(
                    "PROTOCOL_OWNERSHIP_DISPOSITION_MISSING",
                    f"ownership.{role}",
                    f"protocol row lacks explicit {role} ownership disposition",
                )
            elif not _is_proven(section, functions=True):
                gap(
                    "PROTOCOL_DIRECTION",
                    {"serializer": "CLIENT_SERIALIZER", "parser": "CLIENT_PARSER", "dispatcher": "CLIENT_DISPATCHER"}[role],
                    f"applicable {role} ownership is not proven",
                    section.get("evidence"),
                )

    if inventory == "UI" and (
        row.get("interactionKind") == "INTERACTIVE" or row.get("rowKind") == "MENU_ROW"
    ):
        handler = row.get("handler")
        if not isinstance(handler, Mapping) or _status(handler) is None:
            fatal("UI_HANDLER_DISPOSITION_MISSING", "handler", "interactive UI row lacks handler disposition")
        elif not _is_proven(handler, functions=True) and not (
            reachability_text == "SHIPPED_DORMANT" or _is_not_applicable(handler)
        ):
            gap("UI_HANDLER", "UI_HANDLER", "interactive UI handler is not proven", handler.get("evidence"), "UNKNOWN" if _status(handler) == "UNKNOWN" else "PARTIAL")

    if inventory == "ENTITY":
        namespace = row.get("idNamespace")
        if not isinstance(namespace, Mapping) or _status(namespace) is None:
            fatal("ENTITY_ID_NAMESPACE", "idNamespace", "entity row lacks ID namespace disposition")
        elif not _is_proven(namespace) and not _is_not_applicable(namespace):
            gap("ENTITY_ID_NAMESPACE", "ENTITY_ID_NAMESPACE", "entity ID namespace is not proven", namespace.get("evidence"), "UNKNOWN")
        relations = row.get("relations")
        parent = relations.get("parent") if isinstance(relations, Mapping) else None
        if not isinstance(parent, Mapping) or _status(parent) is None:
            fatal("ENTITY_PARENT", "relations.parent", "entity parent relation lacks explicit disposition")
        elif not _is_proven(parent) and not _is_not_applicable(parent):
            if _is_not_applicable_status(parent):
                fatal("ENTITY_PARENT", "relations.parent", "NOT_APPLICABLE parent requires reason and evidence", parent.get("evidence"))
            else:
                gap("ENTITY_PARENT", "ENTITY_PARENT", "entity parent relation is unresolved", parent.get("evidence"), "UNKNOWN")
        layout = row.get("layout")
        fields = layout.get("fields", []) if isinstance(layout, Mapping) else []
        for index, child in enumerate(fields):
            if not isinstance(child, Mapping) or child.get("recoveryDisposition") not in RECOVERY_DISPOSITIONS:
                fatal(
                    "FIELD_RECOVERY_DISPOSITION",
                    f"layout.fields[{index}].recoveryDisposition",
                    "entity field lacks its own recovery disposition",
                    child.get("evidence") if isinstance(child, Mapping) else None,
                )
        populations = row.get("catalogCardinality", [])
        if isinstance(populations, (list, tuple)):
            for index, child in enumerate(populations):
                if not isinstance(child, Mapping) or child.get("recoveryDisposition") not in RECOVERY_DISPOSITIONS:
                    fatal(
                        "POPULATION_RECOVERY_DISPOSITION",
                        f"catalogCardinality[{index}].recoveryDisposition",
                        "entity population claim lacks its own recovery disposition",
                        child.get("evidence") if isinstance(child, Mapping) else None,
                    )
        _audit_vertical_path(
            row, path_name="ENTITY", field_name="entityTrace",
            boundaries=ENTITY_TRACE_BOUNDARIES, required_contract=False,
            fatal=fatal, gap=gap,
        )

    if inventory == "FUNCTION":
        global_fields = row.get("globalStructureFields")
        writes = global_fields.get("writes", []) if isinstance(global_fields, Mapping) else []
        if writes:
            inputs = row.get("inputsOutputs")
            if not isinstance(inputs, Mapping) or _status(inputs) is None:
                fatal("WRITER_INPUT_SOURCE", "inputsOutputs", "state writer lacks input-source disposition")
            elif _is_proven(inputs) and not _input_sources_resolve(
                inputs.get("inputSources"), known_source_ids
            ):
                fatal("WRITER_INPUT_SOURCE", "inputsOutputs.inputSources", "proven writer I/O lacks an explicit input-source identity", inputs.get("evidence"))
            elif not _is_proven(inputs):
                gap("WRITER_INPUT_SOURCE", "WRITER_INPUT_SOURCE", "state writer input source is unresolved", inputs.get("evidence"), "UNKNOWN")

    if inventory == "RESOURCE":
        loader = row.get("loader")
        if not isinstance(loader, Mapping) or _status(loader) is None:
            fatal("RESOURCE_LOADER", "loader", "resource lacks loader disposition")
        elif _is_not_applicable(loader) and _has_evidence(loader):
            pass
        elif _is_not_applicable_status(loader):
            fatal(
                "RESOURCE_LOADER",
                "loader",
                "NOT_APPLICABLE resource loader requires reason and evidence",
                loader.get("evidence"),
            )
        elif not (
            _is_proven(loader, functions=True)
            or (
                _is_proven(loader)
                and loader.get("kind") == "EXTERNAL_PE_CONFIG_ACCESS"
                and isinstance(loader.get("consumerRowKey"), str)
                and loader.get("consumerRowKey")
                and set(loader.get("operations", [])) == {"READ", "WRITE"}
                and bool(loader.get("api"))
            )
        ):
            gap("RESOURCE_LOADER", "RESOURCE_LOADER", "resource loader/runtime binding is not proven", loader.get("evidence"), "UNKNOWN" if _status(loader) == "UNKNOWN" else "PARTIAL")
        owner = row.get("owner")
        if not isinstance(owner, Mapping) or _status(owner) is None:
            fatal("RESOURCE_OWNER", "owner", "resource lacks runtime-owner disposition")
        elif not _is_proven(owner) and not _is_not_applicable(owner):
            gap("RESOURCE_OWNER", "RESOURCE_OWNER", "resource runtime owner is not proven", owner.get("evidence"), "UNKNOWN")
        _audit_vertical_path(
            row, path_name="CONTENT", field_name="contentTrace",
            boundaries=CONTENT_TRACE_BOUNDARIES, required_contract=False,
            fatal=fatal, gap=gap,
        )

    if inventory == "AUTHORITY":
        sections = row.get("sections")
        if not isinstance(sections, Mapping):
            fatal("AUTHORITY_MUTATION", "sections", "authority row lacks typed section dispositions")
        else:
            for section_name, boundary, rule in (
                ("mutation", "AUTHORITY_MUTATION", "AUTHORITY_MUTATION"),
                ("event", "AUTHORITY_EVENT", "AUTHORITY_EVENT"),
                ("persistence", "PERSISTENCE", "AUTHORITY_EVENT"),
            ):
                section = sections.get(section_name)
                if not isinstance(section, Mapping) or _status(section) is None:
                    fatal(rule, f"sections.{section_name}", f"authority {section_name} lacks explicit disposition")
                elif not _is_not_applicable(section) and not _is_proven(section):
                    if _is_not_applicable_status(section):
                        fatal(rule, f"sections.{section_name}", f"NOT_APPLICABLE {section_name} requires reason and evidence", section.get("evidence"))
                    else:
                        gap(rule, boundary, f"authority {section_name} path is unresolved", section.get("evidence"), "BLOCKED" if "MISSING" in str(_status(section)) else "UNKNOWN")

    missing: list[str] = []
    missing.extend(item.first_missing_boundary for item in gaps)
    missing.extend(name for name in STATE_NAMES if not report_states[name])
    unique_missing = tuple(dict.fromkeys(missing))
    rank = {name: index for index, name in enumerate(BOUNDARY_ORDER)}
    all_missing = tuple(sorted(unique_missing, key=lambda name: (rank.get(name, len(rank)), name)))
    first_missing = all_missing[0] if all_missing else None
    if fatals:
        verdict = "BLOCKED"
    elif not all_missing:
        verdict = "PASS"
    elif reachability_text == "UNKNOWN":
        verdict = "UNKNOWN"
    elif not report_states["ENUMERATED"]:
        verdict = "UNSEEN"
    else:
        verdict = "PARTIAL"
    return CoverageRow(
        row_key=key,
        inventory=inventory,
        reachability=reachability_text,
        recovery_disposition=recovery_text,
        implementation_disposition=implementation_map,
        states=report_states,
        verdict=verdict,
        first_missing_boundary=first_missing,
        all_missing_boundaries=all_missing,
        gaps=tuple(sorted(gaps, key=lambda item: (item.first_missing_boundary, item.rule_id, item.detail))),
        fatals=tuple(sorted(fatals, key=lambda item: (item.rule_id, item.path, item.detail))),
    )


def audit_graph(graph: Any, *, bundle: Any | None = None) -> CoverageReport:
    """Audit every immutable source row without promoting any evidence state."""

    rows = _source_rows(graph)
    keys = [row.get("key") for row in rows]
    if any(not isinstance(key, str) or not key.strip() for key in keys):
        raise ValueError("coverage source rows require stable keys")
    folded = [str(key).casefold() for key in keys]
    if len(folded) != len(set(folded)):
        raise ValueError("coverage source rows contain duplicate/case-colliding keys")
    expected = getattr(graph, "conservation", {}).get("sourceRowNodes", len(rows))
    global_fatals: list[CoverageFatal] = []
    if expected != len(rows):
        global_fatals.append(
            CoverageFatal(
                "SOURCE_ROW_CONSERVATION",
                None,
                "graph.conservation.sourceRowNodes",
                ("graph:conservation",),
                f"graph declares {expected} source rows but audit received {len(rows)}",
            )
        )
    if not any(row.get("rowKind") == "FEATURE" for row in rows):
        global_fatals.append(
            CoverageFatal(
                "FEATURE_REACHABILITY_LEDGER_ABSENT",
                None,
                "sourceRows[rowKind=FEATURE]",
                ("coverage:feature-ledger",),
                "no FEATURE rows bind the manual/shipped reachability ledger",
            )
        )
    known_source_ids = frozenset(
        {
            *(str(key) for key in keys),
            *(
                str(node.key)
                for node in getattr(graph, "nodes", ())
                if isinstance(getattr(node, "key", None), str)
            ),
        }
    )
    audited = tuple(_audit_row(row, known_source_ids=known_source_ids) for row in rows)
    fatal_count = sum(len(row.fatals) for row in audited) + len(global_fatals)
    gap_count = sum(len(row.gaps) for row in audited)
    verdict_counts: dict[str, int] = {}
    inventory_counts: dict[str, int] = {}
    boundary_counts: dict[str, int] = {}
    for row in audited:
        verdict_counts[row.verdict] = verdict_counts.get(row.verdict, 0) + 1
        inventory_counts[row.inventory] = inventory_counts.get(row.inventory, 0) + 1
        for boundary in row.all_missing_boundaries:
            boundary_counts[boundary] = boundary_counts.get(boundary, 0) + 1
    conservation = {
        "sourceRowCount": len(rows),
        "auditedRowCount": len(audited),
        "missingRowCount": max(0, int(expected) - len(audited)),
        "extraRowCount": max(0, len(audited) - int(expected)),
        "duplicateRowCount": 0,
        "fatalStructuralCount": fatal_count,
        "evidenceGapCount": gap_count,
        "closedVerticalTraceCount": sum(not row.all_missing_boundaries and not row.fatals for row in audited),
        "rowCountByInventory": dict(sorted(inventory_counts.items())),
        "rowCountByVerdict": dict(sorted(verdict_counts.items())),
        "missingBoundaryCount": sum(len(row.all_missing_boundaries) for row in audited),
        "missingBoundaryCountByBoundary": dict(sorted(boundary_counts.items())),
    }
    return CoverageReport(
        graph_binding=_graph_binding(graph, bundle, rows),
        rows=audited,
        global_fatals=tuple(global_fatals),
        conservation=conservation,
    )


def _report_payload(report: CoverageReport) -> dict[str, Any]:
    return {
        "recordType": "COVERAGE_REPORT",
        "schemaVersion": SCHEMA_VERSION,
        "policy": _plain(POLICY),
        "graphBinding": _plain(report.graph_binding),
        "conservation": _plain(report.conservation),
        "rowResults": [_row_payload(item) for item in report.rows],
        "globalFatals": [_fatal_payload(item) for item in report.global_fatals],
        "rowResultsSha256": report.row_results_sha256,
        "fatalSurfaceSha256": report.fatal_surface_sha256,
        "coverageSurfaceSha256": report.coverage_surface_sha256,
    }


def coverage_json(report: CoverageReport, *, graph: Any, bundle: Any) -> str:
    """Serialize a deterministic report only for its exact current graph and bundle."""

    rows = _source_rows(graph)
    if _plain(report.graph_binding) != _graph_binding(graph, bundle, rows):
        raise ValueError("coverage report graph/bundle binding mismatch")
    expected = audit_graph(graph, bundle=bundle)
    if _report_payload(report) != _report_payload(expected):
        raise ValueError("coverage report differs from current deterministic audit")
    return canonical_json(_report_payload(report))


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate coverage JSON key: {key}")
        result[key] = value
    return result


def load_coverage_json(path: str | Path, *, graph: Any, bundle: Any) -> CoverageReport:
    """Load and independently reproduce a canonical coverage report."""

    data = Path(path).read_bytes()
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError("coverage JSON must be canonical UTF-8 LF JSON")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("coverage JSON is invalid") from error
    if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != data:
        raise ValueError("coverage JSON is not canonical")
    expected = audit_graph(graph, bundle=bundle)
    expected_payload = _report_payload(expected)
    if value.get("recordType") != "COVERAGE_REPORT" or value.get("schemaVersion") != SCHEMA_VERSION:
        raise ValueError("coverage schema mismatch")
    if value.get("graphBinding") != expected_payload["graphBinding"]:
        raise ValueError("coverage graph/bundle binding mismatch")
    if value.get("policy") != expected_payload["policy"]:
        raise ValueError("coverage policy mismatch")
    if value != expected_payload:
        raise ValueError("coverage hash, conservation, or row surface mismatch")
    return expected


__all__ = [
    "COVERAGE_RULES",
    "IMPLEMENTATION_TARGETS",
    "CoverageFatal",
    "CoverageGap",
    "CoverageReport",
    "CoverageRow",
    "audit_graph",
    "coverage_json",
    "load_coverage_json",
]
