"""Bind the 任命 (CommandCardAppointment) candidate-list probe series (2026-09-03) into one receipt.

Runs (all natural PostgreSQL authority, unmodified client code path; item115 = reversible debug-log working copy):
  8   20260903T023227Z  0x1208 LE count=1 cardId=1              accepted, 実行不可 (no candidates)
  8b  20260903T030020Z  0x1208 LE count=2 cardIds 1,2           'unsupported message = 0x1208'
  9   20260903T032306Z  count=1 cardId=1, non-id bytes 0x01     accepted, no candidates
  8c  20260903T033556Z  count=1 cardId=39                       accepted, no candidates
  10  20260903T035028Z  static @6 sweep 40..48, 9 LE frames     RPM [0xC9EAC0]=39; count cell 300 (LE 01 00 read as 256)
  10b 20260903T040557Z  BE count/id at offset 4                 9x Card OK; parsed cell {0, 0x7B, 0x00280000, 0}; no candidates
  10c <RUN_10C>         BE count, packed 7-byte records         expected: candidate list with card 40 (艦隊副司令官)

Static facts bound here (docs/handoffs/2026-09-03-celestial-type-placeholder-finding.md):
  - command panel state 12 (TARGET_SELECT_S_CARD) loaded path 0x57CC85 keeps a 0x1208 record only if
    staticCard[cardId].u16@+6 == [0xC9EAC0] (FUN_004C9140), and [0xC9EAC0] == player card id (39) live.
  - the 0x120x notify bodies are big-endian; 0x1208 wire = count u16 BE + packed 7-byte records {u16 cardId, u32, u8}.
"""
import glob
import hashlib
import json
import os

import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-11-ninmei-appointer-sweep-verification.json"

SERIES = [
    ("8", "20260903T023227Z-natural-l1-relogin-v1", "accepted-no-candidates"),
    ("8b", "20260903T030020Z-natural-l1-relogin-v1", "unsupported-0x1208"),
    ("9", "20260903T032306Z-natural-l1-relogin-v1", "accepted-no-candidates"),
    ("8c", "20260903T033556Z-natural-l1-relogin-v1", "accepted-no-candidates"),
    ("10", "20260903T035028Z-natural-l1-relogin-v1", "accepted-count-cell-300"),
    ("10b", "20260903T040557Z-natural-l1-relogin-v1", "accepted-9-parsed-cardId-0"),
]


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest().upper()


def log_counts(run):
    files = glob.glob(RUNS + run + "/g7mt-debug*ninmei*.log") or glob.glob(RUNS + run + "/g7mt-debug*.log")
    if not files:
        return None
    text = open(files[0], encoding="cp932", errors="replace").read()
    return {
        "file": os.path.basename(files[0]),
        "cardOk": text.count("NotifySimpleInformationCard OK"),
        "unsupported0x1208": text.count("unsupported message = 0x1208"),
        "beginOk": text.count("TransactionSimpleDataBegin OK"),
    }


def wire_meta(run):
    f = RUNS + run + "/wire-after-ninmei.json"
    if not os.path.exists(f):
        return None
    rows = json.load(open(f, encoding="utf-8"))
    rows = rows.get("rows", rows)
    return [r.get("ResponseMetadata") for r in rows if r.get("ObservedApplicationType") == 0x1200]


def closed(run):
    def j(name):
        p = RUNS + run + "/" + name
        return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None
    stop, cleanup, census = j("clean-stop.json"), j("cleanup.json"), j("verify-after-stop.json")
    return bool(stop and stop.get("status") == "RUN_RUNTIME_CLEANLY_STOPPED" and cleanup and cleanup.get("status") == "RUN_CLEANED" and census is not None and not census.get("leftoverProcesses"))


def rpm_u32(run, tag):
    p = RUNS + run + "/rpm-dump-" + tag + ".bin"
    if not os.path.exists(p):
        return None
    b = open(p, "rb").read()
    return int.from_bytes(b[:4], "little")


rows = []
for mode, run, expect in SERIES + ([("10c", sys.argv[1], "candidates-rendered")] if len(sys.argv) > 1 else []):
    row = {"mode": mode, "runId": run, "expected": expect, "log": log_counts(run), "wireMetadata": wire_meta(run), "closedCleanly": closed(run)}
    if mode == "10":
        row["rpm_0xC9EAC0"] = rpm_u32(run, "g-c9ea00-pre") and int.from_bytes(open(RUNS + run + "/rpm-dump-g-c9ea00-pre.bin", "rb").read()[0xC0:0xC4], "little")
        row["rpm_0x1208_count"] = rpm_u32(run, "list-x1")
    if mode == "10b":
        row["rpm_0xC9EAC0"] = rpm_u32(run, "g-a")
        row["rpm_0x1208_count"] = rpm_u32(run, "list-b")
    files = sorted(os.path.basename(f) for f in glob.glob(RUNS + run + "/*") if os.path.isfile(f) and (f.endswith(".json") or f.endswith(".bin") or os.path.basename(f) in ("vnc-s5-ninmei.png", "vnc-s6-dialog.png", "vnc-s6-list.png")))
    row["receipts"] = {f: sha(RUNS + run + "/" + f) for f in files}
    rows.append(row)

checks = {
    "count_gate_was_endianness": all(r["log"] and r["log"]["unsupported0x1208"] == 0 for r in rows if r["mode"] in ("8", "9", "8c", "10", "10b")) and any(r["log"] and r["log"]["unsupported0x1208"] == 1 for r in rows if r["mode"] == "8b"),
    "player_card_id_39_live": all(r.get("rpm_0xC9EAC0") == 39 for r in rows if r["mode"] in ("10", "10b")),
    "le_count_read_as_256_overflow": any(r.get("rpm_0x1208_count") == 300 for r in rows if r["mode"] == "10"),
    "be_count_parsed_9": any(r.get("rpm_0x1208_count") == 9 for r in rows if r["mode"] == "10b"),
    "all_runs_closed_cleanly": all(r["closedCleanly"] for r in rows),
}
verdict = "OBSERVED_PROBE_SERIES" if all(checks.values()) else "FAILED"
out = {
    "schema": "logh7-condition-verification/1",
    "condition": 11,
    "feature": "任命 (CommandCardAppointment) candidate list: 0x1208 NotifySimpleInformationCard wire layout + static-card appointer predicate",
    "verdict": verdict,
    "method": "natural PostgreSQL authority + unmodified client (item115 debug-log working copy for the client log only); read-only RPM (PROCESS_VM_READ) for [0xC9EAC0], world+0x584510 and world+0x4A07D0; static capstone trace of FUN_0057CBF0/0x57CC85, FUN_004C9140, FUN_004C8700, FUN_0055F670",
    "findings": {
        "predicate": "candidate iff staticCard[cardId].u16@+6 == [0xC9EAC0]; [0xC9EAC0] = 39 = player card id (艦隊司令官)",
        "endianness": "0x120x notify bodies are big-endian; LE count 01 00 was read as 256 (count cell 300 = cap after 9 frames), LE 02 00 / 03 00 -> 512/768 > 300 -> 'unsupported message = 0x1208'",
        "wireLayout": "0x1208: count u16 BE, then packed 7-byte records {u16 cardId, u32, u8}; parsed into 12-byte cells {u16 @0, pad, u32 @4, u8 @8} at world+0x584514 (append, cap 300)",
    },
    "checks": checks,
    "runs": rows,
}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", verdict)
for k, v in checks.items():
    print("  ", "PASS" if v else "FAIL", k)
