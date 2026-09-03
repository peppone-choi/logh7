"""Bind the 任命 (CommandCardAppointment) vertical: original client input -> screen -> authority -> PostgreSQL ->
authority restart on the same database -> relogin. Usage: python bind-condition-11-appointment-verification.py <runId> <tag> <reloginTag>

Path (conditions 9 and 11 evidence; run on the natural PostgreSQL authority with the item115 debug-log working copy of the client):
  strategy -> 職務権限カード -> card 39 -> 任命 (0x1200 selector 0x0012 -> 0x1208 posts with static @6 == 39)
  -> post row -> 決定 (0x1200 selector 0x0004 -> 0x1202 characters) -> person row -> 決定 (0x0322 -> 0x0323)
  -> confirm 決定 -> 0x0707 CommandCardAppointment -> authority persists (original_card_appointment,
  original_character_card, domain_event CharacterCardAppointed, account.authority_version+1) -> accepted echo
  -> client closes the dialog -> authority restart on the same DB -> relogin -> DB rows identical.
"""
import glob
import hashlib
import json
import os
import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-11-appointment-verification.json"

run, tag, rtag = sys.argv[1], sys.argv[2], sys.argv[3]
D = RUNS + run


def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()


def J(n):
    p = D + "/" + n
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else None


def rows(n):
    w = J(n)
    return (w.get("rows", w) if w else []) or []


def q(db, key):
    try:
        v = db["results"][key]["stdout"]
        return json.loads(v) if v and v.strip().startswith("[") else v
    except Exception:
        return None


prep, relaunch = J("fresh-run-prep.json"), J("relaunch-prep.json")
cred, credR = J("cred-" + tag + ".json"), J("cred-" + rtag + ".json")
dbA, dbR0, dbR1 = J("db-" + tag + ".json"), J("db-" + rtag + "0.json"), J("db-" + rtag + "1.json")
wireA = rows("wire-after-confirm-" + tag + ".json")
stop, cleanup, census = J("clean-stop.json"), J("cleanup.json"), J("verify-after-stop.json")

appoint = [r for r in wireA if r.get("ObservedApplicationType") == 0x0707]
info = [r for r in wireA if r.get("ObservedApplicationType") == 0x0322]
lists = [r for r in wireA if r.get("ObservedApplicationType") == 0x1200]
apA, apR0, apR1 = q(dbA, "cardAppointment"), q(dbR0, "cardAppointment"), q(dbR1, "cardAppointment")
ccA, ccR0, ccR1 = q(dbA, "characterCard"), q(dbR0, "characterCard"), q(dbR1, "characterCard")
events = q(dbA, "domainEventLatest") or []

checks = {
    "post_list_served_0x1208": any("notify=0x1208" in (r.get("ResponseMetadata") or "") for r in lists),
    "character_list_served_0x1202": any("notify=0x1202" in (r.get("ResponseMetadata") or "") for r in lists),
    "information_character_served_0x0322": any(r.get("status") == "Success" for r in info),
    "appointment_accepted_0x0707": any(r.get("status") == "Success" and "card-appointment-accepted" in (r.get("ResponseMetadata") or "") for r in appoint),
    "db_appointment_row_card40_target2": bool(apA) and any(a.get("card_id") == 40 and a.get("target_character_id") == 2 for a in apA),
    "db_character_card_target2_card40": bool(ccA) and any(c.get("character_id") == 2 and c.get("card_id") == 40 for c in ccA),
    "db_domain_event_CharacterCardAppointed": any(e.get("event_type") == "CharacterCardAppointed" for e in events),
    "authority_restarted_on_same_db": bool(relaunch and prep and relaunch.get("status") == "FRESH_RUN_PREINPUT_READY" and relaunch["authority"]["pid"] != prep["authority"]["pid"]),
    "db_identical_after_restart_and_relogin": bool(apA) and apA == apR0 == apR1 and ccA == ccR0 == ccR1,
    "relogin_one_submission": bool(credR and credR.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and credR["operations"]["inputRetries"] == 0),
    "run_closed_cleanly": bool(stop and stop.get("status") == "RUN_RUNTIME_CLEANLY_STOPPED" and cleanup and cleanup.get("status") == "RUN_CLEANED" and census is not None and not census.get("leftoverProcesses")),
}
verdict = "PLAYER_VISIBLE_REPRODUCIBLE" if all(checks.values()) else "PARTIAL"
files = sorted(os.path.basename(f) for f in glob.glob(D + "/*") if os.path.isfile(f) and (f.endswith(".json") or f.endswith(".jsonl") or "vnc-s" in os.path.basename(f) or "vnc-" + rtag in os.path.basename(f)))
out = {
    "schema": "logh7-condition-verification/1",
    "condition": 11,
    "alsoConditions": [9, 13],
    "feature": "任命 CommandCardAppointment (personnel): post list (0x1208, static appointer == player card), person list (0x1202), character info (0x0322/0x0323), command 0x0707 -> PostgreSQL -> restart -> relogin",
    "verdict": verdict,
    "runId": run,
    "method": "natural PostgreSQL authority, unmodified client code path (item115 = reversible debug-log working copy for logs only), one SendInput credential per login, VNC clicks; read-only DB inspection (guest-db-inspect); authority restart on the same database (guest-restart-authority); no process-memory writes.",
    "newDesignNotes": "The static card field @6 (appointing authority) and the served candidate lists are still driven by probe env (LOGH7_STATIC_CARD_APPOINTER / LOGH7_NINMEI_PROBE=10 / LOGH7_NINMEI_CHARS); persistence tables original_card_appointment / original_character_card are NEW_DESIGN (original server DB semantics unrecoverable).",
    "checks": checks,
    "observed": {"appointmentWire": [r.get("ResponseMetadata") for r in appoint], "dbAppointment": apA, "dbCharacterCard": ccA, "accountVersionAfter": q(dbA, "accountVersion"), "accountVersionAfterRelogin": q(dbR1, "accountVersion")},
    "receipts": {f: sha(D + "/" + f) for f in files},
}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", verdict)
for k, v in checks.items():
    print("  ", "PASS" if v else "FAIL", k)
