from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

CANONICAL_SHA256 = "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"
EXPECTED_PATHS = {
    "managerArtifactLedger": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/artifact-ledger.json",
    "managerCollector": "work/20260829-original-client-manager65-corrected-collector-v3/src/collect-manager65-action2b-v3.ps1",
    "managerEvaluator": "work/20260829-original-client-manager65-corrected-collector-v3/src/evaluate_manager65_capture_v3.py",
    "managerCapture": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/fixture-capture.json",
    "managerEvaluation": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/fixture-evaluation.json",
    "managerReview": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/independent-review.json",
    "managerFinalVerification": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/final-verification.json",
    "prelaunchRootRole": "work/20260829-original-client-warp-prelaunch-v10/evidence/root-role-static-adjudication.json",
    "prelaunchReview": "work/20260829-original-client-warp-prelaunch-v10/evidence/independent-review.json",
    "prelaunchFinalVerification": "work/20260829-original-client-warp-prelaunch-v10/evidence/final-verification.json",
    "prelaunchOperationLedger": "work/20260829-original-client-warp-prelaunch-v10/evidence/operation-attempt-ledger.json",
    "activationPolicy": "work/20260829-original-client-activation-budget-stage-contract-v1/evidence/activation-budget-stage-policy.json",
    "authorityRecord": "work/20260829-original-client-activation-budget-stage-contract-v1/evidence/current-thread-authority-record.json",
}
READINESS_BLOCKERS = [
    "FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION",
    "FRESH_RUN_IDENTITY_RECOLLECTION_REQUIRED",
    "FRESH_LISTENER_HEARTBEAT_FOREGROUND_REQUIRED",
    "FRESH_CORRECTED_MANAGER65_V3_LIVE_CAPTURE_REQUIRED",
    "INDEPENDENT_MANAGER65_HIT_REGION_BINDING_REQUIRED",
    "MANAGER67_PRIOR_STAGE_HIT_REGION_RECEIPT_REQUIRED",
    "FRESH_MOVEMENT_BREAKPOINT_INSTALLATION_AND_INITIAL_DR_SNAPSHOT_MISSING",
    "DEBUGGER_BUILD_RECONCILIATION_REQUIRED",
    "INDEPENDENT_LIVE_PRELAUNCH_REVIEW_REQUIRED",
    "NEW_WARP_STAGE_PERMIT_NOT_ISSUED",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def load(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8-sig"))


def exact(value: Any, keys: set[str], label: str, blockers: list[str]) -> bool:
    if not isinstance(value, dict):
        blockers.append(f"{label}_NOT_OBJECT")
        return False
    if set(value) != keys:
        blockers.append(f"{label}_KEYS_MISMATCH")
        return False
    return True


def valid_sha(value: Any) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdefABCDEF" for c in value)


def evaluate(gate: dict[str, Any], gate_path: Path, expected: dict[str, Any], expected_path: Path,
             expected_manifest_sha256: str, repo_root: Path) -> dict[str, Any]:
    structural: list[str] = []
    exact(expected, {"schemaVersion", "receiptType", "roles"}, "EXPECTED_HASH_MANIFEST", structural)
    if sha256(expected_path) != expected_manifest_sha256.upper():
        structural.append("EXPECTED_HASH_MANIFEST_SHA256_MISMATCH")
    if expected.get("schemaVersion") != 1 or expected.get("receiptType") != "WARP_STAGE_GATE_V2_EXTERNAL_EXPECTED_HASHES":
        structural.append("EXPECTED_HASH_MANIFEST_TYPE_MISMATCH")
    roles = expected.get("roles", {})
    exact(roles, set(EXPECTED_PATHS), "EXPECTED_HASH_ROLES", structural)
    for role, digest in roles.items() if isinstance(roles, dict) else []:
        if not valid_sha(digest):
            structural.append(f"EXPECTED_{role}_SHA256_INVALID")

    gate_keys = {"schemaVersion", "receiptType", "evidenceClass", "oracleRunId", "authorityEnvelope", "sources", "stages", "operations", "claimCeiling"}
    exact(gate, gate_keys, "GATE_INPUT", structural)
    if gate.get("schemaVersion") != 2 or gate.get("receiptType") != "ORIGINAL_CLIENT_WARP_STAGE_GATE_V2_INPUT":
        structural.append("GATE_SCHEMA_OR_TYPE_MISMATCH")
    if gate.get("evidenceClass") != "SYNTHETIC_FIXTURE_OFFLINE_ONLY":
        structural.append("EVIDENCE_CLASS_MISMATCH")
    if gate.get("oracleRunId") != "SYNTHETIC-RUN-V3":
        structural.append("OFFLINE_RUN_ID_MISMATCH")

    source_objects: dict[str, dict[str, Any]] = gate.get("sources", {})
    exact(source_objects, set(EXPECTED_PATHS), "SOURCES", structural)
    loaded: dict[str, dict[str, Any]] = {}
    if isinstance(source_objects, dict):
        for role, expected_relative in EXPECTED_PATHS.items():
            source = source_objects.get(role, {})
            exact(source, {"path", "sha256"}, f"SOURCE_{role}", structural)
            if source.get("path") != expected_relative:
                structural.append(f"SOURCE_{role}_PATH_MISMATCH")
                continue
            if not valid_sha(source.get("sha256")):
                structural.append(f"SOURCE_{role}_SHA256_INVALID")
                continue
            if source.get("sha256", "").upper() != str(roles.get(role, "")).upper():
                structural.append(f"SOURCE_{role}_EXPECTED_SHA256_MISMATCH")
            actual_path = (repo_root / expected_relative).resolve()
            if repo_root not in actual_path.parents:
                structural.append(f"SOURCE_{role}_OUTSIDE_REPOSITORY")
                continue
            if not actual_path.is_file():
                structural.append(f"SOURCE_{role}_MISSING")
                continue
            if sha256(actual_path) != source.get("sha256", "").upper():
                structural.append(f"SOURCE_{role}_ACTUAL_SHA256_MISMATCH")
                continue
            if actual_path.suffix.lower() == ".json":
                loaded[role] = load(actual_path)

    authority_keys = {"authorizationId", "authoritySourceSha256", "oneRunApproved", "maxPhysicalActivations", "physicalActivationsConsumed", "physicalActivationsRemaining", "automaticInputBudget", "retryBudget", "issuedForStage", "reusableAfterFailure"}
    authority = gate.get("authorityEnvelope", {})
    exact(authority, authority_keys, "AUTHORITY_ENVELOPE", structural)
    expected_authority = {
        "authorizationId": "CURRENT_THREAD_ORACLE_ONE_RUN_ONE_ACTIVATION_V1",
        "oneRunApproved": True, "maxPhysicalActivations": 1, "physicalActivationsConsumed": 0,
        "physicalActivationsRemaining": 1, "automaticInputBudget": 0, "retryBudget": 0,
        "issuedForStage": "WARP_ONLY", "reusableAfterFailure": False,
    }
    for key, value in expected_authority.items():
        if authority.get(key) != value:
            structural.append(f"AUTHORITY_{key}_MISMATCH")
    if authority.get("authoritySourceSha256") != roles.get("authorityRecord"):
        structural.append("AUTHORITY_SOURCE_SHA256_MISMATCH")
    if isinstance(authority.get("maxPhysicalActivations"), int) and isinstance(authority.get("physicalActivationsConsumed"), int) and isinstance(authority.get("physicalActivationsRemaining"), int):
        if authority["physicalActivationsConsumed"] + authority["physicalActivationsRemaining"] != authority["maxPhysicalActivations"]:
            structural.append("AUTHORITY_BUDGET_CONSERVATION_MISMATCH")

    stages = gate.get("stages", {})
    exact(stages, {"warp", "destination", "confirm"}, "STAGES", structural)
    for stage in ("warp", "destination", "confirm"):
        record = stages.get(stage, {}) if isinstance(stages, dict) else {}
        exact(record, {"lifecycle", "consumed"}, f"STAGE_{stage}", structural)
        if record.get("lifecycle") != "NOT_CREATED" or record.get("consumed") != 0:
            structural.append(f"STAGE_{stage}_MUST_BE_NOT_CREATED_UNCONSUMED")

    operation_keys = {"liveRuns", "processMemoryReads", "processMemoryWrites", "physicalInputs", "automaticInputs", "retries", "binaryPatches", "debuggerAttach", "debuggerCommands", "breakpointsInstalled", "ownedHwndCaptures", "vmLifecycleChanges", "serverChanges", "protocolChanges", "databaseChanges", "permitIssuance"}
    operations = gate.get("operations", {})
    exact(operations, operation_keys, "OPERATIONS", structural)
    for key in operation_keys:
        if operations.get(key) != 0:
            structural.append(f"OPERATION_{key}_NONZERO")

    claim_keys = {"originalRuntimeObserved", "freshIdentityBound", "independentLiveBinding", "livePromotionAllowed", "warpPrelaunchEligible", "activationEligible", "launchEligible", "permitEligible", "permitIssued", "activationPoint", "automaticActivationPoint", "permit"}
    claims = gate.get("claimCeiling", {})
    exact(claims, claim_keys, "CLAIM_CEILING", structural)
    for key in claim_keys - {"activationPoint", "automaticActivationPoint", "permit"}:
        if claims.get(key) is not False:
            structural.append(f"CLAIM_{key}_MUST_BE_FALSE")
    for key in ("activationPoint", "automaticActivationPoint", "permit"):
        if claims.get(key) is not None:
            structural.append(f"CLAIM_{key}_MUST_BE_NULL")

    # Cross-receipt manager65 v3 invariants.
    capture = loaded.get("managerCapture", {})
    manager_eval = loaded.get("managerEvaluation", {})
    manager_review = loaded.get("managerReview", {})
    manager_final = loaded.get("managerFinalVerification", {})
    manager_ledger = loaded.get("managerArtifactLedger", {})
    if capture.get("schemaVersion") != 3 or capture.get("provenance") != "SYNTHETIC_FIXTURE" or capture.get("oracleRunId") != gate.get("oracleRunId"):
        structural.append("MANAGER_CAPTURE_NOT_SYNTHETIC_BOUND_CANDIDATE")
    if capture.get("externalIdentityReceiptSha256") != "A" * 64:
        structural.append("MANAGER_CAPTURE_SYNTHETIC_IDENTITY_TOKEN_MISMATCH")
    for key in ("originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed", "warpPrelaunchEligible", "launchEligible", "permitEligible", "permitIssued"):
        if capture.get(key) is not False:
            structural.append("MANAGER_CAPTURE_SELF_PROMOTION_PRESENT")
            break
    if capture.get("automaticActivationPoint") is not None:
        structural.append("MANAGER_CAPTURE_ACTIVATION_POINT_PRESENT")
    if manager_eval.get("status") != "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS" or manager_eval.get("sourceCaptureSha256") != roles.get("managerCapture") or manager_eval.get("sourceCollectorSha256") != roles.get("managerCollector") or manager_eval.get("oracleRunId") != gate.get("oracleRunId"):
        structural.append("MANAGER_EVALUATION_CROSS_BINDING_MISMATCH")
    if manager_review.get("verdict") != "APPROVE" or manager_review.get("scope") != "BOUNDED_OFFLINE_MANAGER65_CORRECTED_COLLECTOR_V3_ONLY":
        structural.append("MANAGER_REVIEW_NOT_OFFLINE_APPROVED")
    if manager_final.get("result") != "PASS" or manager_final.get("status") != "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_COLLECTOR_V3_PASS_RUNTIME_UNSEEN" or manager_final.get("overallGameGoal") != "INCOMPLETE":
        structural.append("MANAGER_FINAL_VERIFICATION_MISMATCH")
    verification = manager_final.get("verification", {})
    if verification.get("artifactLedgerSha256") != roles.get("managerArtifactLedger") or verification.get("independentReviewSha256") != roles.get("managerReview") or verification.get("pwsh", {}).get("tests") != 7 or verification.get("pwsh", {}).get("mutations") != 62 or verification.get("windowsPowerShell51", {}).get("tests") != 7:
        structural.append("MANAGER_FINAL_HASH_OR_TEST_BINDING_MISMATCH")
    ledger_map = {item.get("path"): item.get("sha256") for item in manager_ledger.get("artifacts", []) if isinstance(item, dict)}
    expected_ledger = {
        "src/collect-manager65-action2b-v3.ps1": roles.get("managerCollector"),
        "src/evaluate_manager65_capture_v3.py": roles.get("managerEvaluator"),
        "evidence/fixture-capture.json": roles.get("managerCapture"),
        "evidence/fixture-evaluation.json": roles.get("managerEvaluation"),
        "evidence/independent-review.json": roles.get("managerReview"),
    }
    if any(ledger_map.get(path) != digest for path, digest in expected_ledger.items()):
        structural.append("MANAGER_ARTIFACT_LEDGER_CROSS_BINDING_MISMATCH")

    # Current prelaunch must remain blocked before attach/input.
    root_role = loaded.get("prelaunchRootRole", {})
    current_boundary = root_role.get("currentLiveBoundary", {})
    if current_boundary.get("status") != "BLOCKED" or current_boundary.get("reason") != READINESS_BLOCKERS[0] or current_boundary.get("freshOwnedHwnd") != "MISSING":
        structural.append("PRELAUNCH_CURRENT_BOUNDARY_MISMATCH")
    if root_role.get("claimCeiling") != "STATIC_MAPPED_WITH_PARTIAL_RUNTIME_STRUCTURAL_CORROBORATION_NOT_WARP_READY":
        structural.append("PRELAUNCH_CLAIM_CEILING_MISMATCH")
    prelaunch_review = loaded.get("prelaunchReview", {})
    prelaunch_final = loaded.get("prelaunchFinalVerification", {})
    prelaunch_ops = loaded.get("prelaunchOperationLedger", {}).get("aggregate", {})
    if prelaunch_review.get("verdict") != "APPROVE" or prelaunch_review.get("unitVerdict") != "PRELAUNCH_V10_BLOCKED_BEFORE_ATTACH_OR_INPUT":
        structural.append("PRELAUNCH_REVIEW_MISMATCH")
    if prelaunch_final.get("status") != "PASS" or prelaunch_final.get("verdict") != "PRELAUNCH_V10_BLOCKED_BEFORE_ATTACH_OR_INPUT":
        structural.append("PRELAUNCH_FINAL_VERIFICATION_MISMATCH")
    for key in ("processMemoryWrites", "debuggerAttachCount", "debuggerCommands", "breakpointsInstalled", "ownedHwndCaptures", "gameInputs", "automaticInputs", "physicalActivations", "permitIssuance", "vmLifecycleChanges", "serverChanges", "protocolChanges", "databaseChanges"):
        if prelaunch_ops.get(key) != 0:
            structural.append(f"PRELAUNCH_OPERATION_{key}_NONZERO")

    # Authority source exact fields and v1 three-activation mismatch retirement.
    policy = loaded.get("activationPolicy", {})
    authority_record = loaded.get("authorityRecord", {})
    if authority_record.get("authorityId") != expected_authority["authorizationId"] or authority_record.get("adjudication", {}).get("maxPhysicalActivations") != 1 or authority_record.get("adjudication", {}).get("isPermit") is not False or authority_record.get("permitIssued") is not False:
        structural.append("AUTHORITY_RECORD_SEMANTICS_MISMATCH")
    current_authority = policy.get("currentAuthority", {})
    stage_policy = policy.get("stagePolicy", {})
    prior_permit = policy.get("priorPermit", {})
    if current_authority.get("activationBudget") != 1 or current_authority.get("liveRunsRemaining") != 1 or current_authority.get("isPermit") is not False:
        structural.append("ACTIVATION_POLICY_CURRENT_AUTHORITY_MISMATCH")
    if stage_policy.get("currentStage") != "WARP" or stage_policy.get("allocation") != {"WARP": 1, "DESTINATION": 0, "CONFIRM": 0} or stage_policy.get("authorizedPrefixLength") != 1 or stage_policy.get("stopAfterAuthorizedStage") is not True or stage_policy.get("fullTransactionLaunchEligible") is not False:
        structural.append("ACTIVATION_POLICY_STAGE_ALLOCATION_MISMATCH")
    if prior_permit.get("state") != "CONSUMED_NO_RETRY" or prior_permit.get("reusable") is not False:
        structural.append("PRIOR_PERMIT_REUSE_POLICY_MISMATCH")

    structural = list(dict.fromkeys(structural))
    audit_pass = not structural
    return {
        "schemaVersion": 2,
        "receiptType": "ORIGINAL_CLIENT_WARP_STAGE_GATE_V2_EVALUATION",
        "sourceGateInputSha256": sha256(gate_path),
        "expectedHashManifestSha256": sha256(expected_path),
        "oracleRunId": gate.get("oracleRunId"),
        "stage": "WARP",
        "status": "OFFLINE_WARP_GATE_V2_AUDIT_PASS_READY_FALSE" if audit_pass else "OFFLINE_WARP_GATE_V2_AUDIT_REJECTED",
        "auditPass": audit_pass,
        "authorityStatus": "WARP_ONLY_ONE_ACTIVATION_PRESERVED" if audit_pass else "REJECTED",
        "stageLocalMaxPhysicalActivations": 1,
        "physicalActivationsBefore": 0,
        "physicalActivationsRemaining": 1,
        "stages": {"warp": {"lifecycle": "NOT_CREATED", "consumed": 0}, "destination": {"lifecycle": "NOT_CREATED", "consumed": 0}, "confirm": {"lifecycle": "NOT_CREATED", "consumed": 0}},
        "warpPreparation": {"source": "MANAGER65_V3_SYNTHETIC_OFFLINE_CANDIDATE", "offlineCandidatePresent": audit_pass, "liveBindingLifecycle": "NOT_CREATED", "commandId": 43 if audit_pass else None, "bindingSourceSha256": None},
        "structuralBlockers": structural,
        "readinessBlockers": READINESS_BLOCKERS if audit_pass else [],
        "deferredFullTransactionBlocker": "FULL_MOVEMENT_TRANSACTION_AUTHORITY_INSUFFICIENT_TWO_ADDITIONAL_PHYSICAL_ACTIVATIONS_REQUIRED",
        "activationEligible": False,
        "warpPrelaunchEligible": False,
        "launchEligible": False,
        "permitEligible": False,
        "permitIssued": False,
        "permit": None,
        "activationPoint": None,
        "automaticActivationPoint": None,
        "originalRuntimeObserved": False,
        "independentLiveBinding": False,
        "livePromotionAllowed": False,
        "futureStageDisposition": "DESTINATION_AND_CONFIRM_NOT_CREATED_NOT_AUTHORIZED",
        "postWarpRequiredEvidence": ["WARP_INPUT_CONSUMED_EXACTLY_ONCE", "DESTINATION_STAGE_CREATED", "OWNED_HWND_POST_WARP_CAPTURE", "MVB01_ACCEPTED_HIT_COUNT_ZERO", "INITIAL_DR_ACTIVE_SET_UNCHANGED", "STOP_AND_HANDOFF_NO_FURTHER_INPUT"],
        "operationsPerformedByEvaluator": {"fileReads": len(EXPECTED_PATHS) + 2, "liveRuns": 0, "processMemoryReads": 0, "processMemoryWrites": 0, "physicalInputs": 0, "automaticInputs": 0, "debuggerOperations": 0, "vmOperations": 0, "serverProtocolDbChanges": 0, "permitIssuance": 0},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gate-input", type=Path, required=True)
    parser.add_argument("--expected-hashes", type=Path, required=True)
    parser.add_argument("--expected-hashes-sha256", required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo_root = args.repo_root.resolve()
    result = evaluate(load(args.gate_input), args.gate_input, load(args.expected_hashes), args.expected_hashes, args.expected_hashes_sha256, repo_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    temporary.replace(args.output)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if result["auditPass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
