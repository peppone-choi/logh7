from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.coverage import (
    CoverageFatal,
    CoverageGap,
    CoverageReport,
    CoverageRow,
)
from tools.exhaustive_trace.recovery import (
    RESEARCH_STAGES,
    RecoveryRow,
    build_recovery_ledger,
    load_recovery_json,
    recovery_json,
    validate_authored_record,
)


TARGETS = (
    "CONTRACT", "SERVER", "LEGACY_GATEWAY", "NEW_CLIENT", "DATABASE",
    "CONTENT_ADMIN", "QA", "INDEPENDENT_REVIEW",
)
CHARACTER_BOUNDARY = {
    "recordType": "CHARACTER_ROSTER_BOUNDARY",
    "schemaVersion": 1,
    "legacyNamedRows": 99,
    "candidateStatisticRows": 97,
    "officialNameFaceFacts": 12,
    "survivingOfficialPortraitReferences": 2,
    "strictConfirmedPortraitMappings": 1,
    "stalePlanConfirmedPortraitMappings": 2,
    "portraitConflictDisposition": "SOURCE_CONFLICT",
    "decodedOGroupSlots": 513,
    "usableOGroupSlots": 397,
    "datasets": {
        "originalConfirmedCharacters": "RECOVERABLE_STATIC",
        "canonCandidateCharacters": "RECOVERABLE_STATIC",
        "authoredPlayableCharacters": "AUTHORING_REQUIRED",
    },
    "researchHistory": [
        {
            "ordinal": 1,
            "stage": "GENERAL_WEB",
            "status": "EVIDENCE_FOUND",
            "scope": "fixture roster search",
            "query": "fixture general query",
            "performedAt": "2026-08-28",
            "evidence": ["https://example.invalid/general"],
            "outcome": "PARTIAL_ROSTER_EVIDENCE",
            "reason": "fixture receipt",
        },
        {
            "ordinal": 2,
            "stage": "JAPANESE_WEB",
            "status": "EVIDENCE_FOUND",
            "scope": "fixture roster search",
            "query": "fixture Japanese query",
            "performedAt": "2026-08-28",
            "evidence": ["https://example.invalid/japanese"],
            "outcome": "PARTIAL_ROSTER_EVIDENCE",
            "reason": "fixture receipt",
        },
        {
            "ordinal": 3,
            "stage": "ORIGINAL_OFFICIAL_MANUAL_RUNTIME",
            "status": "BLOCKED",
            "scope": "fixture roster import",
            "query": None,
            "performedAt": "2026-08-28",
            "evidence": [],
            "outcome": "ROSTER_MANIFEST_ABSENT",
            "reason": "fixture source is not manifest-bound",
        },
        {
            "ordinal": 4,
            "stage": "USER_ADJUDICATION",
            "status": "NOT_ATTEMPTED",
            "scope": "fixture roster approval",
            "query": None,
            "performedAt": None,
            "evidence": [],
            "outcome": "PENDING",
            "reason": "no user decision",
        },
        {
            "ordinal": 5,
            "stage": "AUTHORED_REPLACEMENT",
            "status": "NOT_ATTEMPTED",
            "scope": "fixture roster authoring",
            "query": None,
            "performedAt": None,
            "evidence": [],
            "outcome": "PENDING",
            "reason": "research is incomplete",
        },
    ],
    "documentPath": "docs/new-design/fixture-character-boundary.md",
    "documentSha256": "C" * 64,
}


def _implementation() -> dict[str, dict[str, object]]:
    return {
        target: {
            "status": "REQUIRED",
            "reason": f"fixture requires {target}",
            "evidence": [f"fixture:target:{target}"],
        }
        for target in TARGETS
    }


def _states() -> dict[str, bool]:
    return {
        "ENUMERATED": True,
        "STATIC_MAPPED": False,
        "CODEC_PROVEN": False,
        "RUNTIME_OBSERVED": False,
        "PLAYER_VISIBLE": False,
        "AUTHORITY_PROVEN": False,
        "PERSISTENCE_PROVEN": False,
        "BOTH_FACTIONS": False,
        "INDEPENDENTLY_REVIEWED": False,
    }


def _row(
    key: str,
    disposition: str,
    *,
    provenance: str = "UNKNOWN",
    nested: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "key": key,
        "inventory": "ENTITY",
        "rowKind": "ENTITY_TYPE",
        "name": key,
        "provenance": provenance,
        "reachability": "UNKNOWN",
        "recoveryDisposition": disposition,
        "implementationDisposition": _implementation(),
        "states": _states(),
        "evidence": [f"fixture:{key}"],
        "catalogCardinality": nested or [],
    }


def _coverage(rows: list[dict[str, object]]) -> CoverageReport:
    results = []
    for index, row in enumerate(rows):
        gap = CoverageGap(
            "ENTITY_VERTICAL_TRACE",
            "UNKNOWN",
            "DEFINITION_PROVENANCE",
            (f"fixture:gap:{row['key']}",),
            "entity definition remains unresolved",
        )
        results.append(CoverageRow(
            row_key=str(row["key"]),
            inventory="ENTITY",
            reachability="UNKNOWN",
            recovery_disposition=str(row["recoveryDisposition"]),
            implementation_disposition=_implementation(),
            states=_states(),
            verdict="UNKNOWN",
            first_missing_boundary="DEFINITION_PROVENANCE",
            all_missing_boundaries=("DEFINITION_PROVENANCE", "STATIC_MAPPED"),
            gaps=(gap,),
            fatals=(),
        ))
    return CoverageReport(
        graph_binding={
            "bundleSha256": "1" * 64,
            "sourceManifestSha256": "2" * 64,
            "clientSha256": "3" * 64,
            "messageDataSha256": "4" * 64,
            "nodesSha256": "5" * 64,
            "edgesSha256": "6" * 64,
            "graphSurfaceSha256": "7" * 64,
            "sourceRowCount": len(rows),
            "sourceRowsSha256": "8" * 64,
        },
        rows=tuple(results),
        global_fatals=(CoverageFatal(
            "FEATURE_REACHABILITY_LEDGER_ABSENT",
            None,
            "sourceRows[rowKind=FEATURE]",
            ("coverage:feature-ledger",),
            "no feature ledger",
        ),),
        conservation={
            "sourceRowCount": len(rows),
            "auditedRowCount": len(rows),
            "missingRowCount": 0,
            "extraRowCount": 0,
            "duplicateRowCount": 0,
            "fatalStructuralCount": 1,
            "evidenceGapCount": len(rows),
            "closedVerticalTraceCount": 0,
            "rowCountByInventory": {"ENTITY": len(rows)},
            "rowCountByVerdict": {"UNKNOWN": len(rows)},
            "missingBoundaryCount": len(rows) * 2,
            "missingBoundaryCountByBoundary": {
                "DEFINITION_PROVENANCE": len(rows),
                "STATIC_MAPPED": len(rows),
            },
        },
    )


def _inputs() -> tuple[list[dict[str, object]], CoverageReport, dict[str, str], dict[str, str]]:
    rows = [
        _row(
            "ENTITY:TYPE:CHARACTER",
            "RECOVERABLE_STATIC",
            nested=[{
                "count": 99,
                "status": "AUTHORED_PLACEHOLDER",
                "sourceId": "fixture-mixed-roster",
                "recoveryDisposition": "AUTHORING_REQUIRED",
                "evidence": ["fixture:mixed-roster"],
            }],
        ),
        _row("AUTHORITY:ENTITY:CHARACTER", "ORIGINAL_SERVER_LOST"),
        _row("ENTITY:TYPE:FACTION", "RECOVERED_ORIGINAL", provenance="ORIGINAL_MANUAL"),
    ]
    coverage = _coverage(rows)
    domains = {str(row["key"]): "D02" for row in rows}
    bindings = {
        "packageSetSha256": "9" * 64,
        "routeSurfaceSha256": "A" * 64,
        "configSha256": "B" * 64,
    }
    return rows, coverage, domains, bindings


class RecoveryRowTests(unittest.TestCase):
    def test_authored_value_cannot_be_original(self) -> None:
        row = RecoveryRow(
            key="RECOVERY:ENTITY:CHARACTER_ROSTER",
            subject_kind="NESTED_VALUE",
            source_row_key="ENTITY:TYPE:CHARACTER",
            source_path="catalogCardinality[0]",
            domain="D02",
            disposition="AUTHORING_REQUIRED",
            output_provenance="ORIGINAL_OBSERVED",
            evidence=("fixture:mixed-roster",),
            falsifier=None,
            research_history=(),
            editable_schema={"type": "object"},
            approval_owner="USER",
            implementation_owner="CONTENT_ADMIN",
        )
        with self.assertRaisesRegex(ValueError, "authored.*original"):
            row.validate()

    def test_recoverable_claim_requires_evidence_and_falsifier(self) -> None:
        row = RecoveryRow(
            key="RECOVERY:STATIC",
            subject_kind="SOURCE_VALUE",
            source_row_key="ENTITY:TYPE:CHARACTER",
            source_path="",
            domain="D02",
            disposition="RECOVERABLE_STATIC",
            output_provenance="UNKNOWN",
            evidence=(),
            falsifier=None,
            research_history=(),
            editable_schema=None,
            approval_owner=None,
            implementation_owner="REVERSE_ENGINEERING",
        )
        with self.assertRaisesRegex(ValueError, "evidence and falsifier"):
            row.validate()

    def test_lost_source_requires_ordered_research_history(self) -> None:
        row = RecoveryRow(
            key="RECOVERY:LOST",
            subject_kind="SOURCE_VALUE",
            source_row_key="AUTHORITY:ENTITY:CHARACTER",
            source_path="",
            domain="D02",
            disposition="ORIGINAL_SERVER_LOST",
            output_provenance="UNKNOWN",
            evidence=("fixture:no-current-server",),
            falsifier=None,
            research_history=({"stage": "USER_ADJUDICATION", "status": "NOT_RUN", "evidence": []},),
            editable_schema=None,
            approval_owner=None,
            implementation_owner="SERVER_DESIGN",
        )
        with self.assertRaisesRegex(ValueError, "research history"):
            row.validate()

    def test_source_conflict_requires_two_disagreeing_claims(self) -> None:
        row = RecoveryRow(
            key="RECOVERY:CONFLICT",
            subject_kind="NESTED_VALUE",
            source_row_key="ENTITY:TYPE:CHARACTER",
            source_path="portrait",
            domain="D02",
            disposition="SOURCE_CONFLICT",
            output_provenance="UNKNOWN",
            evidence=("fixture:claim-a", "fixture:claim-b"),
            falsifier=None,
            research_history=tuple({
                "ordinal": index,
                "stage": stage,
                "status": "EVIDENCE_FOUND" if index == 3 else "NOT_ATTEMPTED",
                "scope": "fixture conflict",
                "query": None,
                "performedAt": None,
                "evidence": ["fixture:receipt"] if index == 3 else [],
                "outcome": "CONFLICT" if index == 3 else "PENDING",
                "reason": "fixture",
            } for index, stage in enumerate(RESEARCH_STAGES, 1)),
            editable_schema=None,
            approval_owner=None,
            implementation_owner="SOURCE_ADJUDICATION",
            conflict_claims=({"value": "same", "evidence": ["fixture:claim-a"]},),
        )
        with self.assertRaisesRegex(ValueError, "two disagreeing claims"):
            row.validate()

    def test_pending_rights_review_cannot_allow_distribution(self) -> None:
        row = RecoveryRow(
            key="RECOVERY:RIGHTS",
            subject_kind="NESTED_VALUE",
            source_row_key="RESOURCE:PORTRAIT",
            source_path="rights",
            domain="D15",
            disposition="RIGHTS_REVIEW_REQUIRED",
            output_provenance="UNKNOWN",
            evidence=("fixture:rights-source",),
            falsifier=None,
            research_history=(),
            editable_schema=None,
            approval_owner=None,
            implementation_owner="RIGHTS_REVIEW",
            rights_review={
                "rightsQuestion": "May this portrait be distributed?",
                "reviewOwner": "RIGHTS_REVIEW",
                "decisionState": "PENDING",
                "distributionAllowed": True,
                "fallback": "author a distributable replacement",
                "evidence": ["fixture:rights-source"],
            },
        )
        with self.assertRaisesRegex(ValueError, "pending rights"):
            row.validate()


class RecoveryLedgerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.rows, self.coverage, self.domains, self.bindings = _inputs()
        self.ledger = build_recovery_ledger(
            self.rows,
            self.coverage,
            row_domains=self.domains,
            domain_bindings=self.bindings,
            character_boundary=CHARACTER_BOUNDARY,
        )

    def test_every_value_nested_population_rule_and_fatal_is_conserved(self) -> None:
        # 3 top-level values + 1 nested population + 3 goal-required roster datasets.
        self.assertEqual(7, len(self.ledger.rows))
        self.assertEqual(3, self.ledger.conservation["inventoryRowSubjectCount"])
        self.assertEqual(1, self.ledger.conservation["nestedSubjectCount"])
        self.assertEqual(3, self.ledger.conservation["mandatoryDatasetSubjectCount"])
        # Rule gaps attach to their owning subjects; they do not inflate the denominator.
        self.assertEqual(3, self.ledger.conservation["coverageGapReferenceCount"])
        self.assertEqual(1, self.ledger.conservation["coverageFatalCount"])
        self.assertEqual(0, self.ledger.conservation["unaccountedRecoverySubjectCount"])
        self.assertEqual(len(self.ledger.rows), len({row.key for row in self.ledger.rows}))

    def test_authoring_rows_have_editable_schema_owner_and_nonoriginal_output(self) -> None:
        authored = [row for row in self.ledger.rows if row.disposition == "AUTHORING_REQUIRED"]
        self.assertEqual(2, len(authored))
        self.assertEqual(2, len(self.ledger.authoring_packages))
        for row in authored:
            self.assertIn(row.output_provenance, {"NEW_DESIGN", "AUTHORED_PLACEHOLDER"})
            self.assertTrue(row.editable_schema)
            self.assertEqual("USER", row.approval_owner)
            self.assertEqual("CONTENT_ADMIN", row.implementation_owner)

    def test_recovered_original_does_not_promote_its_unresolved_rule_gap(self) -> None:
        source = next(row for row in self.ledger.rows if row.source_row_key == "ENTITY:TYPE:FACTION" and row.subject_kind == "SOURCE_VALUE")
        self.assertEqual("RECOVERED_ORIGINAL", source.disposition)
        self.assertEqual("ORIGINAL_MANUAL", source.output_provenance)
        self.assertTrue(source.coverage_gap_refs)
        self.assertIn("DEFINITION_PROVENANCE", source.missing_boundaries)

    def test_mandatory_character_datasets_are_separate_nonpromoting_subjects(self) -> None:
        datasets = [row for row in self.ledger.rows if row.subject_kind == "GOAL_REQUIRED_DATASET"]
        self.assertEqual(
            {
                "originalConfirmedCharacters",
                "canonCandidateCharacters",
                "authoredPlayableCharacters",
            },
            {row.source_row_key for row in datasets},
        )
        confirmed = next(row for row in datasets if row.source_row_key == "originalConfirmedCharacters")
        candidate = next(row for row in datasets if row.source_row_key == "canonCandidateCharacters")
        authored = next(row for row in datasets if row.source_row_key == "authoredPlayableCharacters")
        self.assertEqual(("RECOVERABLE_STATIC", "UNKNOWN"), (confirmed.disposition, confirmed.output_provenance))
        self.assertEqual(("RECOVERABLE_STATIC", "UNKNOWN"), (candidate.disposition, candidate.output_provenance))
        self.assertEqual(("AUTHORING_REQUIRED", "AUTHORED_PLACEHOLDER"), (authored.disposition, authored.output_provenance))

    def test_server_lost_rows_keep_exact_research_order(self) -> None:
        lost = [row for row in self.ledger.rows if row.disposition == "ORIGINAL_SERVER_LOST"]
        self.assertTrue(lost)
        for row in lost:
            self.assertEqual(RESEARCH_STAGES, tuple(item["stage"] for item in row.research_history))

    def test_output_is_deterministic_and_strict_loader_rebuilds(self) -> None:
        first = recovery_json(self.ledger)
        second = recovery_json(build_recovery_ledger(
            list(reversed(self.rows)),
            self.coverage,
            row_domains=self.domains,
            domain_bindings=self.bindings,
            character_boundary=CHARACTER_BOUNDARY,
        ))
        self.assertEqual(first, second)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "recovery.json"
            path.write_text(first, encoding="utf-8", newline="")
            loaded = load_recovery_json(
                path,
                source_rows=self.rows,
                coverage=self.coverage,
                row_domains=self.domains,
                domain_bindings=self.bindings,
                character_boundary=CHARACTER_BOUNDARY,
            )
            self.assertEqual(self.ledger.ledger_surface_sha256, loaded.ledger_surface_sha256)
            value = json.loads(first)
            value["sourceLedgerRows"][0]["disposition"] = "AUTHORING_REQUIRED"
            path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8", newline="")
            with self.assertRaises(ValueError):
                load_recovery_json(
                    path,
                    source_rows=self.rows,
                    coverage=self.coverage,
                    row_domains=self.domains,
                    domain_bindings=self.bindings,
                    character_boundary=CHARACTER_BOUNDARY,
                )

    def test_all_eight_disposition_buckets_are_explicit(self) -> None:
        self.assertEqual(
            {
                "RECOVERED_ORIGINAL", "RECOVERABLE_STATIC", "RECOVERABLE_LIVE",
                "SOURCE_CONFLICT", "ORIGINAL_SERVER_LOST", "ORIGINAL_UNIMPLEMENTED",
                "AUTHORING_REQUIRED", "RIGHTS_REVIEW_REQUIRED",
            },
            set(self.ledger.conservation["countsByDisposition"]),
        )
        self.assertEqual(0, self.ledger.conservation["countsByDisposition"]["SOURCE_CONFLICT"])

    def test_character_boundary_is_bound_and_stale_two_mapping_claim_is_visible(self) -> None:
        self.assertEqual(
            CHARACTER_BOUNDARY["documentSha256"],
            self.ledger.bindings["characterBoundaryDocumentSha256"],
        )
        self.assertEqual("SOURCE_CONFLICT", self.ledger.character_boundary["portraitConflictDisposition"])
        self.assertEqual(1, self.ledger.character_boundary["strictConfirmedPortraitMappings"])
        self.assertEqual(2, self.ledger.character_boundary["stalePlanConfirmedPortraitMappings"])

    def test_goal_only_dataset_does_not_claim_original_research_found(self) -> None:
        authored = next(
            row for row in self.ledger.mandatory_dataset_rows
            if row.source_row_key == "authoredPlayableCharacters"
        )
        original_stage = authored.research_history[2]
        self.assertNotEqual("EVIDENCE_FOUND", original_stage["status"])
        self.assertEqual([], list(original_stage["evidence"]))

    def test_authored_field_cannot_claim_original_without_fact_reference(self) -> None:
        invalid = {
            "key": "CHARACTER:AUTHORED:001",
            "fields": {
                "name": {"origin": "ORIGINAL_OBSERVED", "value": "Candidate"},
            },
            "approvalStatus": "DRAFT",
        }
        with self.assertRaisesRegex(ValueError, "confirmed fact reference"):
            validate_authored_record(invalid)
        valid = {
            "key": "CHARACTER:AUTHORED:001",
            "fields": {
                "name": {
                    "origin": "ORIGINAL_MANUAL",
                    "value": "Confirmed",
                    "confirmedFactRef": "FACT:CHARACTER:001:NAME",
                    "evidenceRefs": ["manual-pdf:fixture:page:1"],
                },
                "balance": {"origin": "NEW_DESIGN", "value": 50},
            },
            "approvalStatus": "DRAFT",
        }
        validate_authored_record(valid)


if __name__ == "__main__":
    unittest.main()
