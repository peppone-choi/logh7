from __future__ import annotations

import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from tools.exhaustive_trace.domains import DomainPackageSet
from tools.exhaustive_trace.io import canonical_json
from tools.exhaustive_trace.work_packages import (
    FEATURE_UNIT_KINDS,
    FeatureDefinition,
    build_work_packages,
    load_work_packages_json,
    validate_work_package_payload,
    work_packages_json,
)


TARGETS = (
    "CONTRACT", "SERVER", "LEGACY_GATEWAY", "NEW_CLIENT", "DATABASE",
    "CONTENT_ADMIN", "QA", "INDEPENDENT_REVIEW",
)


def _row(key: str, *, boundary: str, disposition: str = "RECOVERABLE_STATIC",
         routing: str = "UNRESOLVED", inventory: str = "FUNCTION") -> dict:
    return {
        "allMissingBoundaries": [boundary, "INDEPENDENTLY_REVIEWED"],
        "candidateDomains": ["D06"],
        "coverageFatals": [],
        "coverageVerdict": "UNKNOWN",
        "firstMissingBoundary": boundary,
        "inventory": inventory,
        "primaryDomain": "D06",
        "recoveryDisposition": disposition,
        "routingBasis": "FIXTURE",
        "routingDisposition": routing,
        "routingEvidence": [f"fixture:{key}"],
        "rowKey": key,
        "secondaryDomains": [],
    }


def _packages() -> DomainPackageSet:
    files = []
    core_hashes = {f"D{i:02d}.json": str(i) * 64 for i in range(1, 17)}
    for i in range(1, 17):
        domain = f"D{i:02d}"
        rows = []
        unresolved = []
        if domain == "D06":
            rows = [
                _row("PROTOCOL:MESSAGE16:0x0B07", boundary="STATIC_MAPPED", routing="PROVEN", inventory="PROTOCOL"),
                _row("FUNCTION:INTERNAL:00401000", boundary="STATIC_MAPPED"),
                _row("AUTHORITY:PROTOCOL:MESSAGE16:0x0B01", boundary="PERSISTENCE", disposition="ORIGINAL_SERVER_LOST", inventory="AUTHORITY"),
            ]
            unresolved = [{"rowKey": row["rowKey"]} for row in rows if row["routingDisposition"] != "PROVEN"]
        payload = {
            "recordType": "DOMAIN_PACKAGE",
            "schemaVersion": 1,
            "routingPolicy": {"version": "TASK11-1", "sha256": "A" * 64},
            "graphBinding": {"graphSurfaceSha256": "B" * 64},
            "coverageBinding": {"coverageSurfaceSha256": "C" * 64},
            "coverageGate": {
                "status": "STRUCTURAL_FATAL",
                "globalFatals": [{
                    "ruleId": "FEATURE_REACHABILITY_LEDGER_ABSENT",
                    "rowKey": None,
                    "path": "sourceRows[rowKind=FEATURE]",
                    "evidence": ["coverage:feature-ledger"],
                    "detail": "fixture has no feature rows",
                }],
            },
            "domain": {"id": domain, "slug": f"fixture-{domain}", "hardDependencies": [], "planRefs": []},
            "topologicalOrder": [f"D{n:02d}" for n in range(1, 17)],
            "crossDomainDependencies": [],
            "primaryRows": rows,
            "secondaryRowKeys": [],
            "crossDomainUnresolved": unresolved,
            "conservation": {
                "globalSourceRowCount": 3,
                "primaryRowCount": len(rows),
                "secondaryRowCount": 0,
                "crossDomainUnresolvedCount": len(unresolved),
                "crossDomainDependencyCount": 0,
            },
            "bindings": {
                "configSha256": "D" * 64,
                "routeSurfaceSha256": "E" * 64,
                "packageCoreSha256": core_hashes[f"{domain}.json"],
                "packageSetSha256": "F" * 64,
                "packageCoreSha256ByFile": core_hashes,
            },
        }
        files.append((f"{domain}.json", canonical_json(payload).encode("utf-8")))
    return DomainPackageSet(tuple(files), "E" * 64, "F" * 64, 3, 2, "STRUCTURAL_FATAL", {
        "domainCount": 16, "sourceRowCount": 3, "primaryAssignmentCount": 3,
        "unresolvedRoutingCount": 2, "crossDomainDependencyCount": 0,
    })


def _move_grid() -> FeatureDefinition:
    return FeatureDefinition(
        feature_key="FEATURE:MOVE_GRID",
        domain="D06",
        source_row_keys=("PROTOCOL:MESSAGE16:0x0B07",),
        provenance="INFERRED",
        recovery_disposition="RECOVERABLE_STATIC",
        first_missing_boundary="STATIC_MAPPED",
        evidence=("fixture:observed-request-response-sibling",),
        original_fact_status="UNADJUDICATED",
    )


class WorkPackageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.packages = _packages()
        self.plan = build_work_packages(self.packages, candidate_features=(_move_grid(),))

    def test_move_grid_candidate_has_exact_eight_ordered_units(self) -> None:
        self.assertEqual((), self.plan.confirmed_features)
        package = self.plan.candidate_feature_packages[0]
        self.assertEqual("FEATURE:MOVE_GRID", package.feature_key)
        self.assertEqual("D06", package.domain)
        self.assertEqual("RECOVERABLE_STATIC", package.recovery_disposition)
        self.assertEqual(FEATURE_UNIT_KINDS, tuple(unit.kind for unit in package.units))
        self.assertEqual(
            [(), ("CONTRACT",), ("SERVER",), ("LEGACY_GATEWAY",), ("NEW_CLIENT",),
             ("DATABASE",), ("CONTENT_ADMIN",), ("QA", "INDEPENDENT_REVIEW")],
            [unit.targets for unit in package.units],
        )
        self.assertFalse(package.coverage_promotion)
        self.assertEqual("UNADJUDICATED", package.original_fact_status)

    def test_every_unit_has_closed_operational_contract(self) -> None:
        units = list(self.plan.recovery_units) + list(self.plan.candidate_feature_packages[0].units)
        for unit in units:
            self.assertTrue(unit.question.strip())
            self.assertTrue(unit.input_evidence)
            self.assertTrue(unit.expected_output.strip())
            self.assertTrue(unit.verifier_argv)
            self.assertEqual(set(TARGETS), set(unit.implementation_target_matrix))
            self.assertFalse(unit.mutates_runtime)
            self.assertEqual(0, unit.live_input_count)
            self.assertTrue(unit.independent_review_required)
            self.assertTrue(unit.forbidden_retry.strip())
            self.assertFalse(unit.mutation_scope["originalBinaryWrite"])
            self.assertFalse(unit.mutation_scope["processMemoryWrite"])
            self.assertFalse(unit.mutation_scope["oracleServerMutation"])
            self.assertFalse(unit.mutation_scope["oracleProtocolMutation"])
            self.assertFalse(unit.mutation_scope["oracleDatabaseMutation"])
            self.assertFalse(unit.mutation_scope["vmLifecycleMutation"])

    def test_all_open_rows_get_exactly_one_recovery_unit(self) -> None:
        expected = {
            "PROTOCOL:MESSAGE16:0x0B07", "FUNCTION:INTERNAL:00401000",
            "AUTHORITY:PROTOCOL:MESSAGE16:0x0B01",
        }
        actual = [unit.source_row_keys[0] for unit in self.plan.recovery_units]
        self.assertEqual(expected, set(actual))
        self.assertEqual(len(expected), len(actual))
        self.assertEqual(2, self.plan.conservation["routingUnresolvedRowCount"])
        self.assertEqual(0, self.plan.conservation["uncoveredOpenRowCount"])

    def test_recovery_units_never_combine_rows_or_boundaries(self) -> None:
        for unit in self.plan.recovery_units:
            self.assertEqual(1, len(unit.source_row_keys))
            self.assertEqual(1, len(unit.missing_boundaries))
            self.assertEqual(unit.first_missing_boundary, unit.missing_boundaries[0])

    def test_feature_fatal_is_preserved_and_candidate_does_not_cure_it(self) -> None:
        self.assertEqual("ABSENT", self.plan.feature_ledger_status)
        self.assertEqual("STRUCTURAL_FATAL", self.plan.coverage_gate_status)
        self.assertIn("FEATURE_REACHABILITY_LEDGER_ABSENT", [item["ruleId"] for item in self.plan.global_fatals])
        self.assertEqual(0, self.plan.conservation["confirmedGameplayFeatureCount"])
        self.assertEqual(1, self.plan.conservation["candidateGameplayFeatureCount"])

    def test_feature_definition_cannot_claim_original_fact(self) -> None:
        with self.assertRaisesRegex(ValueError, "candidate feature provenance"):
            FeatureDefinition(
                feature_key="FEATURE:MOVE_GRID", domain="D06",
                source_row_keys=("PROTOCOL:MESSAGE16:0x0B07",),
                provenance="ORIGINAL_OBSERVED",
                recovery_disposition="RECOVERABLE_STATIC",
                first_missing_boundary="STATIC_MAPPED",
                evidence=("fixture:invalid-original-claim",),
            )

    def test_unknown_feature_source_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "unknown feature source row"):
            build_work_packages(self.packages, candidate_features=(replace(_move_grid(), source_row_keys=("GHOST",)),))

    def test_feature_source_domain_and_boundary_must_match(self) -> None:
        with self.assertRaisesRegex(ValueError, "feature source domain"):
            build_work_packages(
                self.packages, candidate_features=(replace(_move_grid(), domain="D04"),)
            )
        with self.assertRaisesRegex(ValueError, "feature source first missing boundary"):
            build_work_packages(
                self.packages,
                candidate_features=(replace(_move_grid(), first_missing_boundary="PERSISTENCE"),),
            )

    def test_feature_cannot_combine_mixed_boundaries_or_dispositions(self) -> None:
        mixed = replace(
            _move_grid(),
            source_row_keys=(
                "PROTOCOL:MESSAGE16:0x0B07",
                "AUTHORITY:PROTOCOL:MESSAGE16:0x0B01",
            ),
        )
        with self.assertRaisesRegex(ValueError, "feature source first missing boundary"):
            build_work_packages(self.packages, candidate_features=(mixed,))
        with self.assertRaisesRegex(ValueError, "duplicate feature source row"):
            replace(
                _move_grid(),
                source_row_keys=(
                    "PROTOCOL:MESSAGE16:0x0B07",
                    "PROTOCOL:MESSAGE16:0x0B07",
                ),
            )

    def test_not_applicable_requires_reason_and_evidence(self) -> None:
        payload = json.loads(work_packages_json(self.plan))
        payload["candidateFeaturePackages"][0]["units"][1]["implementationTargetMatrix"]["CONTRACT"] = {
            "status": "NOT_APPLICABLE", "reason": "", "evidence": [], "ownerUnitId": None,
        }
        with self.assertRaisesRegex(ValueError, "NOT_APPLICABLE"):
            validate_work_package_payload(payload, packages=self.packages, candidate_features=(_move_grid(),))

    def test_live_count_and_automatic_retry_are_fail_closed(self) -> None:
        payload = json.loads(work_packages_json(self.plan))
        unit = payload["candidateFeaturePackages"][0]["units"][0]
        unit["liveInputCount"] = 2
        with self.assertRaisesRegex(ValueError, "liveInputCount"):
            validate_work_package_payload(payload, packages=self.packages, candidate_features=(_move_grid(),))
        payload = json.loads(work_packages_json(self.plan))
        payload["recoveryUnits"][0]["liveSlice"]["automaticRetry"] = True
        with self.assertRaisesRegex(ValueError, "automaticRetry"):
            validate_work_package_payload(payload, packages=self.packages, candidate_features=(_move_grid(),))

    def test_payload_is_deterministic_and_strict_loader_rebuilds(self) -> None:
        first = work_packages_json(self.plan)
        second = work_packages_json(build_work_packages(self.packages, candidate_features=(_move_grid(),)))
        self.assertEqual(first, second)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            path.write_text(first, encoding="utf-8", newline="")
            loaded = load_work_packages_json(path, packages=self.packages, candidate_features=(_move_grid(),))
            self.assertEqual(self.plan.plan_surface_sha256, loaded.plan_surface_sha256)
            path.write_text(first.replace("FEATURE:MOVE_GRID", "FEATURE:MOVE_GRID_TAMPER", 1), encoding="utf-8", newline="")
            with self.assertRaises(ValueError):
                load_work_packages_json(path, packages=self.packages, candidate_features=(_move_grid(),))

    def test_validator_rejects_combined_unrelated_first_boundaries(self) -> None:
        payload = json.loads(work_packages_json(self.plan))
        unit = payload["recoveryUnits"][0]
        unit["sourceRowKeys"].append("AUTHORITY:PROTOCOL:MESSAGE16:0x0B01")
        unit["missingBoundaries"].append("PERSISTENCE")
        with self.assertRaisesRegex(ValueError, "exactly one source row"):
            validate_work_package_payload(payload, packages=self.packages, candidate_features=(_move_grid(),))


if __name__ == "__main__":
    unittest.main()
