"""Bind the 辞任 (CommandCardResignation, 0x0709) vertical: original client input -> screen -> authority -> PostgreSQL ->
authority restart on the same database -> relogin -> the client renders the resigned state. Usage:
  python bind-condition-9-resignation-verification.py <runId> <tag> <reloginTag>

Path (condition 9 evidence; the resigned state is ORIGINAL, not new design):
  card 辞任(722,310) -> confirm 「を辞任します。コマンドポイント80MCP消費…」決定(565,516) -> 0x0709 (no picker; the client
  sends the card it currently displays, here the authored 39 艦隊司令官) -> authority sets original_character_card.card_id = 0,
  records original_card_resignation_command, emits CharacterCardResigned (migration 0015) -> accepted response
  -> authority restart on the same DB -> relogin -> world entry now serves the PERSISTED card (0) instead of the constant,
  and the unmodified client renders 職務権限カード as 「皇宮 ： 個人」 with an empty command grid.

Card 0 is the ORIGINAL's own "holds no post" value, not an invention: constmsg group 3 row 0 = 個人, group 4 row 0 = 皇宮,
and EncodeStaticCards already serves card 0 with zero commands. Proven independently in run 20260903T085429Z by forcing
LOGH7_WORLD_CARD_ID=0 at world entry.
"""
import hashlib
import json
import os
import sys

ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs/"
OUT = ROOT + "/docs/reverse-engineering/condition-9-resignation-verification.json"

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


def card_id(cards):
    return cards[0].get("card_id") if isinstance(cards, list) and cards else None


def changed_pixels(a, b):
    """The post-resignation card panel must not look like the card-39 panel."""
    try:
        from PIL import Image, ImageChops
        x, y = Image.open(D + "/" + a).convert("RGB"), Image.open(D + "/" + b).convert("RGB")
        if x.size != y.size:
            return None
        d = ImageChops.difference(x, y).convert("L").point(lambda p: 255 if p > 40 else 0)
        return sum(1 for p in d.getdata() if p)
    except Exception:
        return None


prep, relaunch = J("fresh-run-prep.json"), J("relaunch-prep.json")
cred, credR = J("cred-" + tag + ".json"), J("cred-" + rtag + ".json")
db, dbR0, dbR1 = J("db-" + tag + ".json"), J("db-" + rtag + "0.json"), J("db-" + rtag + "1.json")
wire = rows("wire-" + tag + ".json")
stop, cleanup, census = J("clean-stop.json"), J("cleanup.json"), J("verify-after-stop.json")

resign = [r for r in wire if r.get("ObservedApplicationType") == 0x0709]
meta = resign[-1].get("ResponseMetadata", "") if resign else ""
cards, cardsR0, cardsR1 = q(db, "characterCard"), q(dbR0, "characterCard"), q(dbR1, "characterCard")
events = q(db, "domainEventLatest") or []
resigned = [e for e in events if e.get("event_type") == "CharacterCardResigned"]
# card panel before resignation (card 39) vs after restart+relogin (card 0)
panel_delta = changed_pixels("vnc-s4-card.png", "vnc-" + rtag + "-4-card.png")

checks = {
    "prepReady": bool(prep and prep.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "oneCredentialSubmission": bool(cred and cred.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "resignationAccepted": bool(resign) and resign[-1].get("status") == "Success" and meta.startswith("card-resignation-accepted"),
    "resignedFromAuthoredCard": "from=39" in meta and "to=0" in meta,
    "noConnectionCloseAfterCommand": bool(resign) and not any(r.get("ev") == "connection-closed" and r.get("t", "") > resign[-1].get("t", "") for r in wire),
    "dbCardIsZero": card_id(cards) == 0,
    "domainEventCharacterCardResigned": bool(resigned) and resigned[0]["payload"].get("sourceCardId") == 39 and resigned[0]["payload"].get("resultingCardId") == 0,
    "relaunchReady": bool(relaunch and relaunch.get("status") == "FRESH_RUN_PREINPUT_READY"),
    "reloginOneCredentialSubmission": bool(credR and credR.get("status") == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT"),
    "cardZeroAfterRestart": card_id(cardsR0) == 0,
    "cardZeroAfterRelogin": card_id(cardsR1) == 0,
    "accountVersionStable": bool(db and dbR1) and q(db, "accountVersion") == q(dbR0, "accountVersion") == q(dbR1, "accountVersion"),
    "clientCardPanelChanged": panel_delta is not None and panel_delta > 1000,
    "cleanStop": bool(stop and stop.get("status") == "RUN_RUNTIME_CLEANLY_STOPPED"),
    "cleanup": bool(cleanup and cleanup.get("status") == "RUN_CLEANED"),
    "noLeftoverProcesses": bool(census and census.get("status") == "VERIFIED" and not any(r.get("postgresData") or r.get("server") for r in census.get("runs", []) if r.get("runId") == run)),
}
passed = sum(1 for v in checks.values() if v)
shots = {n: sha(D + "/" + n) for n in sorted(os.listdir(D)) if n.startswith("vnc-" + tag + "-") or n.startswith("vnc-" + rtag + "-") or n == "vnc-s4-card.png"}
receipt = {
    "schema": "logh7/condition-9-resignation-verification/v1",
    "condition": 9,
    "feature": "辞任 CommandCardResignation 0x0709 (character 2 resigns the authored card 39 艦隊司令官; resulting state = card 0 個人)",
    "runId": run,
    "clientExe": prep.get("client", {}).get("exeSha256") if prep else None,
    "authorityZipSha256": prep.get("server", {}).get("zipSha256") if prep else None,
    "checks": checks,
    "passed": f"{passed}/{len(checks)}",
    "status": "PLAYER_VISIBLE_REPRODUCIBLE" if passed == len(checks) else "PARTIAL",
    "resignationResponse": resign[-1] if resign else None,
    "characterCard": {"afterResign": cards, "afterRestart": cardsR0, "afterRelogin": cardsR1},
    "domainEvent": resigned[0] if resigned else None,
    "cardPanelChangedPixels": panel_delta,
    "screenshots": shots,
    "originalNotNewDesign": "Card 0 = 個人 is the ORIGINAL's own 'holds no post' value (constmsg group 3 row 0; group 4 row 0 = 皇宮), and EncodeStaticCards already emits card 0 with zero commands. Independently proven in run 20260903T085429Z by serving card 0 at world entry via LOGH7_WORLD_CARD_ID: the client rendered 「皇宮 ： 個人」 with an empty command grid and no crash. The authority change is therefore a mechanism (world entry serves the PERSISTED card from original_character_card, falling back to the authored 39) plus migration 0015 (widen the card_id CHECK to allow 0, add original_card_resignation_command); the 80 MCP cost and wait time are not modelled.",
}
json.dump(receipt, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print(OUT, receipt["passed"], receipt["status"])
for k, v in checks.items():
    if not v:
        print("  FAIL", k)
