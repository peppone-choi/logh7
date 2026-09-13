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
COLLECTOR = UNIT / "src" / "collect-manager65-action2b-v3.ps1"
EVALUATOR = UNIT / "src" / "evaluate_manager65_capture_v3.py"
FIXTURE_MEMORY = UNIT / "tests" / "fixture-memory.json"
FIXTURE_IDENTITY = UNIT / "tests" / "fixture-identity.json"

spec = importlib.util.spec_from_file_location("manager65_v3_evaluator", EVALUATOR)
module = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(module)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


class Manager65V3Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp = tempfile.TemporaryDirectory(prefix="manager65-v3-tests-")
        cls.temp_path = Path(cls.temp.name)
        cls.capture_path = cls.temp_path / "capture.json"
        subprocess.run([
            "pwsh", "-NoProfile", "-File", str(COLLECTOR),
            "-OracleRunId", "SYNTHETIC-RUN-V3",
            "-ExternalIdentityReceiptSha256", "A" * 64,
            "-FixtureMemoryPath", str(FIXTURE_MEMORY),
            "-FixtureIdentityPath", str(FIXTURE_IDENTITY),
            "-OutputPath", str(cls.capture_path),
        ], check=True, capture_output=True, text=True)
        cls.base = json.loads(cls.capture_path.read_text(encoding="utf-8-sig"))
        cls.collector_hash = digest(COLLECTOR)

    @classmethod
    def tearDownClass(cls) -> None:
        cls.temp.cleanup()

    def evaluate(self, data: dict, *, capture_hash: str | None = None,
                 collector_hash: str | None = None, run_id: str = "SYNTHETIC-RUN-V3") -> dict:
        path = self.temp_path / f"mutation-{len(list(self.temp_path.glob('mutation-*.json')))}.json"
        path.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        return module.evaluate(
            data, path, COLLECTOR,
            capture_hash or digest(path), collector_hash or self.collector_hash, run_id, "A" * 64,
        )

    def test_positive_corrected_candidate_is_offline_only(self) -> None:
        result = self.evaluate(copy.deepcopy(self.base))
        self.assertEqual(result["status"], "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS")
        self.assertEqual(result["offlineCandidateRegion"]["clientRect"], {"left": 560, "top": 420, "right": 656, "bottom": 436})
        self.assertEqual(result["offlineCandidateRegion"]["safePoint"], {"x": 607, "y": 427})
        for key in ("originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed", "warpPrelaunchEligible", "launchEligible", "permitEligible", "permitIssued"):
            self.assertFalse(result[key])
        self.assertIsNone(result["automaticActivationPoint"])
        self.assertIn("FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION", result["remainingLiveBlockers"])

    def test_collector_removed_false_owner_gates_and_accepts_separate_viewport(self) -> None:
        owner = self.base["snapshotA"]["strategyOwner"]
        self.assertEqual(set(owner), {"pointer", "role"})
        self.assertNotIn("builderMode", owner)
        self.assertEqual(self.base["snapshotA"]["coordinateFrame"]["engineClientRect"]["bottom"], 576)
        self.assertEqual(self.base["process"]["clientHeightA"], 600)
        self.assertTrue(self.base["semanticCandidateEligible"])
        self.assertEqual(self.base["operations"]["memoryReadCount"], 150)

    def test_collector_is_windows_powershell_51_fixture_compatible(self) -> None:
        output = self.temp_path / "powershell51-capture.json"
        completed = subprocess.run([
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(COLLECTOR),
            "-OracleRunId", "SYNTHETIC-RUN-V3", "-ExternalIdentityReceiptSha256", "A" * 64,
            "-FixtureMemoryPath", str(FIXTURE_MEMORY), "-FixtureIdentityPath", str(FIXTURE_IDENTITY),
            "-OutputPath", str(output),
        ], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        data = json.loads(output.read_text(encoding="utf-8-sig"))
        self.assertTrue(data["semanticCandidateEligible"])
        self.assertEqual(data["operations"]["memoryReadCount"], 150)
        completed = subprocess.run([
            "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(COLLECTOR),
            "-OracleRunId", "SYNTHETIC-RUN-V3", "-ExternalIdentityReceiptSha256", "A" * 64,
            "-FixtureMemoryPath", str(FIXTURE_MEMORY), "-FixtureIdentityPath", str(FIXTURE_IDENTITY),
            "-OutputPath", str(output),
        ], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertTrue(json.loads(output.read_text(encoding="utf-8-sig"))["semanticCandidateEligible"])

    def test_closed_and_semantic_mutations(self) -> None:
        cases = []

        def add(name, change, blocker):
            cases.append((name, change, blocker))

        add("root-extra", lambda x: x.__setitem__("extra", 1), "ROOT_KEYS_MISMATCH")
        add("root-missing", lambda x: x.pop("oracleRunId"), "ROOT_KEYS_MISMATCH")
        add("schema", lambda x: x.__setitem__("schemaVersion", 2), "SCHEMA_OR_RECEIPT_TYPE_MISMATCH")
        add("provenance", lambda x: x.__setitem__("provenance", "UNKNOWN"), "PROVENANCE_INVALID")
        add("run", lambda x: x.__setitem__("oracleRunId", "OTHER"), "ORACLE_RUN_ID_MISMATCH")
        add("identity-receipt-hash", lambda x: x.__setitem__("externalIdentityReceiptSha256", "bad"), "EXTERNAL_IDENTITY_RECEIPT_SHA256_INVALID")
        add("identity-receipt-valid-substitution", lambda x: x.__setitem__("externalIdentityReceiptSha256", "B" * 64), "EXTERNAL_IDENTITY_RECEIPT_SHA256_MISMATCH")
        add("hash", lambda x: x["process"].__setitem__("sha256", "0" * 64), "EXECUTABLE_HASH_MISMATCH")
        add("module", lambda x: x["process"].__setitem__("moduleBase", "0x00500000"), "MODULE_IDENTITY_MISMATCH")
        add("pid", lambda x: x["process"].__setitem__("pid", 0), "OWNED_HWND_PID_MISMATCH")
        add("hwnd-owner-a", lambda x: x["process"].__setitem__("hwndOwnerPidA", 1), "OWNED_HWND_PID_MISMATCH")
        add("surface", lambda x: x["process"].__setitem__("clientWidthB", 799), "OWNED_HWND_SURFACE_TORN")
        add("visible", lambda x: x["process"].__setitem__("hwndVisibleB", False), "OWNED_HWND_NOT_VISIBLE")
        add("time-order", lambda x: x.__setitem__("observedAtUtc", "2000-01-01T00:00:00Z"), "CAPTURE_TIME_ORDER_INVALID")
        add("time-future", lambda x: x.__setitem__("captureCompletedAtUtc", "2999-01-01T00:00:00Z"), "CAPTURE_TIME_FUTURE_INVALID")
        add("role", lambda x: x["rootRoles"].__setitem__("strategyOwnerRole", "WRONG"), "ROOT_ROLE_ADJUDICATION_MISMATCH")
        add("torn", lambda x: x["snapshotB"]["manager65"].__setitem__("page", 3), "TORN_SNAPSHOT")
        add("ui-root", lambda x: x["snapshotA"]["uiRoot"].__setitem__("pointer", "0x00000000"), "UI_ROOT_OR_REGISTRY_NULL")
        add("builder", lambda x: x["snapshotA"]["uiRoot"].__setitem__("builderMode", 1), "UI_ROOT_BUILDER_MODE_NOT_2")
        add("handler", lambda x: x["snapshotA"]["uiRoot"].__setitem__("handlerState", 0), "UI_ROOT_HANDLER_STATE_NOT_1")
        add("owner-formula", lambda x: x["snapshotA"]["strategyOwner"].__setitem__("pointer", "0x00C9E000"), "STRATEGY_OWNER_FORMULA_MISMATCH")
        add("legacy-owner-field", lambda x: x["snapshotA"]["strategyOwner"].__setitem__("strategyMode", 2), "STRATEGY_OWNER_KEYS_MISMATCH")
        add("controller", lambda x: x["snapshotA"]["manager65"].__setitem__("controllerPointer", "0x00C9E000"), "MANAGER65_CONTROLLER_FORMULA_MISMATCH")
        add("manager-null", lambda x: x["snapshotA"]["manager65"].__setitem__("managerPointer", "0x00000000"), "MANAGER65_REGISTRY_MISMATCH")
        add("manager-id", lambda x: x["snapshotA"]["manager65"]["context"]["nodes"][0].__setitem__("id", 102), "MANAGER65_ID_MISMATCH")
        add("manager-inactive", lambda x: x["snapshotA"]["manager65"]["context"]["nodes"][0].__setitem__("active", 0), "MANAGER65_CONTEXT_INACTIVE")
        add("input-gate", lambda x: x["snapshotA"]["manager65"].__setitem__("inputGate", 0), "MANAGER65_CONTEXT_INACTIVE")
        add("page", lambda x: x["snapshotA"]["manager65"].__setitem__("page", 6), "MANAGER65_PAGE_OUT_OF_RANGE")
        add("count", lambda x: x["snapshotA"]["manager65"].__setitem__("actionCount", 25), "MANAGER65_ACTION_COUNT_OUT_OF_RANGE")
        add("card", lambda x: x["snapshotA"]["manager65"].__setitem__("cardId", -1), "MANAGER65_BOUND_CARD_ID_INVALID")
        add("record-owner", lambda x: x["snapshotA"]["manager65"].__setitem__("recordOwnerPointer", "0x00000000"), "CURRENT_CHARACTER_OWNER_NULL")
        add("record-count", lambda x: x["snapshotA"]["manager65"].__setitem__("recordActionCount", 2), "MANAGER65_RECORD_ACTION_COUNT_MISMATCH")
        add("selected", lambda x: x["snapshotA"]["manager65"].__setitem__("selectedIndex", 0), "MANAGER65_SELECTED_INDEX_NOT_RESET")
        add("manager67", lambda x: x["snapshotA"]["manager67"].__setitem__("managerId", 102), "MANAGER67_STRUCTURAL_MISMATCH")
        add("both-active", lambda x: (x["snapshotA"]["manager67"].__setitem__("active", 1), x["snapshotA"]["manager67"].__setitem__("inputGate", 1)), "MANAGER65_MANAGER67_SIMULTANEOUSLY_ACTIVE")
        add("manager67-input", lambda x: x["snapshotA"]["manager67"].__setitem__("inputGate", 1), "MANAGER67_NOT_DORMANT")
        add("no-warp", lambda x: x["snapshotA"]["manager65"]["actions"][1].__setitem__("commandId", 44), "ACTION_0X2B_NOT_FOUND")
        add("duplicate-warp", lambda x: x["snapshotA"]["manager65"]["actions"][0].__setitem__("commandId", 43), "ACTION_0X2B_NOT_UNIQUE")
        add("widget-null", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("widgetPointer", "0x00000000"), "ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        add("nested-widget-extra", lambda x: x["snapshotA"]["manager65"]["actions"][0]["widget"].__setitem__("extra", 1), "ACTION_WIDGET_KEYS_MISMATCH")
        add("widget-init", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("initialized", 0), "ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        add("widget-hit", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("hitTestEnabled", 0), "ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        add("widget-active", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("activeVisible", 0), "ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        add("widget-render", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("renderVisible", 0), "ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        add("widget-size", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("width", 0), "ACTION_0X2B_SIZE_INVALID")
        add("local-transform", lambda x: (x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("localSelector", 1), x["snapshotA"]["manager65"]["actions"][1]["widget"].__setitem__("localGate", 0)), "ACTION_0X2B_LOCAL_TRANSFORM_INVALID")
        add("scale-zero", lambda x: x["snapshotA"]["coordinateFrame"].__setitem__("scaleX", 0), "INVALID_SCALE")
        add("viewport", lambda x: x["snapshotA"]["coordinateFrame"]["engineClientRect"].__setitem__("bottom", 601), "ENGINE_VIEWPORT_OUTSIDE_OWNED_HWND")
        add("no-client-pixels", lambda x: x["snapshotA"]["manager65"]["actions"][1]["widget"]["logicalRect"].update({"left": 5000, "right": 5100}), "ACTION_0X2B_NO_OWNED_HWND_CLIENT_PIXELS")
        add("read-count", lambda x: x["operations"].__setitem__("memoryReadCount", 0), "READ_ONLY_OPERATION_RECEIPT_INVALID")
        add("memory-write", lambda x: x["operations"].__setitem__("memoryWrites", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("input", lambda x: x["operations"].__setitem__("gameInputs", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("retry", lambda x: x["operations"].__setitem__("retries", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("debugger", lambda x: x["operations"].__setitem__("debuggerAttach", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("breakpoint", lambda x: x["operations"].__setitem__("breakpointsInstalled", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("vm", lambda x: x["operations"].__setitem__("vmLifecycleChanges", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("server", lambda x: x["operations"].__setitem__("serverChanges", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("protocol", lambda x: x["operations"].__setitem__("protocolChanges", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("database", lambda x: x["operations"].__setitem__("databaseChanges", 1), "FORBIDDEN_OPERATION_PRESENT")
        add("permit", lambda x: x.__setitem__("permitIssued", True), "SELF_PROMOTION_FIELD_TRUE")
        add("activation-point", lambda x: x.__setitem__("automaticActivationPoint", {"x": 1, "y": 1}), "SELF_PROMOTED_ACTIVATION_POINT")
        add("runtime", lambda x: x.__setitem__("originalRuntimeObserved", True), "SELF_PROMOTION_FIELD_TRUE")

        for name, change, blocker in cases:
            with self.subTest(name=name):
                data = copy.deepcopy(self.base)
                change(data)
                result = self.evaluate(data)
                self.assertIn(blocker, result["semanticBlockers"])
                self.assertFalse(result["actionSemanticCandidate"])

    def test_external_hashes_are_not_self_asserted(self) -> None:
        result = self.evaluate(copy.deepcopy(self.base), capture_hash="0" * 64)
        self.assertIn("SOURCE_CAPTURE_SHA256_MISMATCH", result["semanticBlockers"])
        result = self.evaluate(copy.deepcopy(self.base), collector_hash="0" * 64)
        self.assertIn("COLLECTOR_SHA256_MISMATCH", result["semanticBlockers"])

    def test_live_self_claim_remains_rejected(self) -> None:
        data = copy.deepcopy(self.base)
        data["provenance"] = "LIVE_READONLY"
        result = self.evaluate(data)
        self.assertIn("LIVE_CAPTURE_REQUIRES_EXTERNAL_INDEPENDENT_BINDING", result["semanticBlockers"])
        self.assertFalse(result["warpPrelaunchEligible"])
        self.assertIsNone(result["automaticActivationPoint"])

    def test_cli_writes_atomically_and_rejects_bad_hash(self) -> None:
        output = self.temp_path / "cli-output.json"
        completed = subprocess.run([
            sys.executable, str(EVALUATOR), "--capture", str(self.capture_path), "--collector", str(COLLECTOR),
            "--expected-capture-sha256", digest(self.capture_path), "--expected-collector-sha256", self.collector_hash,
            "--expected-run-id", "SYNTHETIC-RUN-V3", "--output", str(output),
            "--expected-external-identity-receipt-sha256", "A" * 64,
        ], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertTrue(output.exists())
        rejected = self.temp_path / "cli-rejected.json"
        completed = subprocess.run([
            sys.executable, str(EVALUATOR), "--capture", str(self.capture_path), "--collector", str(COLLECTOR),
            "--expected-capture-sha256", "0" * 64, "--expected-collector-sha256", self.collector_hash,
            "--expected-run-id", "SYNTHETIC-RUN-V3", "--output", str(rejected),
            "--expected-external-identity-receipt-sha256", "A" * 64,
        ], capture_output=True, text=True)
        self.assertEqual(completed.returncode, 2)
        self.assertTrue(rejected.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
