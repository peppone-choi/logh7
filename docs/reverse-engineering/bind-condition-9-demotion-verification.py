"""Bind the 降等 (CommandRankDown, 0x0706) vertical: original client input -> screen -> authority -> PostgreSQL ->
authority restart on the same database -> relogin. Usage:
  python bind-condition-9-demotion-verification.py <runId> <tag> <reloginTag>

Path (condition 9 evidence; NEW_DESIGN persistence):
  card 抜擢 first (target 20 -> 19, so a demotion has room) then card 降等 -> ladder (0x1200 selector 0x0011) -> ladder row -> 決定
  -> person picker (panel state 3, 0x1200 selector 0x0015 -> 0x1202 characters) -> person row (0x0322) -> 決定 -> confirm 決定
  -> 0x0706 CommandRankDown -> authority persists (character.rank+1, character_rank_down_command replay row,
  domain_event CharacterDemoted, account.authority_version+1) -> accepted response -> client closes the dialog
  -> authority restart on the same DB -> relogin -> DB rows identical, HUD shows the restored rank.
"""
import hashlib
import json
import os
import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-9-demotion-verification.json"

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
wireC, wireD = rows("wire-" + tag + "-c.json"), rows("wire-" + tag + "-d.json")
stop, cleanup, census = J("clean-stop.json"), J("cleanup.json"), J("verify-after-stop.json")

special = [r for r in wireC if r.get("ObservedApplicationType") == 0x0705]
down = [r for r in wireD if r.get("ObservedApplicationType") == 0x0706]
lists = [r for r in wireD if r.get("ObservedApplicationType") == 0x1200]
chA, chR0, chR1 = q(dbA, "characters"), q(dbR0, "characters"), q(dbR1, "characters")
events = q(dbA, "domainEventLatest") or []
demoted = [e for e in events if e.get("event_type") == "CharacterDemoted"]
metaD = down[-1].get("ResponseMetadata", "") if down else ""

checks = {
    "prepReady": bool(prep and prep.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "oneCredentialSubmission": bool(cred and cred.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "specialPromotionFirst": bool(special) and "special-rank-up-accepted" in (special[-1].get("ResponseMetadata") or ""),
    "ladderListServed": any("selector=0x0011" in (r.get("ResponseMetadata") or "") for r in lists),
    "personPickerServedAsCharacterList": any("selector=0x0015" in (r.get("ResponseMetadata") or "") and "notify=0x1202" in (r.get("ResponseMetadata") or "") for r in lists),
    "rankDownAccepted": bool(down) and down[-1].get("status") == "Success" and metaD.startswith("rank-down-accepted") and "updated=True" in metaD,
    "noConnectionCloseAfterCommand": bool(down) and not any(r.get("ev") == "connection-closed" and r.get("t", "") > down[-1].get("t", "") for r in wireD),
    "dbRankBackToTwenty": bool(chA) and any(c.get("rank") == 20 for c in chA),
    "domainEventCharacterDemoted": bool(demoted) and demoted[0]["payload"].get("sourceRank") == 19 and demoted[0]["payload"].get("promotedRank") == 20,
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
shots = {n: sha(D + "/" + n) for n in sorted(os.listdir(D)) if n.startswith("vnc-" + tag + "-") or n.startswith("vnc-" + rtag + "-")}
receipt = {
    "schema": "logh7/condition-9-demotion-verification/v1",
    "condition": 9,
    "feature": "降等 CommandRankDown 0x0706 (card 39, target = own character 2, 一等兵 19 -> 二等兵 20 after a prior 抜擢 20 -> 19)",
    "runId": run,
    "clientExe": prep.get("client", {}).get("exeSha256") if prep else None,
    "authorityZipSha256": prep.get("server", {}).get("zipSha256") if prep else None,
    "checks": checks,
    "passed": f"{passed}/{len(checks)}",
    "status": "PLAYER_VISIBLE_REPRODUCIBLE" if passed == len(checks) else "PARTIAL",
    "rankDownResponse": down[-1] if down else None,
    "dbCharacters": {"afterCommands": chA, "afterRestart": chR0, "afterRelogin": chR1},
    "domainEvent": demoted[0] if demoted else None,
    "screenshots": shots,
    "newDesign": "Demotions persist in character_rank_down_command (migration 0013, demoted_rank = source_rank + 1) with event CharacterDemoted and the actor id; the original 320/80 MCP costs and wait times are not modelled. Store failures soft-reject via 0x0500 NotifyInvalidMessage instead of dropping the connection.",
}
json.dump(receipt, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(OUT, receipt["passed"], receipt["status"])
for k, v in checks.items():
    if not v:
        print("  FAIL", k)
