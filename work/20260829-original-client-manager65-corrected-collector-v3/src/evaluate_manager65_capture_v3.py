from __future__ import annotations

import argparse
import hashlib
import json
import math
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

CANONICAL_SHA256 = "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"
LIVE_BLOCKERS = [
    "FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION",
    "FRESH_RUN_IDENTITY_RECOLLECTION_REQUIRED",
    "FRESH_LISTENER_HEARTBEAT_FOREGROUND_REQUIRED",
    "INDEPENDENT_MANAGER65_HIT_REGION_BINDING_REQUIRED",
    "INDEPENDENT_LIVE_PRELAUNCH_REVIEW_REQUIRED",
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def exact(obj: Any, keys: set[str], label: str, blockers: list[str]) -> bool:
    if not isinstance(obj, dict):
        blockers.append(f"{label}_NOT_OBJECT")
        return False
    actual = set(obj)
    if actual != keys:
        blockers.append(f"{label}_KEYS_MISMATCH")
        return False
    return True


def parse_time(value: Any, label: str, blockers: list[str]) -> datetime | None:
    if not isinstance(value, str):
        blockers.append(f"{label}_INVALID")
        return None
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if result.tzinfo is None:
            raise ValueError
        return result.astimezone(timezone.utc)
    except ValueError:
        blockers.append(f"{label}_INVALID")
        return None


def inverse_axis(lo: int, hi: int, scale: float, limit: int) -> tuple[int, int] | None:
    hits = [pixel for pixel in range(limit) if lo <= math.trunc(pixel * scale) < hi]
    return None if not hits else (hits[0], hits[-1] + 1)


def evaluate(capture: dict[str, Any], capture_path: Path, collector_path: Path,
             expected_capture_sha256: str, expected_collector_sha256: str,
             expected_run_id: str, expected_external_identity_receipt_sha256: str) -> dict[str, Any]:
    blockers: list[str] = []
    top_keys = {
        "schemaVersion", "receiptType", "provenance", "oracleRunId", "externalIdentityReceiptSha256",
        "captureStartedAtUtc", "observedAtUtc", "captureCompletedAtUtc", "process", "rootRoles",
        "snapshotA", "snapshotB", "snapshotStable", "windowSurfaceStable", "semanticCandidateEligible",
        "blockers", "originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed",
        "warpPrelaunchEligible", "launchEligible", "permitEligible", "permitIssued",
        "automaticActivationPoint", "operations",
    }
    exact(capture, top_keys, "ROOT", blockers)
    if capture.get("schemaVersion") != 3 or capture.get("receiptType") != "ORIGINAL_CLIENT_MANAGER65_ACTION_0X2B_RAW_CAPTURE":
        blockers.append("SCHEMA_OR_RECEIPT_TYPE_MISMATCH")
    if capture.get("oracleRunId") != expected_run_id:
        blockers.append("ORACLE_RUN_ID_MISMATCH")
    if not isinstance(capture.get("externalIdentityReceiptSha256"), str) or len(capture["externalIdentityReceiptSha256"]) != 64 or any(character not in "0123456789abcdefABCDEF" for character in capture["externalIdentityReceiptSha256"]):
        blockers.append("EXTERNAL_IDENTITY_RECEIPT_SHA256_INVALID")
    elif capture["externalIdentityReceiptSha256"].upper() != expected_external_identity_receipt_sha256.upper():
        blockers.append("EXTERNAL_IDENTITY_RECEIPT_SHA256_MISMATCH")
    if capture.get("provenance") not in {"SYNTHETIC_FIXTURE", "LIVE_READONLY"}:
        blockers.append("PROVENANCE_INVALID")
    actual_capture_hash = sha256(capture_path)
    actual_collector_hash = sha256(collector_path)
    if actual_capture_hash != expected_capture_sha256.upper():
        blockers.append("SOURCE_CAPTURE_SHA256_MISMATCH")
    if actual_collector_hash != expected_collector_sha256.upper():
        blockers.append("COLLECTOR_SHA256_MISMATCH")

    process_keys = {
        "pid", "startTimeUtc", "path", "sha256", "moduleBase", "moduleSize", "sessionId", "hwnd",
        "hwndOwnerPidA", "hwndVisibleA", "clientWidthA", "clientHeightA", "hwndOwnerPidB", "hwndVisibleB",
        "clientWidthB", "clientHeightB",
    }
    process = capture.get("process", {})
    exact(process, process_keys, "PROCESS", blockers)
    if process.get("sha256") != CANONICAL_SHA256:
        blockers.append("EXECUTABLE_HASH_MISMATCH")
    if process.get("moduleBase") != "0x00400000" or not isinstance(process.get("moduleSize"), int) or process.get("moduleSize", 0) <= 0:
        blockers.append("MODULE_IDENTITY_MISMATCH")
    pid = process.get("pid")
    if not isinstance(pid, int) or pid <= 0 or process.get("hwndOwnerPidA") != pid or process.get("hwndOwnerPidB") != pid:
        blockers.append("OWNED_HWND_PID_MISMATCH")
    for name in ("clientWidthA", "clientHeightA", "clientWidthB", "clientHeightB"):
        if not isinstance(process.get(name), int) or process.get(name, 0) <= 0:
            blockers.append("NONPOSITIVE_CLIENT_SURFACE")
            break
    if process.get("clientWidthA") != process.get("clientWidthB") or process.get("clientHeightA") != process.get("clientHeightB"):
        blockers.append("OWNED_HWND_SURFACE_TORN")
    if process.get("hwndVisibleA") is not True or process.get("hwndVisibleB") is not True:
        blockers.append("OWNED_HWND_NOT_VISIBLE")

    started = parse_time(capture.get("captureStartedAtUtc"), "CAPTURE_STARTED_AT", blockers)
    observed = parse_time(capture.get("observedAtUtc"), "OBSERVED_AT", blockers)
    completed = parse_time(capture.get("captureCompletedAtUtc"), "CAPTURE_COMPLETED_AT", blockers)
    process_started = parse_time(process.get("startTimeUtc"), "PROCESS_START", blockers)
    if all(value is not None for value in (process_started, started, observed, completed)):
        assert process_started and started and observed and completed
        if not process_started <= started <= observed <= completed:
            blockers.append("CAPTURE_TIME_ORDER_INVALID")
        if completed > datetime.now(timezone.utc).replace(microsecond=0) and (completed - datetime.now(timezone.utc)).total_seconds() > 300:
            blockers.append("CAPTURE_TIME_FUTURE_INVALID")

    root_roles = capture.get("rootRoles", {})
    exact(root_roles, {"uiRootRole", "strategyOwnerRole", "legacyOwnerModeFieldsRejected"}, "ROOT_ROLES", blockers)
    if root_roles.get("uiRootRole") != "UI_MODE_AND_REGISTRY_HOST" or root_roles.get("strategyOwnerRole") != "INLINE_STRATEGY_MANAGER_OWNER" or root_roles.get("legacyOwnerModeFieldsRejected") is not True:
        blockers.append("ROOT_ROLE_ADJUDICATION_MISMATCH")

    snapshot_keys = {"uiRoot", "strategyOwner", "manager65", "manager67", "coordinateFrame"}
    snapshot_a = capture.get("snapshotA", {})
    snapshot_b = capture.get("snapshotB", {})
    exact(snapshot_a, snapshot_keys, "SNAPSHOT_A", blockers)
    exact(snapshot_b, snapshot_keys, "SNAPSHOT_B", blockers)
    if snapshot_a != snapshot_b or capture.get("snapshotStable") is not True:
        blockers.append("TORN_SNAPSHOT")
    if capture.get("windowSurfaceStable") is not True:
        blockers.append("WINDOW_SURFACE_NOT_STABLE")

    ui = snapshot_a.get("uiRoot", {})
    exact(ui, {"pointer", "builderMode", "handlerState", "registryPointer"}, "UI_ROOT", blockers)
    if ui.get("pointer") == "0x00000000" or ui.get("registryPointer") == "0x00000000":
        blockers.append("UI_ROOT_OR_REGISTRY_NULL")
    if ui.get("builderMode") != 2:
        blockers.append("UI_ROOT_BUILDER_MODE_NOT_2")
    if ui.get("handlerState") != 1:
        blockers.append("UI_ROOT_HANDLER_STATE_NOT_1")

    owner = snapshot_a.get("strategyOwner", {})
    exact(owner, {"pointer", "role"}, "STRATEGY_OWNER", blockers)
    if owner.get("pointer") != "0x00C9E638" or owner.get("role") != "INLINE_STRATEGY_MANAGER_OWNER":
        blockers.append("STRATEGY_OWNER_FORMULA_MISMATCH")
    if any(key in owner for key in ("builderMode", "handlerState", "strategyMode")):
        blockers.append("LEGACY_OWNER_MODE_FIELD_PRESENT")

    manager65 = snapshot_a.get("manager65", {})
    exact(manager65, {"controllerPointer", "managerPointer", "registrySlotPointer", "inputGate", "context", "page", "actionCount", "selectedIndex", "cardId", "recordOwnerPointer", "recordActionCount", "actions"}, "MANAGER65", blockers)
    if manager65.get("controllerPointer") != "0x00C9E768":
        blockers.append("MANAGER65_CONTROLLER_FORMULA_MISMATCH")
    if manager65.get("managerPointer") == "0x00000000" or manager65.get("managerPointer") != manager65.get("registrySlotPointer"):
        blockers.append("MANAGER65_REGISTRY_MISMATCH")
    context = manager65.get("context", {})
    exact(context, {"nodes", "resolvedX", "resolvedY"}, "MANAGER65_CONTEXT", blockers)
    nodes = context.get("nodes", [])
    if not isinstance(nodes, list) or not nodes:
        blockers.append("MANAGER65_CONTEXT_EMPTY")
    else:
        for node in nodes:
            exact(node, {"pointer", "id", "active", "parentId", "localX", "localY", "registry"}, "CONTEXT_NODE", blockers)
        if nodes[0].get("id") != 0x65:
            blockers.append("MANAGER65_ID_MISMATCH")
        if nodes[0].get("active") == 0 or manager65.get("inputGate") == 0:
            blockers.append("MANAGER65_CONTEXT_INACTIVE")
        if nodes[-1].get("parentId") != -1 or len({node.get("pointer") for node in nodes}) != len(nodes):
            blockers.append("MANAGER65_CONTEXT_CHAIN_INVALID")
        if context.get("resolvedX") != sum(node.get("localX", 0) for node in nodes) or context.get("resolvedY") != sum(node.get("localY", 0) for node in nodes):
            blockers.append("MANAGER65_CONTEXT_ORIGIN_MISMATCH")
    if not isinstance(manager65.get("page"), int) or not 1 <= manager65.get("page", 0) <= 5:
        blockers.append("MANAGER65_PAGE_OUT_OF_RANGE")
    if not isinstance(manager65.get("actionCount"), int) or not 1 <= manager65.get("actionCount", 0) <= 24:
        blockers.append("MANAGER65_ACTION_COUNT_OUT_OF_RANGE")
    if not isinstance(manager65.get("cardId"), int) or not 0 <= manager65.get("cardId", -1) <= 0xFFFF:
        blockers.append("MANAGER65_BOUND_CARD_ID_INVALID")
    if manager65.get("recordOwnerPointer") == "0x00000000":
        blockers.append("CURRENT_CHARACTER_OWNER_NULL")
    if manager65.get("recordActionCount") != manager65.get("actionCount"):
        blockers.append("MANAGER65_RECORD_ACTION_COUNT_MISMATCH")
    if manager65.get("selectedIndex") != -1:
        blockers.append("MANAGER65_SELECTED_INDEX_NOT_RESET")

    manager67 = snapshot_a.get("manager67", {})
    exact(manager67, {"controllerPointer", "managerPointer", "registrySlotPointer", "managerId", "active", "inputGate", "disposition"}, "MANAGER67", blockers)
    if manager67.get("controllerPointer") != "0x00C9EAC4" or manager67.get("managerPointer") == "0x00000000" or manager67.get("managerPointer") != manager67.get("registrySlotPointer") or manager67.get("managerId") != 0x67:
        blockers.append("MANAGER67_STRUCTURAL_MISMATCH")
    if manager67.get("active") != 0 or manager67.get("inputGate") != 0:
        blockers.append("MANAGER67_NOT_DORMANT")
    if nodes and nodes[0].get("active") and manager65.get("inputGate") and manager67.get("active") and manager67.get("inputGate"):
        blockers.append("MANAGER65_MANAGER67_SIMULTANEOUSLY_ACTIVE")

    actions = manager65.get("actions", [])
    if not isinstance(actions, list) or len(actions) != manager65.get("actionCount"):
        blockers.append("ACTION_ARRAY_COUNT_MISMATCH")
        actions = []
    widget_keys = {"widgetPointer", "status", "initialized", "localSelector", "localX", "localY", "localGate", "hitTestEnabled", "activeVisible", "renderVisible", "width", "height", "eligible", "logicalRect"}
    for action in actions:
        exact(action, {"index", "commandId", "widget"}, "ACTION", blockers)
        widget = action.get("widget", {})
        exact(widget, widget_keys, "ACTION_WIDGET", blockers)
        exact(widget.get("logicalRect", {}), {"left", "top", "right", "bottom"}, "ACTION_LOGICAL_RECT", blockers)
    matches = [action for action in actions if action.get("commandId") == 0x2B]
    if len(matches) != 1:
        blockers.append("ACTION_0X2B_NOT_UNIQUE" if matches else "ACTION_0X2B_NOT_FOUND")
    warp = matches[0] if len(matches) == 1 else None
    if warp:
        exact(warp, {"index", "commandId", "widget"}, "WARP_ACTION", blockers)
        widget = warp.get("widget", {})
        exact(widget, widget_keys, "WARP_WIDGET", blockers)
        if widget.get("widgetPointer") == "0x00000000" or widget.get("initialized") == 0 or widget.get("hitTestEnabled") == 0 or widget.get("activeVisible") == 0 or widget.get("renderVisible") == 0 or widget.get("eligible") is not True:
            blockers.append("ACTION_0X2B_WIDGET_NOT_ELIGIBLE")
        if widget.get("localSelector") not in (0, 1) or (widget.get("localSelector") == 1 and widget.get("localGate") == 0):
            blockers.append("ACTION_0X2B_LOCAL_TRANSFORM_INVALID")
        if not isinstance(widget.get("width"), int) or widget.get("width", 0) <= 0 or not isinstance(widget.get("height"), int) or widget.get("height", 0) <= 0:
            blockers.append("ACTION_0X2B_SIZE_INVALID")

    frame = snapshot_a.get("coordinateFrame", {})
    exact(frame, {"scaleX", "scaleY", "logicalWidth", "logicalHeight", "engineClientRect"}, "COORDINATE_FRAME", blockers)
    sx, sy = frame.get("scaleX"), frame.get("scaleY")
    if not isinstance(sx, (int, float)) or not math.isfinite(sx) or sx <= 0 or not isinstance(sy, (int, float)) or not math.isfinite(sy) or sy <= 0:
        blockers.append("INVALID_SCALE")
    engine = frame.get("engineClientRect", {})
    exact(engine, {"left", "top", "right", "bottom"}, "ENGINE_VIEWPORT", blockers)
    if all(isinstance(engine.get(k), int) for k in ("left", "top", "right", "bottom")):
        if engine["left"] < 0 or engine["top"] < 0 or engine["right"] <= engine["left"] or engine["bottom"] <= engine["top"] or engine["right"] > process.get("clientWidthA", 0) or engine["bottom"] > process.get("clientHeightA", 0):
            blockers.append("ENGINE_VIEWPORT_OUTSIDE_OWNED_HWND")

    region = None
    if warp and isinstance(sx, (int, float)) and isinstance(sy, (int, float)) and sx > 0 and sy > 0:
        rect = warp["widget"].get("logicalRect", {})
        exact(rect, {"left", "top", "right", "bottom"}, "WARP_LOGICAL_RECT", blockers)
        if all(isinstance(rect.get(k), int) for k in ("left", "top", "right", "bottom")) and rect["right"] > rect["left"] and rect["bottom"] > rect["top"]:
            x = inverse_axis(rect["left"], rect["right"], float(sx), process.get("clientWidthA", 0))
            y = inverse_axis(rect["top"], rect["bottom"], float(sy), process.get("clientHeightA", 0))
            if x is None or y is None:
                blockers.append("ACTION_0X2B_NO_OWNED_HWND_CLIENT_PIXELS")
            elif x[1] - x[0] < 3 or y[1] - y[0] < 3:
                blockers.append("ACTION_0X2B_NO_3X3_SAFE_MARGIN")
            else:
                safe_x = (x[0] + x[1] - 1) // 2
                safe_y = (y[0] + y[1] - 1) // 2
                region = {"clientRect": {"left": x[0], "top": y[0], "right": x[1], "bottom": y[1]}, "safePoint": {"x": safe_x, "y": safe_y}, "forwardLogical": {"x": math.trunc(safe_x * sx), "y": math.trunc(safe_y * sy)}}
        else:
            blockers.append("WARP_LOGICAL_RECT_INVALID")

    operations = capture.get("operations", {})
    operation_keys = {"memoryAccess", "memoryReadCount", "memoryWrites", "gameInputs", "automaticInputs", "retries", "debuggerAttach", "debuggerCommands", "breakpointsInstalled", "vmLifecycleChanges", "serverChanges", "protocolChanges", "databaseChanges", "permitIssuance"}
    exact(operations, operation_keys, "OPERATIONS", blockers)
    if operations.get("memoryAccess") != "READ_ONLY" or not isinstance(operations.get("memoryReadCount"), int) or operations.get("memoryReadCount", 0) <= 0:
        blockers.append("READ_ONLY_OPERATION_RECEIPT_INVALID")
    for key in operation_keys - {"memoryAccess", "memoryReadCount"}:
        if operations.get(key) != 0:
            blockers.append("FORBIDDEN_OPERATION_PRESENT")
            break

    for key in ("originalRuntimeObserved", "independentLiveBinding", "livePromotionAllowed", "warpPrelaunchEligible", "launchEligible", "permitEligible", "permitIssued"):
        if capture.get(key) is not False:
            blockers.append("SELF_PROMOTION_FIELD_TRUE")
            break
    if capture.get("automaticActivationPoint") is not None:
        blockers.append("SELF_PROMOTED_ACTIVATION_POINT")

    blockers = list(dict.fromkeys(blockers))
    fixture_pass = not blockers and capture.get("provenance") == "SYNTHETIC_FIXTURE"
    if capture.get("provenance") == "LIVE_READONLY":
        blockers.append("LIVE_CAPTURE_REQUIRES_EXTERNAL_INDEPENDENT_BINDING")
    return {
        "schemaVersion": 1,
        "receiptType": "ORIGINAL_CLIENT_MANAGER65_ACTION_0X2B_OFFLINE_EVALUATION_V3",
        "sourceCaptureSha256": actual_capture_hash,
        "sourceCollectorSha256": actual_collector_hash,
        "oracleRunId": capture.get("oracleRunId"),
        "status": "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS" if fixture_pass else "OFFLINE_MANAGER65_ACTION_0X2B_REJECTED",
        "rootRolesCorrected": not any(blocker.startswith(("ROOT_ROLE", "UI_ROOT", "STRATEGY_OWNER", "LEGACY_OWNER")) for blocker in blockers),
        "actionSemanticCandidate": fixture_pass,
        "offlineCandidateRegion": region if fixture_pass else None,
        "semanticBlockers": blockers,
        "remainingLiveBlockers": LIVE_BLOCKERS,
        "originalRuntimeObserved": False,
        "independentLiveBinding": False,
        "livePromotionAllowed": False,
        "warpPrelaunchEligible": False,
        "launchEligible": False,
        "permitEligible": False,
        "permitIssued": False,
        "automaticActivationPoint": None,
        "claimCeiling": "OFFLINE_STATIC_AND_SYNTHETIC_CANDIDATE_ONLY",
        "operationsPerformedByEvaluator": {"memoryReads": 0, "memoryWrites": 0, "gameInputs": 0, "automaticInputs": 0, "vmOperations": 0, "serverProtocolDbChanges": 0},
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture", required=True, type=Path)
    parser.add_argument("--collector", required=True, type=Path)
    parser.add_argument("--expected-capture-sha256", required=True)
    parser.add_argument("--expected-collector-sha256", required=True)
    parser.add_argument("--expected-run-id", required=True)
    parser.add_argument("--expected-external-identity-receipt-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    capture = json.loads(args.capture.read_text(encoding="utf-8-sig"))
    result = evaluate(capture, args.capture, args.collector, args.expected_capture_sha256, args.expected_collector_sha256, args.expected_run_id, args.expected_external_identity_receipt_sha256)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(args.output.name + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    temporary.replace(args.output)
    print(json.dumps(result, ensure_ascii=False, separators=(",", ":")))
    return 0 if result["status"] == "OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_CANDIDATE_PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
