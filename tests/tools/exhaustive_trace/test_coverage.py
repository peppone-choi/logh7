from __future__ import annotations

import copy
import json
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path

from tools.exhaustive_trace.coverage import (
    FEATURE_TRACE_BOUNDARIES,
    IMPLEMENTATION_TARGETS,
    audit_graph,
    coverage_json,
    load_coverage_json,
)


STATE_NAMES = (
    "ENUMERATED",
    "STATIC_MAPPED",
    "CODEC_PROVEN",
    "RUNTIME_OBSERVED",
    "PLAYER_VISIBLE",
    "AUTHORITY_PROVEN",
    "PERSISTENCE_PROVEN",
    "BOTH_FACTIONS",
    "INDEPENDENTLY_REVIEWED",
)


def complete_implementation() -> dict[str, dict[str, object]]:
    return {
        target: {
            "status": "REQUIRED",
            "reason": f"fixture requires {target}",
            "evidence": [f"fixture:implementation:{target}"],
        }
        for target in IMPLEMENTATION_TARGETS
    }


def source_row(
    key: str,
    *,
    inventory: str = "UI",
    row_kind: str = "FEATURE",
) -> dict[str, object]:
    return {
        "inventory": inventory,
        "key": key,
        "name": key,
        "rowKind": row_kind,
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "SHIPPED_REACHABLE",
        "reachabilityEvidence": ["fixture:reachable"],
        "recoveryDisposition": "RECOVERABLE_STATIC",
        "implementationDisposition": complete_implementation(),
        "states": {name: True for name in STATE_NAMES},
        "evidence": [f"fixture:{key}"],
    }


@dataclass(frozen=True)
class FakeGraph:
    source_rows: tuple[dict[str, object], ...]
    nodes_sha256: str = "1" * 64
    edges_sha256: str = "2" * 64
    graph_surface_sha256: str = "3" * 64

    @property
    def conservation(self):
        return {"sourceRowNodes": len(self.source_rows)}


@dataclass(frozen=True)
class FakeBundle:
    bundle_sha256: str = "4" * 64
    source_manifest_sha256: str = "5" * 64
    client_sha256: str = "6" * 64
    message_data_sha256: str = "7" * 64


class CoverageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.bundle = FakeBundle()

    def report(self, *rows: dict[str, object]):
        return audit_graph(FakeGraph(tuple(rows)), bundle=self.bundle)

    def test_report_is_immutable_and_conserves_every_source_row_once(self) -> None:
        first = source_row("UI:FEATURE:A")
        second = source_row("UI:FEATURE:B")
        report = self.report(second, first)

        self.assertEqual(("UI:FEATURE:A", "UI:FEATURE:B"), tuple(row.row_key for row in report.rows))
        self.assertEqual(2, report.conservation["sourceRowCount"])
        self.assertEqual(2, report.conservation["auditedRowCount"])
        self.assertEqual(0, report.conservation["missingRowCount"])
        with self.assertRaises(TypeError):
            report.conservation["sourceRowCount"] = 0
        with self.assertRaises(TypeError):
            report.rows[0].states["PLAYER_VISIBLE"] = False

    def test_missing_dispositions_are_fatal_but_explicit_unknowns_are_gaps(self) -> None:
        row = source_row("PROTOCOL:FEATURE:0x0001", inventory="PROTOCOL")
        row["direction"] = {"status": "UNKNOWN", "evidence": ["fixture:direction-unknown"]}
        del row["implementationDisposition"]["DATABASE"]
        report = self.report(row)
        result = report.rows[0]

        self.assertIn("IMPLEMENTATION_TARGET_SET", {fatal.rule_id for fatal in result.fatals})
        self.assertIn("PROTOCOL_DIRECTION", {gap.first_missing_boundary for gap in result.gaps})
        self.assertGreater(report.conservation["fatalStructuralCount"], 0)
        self.assertGreater(report.conservation["evidenceGapCount"], 0)

    def test_direction_applicable_protocol_ownership_is_not_over_required(self) -> None:
        row = source_row("PROTOCOL:FEATURE:0x0002", inventory="PROTOCOL")
        row["direction"] = {"status": "SERVER_TO_CLIENT", "evidence": ["fixture:direction"]}
        row["ownership"] = {
            "parser": {"status": "UNKNOWN", "functions": [], "evidence": ["fixture:parser"]},
            "dispatcher": {"status": "PROVEN", "functions": ["FUN_1"], "evidence": ["fixture:dispatch"]},
            "serializer": {"status": "UNKNOWN", "functions": [], "evidence": ["fixture:serializer"]},
        }
        report = self.report(row)
        boundaries = set(report.rows[0].all_missing_boundaries)

        self.assertIn("CLIENT_PARSER", boundaries)
        self.assertNotIn("CLIENT_SERIALIZER", boundaries)

    def test_ui_entity_writer_resource_and_authority_gaps_are_typed(self) -> None:
        ui = source_row("UI:FEATURE:INTERACTIVE")
        ui.update(
            interactionKind="INTERACTIVE",
            handler={"status": "UNKNOWN", "functions": [], "evidence": ["fixture:handler"]},
        )
        entity = source_row("ENTITY:FEATURE:PLANET", inventory="ENTITY")
        entity.update(
            idNamespace={"status": "UNKNOWN", "evidence": ["fixture:id"]},
            relations={
                "parent": {"status": "UNKNOWN", "edges": [], "evidence": ["fixture:parent"]}
            },
        )
        function = source_row("FUNCTION:FEATURE:WRITER", inventory="FUNCTION")
        function.update(
            globalStructureFields={"writes": [{"targetAddress": "00700000"}]},
            inputsOutputs={"status": "UNKNOWN", "parameters": [], "evidence": ["fixture:io"]},
        )
        resource = source_row("RESOURCE:FEATURE:ASSET", inventory="RESOURCE")
        resource.update(
            loader={"status": "CANDIDATE", "functions": ["FUN_1"], "evidence": ["fixture:loader"]},
            owner={"status": "UNKNOWN", "functions": [], "ownerKeys": [], "evidence": ["fixture:owner"]},
            usageDisposition="ENUMERATED_ONLY",
        )
        authority = source_row("AUTHORITY:FEATURE:MUTATION", inventory="AUTHORITY")
        authority["sections"] = {
            "mutation": {"status": "MISSING_CURRENT_SOURCE", "evidence": ["fixture:mutation"]},
            "event": {"status": "MISSING_CURRENT_SOURCE", "evidence": ["fixture:event"]},
            "persistence": {"status": "MISSING_CURRENT_SOURCE", "evidence": ["fixture:persistence"]},
        }
        report = self.report(ui, entity, function, resource, authority)
        boundaries = {row.row_key: set(row.all_missing_boundaries) for row in report.rows}

        self.assertIn("UI_HANDLER", boundaries[ui["key"]])
        self.assertIn("ENTITY_ID_NAMESPACE", boundaries[entity["key"]])
        self.assertIn("ENTITY_PARENT", boundaries[entity["key"]])
        self.assertIn("WRITER_INPUT_SOURCE", boundaries[function["key"]])
        self.assertIn("RESOURCE_LOADER", boundaries[resource["key"]])
        self.assertIn("AUTHORITY_MUTATION", boundaries[authority["key"]])
        self.assertIn("AUTHORITY_EVENT", boundaries[authority["key"]])
        self.assertIn("PERSISTENCE", boundaries[authority["key"]])

    def test_fields_and_populations_require_their_own_recovery_disposition(self) -> None:
        row = source_row("ENTITY:FEATURE:CATALOG", inventory="ENTITY")
        row.update(
            idNamespace={"status": "PROVEN", "evidence": ["fixture:id"]},
            relations={"parent": {"status": "NOT_APPLICABLE", "reason": "root", "evidence": ["fixture:root"]}},
            layout={"fields": [{"key": "FIELD:CATALOG:id", "evidence": ["fixture:field"]}]},
            # TraceGraph deep-freezes JSON arrays to tuples before auditing.
            catalogCardinality=({"sourceId": "fixture", "count": 2, "evidence": ["fixture:count"]},),
        )
        report = self.report(row)
        rules = {fatal.rule_id for fatal in report.rows[0].fatals}

        self.assertIn("FIELD_RECOVERY_DISPOSITION", rules)
        self.assertIn("POPULATION_RECOVERY_DISPOSITION", rules)

    def test_no_feature_rows_is_a_global_structural_fatal(self) -> None:
        row = source_row("UI:MODE:0x02", row_kind="MODE_ROOT")
        row.update(handler={"status": "NOT_APPLICABLE", "reason": "container", "evidence": ["fixture"]})
        report = self.report(row)

        self.assertIn("FEATURE_REACHABILITY_LEDGER_ABSENT", {fatal.rule_id for fatal in report.global_fatals})

    def test_first_missing_boundary_and_all_boundaries_do_not_promote_states(self) -> None:
        row = source_row("UI:FEATURE:PARTIAL")
        row["firstMissingBoundary"] = "UI_HANDLER"
        row["interactionKind"] = "INTERACTIVE"
        row["handler"] = {
            "status": "UNKNOWN",
            "functions": [],
            "evidence": ["fixture:handler"],
        }
        row["states"]["RUNTIME_OBSERVED"] = False
        row["states"]["PLAYER_VISIBLE"] = False
        row["featureTrace"] = {
            boundary: {"status": "PROVEN", "evidence": [f"fixture:{boundary}"]}
            for boundary in FEATURE_TRACE_BOUNDARIES
        }
        original = copy.deepcopy(row["states"])
        report = self.report(row)
        result = report.rows[0]

        self.assertEqual("UI_HANDLER", result.first_missing_boundary)
        self.assertEqual(
            ("UI_HANDLER", "RUNTIME_OBSERVED", "PLAYER_VISIBLE"),
            result.all_missing_boundaries,
        )
        self.assertEqual(original, row["states"])
        self.assertEqual(original, dict(result.states))

    def test_canonical_report_round_trips_and_binds_graph_and_bundle(self) -> None:
        row = source_row("UI:FEATURE:A")
        graph = FakeGraph((row,))
        report = audit_graph(graph, bundle=self.bundle)
        text_a = coverage_json(report, graph=graph, bundle=self.bundle)
        text_b = coverage_json(audit_graph(graph, bundle=self.bundle), graph=graph, bundle=self.bundle)
        self.assertEqual(text_a, text_b)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "coverage.json"
            path.write_text(text_a, encoding="utf-8", newline="\n")
            loaded = load_coverage_json(path, graph=graph, bundle=self.bundle)
            self.assertEqual(report.coverage_surface_sha256, loaded.coverage_surface_sha256)

            value = json.loads(text_a)
            value["graphBinding"]["bundleSha256"] = "F" * 64
            path.write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
            with self.assertRaisesRegex(ValueError, "canonical|bundle|binding|hash"):
                load_coverage_json(path, graph=graph, bundle=self.bundle)

    def test_feature_row_requires_the_complete_vertical_trace_contract(self) -> None:
        row = source_row("UI:FEATURE:NO_TRACE")

        report = self.report(row)

        self.assertIn("FEATURE_VERTICAL_TRACE_CONTRACT", {fatal.rule_id for fatal in report.rows[0].fatals})
        self.assertNotEqual("PASS", report.rows[0].verdict)

    def test_closed_feature_and_open_entity_content_paths_use_frozen_contracts(self) -> None:
        from tools.exhaustive_trace.coverage import (
            CONTENT_TRACE_BOUNDARIES,
            ENTITY_TRACE_BOUNDARIES,
            FEATURE_TRACE_BOUNDARIES,
        )

        feature = source_row("UI:FEATURE:CLOSED")
        feature["featureTrace"] = {
            boundary: {"status": "PROVEN", "evidence": [f"fixture:{boundary}"]}
            for boundary in FEATURE_TRACE_BOUNDARIES
        }
        entity = source_row("ENTITY:FEATURE:OPEN", inventory="ENTITY", row_kind="ENTITY_TYPE")
        entity.update(
            idNamespace={"status": "PROVEN", "evidence": ["fixture:id"]},
            relations={"parent": {"status": "PROVEN", "evidence": ["fixture:parent"]}},
        )
        content = source_row("RESOURCE:FEATURE:OPEN", inventory="RESOURCE", row_kind="TREE_FILE")
        content.update(
            loader={"status": "PROVEN", "functions": ["FUN_1"], "evidence": ["fixture:loader"]},
            owner={"status": "PROVEN", "evidence": ["fixture:owner"]},
        )

        report = self.report(feature, entity, content)
        by_key = {row.row_key: row for row in report.rows}

        self.assertEqual("PASS", by_key[feature["key"]].verdict)
        self.assertTrue(set(ENTITY_TRACE_BOUNDARIES) <= set(by_key[entity["key"]].all_missing_boundaries))
        self.assertTrue(set(CONTENT_TRACE_BOUNDARIES) <= set(by_key[content["key"]].all_missing_boundaries))

    def test_evidence_free_proven_feature_boundaries_do_not_close(self) -> None:
        row = source_row("UI:FEATURE:EVIDENCE_FREE")
        row["featureTrace"] = {
            boundary: {"status": "PROVEN"}
            for boundary in FEATURE_TRACE_BOUNDARIES
        }

        result = self.report(row).rows[0]

        self.assertNotEqual("PASS", result.verdict)
        self.assertTrue(set(FEATURE_TRACE_BOUNDARIES) <= set(result.all_missing_boundaries))

    def test_stale_stored_first_missing_boundary_is_not_an_audit_fact(self) -> None:
        row = source_row("UI:FEATURE:STALE")
        row["firstMissingBoundary"] = "UI_HANDLER"
        row["featureTrace"] = {
            boundary: {"status": "PROVEN", "evidence": [f"fixture:{boundary}"]}
            for boundary in FEATURE_TRACE_BOUNDARIES
        }

        result = self.report(row).rows[0]

        self.assertEqual("PASS", result.verdict)
        self.assertIsNone(result.first_missing_boundary)
        self.assertEqual((), result.all_missing_boundaries)

    def test_not_applicable_requires_reason_and_evidence(self) -> None:
        entity = source_row("ENTITY:FEATURE:ROOT", inventory="ENTITY")
        entity.update(
            idNamespace={"status": "PROVEN", "evidence": ["fixture:id"]},
            relations={"parent": {"status": "NOT_APPLICABLE"}},
        )
        authority = source_row("AUTHORITY:FEATURE:NA", inventory="AUTHORITY")
        authority["sections"] = {
            name: {"status": "NOT_APPLICABLE_ROW_KIND"}
            for name in ("mutation", "event", "persistence")
        }

        report = self.report(entity, authority)
        by_key = {row.row_key: {fatal.rule_id for fatal in row.fatals} for row in report.rows}

        self.assertIn("ENTITY_PARENT", by_key[entity["key"]])
        self.assertIn("AUTHORITY_MUTATION", by_key[authority["key"]])
        self.assertIn("AUTHORITY_EVENT", by_key[authority["key"]])

    def test_writer_proven_io_without_input_source_identity_is_fatal(self) -> None:
        row = source_row("FUNCTION:FEATURE:FALSE_PROVEN", inventory="FUNCTION")
        row.update(
            globalStructureFields={"writes": [{"targetAddress": "00700000"}]},
            inputsOutputs={"status": "PROVEN", "parameters": [], "evidence": ["fixture:signature"]},
        )

        report = self.report(row)

        self.assertIn("WRITER_INPUT_SOURCE", {fatal.rule_id for fatal in report.rows[0].fatals})

    def test_writer_input_source_must_resolve_to_audited_identity(self) -> None:
        row = source_row("FUNCTION:FEATURE:GHOST_SOURCE", inventory="FUNCTION")
        row.update(
            globalStructureFields={"writes": [{"targetAddress": "00700000"}]},
            inputsOutputs={
                "status": "PROVEN",
                "inputSources": ["GHOST:NOT_IN_GRAPH"],
                "evidence": ["fixture:signature"],
            },
        )

        result = self.report(row).rows[0]

        self.assertIn("WRITER_INPUT_SOURCE", {fatal.rule_id for fatal in result.fatals})

    def test_missing_boundaries_use_policy_order_not_stored_order(self) -> None:
        row = source_row("UI:FEATURE:ORDERED")
        row["firstMissingBoundary"] = "PERSISTENCE"
        row["interactionKind"] = "INTERACTIVE"
        row["handler"] = {"status": "UNKNOWN", "functions": [], "evidence": ["fixture:handler"]}

        result = self.report(row).rows[0]

        self.assertEqual("UI_HANDLER", result.first_missing_boundary)
        self.assertLess(
            result.all_missing_boundaries.index("UI_HANDLER"),
            result.all_missing_boundaries.index("PERSISTENCE"),
        )


if __name__ == "__main__":
    unittest.main()
