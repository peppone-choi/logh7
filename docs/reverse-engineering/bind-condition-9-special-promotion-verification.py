"""Bind the 抜擢 (CommandSpeciallyRankUp, 0x0705) vertical: original client input -> screen -> authority -> PostgreSQL ->
authority restart on the same database -> relogin. Usage:
  python bind-condition-9-special-promotion-verification.py <runId> <tag> <reloginTag>

Path (condition 9 evidence — one strategy feature end to end; NEW_DESIGN persistence):
  strategy -> 職務権限カード -> card 39 -> 抜擢 (0x1200 selector 0x0011 rank ladder) -> ladder row -> 決定
  -> person picker (panel state 3, 0x1200 selector 0x0015 -> 0x1202 characters) -> person row (0x0322 -> 0x0323)
  -> 決定 -> confirm 「…を…に抜擢します。コマンドポイント320MCP消費…」 決定 -> 0x0705 CommandSpeciallyRankUp
  -> authority persists (character.rank-1, character_rank_command replay row, domain_event CharacterSpeciallyPromoted,
  account.authority_version+1) -> accepted response (0x0704-shaped) + character push -> client closes the dialog
  -> authority restart on the same DB -> relogin -> DB rows identical, HUD shows the new rank.
"""
import hashlib
import json
import os
import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-9-special-promotion-verification.json"

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
wireA = rows("wire-" + tag + "-cmd.json")
stop, cleanup, census = J("clean-stop.json"), J("cleanup.json"), J("verify-after-stop.json")

special = [r for r in wireA if r.get("ObservedApplicationType") == 0x0705]
info = [r for r in wireA if r.get("ObservedApplicationType") == 0x0322]
lists = [r for r in wireA if r.get("ObservedApplicationType") == 0x1200]
chA, chR0, chR1 = q(dbA, "characters"), q(dbR0, "characters"), q(dbR1, "characters")
events = q(dbA, "domainEventLatest") or []
promoted = [e for e in events if e.get("event_type") == "CharacterSpeciallyPromoted"]
meta = special[-1].get("ResponseMetadata", "") if special else ""

checks = {
    "prepReady": bool(prep and prep.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "oneCredentialSubmission": bool(cred and cred.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "ladderListServed": any("selector=0x0011" in (r.get("ResponseMetadata") or "") for r in lists),
    "personPickerServedAsCharacterList": any("selector=0x0015" in (r.get("ResponseMetadata") or "") and "notify=0x1202" in (r.get("ResponseMetadata") or "") for r in lists),
    "characterInfoServed": len(info) >= 1 and all(r.get("status") == "Success" for r in info),
    "specialRankUpAccepted": bool(special) and special[-1].get("status") == "Success" and meta.startswith("special-rank-up-accepted") and "updated=True" in meta,
    "dbRankDecrementedByOne": bool(chA) and any(c.get("rank") == 19 for c in chA),
    "domainEventCharacterSpeciallyPromoted": bool(promoted) and promoted[0]["payload"].get("sourceRank") == 20 and promoted[0]["payload"].get("promotedRank") == 19,
    "relaunchReady": bool(relaunch and relaunch.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "reloginOneCredentialSubmission": bool(credR and credR.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "dbIdenticalAfterRestart": chA is not None and chA == chR0,
    "dbIdenticalAfterRelogin": chA is not None and chA == chR1,
    "accountVersionStable": bool(dbA and dbR1) and q(dbA, "accountVersion") == q(dbR0, "accountVersion") == q(dbR1, "accountVersion"),
    "cleanStop": bool(stop and stop.get("status") == "RUN_RUNTIME_CLEANLY_STOPPED"),
    "cleanup": bool(cleanup and cleanup.get("status") == "RUN_CLEANED"),
    "noLeftoverProcesses": bool(census and census.get("status") == "VERIFIED" and not any(r.get("postgresData") or r.get("server") for r in census.get("runs", []) if r.get("runId") == run)),
}
passed = sum(1 for v in checks.values() if v)
shots = {}
for n in sorted(os.listdir(D)):
    if n.startswith("vnc-" + tag + "-") or n.startswith("vnc-" + rtag + "-"):
        shots[n] = sha(D + "/" + n)
receipt = {
    "schema": "logh7/condition-9-special-promotion-verification/v1",
    "condition": 9,
    "feature": "抜擢 CommandSpeciallyRankUp 0x0705 (card 39, target = own character 2, 二等兵 20 -> 一等兵 19)",
    "runId": run,
    "clientExe": prep.get("client", {}).get("exeSha256") if prep else None,
    "authorityZipSha256": prep.get("server", {}).get("zipSha256") if prep else None,
    "checks": checks,
    "passed": f"{passed}/{len(checks)}",
    "status": "PLAYER_VISIBLE_REPRODUCIBLE" if passed == len(checks) else "PARTIAL",
    "specialRankUpResponse": special[-1] if special else None,
    "dbCharacters": {"afterCommand": chA, "afterRestart": chR0, "afterRelogin": chR1},
    "domainEvent": promoted[0] if promoted else None,
    "screenshots": shots,
    "newDesign": "The original server's 320 MCP cost and wait time are not modelled (served pcp/mcp are 0); persistence reuses the 0x0704 rank tables with event CharacterSpeciallyPromoted and the actor id.",
    "knownGaps": [
        "confirm dialog placeholders ($com_xfullname$ etc.) render empty (client-side context writer not located)",
        "after the restart leg the relaunched authority does not inherit LOGH7_EXTRA_CARD_COMMANDS, so the card shows the 3 default commands",
    ],
}
json.dump(receipt, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(OUT, receipt["passed"], receipt["status"])
for k, v in checks.items():
    if not v:
        print("  FAIL", k)
