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
EVALUATOR = UNIT / "src" / "evaluate_warp_external_binding_subject_v1.py"
SCHEMA = UNIT / "evidence" / "warp-external-binding-subject-v1.schema.json"
CONTRACT = UNIT / "tests" / "current-offline-contract.json"

spec = importlib.util.spec_from_file_location("warp_external_binding_v1", EVALUATOR)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


class WarpExternalBindingSubjectV1Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.base = json.loads(CONTRACT.read_text(encoding="utf-8"))
        cls.schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
        cls.temp = tempfile.TemporaryDirectory(prefix="warp-external-binding-v1-")
        cls.temp_path = Path(cls.temp.name)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def evaluate(self, contract: dict, *, schema_hash: str | None = None) -> dict:
        path = self.temp_path / f"contract-{len(list(self.temp_path.glob('contract-*.json')))}.json"
        path.write_text(json.dumps(contract, allow_nan=True), encoding="utf-8")
        return module.evaluate(contract, path, self.schema, SCHEMA, schema_hash or digest(SCHEMA), digest(REPO / self.base["testOnlyVector"]["source"]["capturePath"]), digest(REPO / self.base["testOnlyVector"]["source"]["evaluationPath"]), REPO)

    def test_current_contract_passes_without_creating_live_subject(self) -> None:
        result = self.evaluate(copy.deepcopy(self.base))
        self.assertEqual(result["status"], "OFFLINE_WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_PASS_NOT_CREATED_NOT_ELIGIBLE")
        self.assertTrue(result["contractPass"])
        self.assertTrue(result["testOnlyVectorValid"])
        self.assertEqual(result["testOnlyGeometryStatus"], "PASS_TEST_ONLY_NONPROMOTABLE")
        self.assertEqual(set(result["chainLifecycle"].values()), {"NOT_CREATED"})
        self.assertEqual(result["readinessBlockers"][0], "FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION")
        for key in ("liveSubjectEligible", "warpPrelaunchEligible", "activationEligible", "launchEligible", "permitEligible", "permitIssued", "originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed"):
            self.assertFalse(result[key])
        for key in ("activationPoint", "automaticActivationPoint", "permit", "bindingDigest"):
            self.assertIsNone(result[key])

    def test_mutation_matrix(self) -> None:
        cases = []

        def add(name, change):
            cases.append((name, change))

        add("root-extra", lambda x: x.__setitem__("extra", 1))
        add("root-missing", lambda x: x.pop("liveSubject"))
        add("schema-version", lambda x: x.__setitem__("schemaVersion", 2))
        add("evidence-class", lambda x: x.__setitem__("evidenceClass", "LIVE_READONLY"))

        authority_mutations = {
            "authorizationId": "OTHER", "oneRunApproved": False, "maxPhysicalActivations": 3,
            "physicalActivationsConsumed": 1, "physicalActivationsRemaining": 0,
            "automaticInputBudget": 1, "retryBudget": 1, "issuedForStage": "ALL",
            "reusableAfterFailure": True, "isPermit": True,
        }
        for key, value in authority_mutations.items():
            add(f"authority-{key}", lambda x, k=key, v=value: x["authorityEnvelope"].__setitem__(k, v))
        add("authority-extra", lambda x: x["authorityEnvelope"].__setitem__("extra", 1))

        for step in module.CHAIN_KEYS:
            add(f"{step}-created", lambda x, s=step: x["liveSubject"]["chain"][s].__setitem__("lifecycle", "CREATED"))
            add(f"{step}-source-hash", lambda x, s=step: x["liveSubject"]["chain"][s].__setitem__("sourceSha256", "B" * 64))
            add(f"{step}-receipt", lambda x, s=step: x["liveSubject"]["chain"][s].__setitem__("receipt", {}))
        add("live-run", lambda x: x["liveSubject"].__setitem__("oracleRunId", "LIVE-RUN"))
        add("live-binding", lambda x: x["liveSubject"].__setitem__("bindingDigest", "B" * 64))
        add("live-rect", lambda x: x["liveSubject"].__setitem__("fullClientRect", {"left": 0, "top": 0, "right": 1, "bottom": 1}))
        add("live-point", lambda x: x["liveSubject"].__setitem__("replaySafeManualPoint", {"x": 1, "y": 1}))
        add("live-cell", lambda x: x["liveSubject"].__setitem__("activationCell", {"left": 1, "top": 1, "right": 2, "bottom": 2}))

        add("future-order", lambda x: x["futureChainContract"]["order"].reverse())
        for role in module.EXPECTED_ORDER:
            add(f"future-{role}", lambda x, r=role: x["futureChainContract"]["steps"][r].__setitem__("receiptType", "WRONG"))
        add("h4-independent", lambda x: x["futureChainContract"].__setitem__("h4IndependentlyRecomputed", False))
        add("h4-point-source", lambda x: x["futureChainContract"].__setitem__("h4PointSource", "H3_POINT"))
        add("h4-scale-source", lambda x: x["futureChainContract"].__setitem__("h4ScaleSource", "DECIMAL"))
        add("h5-independence", lambda x: x["futureChainContract"].__setitem__("h5ReviewerMustDifferFromSingleWriter", False))
        add("h6-permit", lambda x: x["futureChainContract"].__setitem__("h6MayIssuePermit", True))
        add("h6-input", lambda x: x["futureChainContract"].__setitem__("h6MayPerformInput", True))

        add("blocker-first", lambda x: x["blockers"].reverse())
        add("blocker-missing", lambda x: x["blockers"].pop())

        for key in ("liveSubjectEligible", "warpPrelaunchEligible", "activationEligible", "launchEligible", "permitEligible", "permitIssued", "originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed"):
            add(f"claim-{key}", lambda x, k=key: x["claimCeiling"].__setitem__(k, True))
        for key in ("activationPoint", "automaticActivationPoint", "permit", "bindingDigest"):
            add(f"claim-{key}", lambda x, k=key: x["claimCeiling"].__setitem__(k, {"fabricated": True}))

        for key in self.base["operations"]:
            add(f"operation-{key}", lambda x, k=key: x["operations"].__setitem__(k, 1))

        source_mutations = {
            "capturePath": "../outside.json", "captureSha256": "B" * 64,
            "expectedCaptureSha256": "B" * 64, "evaluationPath": "../outside-eval.json",
            "evaluationSha256": "B" * 64, "expectedEvaluationSha256": "B" * 64,
        }
        for key, value in source_mutations.items():
            add(f"vector-source-{key}", lambda x, k=key, v=value: x["testOnlyVector"]["source"].__setitem__(k, v))

        scale_mutations = [
            ("malformed", "BAD"), ("zero", "00000000"), ("negative", "BFA00000"),
            ("subnormal", "00000001"), ("inf", "7F800000"), ("nan", "7FC00000"),
            ("endian", "0000A03F"), ("swapped", "3FC00000"),
        ]
        for name, value in scale_mutations:
            add(f"scale-{name}", lambda x, v=value: x["testOnlyVector"]["scale"].__setitem__("xBits", v))
        add("scale-mirror", lambda x: x["testOnlyVector"]["scale"].__setitem__("xDecimalMirror", 1.5))

        add("full-origin", lambda x: x["testOnlyVector"]["fullClientRect"].__setitem__("left", 1))
        add("full-empty", lambda x: x["testOnlyVector"]["fullClientRect"].__setitem__("right", 0))
        add("logical-empty", lambda x: x["testOnlyVector"]["logicalRect"].__setitem__("right", 700))
        add("candidate-left", lambda x: x["testOnlyVector"]["candidateClientRect"].__setitem__("left", 559))
        add("candidate-right", lambda x: x["testOnlyVector"]["candidateClientRect"].__setitem__("right", 655))
        add("manual-x", lambda x: x["testOnlyVector"]["manualPoint"].__setitem__("x", 608))
        add("manual-y", lambda x: x["testOnlyVector"]["manualPoint"].__setitem__("y", 428))
        add("3x3-left", lambda x: x["testOnlyVector"]["threeByThreeRect"].__setitem__("left", 607))
        add("3x3-bottom", lambda x: x["testOnlyVector"]["threeByThreeRect"].__setitem__("bottom", 428))
        add("forward-x", lambda x: x["testOnlyVector"]["forwardLogical"].__setitem__("x", 759))
        add("forward-y", lambda x: x["testOnlyVector"]["forwardLogical"].__setitem__("y", 641))
        add("cell-width", lambda x: x["testOnlyVector"]["activationCell"].__setitem__("right", 609))
        add("cell-origin", lambda x: x["testOnlyVector"]["activationCell"].__setitem__("left", 606))
        for key in ("promotable", "liveEligible", "activationEligible", "permitEligible"):
            add(f"vector-promotion-{key}", lambda x, k=key: x["testOnlyVector"].__setitem__(k, True))

        def coordinated_geometry_change(x):
            vector = x["testOnlyVector"]
            vector["logicalRect"] = {"left": 600, "top": 630, "right": 720, "bottom": 654}
            vector["candidateClientRect"] = {"left": 480, "top": 420, "right": 576, "bottom": 436}
            vector["manualPoint"] = {"x": 527, "y": 427}
            vector["threeByThreeRect"] = {"left": 526, "top": 426, "right": 529, "bottom": 429}
            vector["forwardLogical"] = {"x": 658, "y": 640}
            vector["activationCell"] = {"left": 527, "top": 427, "right": 528, "bottom": 428}
        add("coordinated-alternate-geometry", coordinated_geometry_change)

        self.assertEqual(len(cases), 115)
        for name, change in cases:
            with self.subTest(name=name):
                value = copy.deepcopy(self.base)
                change(value)
                result = self.evaluate(value)
                self.assertFalse(result["contractPass"], name)
                self.assertFalse(result["liveSubjectEligible"], name)
                self.assertIsNone(result["activationPoint"], name)
                self.assertIsNone(result["permit"], name)

    def test_schema_hash_is_external(self) -> None:
        result = self.evaluate(copy.deepcopy(self.base), schema_hash="0" * 64)
        self.assertIn("SCHEMA_SHA256_MISMATCH", result["structuralBlockers"])
        self.assertFalse(result["contractPass"])

    def test_cli_is_atomic_and_never_exports_test_geometry(self) -> None:
        output = self.temp_path / "evaluation.json"
        command = [sys.executable, "-B", str(EVALUATOR), "--contract", str(CONTRACT), "--schema", str(SCHEMA), "--expected-schema-sha256", digest(SCHEMA), "--expected-capture-sha256", digest(REPO / self.base["testOnlyVector"]["source"]["capturePath"]), "--expected-evaluation-sha256", digest(REPO / self.base["testOnlyVector"]["source"]["evaluationPath"]), "--repo-root", str(REPO), "--output", str(output)]
        for _ in range(2):
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(completed.returncode, 0, completed.stderr)
        result = json.loads(output.read_text(encoding="utf-8"))
        serialized = json.dumps(result)
        self.assertNotIn("607", serialized)
        self.assertNotIn("560", serialized)
        self.assertTrue(result["testOnlyVectorValid"])
        self.assertIsNone(result["activationPoint"])
        self.assertFalse(any(output.parent.glob(output.name + ".tmp*")))


if __name__ == "__main__":
    unittest.main(verbosity=2)
