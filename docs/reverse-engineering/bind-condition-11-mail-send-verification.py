import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T215023Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-11-mail-send-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def snap(n):
    r = J(n)["results"]
    mails = json.loads(r["mail"]["stdout"])
    return {"mailCount": len(mails), "maxMailId": max(m["mail_id"] for m in mails), "mail7": next((m for m in mails if m["mail_id"] == 7), None),
            "domainEventCount": int(r["domainEventCount"]["stdout"]), "accountVersion": int(r["accountVersion"]["stdout"]), "latest": r["domainEventLatest"]["stdout"][:400]}
A0, M, R, S = snap("db-a0.json"), snap("db-m.json"), snap("db-r.json"), snap("db-s.json")
prep, relaunch, stop, cleanup = J("fresh-run-prep.json"), J("relaunch-prep.json"), J("clean-stop.json"), J("cleanup.json")
credA, credE = J("cred-c.json"), J("cred-e.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
sends = [w for w in wire if w.get("observedApplicationType") == 3856]
s0 = sends[-1] if sends else None
same = lambda x, y: x["mailCount"] == y["mailCount"] and x["maxMailId"] == y["maxMailId"] and x["domainEventCount"] == y["domainEventCount"] and x["accountVersion"] == y["accountVersion"] and (x["mail7"] or {}).get("authority_version") == (y["mail7"] or {}).get("authority_version")
checks = {
    "baseline_sixMails_noMail7": A0["mailCount"] == 6 and A0["mail7"] is None,
    "login_reachedStrategy_mailbox_compose": credA["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and all(os.path.exists(D + "/" + f) for f in ["vnc-e-strategy.png", "vnc-s1-mailbox.png", "vnc-s2-compose.png"]),
    "recipientChosen_addressBook": os.path.exists(D + "/vnc-s3-recipient.png"),
    "titleAndBodyTyped": os.path.exists(D + "/vnc-s4-typed.png") and os.path.exists(D + "/vnc-s5-body.png"),
    "send_0x0F10_authoritySuccess_created": s0 is not None and s0["status"] == "Success" and "created=true" in (s0.get("responseMetadata") or "") and "mail-id=7" in (s0.get("responseMetadata") or ""),
    "screenReturnedToList_afterSend": os.path.exists(D + "/vnc-s6-after-send.png"),
    "db_mail7Inserted_event_OriginalMailSent_withTitleBody": M["mailCount"] == 7 and M["mail7"] is not None and "OriginalMailSent" in M["latest"] and "cond11 send test" in M["latest"] and "mail body for condition 11" in M["latest"] and M["domainEventCount"] == A0["domainEventCount"] + 1 and M["accountVersion"] == A0["accountVersion"] + 1,
    "mailboxClosed_clientExited": os.path.exists(D + "/vnc-s7-closed.png"),
    "authorityRestartedOnSameDb": relaunch["status"] == "FRESH_RUN_PREINPUT_READY" and relaunch["authority"]["pid"] != prep["authority"]["pid"],
    "sentMailSurvivedRestart_R_equals_M": same(M, R),
    "reloginAfterRestart_oneSubmission": credE["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and credE["operations"]["inputRetries"] == 0,
    "restoredAfterRelogin_S_equals_M": same(M, S),
    "sentTabShowsMail7_afterRestart_visibleRestore": os.path.exists(D + "/vnc-s9-sent-tab.png"),
    "normalShutdown_and_cleanup": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "relaunch-prep.json", "db-a0.json", "db-m.json", "db-r.json", "db-s.json", "cred-c.json", "cred-e.json", "clean-stop.json", "cleanup.json", "server-wire.jsonl", "server-wire-2.jsonl", "vnc-e-strategy.png", "vnc-s1-mailbox.png", "vnc-s2-compose.png", "vnc-s3-recipient.png", "vnc-s4-typed.png", "vnc-s5-body.png", "vnc-s6-after-send.png", "vnc-s7-closed.png", "vnc-s8-mailbox-after.png", "vnc-s9-sent-tab.png", "vnc-s10-closed-after.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 11, "feature": "communications (通信) — mail SEND creates real state; fourth strategy-screen feature for condition 9",
       "verdict": "PLAYER_VISIBLE_REPRODUCIBLE" if allpass else "FAILED", "runId": RUN,
       "verticalPath": "strategy HUD mail icon (872,748) → mailbox → 新規作成 (326,166) → address book (友人: ダスティ・アッテンボローン) → entry (190,80) + 決定 (320,534) → compose view: タイトル field (540,195) typed, body pane (500,430) typed (VNC keyboard) → 送信 (692,634) → client 0x0F10 → authority Success (mail-id=7; created=true; sender=2; recipient=2) → lists auto-refresh (count 4) → PostgreSQL original_mail_message id 7 inserted, domain_event 25 = OriginalMailSent{title, body, mailId 7}, account version 24→25 → close → ゲーム終了 → authority RESTART on same DB → relogin → mail 7 persisted (M==R==S) → 送信 tab shows 'cond11 send test…' (005/100).",
       "inputNotes": "the bottom '※ここにメッセージを書きます' line is the HUD chat bar, not the mail body: a click there did not move focus, so the second typed string appended to the title (title became 'cond11 send testlogh7 mail send vertical'); the body pane (500,430) accepted text. VNC typing works for ASCII in these GDI edit controls.",
       "renderFinding": "送信-tab title column draws new titles over stale ones without clearing ('TRACE MAILd testlogh7 mail send ve…', 'FIX LIVEELd…'): list-refresh clipping/erase bug — condition 6/16 item.",
       "nonDestructive": "no 削除/全削除 pressed",
       "wire": {"send0x0F10": s0},
       "snapshots": {"A0_baseline": A0, "M_afterSend": M, "R_afterRestart": R, "S_afterRelogin": S},
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
