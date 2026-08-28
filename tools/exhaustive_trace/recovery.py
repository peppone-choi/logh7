"""Deterministic recovery and authoring ledger for exhaustive-trace subjects."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from types import MappingProxyType
from typing import Any, Mapping, Sequence

from .coverage import CoverageReport, load_coverage_json
from .domains import load_domain_config, load_domain_packages
from .graph import load_graph_jsonl
from .inventories import load_inventory_bundle
from .io import canonical_json
from .model import ALLOWED_PROVENANCE, RecoveryDisposition, freeze_json


SCHEMA_VERSION = 1
POLICY_VERSION = "TASK13-1"
DISPOSITIONS = frozenset(item.value for item in RecoveryDisposition)
RESEARCH_STAGES = (
    "GENERAL_WEB",
    "JAPANESE_WEB",
    "ORIGINAL_OFFICIAL_MANUAL_RUNTIME",
    "USER_ADJUDICATION",
    "AUTHORED_REPLACEMENT",
)
VERDICTS = frozenset({"PASS", "PARTIAL", "UNSEEN", "BLOCKED", "UNKNOWN"})
AUTHORING_PROVENANCE = frozenset({"NEW_DESIGN", "AUTHORED_PLACEHOLDER"})
ORIGINAL_PROVENANCE = frozenset({"ORIGINAL_OBSERVED", "ORIGINAL_MANUAL"})
RESEARCH_STATUSES = frozenset(
    {"NOT_ATTEMPTED", "NO_RESULT", "EVIDENCE_FOUND", "BLOCKED", "NOT_APPLICABLE"}
)
MANDATORY_DATASETS = MappingProxyType(
    {
        "originalConfirmedCharacters": "RECOVERABLE_STATIC",
        "canonCandidateCharacters": "RECOVERABLE_STATIC",
        "authoredPlayableCharacters": "AUTHORING_REQUIRED",
    }
)


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


def _text(value: Any, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must be non-empty text")
    return value


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate recovery JSON key: {key}")
        result[key] = value
    return result


def _is_link_or_reparse(path: Path) -> bool:
    try:
        stat = path.lstat()
    except OSError:
        return False
    return path.is_symlink() or bool(getattr(stat, "st_file_attributes", 0) & 0x400)


def _reject_link_chain(path: Path, label: str) -> None:
    for component in (*reversed(path.parents), path):
        if component == Path(component.anchor):
            continue
        if (component.exists() or component.is_symlink()) and _is_link_or_reparse(component):
            raise ValueError(f"{label} contains a link or reparse point: {component}")


def _research_history(evidence: Sequence[str], *, reason: str) -> tuple[Mapping[str, Any], ...]:
    del evidence
    values = []
    for ordinal, stage in enumerate(RESEARCH_STAGES, 1):
        values.append(
            {
                "ordinal": ordinal,
                "stage": stage,
                "status": "NOT_ATTEMPTED",
                "scope": "original VII value, rule, population, or replacement boundary",
                "query": None,
                "performedAt": None,
                "evidence": [],
                "outcome": "PENDING",
                "reason": f"{reason}; no subject-specific completed search receipt is claimed",
            }
        )
    return tuple(values)


def _falsifier(disposition: str, subject_key: str) -> Mapping[str, Any] | None:
    if disposition == "RECOVERABLE_STATIC":
        return {
            "condition": f"A complete hash-bound static extraction proves {subject_key} cannot be recovered from shipped artifacts or consumers.",
            "evidenceRequired": ["extractor-output", "source-hash", "consumer-or-no-consumer-result"],
            "verifierArgv": ["python", "-m", "unittest", "tests.tools.exhaustive_trace.test_recovery"],
        }
    if disposition == "RECOVERABLE_LIVE":
        return {
            "condition": f"One approved fresh-identity observation fails to expose {subject_key} at the declared boundary.",
            "evidenceRequired": ["fresh-pid-hwnd", "single-run-receipt", "read-only-capture"],
            "verifierArgv": ["python", "-m", "unittest", "tests.tools.exhaustive_trace.test_recovery"],
            "liveContract": {
                "maxSemanticPlayerActions": 1,
                "automaticRetry": False,
                "processMemoryWrite": False,
                "originalBinaryWrite": False,
                "vmLifecycleMutation": False,
            },
        }
    if disposition == "ORIGINAL_SERVER_LOST":
        return {
            "condition": "A hash-authenticated original populated server source or database is recovered.",
            "evidenceRequired": ["original-server-provenance", "content-hash", "ownership-proof"],
            "verifierArgv": ["python", "-m", "unittest", "tests.tools.exhaustive_trace.test_recovery"],
        }
    return None


def _editable_schema() -> Mapping[str, Any]:
    schema = {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "additionalProperties": False,
        "required": ["key", "fields", "approvalStatus"],
        "properties": {
            "key": {"type": "string", "minLength": 1},
            "fields": {
                "type": "object",
                "additionalProperties": {
                    "oneOf": [
                        {
                            "type": "object",
                            "additionalProperties": False,
                            "required": ["origin", "value", "confirmedFactRef", "evidenceRefs"],
                            "properties": {
                                "origin": {"enum": ["ORIGINAL_OBSERVED", "ORIGINAL_MANUAL"]},
                                "value": {},
                                "confirmedFactRef": {"type": "string", "minLength": 1},
                                "evidenceRefs": {"type": "array", "minItems": 1, "items": {"type": "string", "minLength": 1}},
                            },
                        },
                        {
                            "type": "object",
                            "additionalProperties": False,
                            "required": ["origin", "value"],
                            "properties": {
                                "origin": {"enum": ["NEW_DESIGN", "AUTHORED_PLACEHOLDER"]},
                                "value": {},
                            },
                        },
                    ]
                },
            },
            "approvalStatus": {"enum": ["DRAFT", "APPROVED", "REJECTED"]},
        },
    }
    return {"schemaVersion": 1, "inlineSchema": schema, "schemaSha256": _sha(schema)}


def validate_authored_record(value: Mapping[str, Any]) -> None:
    """Validate the field-level provenance boundary used by every authoring package."""

    if not isinstance(value, Mapping) or set(value) != {"key", "fields", "approvalStatus"}:
        raise ValueError("authored record schema mismatch")
    _text(value.get("key"), "authored record key")
    if value.get("approvalStatus") not in {"DRAFT", "APPROVED", "REJECTED"}:
        raise ValueError("authored record approval status mismatch")
    fields = value.get("fields")
    if not isinstance(fields, Mapping):
        raise ValueError("authored record fields must be an object")
    for field_name, item in fields.items():
        _text(field_name, "authored field name")
        if not isinstance(item, Mapping) or "origin" not in item or "value" not in item:
            raise ValueError("authored field schema mismatch")
        origin = item.get("origin")
        if origin in ORIGINAL_PROVENANCE:
            if set(item) != {"origin", "value", "confirmedFactRef", "evidenceRefs"}:
                raise ValueError("original authored field requires confirmed fact reference and evidence")
            _text(item.get("confirmedFactRef"), "confirmed fact reference")
            evidence = item.get("evidenceRefs")
            if not isinstance(evidence, list) or not evidence or any(not isinstance(ref, str) or not ref.strip() for ref in evidence):
                raise ValueError("original authored field requires confirmed fact reference and evidence")
        elif origin in AUTHORING_PROVENANCE:
            if set(item) != {"origin", "value"}:
                raise ValueError("authored field must not carry original fact metadata")
        else:
            raise ValueError("authored field origin mismatch")


def _owner(disposition: str) -> str:
    return {
        "RECOVERED_ORIGINAL": "INDEPENDENT_REVIEW",
        "RECOVERABLE_STATIC": "REVERSE_ENGINEERING",
        "RECOVERABLE_LIVE": "LIVE_ORACLE_VALIDATION",
        "SOURCE_CONFLICT": "SOURCE_ADJUDICATION",
        "ORIGINAL_SERVER_LOST": "SERVER_DESIGN",
        "ORIGINAL_UNIMPLEMENTED": "PRODUCT_DESIGN",
        "AUTHORING_REQUIRED": "CONTENT_ADMIN",
        "RIGHTS_REVIEW_REQUIRED": "RIGHTS_REVIEW",
    }[disposition]


@dataclass(frozen=True)
class RecoveryRow:
    key: str
    subject_kind: str
    source_row_key: str
    source_path: str
    domain: str
    disposition: str
    output_provenance: str
    evidence: tuple[str, ...]
    falsifier: Mapping[str, Any] | None
    research_history: tuple[Mapping[str, Any], ...]
    editable_schema: Mapping[str, Any] | None
    approval_owner: str | None
    implementation_owner: str
    coverage_gap_refs: tuple[str, ...] = ()
    missing_boundaries: tuple[str, ...] = ()
    adjudication_verdict: str = "UNSEEN"
    first_missing_adjudication_field: str = "EVIDENCE"
    conflict_claims: tuple[Mapping[str, Any], ...] = ()
    rights_review: Mapping[str, Any] | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "evidence", tuple(self.evidence))
        object.__setattr__(self, "falsifier", freeze_json(self.falsifier) if self.falsifier is not None else None)
        object.__setattr__(self, "research_history", tuple(freeze_json(item) for item in self.research_history))
        object.__setattr__(self, "editable_schema", freeze_json(self.editable_schema) if self.editable_schema is not None else None)
        object.__setattr__(self, "coverage_gap_refs", tuple(self.coverage_gap_refs))
        object.__setattr__(self, "missing_boundaries", tuple(self.missing_boundaries))
        object.__setattr__(self, "conflict_claims", tuple(freeze_json(item) for item in self.conflict_claims))
        object.__setattr__(self, "rights_review", freeze_json(self.rights_review) if self.rights_review is not None else None)

    def validate(self) -> None:
        for name, value in (
            ("recovery key", self.key),
            ("subject kind", self.subject_kind),
            ("source row key", self.source_row_key),
            ("domain", self.domain),
            ("implementation owner", self.implementation_owner),
            ("first missing adjudication field", self.first_missing_adjudication_field),
        ):
            _text(value, name)
        if self.disposition not in DISPOSITIONS:
            raise ValueError("recovery row must have exactly one supported disposition")
        if self.output_provenance not in ALLOWED_PROVENANCE:
            raise ValueError("unsupported recovery output provenance")
        if self.adjudication_verdict not in VERDICTS:
            raise ValueError("unsupported recovery adjudication verdict")
        if self.disposition in {"RECOVERABLE_STATIC", "RECOVERABLE_LIVE"} and (
            not self.evidence
            or any(not isinstance(item, str) or not item.strip() for item in self.evidence)
            or self.falsifier is None
        ):
            raise ValueError("recoverable claim requires evidence and falsifier")
        if not self.evidence or any(not isinstance(item, str) or not item.strip() for item in self.evidence):
            raise ValueError("recovery row evidence must contain non-empty references")
        if self.disposition == "RECOVERABLE_LIVE":
            live = self.falsifier.get("liveContract") if isinstance(self.falsifier, Mapping) else None
            if not isinstance(live, Mapping) or live.get("maxSemanticPlayerActions") != 1 or live.get("automaticRetry") is not False:
                raise ValueError("live recovery requires one-action no-retry contract")
        if self.disposition == "AUTHORING_REQUIRED" and self.output_provenance in ORIGINAL_PROVENANCE:
            raise ValueError("authored value cannot be presented as original")
        if self.disposition in {"SOURCE_CONFLICT", "ORIGINAL_SERVER_LOST", "AUTHORING_REQUIRED"}:
            stages = tuple(item.get("stage") for item in self.research_history if isinstance(item, Mapping))
            if stages != RESEARCH_STAGES:
                raise ValueError("recovery research history must preserve exact ordered stages")
            for ordinal, item in enumerate(self.research_history, 1):
                if set(item) != {
                    "ordinal", "stage", "status", "scope", "query", "performedAt",
                    "evidence", "outcome", "reason",
                } or item.get("ordinal") != ordinal or item.get("status") not in RESEARCH_STATUSES:
                    raise ValueError("recovery research history entry mismatch")
                _text(item.get("scope"), "research scope")
                _text(item.get("outcome"), "research outcome")
                _text(item.get("reason"), "research reason")
                if item.get("status") in {"NO_RESULT", "EVIDENCE_FOUND"} and not item.get("evidence"):
                    raise ValueError("performed research stage requires receipt evidence")
        if self.disposition == "RECOVERED_ORIGINAL" and self.output_provenance not in ORIGINAL_PROVENANCE:
            raise ValueError("recovered original requires original output provenance")
        if self.disposition == "SOURCE_CONFLICT":
            values = []
            for claim in self.conflict_claims:
                if not isinstance(claim, Mapping) or "value" not in claim or not claim.get("evidence"):
                    raise ValueError("source conflict requires typed claims with evidence")
                values.append(canonical_json(_plain(claim["value"])))
            if len(values) < 2 or len(set(values)) < 2:
                raise ValueError("source conflict requires at least two disagreeing claims")
        if self.disposition == "AUTHORING_REQUIRED":
            if self.output_provenance not in AUTHORING_PROVENANCE:
                raise ValueError("authored value requires NEW_DESIGN or AUTHORED_PLACEHOLDER provenance")
            if not isinstance(self.editable_schema, Mapping) or not self.editable_schema:
                raise ValueError("authored value requires editable schema")
            if self.approval_owner != "USER" or self.implementation_owner != "CONTENT_ADMIN":
                raise ValueError("authored value requires explicit authoring and approval owners")
        if self.disposition == "RIGHTS_REVIEW_REQUIRED":
            if self.implementation_owner != "RIGHTS_REVIEW" or not isinstance(self.rights_review, Mapping):
                raise ValueError("rights-review row requires rights owner and contract")
            required = {
                "rightsQuestion", "reviewOwner", "decisionState", "distributionAllowed",
                "fallback", "evidence",
            }
            if set(self.rights_review) != required or self.rights_review.get("reviewOwner") != "RIGHTS_REVIEW":
                raise ValueError("rights-review contract mismatch")
            for name in ("rightsQuestion", "fallback"):
                _text(self.rights_review.get(name), f"rights {name}")
            if self.rights_review.get("decisionState") not in {"PENDING", "APPROVED", "REJECTED"}:
                raise ValueError("rights decision state mismatch")
            if self.rights_review.get("decisionState") == "PENDING" and self.rights_review.get("distributionAllowed") is not False:
                raise ValueError("pending rights review cannot allow distribution")
            if not self.rights_review.get("evidence"):
                raise ValueError("rights-review contract requires evidence")


@dataclass(frozen=True)
class RecoveryLedger:
    bindings: Mapping[str, Any]
    character_boundary: Mapping[str, Any]
    source_rows: tuple[RecoveryRow, ...]
    nested_rows: tuple[RecoveryRow, ...]
    mandatory_dataset_rows: tuple[RecoveryRow, ...]
    coverage_gaps: tuple[Mapping[str, Any], ...]
    coverage_fatals: tuple[Mapping[str, Any], ...]
    authoring_packages: tuple[Mapping[str, Any], ...]
    conservation: Mapping[str, Any]
    subject_surface_sha256: str
    ledger_surface_sha256: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "bindings", freeze_json(self.bindings))
        object.__setattr__(self, "character_boundary", freeze_json(self.character_boundary))
        object.__setattr__(self, "source_rows", tuple(self.source_rows))
        object.__setattr__(self, "nested_rows", tuple(self.nested_rows))
        object.__setattr__(self, "mandatory_dataset_rows", tuple(self.mandatory_dataset_rows))
        object.__setattr__(self, "coverage_gaps", tuple(freeze_json(item) for item in self.coverage_gaps))
        object.__setattr__(self, "coverage_fatals", tuple(freeze_json(item) for item in self.coverage_fatals))
        object.__setattr__(self, "authoring_packages", tuple(freeze_json(item) for item in self.authoring_packages))
        object.__setattr__(self, "conservation", freeze_json(self.conservation))

    @property
    def rows(self) -> tuple[RecoveryRow, ...]:
        return tuple(sorted(
            (*self.source_rows, *self.nested_rows, *self.mandatory_dataset_rows),
            key=lambda item: (item.key.casefold(), item.key),
        ))


def _coverage_surfaces(coverage: CoverageReport) -> tuple[list[Mapping[str, Any]], list[Mapping[str, Any]], dict[str, tuple[str, ...]]]:
    gaps: list[Mapping[str, Any]] = []
    refs: dict[str, list[str]] = {}
    for row in coverage.rows:
        for gap in row.gaps:
            core = {
                "sourceRowKey": row.row_key,
                "ruleId": gap.rule_id,
                "verdict": gap.verdict,
                "firstMissingBoundary": gap.first_missing_boundary,
                "evidence": list(gap.evidence),
                "detail": gap.detail,
            }
            gap_id = f"RECOVERY_GAP:{_sha(core)}"
            payload = {"gapId": gap_id, **core}
            gaps.append(payload)
            refs.setdefault(row.row_key, []).append(gap_id)
    gaps.sort(key=lambda item: item["gapId"])
    if len({item["gapId"] for item in gaps}) != len(gaps):
        raise ValueError("duplicate coverage recovery gap identity")
    fatals: list[Mapping[str, Any]] = []
    for fatal in coverage.fatals:
        core = {
            "ruleId": fatal.rule_id,
            "sourceRowKey": fatal.row_key,
            "path": fatal.path,
            "evidence": list(fatal.evidence),
            "detail": fatal.detail,
        }
        fatals.append({"fatalId": f"RECOVERY_FATAL:{_sha(core)}", **core})
    fatals.sort(key=lambda item: item["fatalId"])
    return gaps, fatals, {key: tuple(sorted(values)) for key, values in refs.items()}


def _output_provenance(disposition: str, source: Mapping[str, Any], parent: Mapping[str, Any]) -> str:
    if disposition == "RECOVERED_ORIGINAL":
        candidate = source.get("provenance", source.get("status", parent.get("provenance")))
        if candidate not in ORIGINAL_PROVENANCE:
            raise ValueError("RECOVERED_ORIGINAL subject lacks original provenance")
        return str(candidate)
    if disposition == "AUTHORING_REQUIRED":
        return "NEW_DESIGN" if source.get("status") == "NEW_DESIGN" else "AUTHORED_PLACEHOLDER"
    return "UNKNOWN"


def _row_contract(
    *,
    recovery_key: str,
    subject_kind: str,
    subject_key: str,
    source_path: str,
    domain: str,
    disposition: str,
    source: Mapping[str, Any],
    parent: Mapping[str, Any],
    evidence: Sequence[str],
    gap_refs: Sequence[str] = (),
    missing_boundaries: Sequence[str] = (),
) -> RecoveryRow:
    evidence_items = tuple(sorted({item for item in evidence if isinstance(item, str) and item.strip()}))
    if not evidence_items:
        evidence_items = (f"inventory:{parent.get('key', subject_key)}",)
    research = ()
    if disposition in {"SOURCE_CONFLICT", "ORIGINAL_SERVER_LOST", "AUTHORING_REQUIRED"}:
        research = _research_history(
            evidence_items,
            reason="current hash-bound project evidence establishes this provisional recovery boundary",
        )
    verdict = {
        "RECOVERED_ORIGINAL": "PARTIAL",
        "ORIGINAL_SERVER_LOST": "BLOCKED",
        "AUTHORING_REQUIRED": "UNSEEN",
    }.get(disposition, "UNSEEN")
    first = {
        "RECOVERED_ORIGINAL": "INDEPENDENT_REVIEW",
        "RECOVERABLE_STATIC": "STATIC_EXTRACTION",
        "RECOVERABLE_LIVE": "LIVE_OBSERVATION",
        "SOURCE_CONFLICT": "SOURCE_ADJUDICATION",
        "ORIGINAL_SERVER_LOST": "RESEARCH_HISTORY",
        "ORIGINAL_UNIMPLEMENTED": "NEW_DESIGN_DECISION",
        "AUTHORING_REQUIRED": "AUTHORED_VALUE",
        "RIGHTS_REVIEW_REQUIRED": "RIGHTS_DECISION",
    }[disposition]
    value = RecoveryRow(
        key=recovery_key,
        subject_kind=subject_kind,
        source_row_key=subject_key,
        source_path=source_path,
        domain=domain,
        disposition=disposition,
        output_provenance=_output_provenance(disposition, source, parent),
        evidence=evidence_items,
        falsifier=_falsifier(disposition, subject_key),
        research_history=research,
        editable_schema=_editable_schema() if disposition == "AUTHORING_REQUIRED" else None,
        approval_owner="USER" if disposition == "AUTHORING_REQUIRED" else None,
        implementation_owner=_owner(disposition),
        coverage_gap_refs=tuple(sorted(gap_refs)),
        missing_boundaries=tuple(missing_boundaries),
        adjudication_verdict=verdict,
        first_missing_adjudication_field=first,
    )
    value.validate()
    return value


def _validate_research_history(values: Sequence[Mapping[str, Any]]) -> tuple[Mapping[str, Any], ...]:
    frozen = tuple(freeze_json(item) for item in values)
    if tuple(item.get("stage") for item in frozen) != RESEARCH_STAGES:
        raise ValueError("character research history must preserve exact ordered stages")
    for ordinal, item in enumerate(frozen, 1):
        if set(item) != {
            "ordinal", "stage", "status", "scope", "query", "performedAt",
            "evidence", "outcome", "reason",
        } or item.get("ordinal") != ordinal or item.get("status") not in RESEARCH_STATUSES:
            raise ValueError("character research history entry mismatch")
        for name in ("scope", "outcome", "reason"):
            _text(item.get(name), f"character research {name}")
        if item.get("status") == "EVIDENCE_FOUND":
            if not item.get("evidence") or not item.get("performedAt") or not item.get("query"):
                raise ValueError("character EVIDENCE_FOUND requires query, date, and evidence receipt")
    return frozen


def validate_character_boundary(value: Mapping[str, Any]) -> Mapping[str, Any]:
    expected = {
        "recordType", "schemaVersion", "legacyNamedRows", "candidateStatisticRows",
        "officialNameFaceFacts", "survivingOfficialPortraitReferences",
        "strictConfirmedPortraitMappings", "stalePlanConfirmedPortraitMappings",
        "portraitConflictDisposition", "decodedOGroupSlots", "usableOGroupSlots",
        "datasets", "researchHistory", "documentPath", "documentSha256",
    }
    if not isinstance(value, Mapping) or set(value) != expected:
        raise ValueError("character roster boundary schema mismatch")
    if value.get("recordType") != "CHARACTER_ROSTER_BOUNDARY" or value.get("schemaVersion") != 1:
        raise ValueError("character roster boundary identity mismatch")
    exact_counts = {
        "legacyNamedRows": 99,
        "candidateStatisticRows": 97,
        "officialNameFaceFacts": 12,
        "survivingOfficialPortraitReferences": 2,
        "strictConfirmedPortraitMappings": 1,
        "stalePlanConfirmedPortraitMappings": 2,
        "decodedOGroupSlots": 513,
        "usableOGroupSlots": 397,
    }
    for name, expected_count in exact_counts.items():
        if type(value.get(name)) is not int or value[name] != expected_count:
            raise ValueError(f"character roster boundary {name} mismatch")
    if value.get("portraitConflictDisposition") != "SOURCE_CONFLICT":
        raise ValueError("stale two-to-one portrait claim must remain SOURCE_CONFLICT")
    if value.get("datasets") != dict(MANDATORY_DATASETS):
        raise ValueError("character roster dataset boundary mismatch")
    _text(value.get("documentPath"), "character boundary document path")
    document_sha = value.get("documentSha256")
    if not isinstance(document_sha, str) or len(document_sha) != 64:
        raise ValueError("character boundary document hash mismatch")
    _validate_research_history(value.get("researchHistory", ()))
    return freeze_json(value)


CHARACTER_BOUNDARY_START = "<!-- CHARACTER_ROSTER_BOUNDARY_JSON\n"
CHARACTER_BOUNDARY_END = "\n-->"


def load_character_boundary(path: str | Path, *, project_root: str | Path) -> Mapping[str, Any]:
    declared = Path(os.path.abspath(path))
    _reject_link_chain(declared, "character roster boundary document")
    root = Path(os.path.abspath(project_root)).resolve()
    resolved = declared.resolve()
    try:
        relative = resolved.relative_to(root).as_posix()
    except ValueError as error:
        raise ValueError("character roster boundary document escapes project root") from error
    data = resolved.read_bytes()
    if data.startswith(b"\xef\xbb\xbf") or b"\r" in data or not data.endswith(b"\n"):
        raise ValueError("character roster boundary document must be UTF-8 LF")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ValueError("character roster boundary document is not UTF-8") from error
    if text.count(CHARACTER_BOUNDARY_START) != 1:
        raise ValueError("character roster boundary structured block missing or duplicated")
    encoded = text.split(CHARACTER_BOUNDARY_START, 1)[1].split(CHARACTER_BOUNDARY_END, 1)[0]
    try:
        core = json.loads(encoded, object_pairs_hook=_object_no_duplicates)
    except json.JSONDecodeError as error:
        raise ValueError("character roster boundary structured block invalid") from error
    if canonical_json(core).strip() != encoded:
        raise ValueError("character roster boundary structured block must be canonical JSON")
    enriched = {
        **core,
        "documentPath": relative,
        "documentSha256": hashlib.sha256(data).hexdigest().upper(),
    }
    return validate_character_boundary(enriched)


def _mandatory_dataset_rows(character_boundary: Mapping[str, Any]) -> tuple[RecoveryRow, ...]:
    boundary = validate_character_boundary(character_boundary)
    boundary_history = tuple(boundary["researchHistory"])
    values = []
    for dataset, disposition in MANDATORY_DATASETS.items():
        source = {
            "status": "AUTHORED_PLACEHOLDER" if disposition == "AUTHORING_REQUIRED" else "UNKNOWN",
            "evidence": [
                "goal:character-roster-three-dataset-boundary",
                f"goal-required-dataset:{dataset}",
            ],
        }
        value = _row_contract(
            recovery_key=f"RECOVERY:DATASET:{dataset}",
            subject_kind="GOAL_REQUIRED_DATASET",
            subject_key=dataset,
            source_path="",
            domain="D02",
            disposition=disposition,
            source=source,
            parent={"key": dataset, "provenance": "UNKNOWN"},
            evidence=source["evidence"],
        )
        value = RecoveryRow(
            **{
                **value.__dict__,
                "research_history": boundary_history,
            }
        )
        value.validate()
        values.append(value)
    return tuple(sorted(values, key=lambda item: item.key))


def build_recovery_ledger(
    source_rows: Sequence[Mapping[str, Any]],
    coverage: CoverageReport,
    *,
    row_domains: Mapping[str, str],
    domain_bindings: Mapping[str, str],
    character_boundary: Mapping[str, Any],
) -> RecoveryLedger:
    """Build one recovery row per recovery-bearing subject, with coverage gaps attached."""

    rows = tuple(sorted(source_rows, key=lambda item: (str(item.get("key", "")).casefold(), str(item.get("key", "")))))
    keys = [str(row.get("key", "")) for row in rows]
    if any(not key for key in keys) or len({key.casefold() for key in keys}) != len(keys):
        raise ValueError("recovery source rows require unique stable keys")
    coverage_by_key = {row.row_key: row for row in coverage.rows}
    if set(coverage_by_key) != set(keys):
        raise ValueError("recovery source/coverage row set mismatch")
    if set(row_domains) != set(keys):
        raise ValueError("recovery source/domain row set mismatch")
    gaps, fatals, gap_refs = _coverage_surfaces(coverage)
    source_contracts: list[RecoveryRow] = []
    nested_contracts: list[RecoveryRow] = []
    for row in rows:
        key = str(row["key"])
        disposition = row.get("recoveryDisposition")
        if disposition not in DISPOSITIONS:
            raise ValueError(f"source row lacks supported recovery disposition: {key}")
        coverage_row = coverage_by_key[key]
        if coverage_row.recovery_disposition != disposition:
            raise ValueError("source/coverage recovery disposition mismatch")
        source_contracts.append(_row_contract(
            recovery_key=f"RECOVERY:ROW:{key}",
            subject_kind="SOURCE_VALUE",
            subject_key=key,
            source_path="",
            domain=_text(row_domains[key], "recovery domain"),
            disposition=str(disposition),
            source=row,
            parent=row,
            evidence=row.get("evidence", ()),
            gap_refs=gap_refs.get(key, ()),
            missing_boundaries=coverage_row.all_missing_boundaries,
        ))
        layout = row.get("layout")
        fields = layout.get("fields", ()) if isinstance(layout, Mapping) else ()
        if not isinstance(fields, (list, tuple)):
            raise ValueError("entity layout fields must be a list")
        field_keys: set[str] = set()
        for index, child in enumerate(fields):
            if not isinstance(child, Mapping):
                raise ValueError("entity field recovery subject must be an object")
            child_key = _text(child.get("key"), "entity field key")
            if child_key.casefold() in field_keys:
                raise ValueError("duplicate entity field recovery subject")
            field_keys.add(child_key.casefold())
            child_disposition = child.get("recoveryDisposition")
            if child_disposition not in DISPOSITIONS:
                raise ValueError("entity field lacks supported recovery disposition")
            nested_contracts.append(_row_contract(
                recovery_key=f"RECOVERY:FIELD:{key}:{child_key}",
                subject_kind="ENTITY_FIELD",
                subject_key=child_key,
                source_path=f"layout.fields[{index}]",
                domain=row_domains[key],
                disposition=str(child_disposition),
                source=child,
                parent=row,
                evidence=child.get("evidence", row.get("evidence", ())),
            ))
        populations = row.get("catalogCardinality", ())
        if not isinstance(populations, (list, tuple)):
            raise ValueError("entity populations must be a list")
        source_ids: set[str] = set()
        for index, child in enumerate(populations):
            if not isinstance(child, Mapping):
                raise ValueError("entity population recovery subject must be an object")
            source_id = _text(child.get("sourceId"), "entity population sourceId")
            if source_id.casefold() in source_ids:
                raise ValueError("duplicate entity population recovery subject")
            source_ids.add(source_id.casefold())
            child_disposition = child.get("recoveryDisposition")
            if child_disposition not in DISPOSITIONS:
                raise ValueError("entity population lacks supported recovery disposition")
            nested_contracts.append(_row_contract(
                recovery_key=f"RECOVERY:POPULATION:{key}:{source_id}",
                subject_kind="ENTITY_POPULATION",
                subject_key=source_id,
                source_path=f"catalogCardinality[{index}]",
                domain=row_domains[key],
                disposition=str(child_disposition),
                source=child,
                parent=row,
                evidence=child.get("evidence", row.get("evidence", ())),
            ))
    boundary = validate_character_boundary(character_boundary)
    mandatory = _mandatory_dataset_rows(boundary)
    all_rows = tuple(sorted((*source_contracts, *nested_contracts, *mandatory), key=lambda item: (item.key.casefold(), item.key)))
    folded = [item.key.casefold() for item in all_rows]
    if len(folded) != len(set(folded)):
        raise ValueError("duplicate recovery subject key")
    authoring_packages = tuple(
        {
            "packageKey": f"AUTHORING:{item.key}",
            "recoveryKey": item.key,
            "editableSchema": _plain(item.editable_schema),
            "outputProvenance": item.output_provenance,
            "authoringOwner": item.implementation_owner,
            "approvalOwner": item.approval_owner,
            "approvalStatus": "DRAFT",
            "validationArgv": ["python", "-m", "unittest", "tests.tools.exhaustive_trace.test_recovery"],
        }
        for item in all_rows
        if item.disposition == "AUTHORING_REQUIRED"
    )
    disposition_counts: dict[str, int] = {name: 0 for name in sorted(DISPOSITIONS)}
    kind_counts: dict[str, int] = {}
    for item in all_rows:
        disposition_counts[item.disposition] = disposition_counts.get(item.disposition, 0) + 1
        kind_counts[item.subject_kind] = kind_counts.get(item.subject_kind, 0) + 1
    conservation = {
        "inventoryRowSubjectCount": len(source_contracts),
        "nestedSubjectCount": len(nested_contracts),
        "entityFieldSubjectCount": sum(item.subject_kind == "ENTITY_FIELD" for item in nested_contracts),
        "entityPopulationSubjectCount": sum(item.subject_kind == "ENTITY_POPULATION" for item in nested_contracts),
        "mandatoryDatasetSubjectCount": len(mandatory),
        "totalSubjectCount": len(all_rows),
        "ledgerRowCount": len(all_rows),
        "uniqueSubjectCount": len(set(folded)),
        "actionableSubjectCount": sum(item.disposition != "RECOVERED_ORIGINAL" for item in all_rows),
        "recoveredSubjectCount": sum(item.disposition == "RECOVERED_ORIGINAL" for item in all_rows),
        "coverageGapReferenceCount": len(gaps),
        "coverageGapAttachedReferenceCount": sum(len(item.coverage_gap_refs) for item in source_contracts),
        "coverageFatalCount": len(fatals),
        "authoringPackageCount": len(authoring_packages),
        "unaccountedRecoverySubjectCount": 0,
        "countsByDisposition": dict(sorted(disposition_counts.items())),
        "countsBySubjectKind": dict(sorted(kind_counts.items())),
    }
    bindings = {
        **dict(domain_bindings),
        "bundleSha256": coverage.graph_binding.get("bundleSha256"),
        "sourceManifestSha256": coverage.graph_binding.get("sourceManifestSha256"),
        "clientSha256": coverage.graph_binding.get("clientSha256"),
        "messageDataSha256": coverage.graph_binding.get("messageDataSha256"),
        "graphSurfaceSha256": coverage.graph_binding.get("graphSurfaceSha256"),
        "coverageSurfaceSha256": coverage.coverage_surface_sha256,
        "coverageRowResultsSha256": coverage.row_results_sha256,
        "coverageFatalSurfaceSha256": coverage.fatal_surface_sha256,
        "characterBoundaryDocumentSha256": boundary["documentSha256"],
        "characterBoundarySurfaceSha256": _sha(boundary),
    }
    subject_payloads = [_recovery_row_payload(item) for item in all_rows]
    subject_sha = _sha(subject_payloads)
    surface = {
        "policyVersion": POLICY_VERSION,
        "bindings": bindings,
        "characterBoundary": _plain(boundary),
        "subjectSurfaceSha256": subject_sha,
        "coverageGapsSha256": _sha(gaps),
        "coverageFatalsSha256": _sha(fatals),
        "authoringPackagesSha256": _sha(authoring_packages),
        "conservation": conservation,
    }
    return RecoveryLedger(
        bindings=bindings,
        character_boundary=boundary,
        source_rows=tuple(source_contracts),
        nested_rows=tuple(nested_contracts),
        mandatory_dataset_rows=mandatory,
        coverage_gaps=tuple(gaps),
        coverage_fatals=tuple(fatals),
        authoring_packages=authoring_packages,
        conservation=conservation,
        subject_surface_sha256=subject_sha,
        ledger_surface_sha256=_sha(surface),
    )


def _recovery_row_payload(item: RecoveryRow) -> Mapping[str, Any]:
    return {
        "recoveryKey": item.key,
        "subjectKind": item.subject_kind,
        "sourceRowKey": item.source_row_key,
        "sourcePath": item.source_path,
        "domain": item.domain,
        "disposition": item.disposition,
        "adjudicationVerdict": item.adjudication_verdict,
        "outputProvenance": item.output_provenance,
        "evidence": list(item.evidence),
        "coverageGapRefs": list(item.coverage_gap_refs),
        "missingBoundaries": list(item.missing_boundaries),
        "falsifier": _plain(item.falsifier),
        "researchHistory": [_plain(value) for value in item.research_history],
        "editableSchema": _plain(item.editable_schema),
        "approvalOwner": item.approval_owner,
        "implementationOwner": item.implementation_owner,
        "firstMissingAdjudicationField": item.first_missing_adjudication_field,
        "conflictClaims": [_plain(value) for value in item.conflict_claims],
        "rightsReview": _plain(item.rights_review),
    }


def _ledger_payload(ledger: RecoveryLedger) -> Mapping[str, Any]:
    return {
        "recordType": "RECOVERY_LEDGER",
        "schemaVersion": SCHEMA_VERSION,
        "policy": {
            "version": POLICY_VERSION,
            "dispositions": sorted(DISPOSITIONS),
            "researchStages": list(RESEARCH_STAGES),
            "mandatoryDatasets": dict(MANDATORY_DATASETS),
            "coverageGapsAreSubjects": False,
        },
        "bindings": _plain(ledger.bindings),
        "characterRosterBoundary": _plain(ledger.character_boundary),
        "sourceLedgerRows": [_recovery_row_payload(item) for item in ledger.source_rows],
        "nestedRecoveryRows": [_recovery_row_payload(item) for item in ledger.nested_rows],
        "mandatoryDatasetRows": [_recovery_row_payload(item) for item in ledger.mandatory_dataset_rows],
        "coverageGaps": [_plain(item) for item in ledger.coverage_gaps],
        "coverageFatals": [_plain(item) for item in ledger.coverage_fatals],
        "authoringPackages": [_plain(item) for item in ledger.authoring_packages],
        "conservation": _plain(ledger.conservation),
        "subjectSurfaceSha256": ledger.subject_surface_sha256,
        "coverageGapsSha256": _sha(ledger.coverage_gaps),
        "coverageFatalsSha256": _sha(ledger.coverage_fatals),
        "authoringPackagesSha256": _sha(ledger.authoring_packages),
        "ledgerSurfaceSha256": ledger.ledger_surface_sha256,
    }


def recovery_json(ledger: RecoveryLedger) -> str:
    return canonical_json(_ledger_payload(ledger))


def load_recovery_json(
    path: str | Path,
    *,
    source_rows: Sequence[Mapping[str, Any]],
    coverage: CoverageReport,
    row_domains: Mapping[str, str],
    domain_bindings: Mapping[str, str],
    character_boundary: Mapping[str, Any],
) -> RecoveryLedger:
    artifact = Path(os.path.abspath(path))
    _reject_link_chain(artifact, "recovery artifact")
    data = artifact.read_bytes()
    if data.startswith(b"\xef\xbb\xbf") or not data.endswith(b"\n") or b"\r" in data:
        raise ValueError("recovery artifact must be canonical UTF-8 LF JSON")
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_object_no_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("recovery artifact is invalid JSON") from error
    if not isinstance(value, Mapping) or canonical_json(value).encode("utf-8") != data:
        raise ValueError("recovery artifact is not canonical JSON")
    expected = build_recovery_ledger(
        source_rows,
        coverage,
        row_domains=row_domains,
        domain_bindings=domain_bindings,
        character_boundary=character_boundary,
    )
    if value != _ledger_payload(expected):
        raise ValueError("recovery binding/hash/conservation/reproducibility mismatch")
    return expected


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INVENTORIES = PROJECT_ROOT / "evidence" / "exhaustive-trace" / "inventories"
DEFAULT_DOMAIN_CONFIG = PROJECT_ROOT / "docs" / "reverse-engineering" / "exhaustive-trace" / "domains.json"
DEFAULT_DOMAIN_PACKAGES = PROJECT_ROOT / "evidence" / "exhaustive-trace" / "domains"
DEFAULT_CHARACTER_BOUNDARY = (
    PROJECT_ROOT / "docs" / "new-design" /
    "2026-08-27-original-character-roster-recovery-boundary.md"
)


def _domain_inputs(packages: Any) -> tuple[dict[str, str], dict[str, str]]:
    domains: dict[str, str] = {}
    common: dict[str, str] | None = None
    for name, data in packages.files:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_object_no_duplicates)
        domain = value["domain"]["id"]
        for row in value["primaryRows"]:
            domains[row["rowKey"]] = domain
        binding = {
            "packageSetSha256": value["bindings"]["packageSetSha256"],
            "routeSurfaceSha256": value["bindings"]["routeSurfaceSha256"],
            "configSha256": value["bindings"]["configSha256"],
            "routingPolicySha256": value["routingPolicy"]["sha256"],
        }
        if common is None:
            common = binding
        elif common != binding:
            raise ValueError("domain package recovery bindings disagree")
    assert common is not None
    return domains, common


def _write_atomic(path: Path, data: bytes, *, verify: Any) -> RecoveryLedger:
    output = Path(os.path.abspath(path))
    _reject_link_chain(output.parent, "recovery output parent")
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.is_symlink():
        raise ValueError("recovery output must not be a link")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        verified = verify(temporary)
        os.replace(temporary, output)
        return verified
    finally:
        if temporary.exists():
            temporary.unlink()


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--graph", required=True, type=Path)
    parser.add_argument("--coverage", required=True, type=Path)
    parser.add_argument("--sources", required=True, type=Path)
    parser.add_argument("--inventories", type=Path, default=DEFAULT_INVENTORIES)
    parser.add_argument("--domain-config", type=Path, default=DEFAULT_DOMAIN_CONFIG)
    parser.add_argument("--domain-packages", type=Path, default=DEFAULT_DOMAIN_PACKAGES)
    parser.add_argument("--character-boundary", type=Path, default=DEFAULT_CHARACTER_BOUNDARY)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    bundle = load_inventory_bundle(args.inventories, source_manifest=args.sources)
    graph = load_graph_jsonl(args.graph, bundle=bundle)
    coverage = load_coverage_json(args.coverage, graph=graph, bundle=bundle)
    config = load_domain_config(args.domain_config, project_root=PROJECT_ROOT)
    packages = load_domain_packages(args.domain_packages, graph=graph, coverage=coverage, config=config)
    row_domains, domain_bindings = _domain_inputs(packages)
    character_boundary = load_character_boundary(
        args.character_boundary, project_root=PROJECT_ROOT
    )
    ledger = build_recovery_ledger(
        graph.source_rows,
        coverage,
        row_domains=row_domains,
        domain_bindings=domain_bindings,
        character_boundary=character_boundary,
    )
    data = recovery_json(ledger).encode("utf-8")
    verified = _write_atomic(
        args.output,
        data,
        verify=lambda temporary: load_recovery_json(
            temporary,
            source_rows=graph.source_rows,
            coverage=coverage,
            row_domains=row_domains,
            domain_bindings=domain_bindings,
            character_boundary=character_boundary,
        ),
    )
    print(canonical_json({
        "command": "build-recovery-ledger",
        "fileSha256": hashlib.sha256(data).hexdigest().upper(),
        "ledgerSurfaceSha256": verified.ledger_surface_sha256,
        "totalSubjectCount": verified.conservation["totalSubjectCount"],
        "actionableSubjectCount": verified.conservation["actionableSubjectCount"],
        "authoringPackageCount": verified.conservation["authoringPackageCount"],
        "coverageFatalCount": verified.conservation["coverageFatalCount"],
        "output": str(Path(os.path.abspath(args.output))),
        "status": "PASS",
    }), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = [
    "RESEARCH_STAGES", "RecoveryLedger", "RecoveryRow", "build_recovery_ledger",
    "load_character_boundary", "load_recovery_json", "recovery_json",
    "validate_authored_record", "validate_character_boundary",
]
