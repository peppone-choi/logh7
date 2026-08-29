from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

UNIT = Path(__file__).resolve().parents[1]
REPO = UNIT.parents[1]
EVALUATOR = UNIT / "src" / "evaluate_warp_stage_gate_v2.py"
GATE = UNIT / "tests" / "current-offline-gate-input.json"
EXPECTED = UNIT / "tests" / "expected-source-hashes.json"

spec = importlib.util.spec_from_file_location("warp_gate_v2", EVALUATOR)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


class WarpStageGateV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base_gate = json.loads(GATE.read_text(encoding="utf-8"))
        cls.base_expected = json.loads(EXPECTED.read_text(encoding="utf-8"))
        cls.temp = tempfile.TemporaryDirectory(prefix="warp-stage-gate-v2-")
        cls.temp_path = Path(cls.temp.name)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def evaluate(self, gate: dict, expected: dict, *, expected_manifest_hash: str | None = None) -> dict:
        ordinal = len(list(self.temp_path.glob("gate-*.json")))
        gate_path = self.temp_path / f"gate-{ordinal}.json"
        expected_path = self.temp_path / f"expected-{ordinal}.json"
        gate_path.write_text(json.dumps(gate), encoding="utf-8")
        expected_path.write_text(json.dumps(expected), encoding="utf-8")
        return module.evaluate(gate, gate_path, expected, expected_path, expected_manifest_hash or digest(expected_path), REPO)

    def assert_blocked(self, change, blocker: str) -> None:
        gate = copy.deepcopy(self.base_gate)
        expected = copy.deepcopy(self.base_expected)
        change(gate, expected)
        result = self.evaluate(gate, expected)
        self.assertFalse(result["auditPass"])
        self.assertIn(blocker, result["structuralBlockers"])
        self.assertFalse(result["activationEligible"])
        self.assertIsNone(result["activationPoint"])
        self.assertIsNone(result["permit"])

    def test_current_evidence_passes_audit_but_is_not_ready(self) -> None:
        result = self.evaluate(copy.deepcopy(self.base_gate), copy.deepcopy(self.base_expected))
        self.assertEqual(result["status"], "OFFLINE_WARP_GATE_V2_AUDIT_PASS_READY_FALSE")
        self.assertTrue(result["auditPass"])
        self.assertEqual(result["stageLocalMaxPhysicalActivations"], 1)
        self.assertEqual(result["physicalActivationsRemaining"], 1)
        self.assertEqual(result["readinessBlockers"][0], "FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION")
        self.assertEqual(result["stages"], {"warp": {"lifecycle": "NOT_CREATED", "consumed": 0}, "destination": {"lifecycle": "NOT_CREATED", "consumed": 0}, "confirm": {"lifecycle": "NOT_CREATED", "consumed": 0}})
        for key in ("activationEligible", "warpPrelaunchEligible", "launchEligible", "permitEligible", "permitIssued", "originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed"):
            self.assertFalse(result[key])
        self.assertIsNone(result["activationPoint"])
        self.assertIsNone(result["automaticActivationPoint"])
        self.assertIsNone(result["permit"])
        self.assertIsNone(result["warpPreparation"]["bindingSourceSha256"])

    def test_authority_mutations_fail_closed(self) -> None:
        cases = [
            ("authorizationId", "OTHER", "AUTHORITY_authorizationId_MISMATCH"),
            ("oneRunApproved", False, "AUTHORITY_oneRunApproved_MISMATCH"),
            ("maxPhysicalActivations", 3, "AUTHORITY_maxPhysicalActivations_MISMATCH"),
            ("physicalActivationsConsumed", 1, "AUTHORITY_physicalActivationsConsumed_MISMATCH"),
            ("physicalActivationsRemaining", 0, "AUTHORITY_physicalActivationsRemaining_MISMATCH"),
            ("automaticInputBudget", 1, "AUTHORITY_automaticInputBudget_MISMATCH"),
            ("retryBudget", 1, "AUTHORITY_retryBudget_MISMATCH"),
            ("issuedForStage", "ALL", "AUTHORITY_issuedForStage_MISMATCH"),
            ("reusableAfterFailure", True, "AUTHORITY_reusableAfterFailure_MISMATCH"),
        ]
        for key, value, blocker in cases:
            with self.subTest(key=key):
                self.assert_blocked(lambda g, e, k=key, v=value: g["authorityEnvelope"].__setitem__(k, v), blocker)
        self.assert_blocked(lambda g, e: g["authorityEnvelope"].__setitem__("extra", 1), "AUTHORITY_ENVELOPE_KEYS_MISMATCH")

    def test_stage_lifecycle_and_future_prebinding_fail_closed(self) -> None:
        for stage in ("warp", "destination", "confirm"):
            with self.subTest(stage=stage, kind="created"):
                self.assert_blocked(lambda g, e, s=stage: g["stages"][s].__setitem__("lifecycle", "CREATED"), f"STAGE_{stage}_MUST_BE_NOT_CREATED_UNCONSUMED")
            with self.subTest(stage=stage, kind="consumed"):
                self.assert_blocked(lambda g, e, s=stage: g["stages"][s].__setitem__("consumed", 1), f"STAGE_{stage}_MUST_BE_NOT_CREATED_UNCONSUMED")
        self.assert_blocked(lambda g, e: g["stages"]["destination"].__setitem__("binding", {}), "STAGE_destination_KEYS_MISMATCH")
        self.assert_blocked(lambda g, e: g["stages"]["confirm"].__setitem__("rect", {"left": 1}), "STAGE_confirm_KEYS_MISMATCH")

    def test_claim_self_promotion_fails_closed(self) -> None:
        booleans = ["originalRuntimeObserved", "freshIdentityBound", "independentLiveBinding", "livePromotionAllowed", "warpPrelaunchEligible", "activationEligible", "launchEligible", "permitEligible", "permitIssued"]
        for key in booleans:
            with self.subTest(key=key):
                self.assert_blocked(lambda g, e, k=key: g["claimCeiling"].__setitem__(k, True), f"CLAIM_{key}_MUST_BE_FALSE")
        for key in ("activationPoint", "automaticActivationPoint", "permit"):
            with self.subTest(key=key):
                self.assert_blocked(lambda g, e, k=key: g["claimCeiling"].__setitem__(k, {"fabricated": True}), f"CLAIM_{key}_MUST_BE_NULL")

    def test_each_forbidden_operation_fails_closed(self) -> None:
        for key in self.base_gate["operations"]:
            with self.subTest(key=key):
                self.assert_blocked(lambda g, e, k=key: g["operations"].__setitem__(k, 1), f"OPERATION_{key}_NONZERO")

    def test_all_external_roles_reject_valid_sha_substitution(self) -> None:
        for role in self.base_expected["roles"]:
            with self.subTest(role=role, side="source"):
                self.assert_blocked(lambda g, e, r=role: g["sources"][r].__setitem__("sha256", "B" * 64), f"SOURCE_{role}_EXPECTED_SHA256_MISMATCH")
            with self.subTest(role=role, side="expected"):
                self.assert_blocked(lambda g, e, r=role: e["roles"].__setitem__(r, "B" * 64), f"SOURCE_{role}_EXPECTED_SHA256_MISMATCH")

    def test_closed_root_source_and_manifest_hash_contracts(self) -> None:
        cases = [
            (lambda g, e: g.__setitem__("extra", 1), "GATE_INPUT_KEYS_MISMATCH"),
            (lambda g, e: g.pop("stages"), "GATE_INPUT_KEYS_MISMATCH"),
            (lambda g, e: g.__setitem__("schemaVersion", 1), "GATE_SCHEMA_OR_TYPE_MISMATCH"),
            (lambda g, e: g.__setitem__("evidenceClass", "LIVE_READONLY"), "EVIDENCE_CLASS_MISMATCH"),
            (lambda g, e: g.__setitem__("oracleRunId", "LIVE-RUN"), "OFFLINE_RUN_ID_MISMATCH"),
            (lambda g, e: g["sources"].pop("managerCapture"), "SOURCES_KEYS_MISMATCH"),
            (lambda g, e: g["sources"]["managerCapture"].__setitem__("path", "../outside.json"), "SOURCE_managerCapture_PATH_MISMATCH"),
            (lambda g, e: g["sources"]["managerCapture"].__setitem__("sha256", "bad"), "SOURCE_managerCapture_SHA256_INVALID"),
            (lambda g, e: e.__setitem__("extra", 1), "EXPECTED_HASH_MANIFEST_KEYS_MISMATCH"),
            (lambda g, e: e["roles"].pop("managerCapture"), "EXPECTED_HASH_ROLES_KEYS_MISMATCH"),
        ]
        for index, (change, blocker) in enumerate(cases):
            with self.subTest(index=index):
                self.assert_blocked(change, blocker)
        result = self.evaluate(copy.deepcopy(self.base_gate), copy.deepcopy(self.base_expected), expected_manifest_hash="0" * 64)
        self.assertIn("EXPECTED_HASH_MANIFEST_SHA256_MISMATCH", result["structuralBlockers"])

    def test_cli_output_is_offline_only_and_atomic(self) -> None:
        output = self.temp_path / "cli-evaluation.json"
        expected_hash = digest(EXPECTED)
        command = [sys.executable, "-B", str(EVALUATOR), "--gate-input", str(GATE), "--expected-hashes", str(EXPECTED), "--expected-hashes-sha256", expected_hash, "--repo-root", str(REPO), "--output", str(output)]
        first = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        second = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(second.returncode, 0, second.stderr)
        result = json.loads(output.read_text(encoding="utf-8"))
        self.assertTrue(result["auditPass"])
        self.assertFalse(result["activationEligible"])
        self.assertIsNone(result["activationPoint"])
        self.assertFalse(any(output.parent.glob(output.name + ".tmp*")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
