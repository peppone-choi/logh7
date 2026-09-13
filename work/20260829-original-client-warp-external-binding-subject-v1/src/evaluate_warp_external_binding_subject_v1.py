from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from fractions import Fraction
from pathlib import Path
from typing import Any

import jsonschema

CHAIN_KEYS = ["h1Identity", "h2Capture", "h3Evaluation", "h4HitRegionSubject", "h5IndependentReview", "h6Bundle"]
EXPECTED_BLOCKERS = [
    "FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION",
    "H1_IDENTITY_NOT_CREATED", "H2_CAPTURE_NOT_CREATED", "H3_EVALUATION_NOT_CREATED",
    "H4_HIT_REGION_SUBJECT_NOT_CREATED", "H5_INDEPENDENT_REVIEW_NOT_CREATED", "H6_BUNDLE_NOT_CREATED",
    "NEW_WARP_STAGE_PERMIT_NOT_ISSUED",
]
EXPECTED_ORDER = ["H1_IDENTITY", "H2_CAPTURE", "H3_EVALUATION", "H4_HIT_REGION_SUBJECT", "H5_INDEPENDENT_REVIEW", "H6_BUNDLE"]
EXPECTED_STEPS = {
    "H1_IDENTITY": ([], "FRESH_SAME_RUN_IDENTITY"),
    "H2_CAPTURE": (["H1_IDENTITY"], "CORRECTED_MANAGER65_LIVE_CAPTURE"),
    "H3_EVALUATION": (["H1_IDENTITY", "H2_CAPTURE"], "MANAGER65_LIVE_EVALUATION"),
    "H4_HIT_REGION_SUBJECT": (["H1_IDENTITY", "H2_CAPTURE", "H3_EVALUATION"], "INDEPENDENT_HIT_REGION_SUBJECT"),
    "H5_INDEPENDENT_REVIEW": (["H1_IDENTITY", "H2_CAPTURE", "H3_EVALUATION", "H4_HIT_REGION_SUBJECT"], "INDEPENDENT_READ_ONLY_REVIEW"),
    "H6_BUNDLE": (["H1_IDENTITY", "H2_CAPTURE", "H3_EVALUATION", "H4_HIT_REGION_SUBJECT", "H5_INDEPENDENT_REVIEW"], "EXTERNAL_WARP_BINDING_BUNDLE"),
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def rect_tuple(rect: dict[str, int]) -> tuple[int, int, int, int]:
    return rect["left"], rect["top"], rect["right"], rect["bottom"]


def decode_positive_normal_binary32(bits_hex: str) -> tuple[Fraction, float]:
    bits = int(bits_hex, 16)
    sign = bits >> 31
    exponent = (bits >> 23) & 0xFF
    fraction = bits & 0x7FFFFF
    if sign or exponent in (0, 0xFF):
        raise ValueError("scale must be positive normal IEEE-754 binary32")
    numerator = (1 << 23) + fraction
    power = exponent - 127 - 23
    rational = Fraction(numerator << power, 1) if power >= 0 else Fraction(numerator, 1 << (-power))
    decoded = struct.unpack(">f", bytes.fromhex(bits_hex))[0]
    return rational, decoded


def trunc_nonnegative(pixel: int, scale: Fraction) -> int:
    return (pixel * scale.numerator) // scale.denominator


def inverse_axis(lo: int, hi: int, limit: int, scale: Fraction) -> tuple[int, int] | None:
    members = [pixel for pixel in range(limit) if lo <= trunc_nonnegative(pixel, scale) < hi]
    if not members:
        return None
    if members != list(range(members[0], members[-1] + 1)):
        raise ValueError("discrete inverse is non-contiguous")
    return members[0], members[-1] + 1


def evaluate(contract: dict[str, Any], contract_path: Path, schema: dict[str, Any], schema_path: Path,
             expected_schema_sha256: str, expected_capture_sha256: str,
             expected_evaluation_sha256: str, repo_root: Path) -> dict[str, Any]:
    blockers: list[str] = []
    if sha256(schema_path) != expected_schema_sha256.upper():
        blockers.append("SCHEMA_SHA256_MISMATCH")
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
        errors = sorted(jsonschema.Draft202012Validator(schema).iter_errors(contract), key=lambda error: list(error.absolute_path))
        blockers.extend(f"SCHEMA_VALIDATION:{'/'.join(map(str, error.absolute_path)) or '$'}:{error.validator}" for error in errors)
    except Exception as error:
        blockers.append(f"SCHEMA_ENGINE_ERROR:{type(error).__name__}")

    authority = contract.get("authorityEnvelope", {})
    if all(isinstance(authority.get(key), int) and not isinstance(authority.get(key), bool) for key in ("maxPhysicalActivations", "physicalActivationsConsumed", "physicalActivationsRemaining")):
        if authority["physicalActivationsConsumed"] + authority["physicalActivationsRemaining"] != authority["maxPhysicalActivations"]:
            blockers.append("AUTHORITY_BUDGET_CONSERVATION_MISMATCH")

    live = contract.get("liveSubject", {})
    chain = live.get("chain", {})
    for key in CHAIN_KEYS:
        step = chain.get(key, {}) if isinstance(chain, dict) else {}
        if step.get("lifecycle") != "NOT_CREATED" or any(step.get(field) is not None for field in ("receipt", "sourcePath", "sourceSha256", "expectedSha256", "observedAtUtc")):
            blockers.append(f"{key.upper()}_MUST_BE_NOT_CREATED_NULL")
    if live.get("oracleRunId") is not None or live.get("lifecycle") != "NOT_CREATED" or any(live.get(field) is not None for field in ("bindingDigest", "fullClientRect", "replaySafeManualPoint", "activationCell")):
        blockers.append("LIVE_SUBJECT_MUST_BE_NOT_CREATED_NULL")

    future = contract.get("futureChainContract", {})
    if future.get("order") != EXPECTED_ORDER:
        blockers.append("FUTURE_CHAIN_ORDER_MISMATCH")
    steps = future.get("steps", {})
    for role, (dependencies, receipt_type) in EXPECTED_STEPS.items():
        step = steps.get(role, {}) if isinstance(steps, dict) else {}
        if step.get("dependsOn") != dependencies or step.get("receiptType") != receipt_type or step.get("requiresExpectedSha256") is not True or step.get("sameOracleRunRequired") is not True:
            blockers.append(f"FUTURE_{role}_CONTRACT_MISMATCH")
    if future.get("h4IndependentlyRecomputed") is not True or future.get("h4PointSource") != "RAW_H2_CAPTURE_NOT_H3_EVALUATION" or future.get("h4ScaleSource") != "H2_RAW_IEEE754_BINARY32_BITS":
        blockers.append("H4_INDEPENDENT_RECOMPUTATION_CONTRACT_MISMATCH")
    if future.get("h5ReviewerMustDifferFromSingleWriter") is not True:
        blockers.append("H5_REVIEWER_INDEPENDENCE_MISSING")
    if future.get("h6MayIssuePermit") is not False or future.get("h6MayPerformInput") is not False:
        blockers.append("H6_CAPABILITY_EXPANSION")

    if contract.get("blockers") != EXPECTED_BLOCKERS:
        blockers.append("CURRENT_BLOCKER_ORDER_MISMATCH")

    vector_valid = False
    vector = contract.get("testOnlyVector", {})
    try:
        source = vector["source"]
        if source["captureSha256"] != source["expectedCaptureSha256"]:
            raise ValueError("capture source/expected mismatch")
        if source["evaluationSha256"] != source["expectedEvaluationSha256"]:
            raise ValueError("evaluation source/expected mismatch")
        expected_paths = {
            "capturePath": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/fixture-capture.json",
            "evaluationPath": "work/20260829-original-client-manager65-corrected-collector-v3/evidence/fixture-evaluation.json",
        }
        bound_documents: dict[str, dict[str, Any]] = {}
        for field, relative in expected_paths.items():
            if source[field] != relative:
                raise ValueError(f"{field} mismatch")
            actual = (repo_root / relative).resolve()
            if repo_root not in actual.parents or not actual.is_file():
                raise ValueError(f"{field} outside or missing")
            digest_field = "captureSha256" if field == "capturePath" else "evaluationSha256"
            if sha256(actual) != source[digest_field]:
                raise ValueError(f"{field} actual hash mismatch")
            bound_documents[field] = json.loads(actual.read_text(encoding="utf-8-sig"))
        if source["captureSha256"] != expected_capture_sha256.upper():
            raise ValueError("capture external expected hash mismatch")
        if source["evaluationSha256"] != expected_evaluation_sha256.upper():
            raise ValueError("evaluation external expected hash mismatch")

        capture = bound_documents["capturePath"]
        bound_evaluation = bound_documents["evaluationPath"]
        if capture.get("schemaVersion") != 3 or capture.get("provenance") != "SYNTHETIC_FIXTURE" or capture.get("oracleRunId") != "SYNTHETIC-RUN-V3":
            raise ValueError("capture identity/status mismatch")
        process = capture.get("process", {})
        expected_full = {"left": 0, "top": 0, "right": process.get("clientWidthA"), "bottom": process.get("clientHeightA")}
        if process.get("clientWidthA") != process.get("clientWidthB") or process.get("clientHeightA") != process.get("clientHeightB") or vector["fullClientRect"] != expected_full:
            raise ValueError("full client rect not bound to capture A/B")
        snapshot_a = capture.get("snapshotA", {})
        snapshot_b = capture.get("snapshotB", {})
        if snapshot_a != snapshot_b:
            raise ValueError("capture A/B mismatch")
        warp_actions = [action for action in snapshot_a.get("manager65", {}).get("actions", []) if action.get("commandId") == 0x2B]
        if len(warp_actions) != 1 or warp_actions[0].get("widget", {}).get("logicalRect") != vector["logicalRect"]:
            raise ValueError("logical rect not bound to unique capture command 0x2B")
        capture_scale_x = snapshot_a.get("coordinateFrame", {}).get("scaleX")
        capture_scale_y = snapshot_a.get("coordinateFrame", {}).get("scaleY")
        if capture_scale_x != vector["scale"]["xDecimalMirror"] or capture_scale_y != vector["scale"]["yDecimalMirror"]:
            raise ValueError("decimal scale mirror not bound to capture")
        if bound_evaluation.get("status") != "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS" or bound_evaluation.get("oracleRunId") != capture.get("oracleRunId") or bound_evaluation.get("sourceCaptureSha256") != source["captureSha256"]:
            raise ValueError("evaluation status/run/capture chain mismatch")
        bound_region = bound_evaluation.get("offlineCandidateRegion", {})
        if bound_region.get("clientRect") != vector["candidateClientRect"] or bound_region.get("safePoint") != vector["manualPoint"] or bound_region.get("forwardLogical") != vector["forwardLogical"]:
            raise ValueError("geometry not bound to manager evaluation")

        scale_x, decoded_x = decode_positive_normal_binary32(vector["scale"]["xBits"])
        scale_y, decoded_y = decode_positive_normal_binary32(vector["scale"]["yBits"])
        mirror_x_bits = struct.pack(">f", float(vector["scale"]["xDecimalMirror"])).hex().upper()
        mirror_y_bits = struct.pack(">f", float(vector["scale"]["yDecimalMirror"])).hex().upper()
        if mirror_x_bits != vector["scale"]["xBits"] or mirror_y_bits != vector["scale"]["yBits"]:
            raise ValueError("decimal mirror does not round-trip to raw bits")
        if decoded_x != float(capture_scale_x) or decoded_y != float(capture_scale_y):
            raise ValueError("raw bits do not decode to capture scale")
        if not math.isfinite(decoded_x) or not math.isfinite(decoded_y) or decoded_x <= 0 or decoded_y <= 0:
            raise ValueError("decoded scale invalid")

        full = vector["fullClientRect"]
        logical = vector["logicalRect"]
        if full["left"] != 0 or full["top"] != 0 or full["right"] <= 0 or full["bottom"] <= 0:
            raise ValueError("full client rect invalid")
        if logical["right"] <= logical["left"] or logical["bottom"] <= logical["top"]:
            raise ValueError("logical rect invalid")
        x_axis = inverse_axis(logical["left"], logical["right"], full["right"], scale_x)
        y_axis = inverse_axis(logical["top"], logical["bottom"], full["bottom"], scale_y)
        if x_axis is None or y_axis is None:
            raise ValueError("empty inverse")
        calculated = (x_axis[0], y_axis[0], x_axis[1], y_axis[1])
        if rect_tuple(vector["candidateClientRect"]) != calculated:
            raise ValueError("candidate rect mismatch")
        left, top, right, bottom = calculated
        if right - left < 3 or bottom - top < 3:
            raise ValueError("candidate lacks 3x3 margin")
        safe_x = (left + right - 1) // 2
        safe_y = (top + bottom - 1) // 2
        if vector["manualPoint"] != {"x": safe_x, "y": safe_y}:
            raise ValueError("manual point mismatch")
        if rect_tuple(vector["threeByThreeRect"]) != (safe_x - 1, safe_y - 1, safe_x + 2, safe_y + 2):
            raise ValueError("3x3 rect mismatch")
        for y in range(safe_y - 1, safe_y + 2):
            for x in range(safe_x - 1, safe_x + 2):
                if not (left <= x < right and top <= y < bottom):
                    raise ValueError("3x3 pixel outside candidate")
                logical_x = trunc_nonnegative(x, scale_x)
                logical_y = trunc_nonnegative(y, scale_y)
                if not (logical["left"] <= logical_x < logical["right"] and logical["top"] <= logical_y < logical["bottom"]):
                    raise ValueError("3x3 replay outside logical rect")
        forward = {"x": trunc_nonnegative(safe_x, scale_x), "y": trunc_nonnegative(safe_y, scale_y)}
        if vector["forwardLogical"] != forward:
            raise ValueError("forward logical mismatch")
        if rect_tuple(vector["activationCell"]) != (safe_x, safe_y, safe_x + 1, safe_y + 1):
            raise ValueError("activation cell mismatch")
        if any(vector.get(field) is not False for field in ("promotable", "liveEligible", "activationEligible", "permitEligible")):
            raise ValueError("test vector promotion flag")
        vector_valid = True
    except Exception as error:
        blockers.append(f"TEST_ONLY_VECTOR_INVALID:{type(error).__name__}:{error}")

    claims = contract.get("claimCeiling", {})
    for key in ("liveSubjectEligible", "warpPrelaunchEligible", "activationEligible", "launchEligible", "permitEligible", "permitIssued", "originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed"):
        if claims.get(key) is not False:
            blockers.append(f"CLAIM_{key}_MUST_BE_FALSE")
    for key in ("activationPoint", "automaticActivationPoint", "permit", "bindingDigest"):
        if claims.get(key) is not None:
            blockers.append(f"CLAIM_{key}_MUST_BE_NULL")

    operations = contract.get("operations", {})
    for key, value in operations.items() if isinstance(operations, dict) else []:
        if value != 0:
            blockers.append(f"OPERATION_{key}_NONZERO")

    blockers = list(dict.fromkeys(blockers))
    passed = not blockers
    return {
        "schemaVersion": 1,
        "receiptType": "WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_OFFLINE_EVALUATION",
        "sourceContractSha256": sha256(contract_path),
        "schemaSha256": sha256(schema_path),
        "status": "OFFLINE_WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_PASS_NOT_CREATED_NOT_ELIGIBLE" if passed else "OFFLINE_WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1_REJECTED",
        "contractPass": passed,
        "testOnlyVectorValid": vector_valid and passed,
        "testOnlyGeometryStatus": "PASS_TEST_ONLY_NONPROMOTABLE" if vector_valid and passed else "REJECTED",
        "liveSubjectLifecycle": "NOT_CREATED",
        "chainLifecycle": {key: "NOT_CREATED" for key in CHAIN_KEYS},
        "structuralBlockers": blockers,
        "readinessBlockers": EXPECTED_BLOCKERS if passed else [],
        "authority": {"stage": "WARP", "max": 1, "consumed": 0, "remaining": 1, "isPermit": False},
        "liveSubjectEligible": False,
        "warpPrelaunchEligible": False,
        "activationEligible": False,
        "launchEligible": False,
        "permitEligible": False,
        "permitIssued": False,
        "activationPoint": None,
        "automaticActivationPoint": None,
        "permit": None,
        "bindingDigest": None,
        "originalRuntimeObserved": False,
        "independentLiveBinding": False,
        "livePromotionAllowed": False,
        "claimCeiling": "CONTRACT_AND_TEST_ONLY_GEOMETRY_NO_LIVE_BINDING",
        "operationsPerformedByEvaluator": {"fileReads": 4, "liveRuns": 0, "processMemoryReads": 0, "processMemoryWrites": 0, "physicalInputs": 0, "automaticInputs": 0, "debuggerOperations": 0, "vmOperations": 0, "serverProtocolDbChanges": 0, "permitIssuance": 0},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--expected-schema-sha256", required=True)
    parser.add_argument("--expected-capture-sha256", required=True)
    parser.add_argument("--expected-evaluation-sha256", required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8-sig"))
    schema = json.loads(args.schema.read_text(encoding="utf-8-sig"))
    result = evaluate(contract, args.contract, schema, args.schema, args.expected_schema_sha256, args.expected_capture_sha256, args.expected_evaluation_sha256, args.repo_root.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    temporary.replace(args.output)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if result["contractPass"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
