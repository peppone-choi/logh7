import json, hashlib, os, struct
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T224357Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-2-lobby-panel-gate-rpm-verification.json"
BASE = 0x02210000
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def dump(n): return open(D + "/" + n, "rb").read()
def dw(b, adr): i = adr - BASE; return struct.unpack("<I", b[i:i+4])[0]
def diff(x, y): return [(BASE + i, struct.unpack("<I", x[i:i+4])[0], struct.unpack("<I", y[i:i+4])[0]) for i in range(0, min(len(x), len(y)) - 3, 4) if x[i:i+4] != y[i:i+4]]
a, a2, b, b2, b3, b4 = J("rpm-a.json"), J("rpm-a2.json"), J("rpm-b.json"), J("rpm-b2.json"), J("rpm-b3.json"), J("rpm-b4.json")
a3 = J("rpm-a3.json"); mu = J("mouse-up.json"); stop, so, cleanup, census = J("clean-stop.json"), J("stop-own-client.json"), J("cleanup.json"), J("verify-after-stop.json")
A3, B3, B4 = dump("rpm-dump-a3.bin"), dump("rpm-dump-b3.bin"), dump("rpm-dump-b4.bin")
d_ab = diff(A3, B3); d_bc = diff(B3, B4)
mode_rows = sum(1 for adr, va, vb in d_ab if 0x02216700 <= adr < 0x02216800)
checks = {
    "probe_readOnly_VM_READ_only": all(r["access"].startswith("PROCESS_VM_READ") and r["operations"]["writes"] == 0 for r in (a, a2, a3, b, b2, b3, b4)),
    "armByte05_zero_in_responsive_lobby": a["armedCount"] == 0 and a2["armedCount"] == 0 and a3["armedCount"] == 0,
    "armByte05_zero_in_wedged_state": b["armedCount"] == 0 and b2["armedCount"] == 0 and b3["armedCount"] == 0 and b4["armedCount"] == 0,
    "settingsOpen_sets_subPanelCell_0x02216C80": dw(A3, 0x02216C80) == 0 and dw(B3, 0x02216C80) == 1,
    "settingsOpen_sets_inputOwner_modal_0x100": dw(A3, 0x0221443C) == 0 and dw(B3, 0x0221443C) == 0x100 and dw(A3, 0x0221453C) == 0 and dw(B3, 0x0221453C) == 0x100,
    "settingsOpen_records_clickPoint_122_481": dw(B3, 0x022143DC) == 122 and dw(B3, 0x022143E0) == 481 and dw(B3, 0x02214408) == 122,
    "settingsOpen_fills_displayModeTable": mode_rows >= 40,
    "settingsObject_0x02216C20_fields_set": dw(B3, 0x02216C28) == 1 and dw(B3, 0x02216C34) == 2 and dw(B3, 0x02216C70) == 0x400 and dw(B3, 0x02216C74) == 0x300,
    "bareLeftUp_registered_but_modal_persists": mu["status"] == "ONE_LEFTUP_SENT" and len(d_bc) >= 10 and all(va == 2 and vb == 1 for _, va, vb in d_bc) and dw(B4, 0x0221443C) == 0x100 and dw(B4, 0x0221453C) == 0x100,
    "clientWedged_stoppedByOwnPidStop_cleanShutdown": so["status"] == "OWN_CLIENT_STOPPED" and stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and cleanup["status"] == "RUN_CLEANED" and census["leftoverProcesses"] == [],
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "relaunch-prep.json", "rpm-a.json", "rpm-a2.json", "rpm-a3.json", "rpm-b.json", "rpm-b2.json", "rpm-b3.json", "rpm-b4.json", "rpm-dump-a3.bin", "rpm-dump-b3.bin", "rpm-dump-b4.bin", "mouse-up.json", "stop-own-client.json", "clean-stop.json", "cleanup.json", "verify-after-stop.json", "vnc-p1-lobby.png", "vnc-p2-settings.png", "vnc-p3-lobby2.png", "vnc-p4-settings2.png", "vnc-p5-after-up.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 2, "feature": "lobby sub-panel input gate — read-only RPM measurement (also conditions 7/8)",
       "verdict": "OBSERVED_MODAL_STATE" if allpass else "FAILED", "runId": RUN,
       "method": "guest-rpm-manager-arm.ps1: OpenProcess(PROCESS_VM_READ|PROCESS_QUERY_INFORMATION), scan private+image writable regions for dword tag 0x63 (FUN_005024B0 guard) and dump 64 KB of the lobby UI globals at 0x02210000; states: responsive lobby (A/A2/A3), 環境設定 open = wedged (B/B2/B3), wedged after one bare mouse_event LEFTUP (B4). No writes, no threads, no debugger.",
       "findings": {
           "armByte05": "0 on every tag-0x63 candidate in BOTH states -> the manager +0x05 input arm is not what gates the lobby (hypothesis rejected)",
           "A3_to_B3_changedDwords": len(d_ab),
           "subPanelCell_0x02216C80": "0 -> 1 when 環境設定 opens; the same cell the create/session flow writes (0x51b0d9/0x51b19c) and the settings flow writes (0x5210d9): a shared 'lobby sub-panel active' selector",
           "settingsObject_0x02216C20": "+8=1, +0xC=1, +0x14=2 (mode index), +0x4C=1, +0x50/+0x54=1024x768",
           "inputOwner_modal_0x0221443C_0x0221453C": "0 -> 0x100 when the panel opens; NOT cleared by a bare LEFTUP",
           "clickPoint_cells": "0x022143DC/E0 (last cursor) and 0x02214408/0x02214434/0x02214BD8 := (122,481)",
           "displayModeTable_0x02216700": f"{mode_rows} dwords populated (w,h,refresh triples) — panel data, not the gate",
           "bareLeftUp": f"registered ({len(d_bc)} dwords 2->1 in 0x02214778..0x02214944) but the 0x100 modal cells persist and the panel stays inert",
       },
       "conclusion": "The lobby wedge is a modal sub-panel STATE (0x02216C80 selector + input-owner 0x100 cells) under which the dispatcher drops mouse input to the panel's own controls; it is not a stuck button, not input transport, not keyboard, not part existence, not the +0x05 arm. Static readers of the 0x100 cells are not reachable by displacement search (dynamic indexing); the next step is to locate the dispatcher branch that tests the input-owner modal cells (e.g. from FUN_005015F0's callers) or the code paths that clear 0x02216C80/0x100 in the original flow.",
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
