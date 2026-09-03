"""Bind the 罷免 (CommandCardDismissal, 0x0708) vertical: original client input -> screen -> authority -> PostgreSQL ->
authority restart on the same database -> relogin. Usage:
  python bind-condition-9-dismissal-verification.py <runId> <appointTag> <dismissTag> <reloginTag>

Path (condition 9 evidence; NEW_DESIGN persistence, inverse of 任命):
  card 任命(923,283) -> post 40 -> 決定 -> person -> 決定 -> confirm -> 0x0707 (appoint card 40 to character 2), THEN
  card 罷免(822,310 with two extras) -> post 艦隊副司令官(card 40) -> 決定 -> holder picker (0x1200 selector 0x0005 -> 0x1202)
  -> holder アッテンボロー(character 2) -> 決定 -> confirm 「をから罷免します。コマンドポイント160MCP消費…」決定
  -> 0x0708 CommandCardDismissal -> authority removes original_character_card, records original_card_dismissal_command,
  domain_event CharacterCardDismissed, account.authority_version+1 -> accepted response -> client closes the dialog
  -> authority restart on the same DB -> relogin -> the appointment (original_character_card) stays removed.
"""
import hashlib
import json
import os
import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-9-dismissal-verification.json"

run, atag, dtag, rtag = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
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
cred, credR = J("cred-" + atag + ".json"), J("cred-" + rtag + ".json")
# appointment fired in the atag phase; dismissal fired in the dtag phase (same run, continued input)
dbAppoint, dbDismiss = J("db-" + atag + ".json"), J("db-" + dtag + ".json")
dbR0, dbR1 = J("db-" + rtag + "0.json"), J("db-" + rtag + "1.json")
wireAppoint, wireDismiss = rows("wire-" + atag + "-a.json"), rows("wire-" + dtag + ".json")
stop, cleanup, census = J("clean-stop.json"), J("cleanup.json"), J("verify-after-stop.json")

appoint = [r for r in wireAppoint if r.get("ObservedApplicationType") == 0x0707]
dismiss = [r for r in wireDismiss if r.get("ObservedApplicationType") == 0x0708]
lists = [r for r in wireDismiss if r.get("ObservedApplicationType") == 0x1200]
cardAppoint = q(dbAppoint, "characterCard")
cardDismiss = q(dbDismiss, "characterCard")
cardR0, cardR1 = q(dbR0, "characterCard"), q(dbR1, "characterCard")
events = q(dbDismiss, "domainEventLatest") or []
dismissed = [e for e in events if e.get("event_type") == "CharacterCardDismissed"]
metaD = dismiss[-1].get("ResponseMetadata", "") if dismiss else ""


def empty(card):
    # db-inspect prints the query stdout; an empty result set is "" (falsy) or []
    return card in (None, "", "[]", []) or card == []


checks = {
    "prepReady": bool(prep and prep.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "oneCredentialSubmission": bool(cred and cred.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "appointmentFirst": bool(appoint) and "card-appointment-accepted" in (appoint[-1].get("ResponseMetadata") or ""),
    "appointmentPersisted": bool(cardAppoint) and any(c.get("card_id") == 40 and c.get("character_id") == 2 for c in cardAppoint),
    "postListServed": any("himen" in (r.get("ResponseMetadata") or "").lower() or "selector=0x0012" in (r.get("ResponseMetadata") or "") for r in lists),
    "holderPickerServedAsCharacterList": any("selector=0x0005" in (r.get("ResponseMetadata") or "") for r in lists),
    "dismissalAccepted": bool(dismiss) and dismiss[-1].get("status") == "Success" and metaD.startswith("card-dismissal-accepted"),
    "noConnectionCloseAfterCommand": bool(dismiss) and not any(r.get("ev") == "connection-closed" and r.get("t", "") > dismiss[-1].get("t", "") for r in wireDismiss),
    "appointmentRemoved": empty(cardDismiss),
    "domainEventCharacterCardDismissed": bool(dismissed) and dismissed[0]["payload"].get("cardId") == 40 and dismissed[0]["payload"].get("targetCharacterId") == 2,
    "relaunchReady": bool(relaunch and relaunch.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "reloginOneCredentialSubmission": bool(credR and credR.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "removedAfterRestart": empty(cardR0),
    "removedAfterRelogin": empty(cardR1),
    "accountVersionStable": bool(dbDismiss and dbR1) and q(dbDismiss, "accountVersion") == q(dbR0, "accountVersion") == q(dbR1, "accountVersion"),
    "cleanStop": bool(stop and stop.get("status") == "RUN_RUNTIME_CLEANLY_STOPPED"),
    "cleanup": bool(cleanup and cleanup.get("status") == "RUN_CLEANED"),
    "noLeftoverProcesses": bool(census and census.get("status") == "VERIFIED" and not any(r.get("postgresData") or r.get("server") for r in census.get("runs", []) if r.get("runId") == run)),
}
passed = sum(1 for v in checks.values() if v)
shots = {n: sha(D + "/" + n) for n in sorted(os.listdir(D)) if n.startswith("vnc-" + atag + "-") or n.startswith("vnc-" + dtag + "-") or n.startswith("vnc-" + rtag + "-")}
receipt = {
    "schema": "logh7/condition-9-dismissal-verification/v1",
    "condition": 9,
    "feature": "罷免 CommandCardDismissal 0x0708 (remove card 40 = 艦隊副司令官 from character 2, the inverse of 任命 0x0707)",
    "runId": run,
    "clientExe": prep.get("client", {}).get("exeSha256") if prep else None,
    "authorityZipSha256": prep.get("server", {}).get("zipSha256") if prep else None,
    "checks": checks,
    "passed": f"{passed}/{len(checks)}",
    "status": "PLAYER_VISIBLE_REPRODUCIBLE" if passed == len(checks) else "PARTIAL",
    "dismissalResponse": dismiss[-1] if dismiss else None,
    "characterCard": {"afterAppoint": cardAppoint, "afterDismiss": cardDismiss, "afterRestart": cardR0, "afterRelogin": cardR1},
    "domainEvent": dismissed[0] if dismissed else None,
    "screenshots": shots,
    "newDesign": "Dismissal removes original_character_card and records original_card_dismissal_command (migration 0014) with event CharacterCardDismissed; the 任命 audit row in original_card_appointment is retained. The original 160 MCP cost and wait time are not modelled. Store failures (no such appointment) soft-reject via 0x0500 NotifyInvalidMessage instead of dropping the connection.",
}
json.dump(receipt, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(OUT, receipt["passed"], receipt["status"])
for k, v in checks.items():
    if not v:
        print("  FAIL", k)
