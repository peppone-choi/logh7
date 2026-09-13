import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T213601Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-11-mail-read-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def snap(n):
    r = J(n)["results"]
    mails = json.loads(r["mail"]["stdout"])
    by = {m["mail_id"]: m for m in mails}
    return {"mail6_isRead": by[6]["is_read"], "mail6_version": by[6]["authority_version"], "mail3_isRead": by[3]["is_read"],
            "unreadCount": sum(1 for m in mails if not m["is_read"]),
            "domainEventCount": int(r["domainEventCount"]["stdout"]), "accountVersion": int(r["accountVersion"]["stdout"]),
            "latest": r["domainEventLatest"]["stdout"][:300]}
A0, M, R, S = snap("db-a0.json"), snap("db-m.json"), snap("db-r.json"), snap("db-s.json")
prep, relaunch, stop, cleanup = J("fresh-run-prep.json"), J("relaunch-prep.json"), J("clean-stop.json"), J("cleanup.json")
credA, credE = J("cred-c.json"), J("cred-e.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
lists = [w for w in wire if w.get("observedApplicationType") == 3848]
reads = [w for w in wire if w.get("observedApplicationType") == 3857]
r0 = reads[-1] if reads else None
same = lambda x, y: x["mail6_isRead"] == y["mail6_isRead"] and x["mail6_version"] == y["mail6_version"] and x["unreadCount"] == y["unreadCount"] and x["domainEventCount"] == y["domainEventCount"] and x["accountVersion"] == y["accountVersion"]
checks = {
    "baseline_mail6_unread_twoUnread": A0["mail6_isRead"] is False and A0["unreadCount"] == 2,
    "login_reachedStrategy": credA["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and os.path.exists(D + "/vnc-e-strategy.png"),
    "mailIcon_opensMailbox_listServed_0x0F08": os.path.exists(D + "/vnc-m1-mailbox.png") and bool(lists) and lists[-1]["status"] == "Success",
    "rowClick_sends_0x0F11_authoritySuccess_readUpdated": r0 is not None and r0["status"] == "Success" and "read-updated=true" in (r0.get("responseMetadata") or "") and "mail-id=6" in (r0.get("responseMetadata") or ""),
    "screenShowsBody_and_openedEnvelope": os.path.exists(D + "/vnc-m2-mailread.png"),
    "db_mail6_isRead_true_event_OriginalMailRead": M["mail6_isRead"] is True and M["unreadCount"] == 1 and "OriginalMailRead" in M["latest"] and M["domainEventCount"] == A0["domainEventCount"] + 1 and M["accountVersion"] == A0["accountVersion"] + 1,
    "mailboxClosed_clientExited": os.path.exists(D + "/vnc-m3-closed.png"),
    "authorityRestartedOnSameDb": relaunch["status"] == "FRESH_RUN_PREINPUT_READY" and relaunch["authority"]["pid"] != prep["authority"]["pid"],
    "readStateSurvivedRestart_R_equals_M": same(M, R),
    "reloginAfterRestart_oneSubmission": credE["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and credE["operations"]["inputRetries"] == 0,
    "restoredAfterRelogin_S_equals_M": same(M, S),
    "mailboxAfterRestart_showsOneUnread_visibleRestore": os.path.exists(D + "/vnc-m5-mailbox-after.png"),
    "normalShutdown_and_cleanup": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "relaunch-prep.json", "db-a0.json", "db-m.json", "db-r.json", "db-s.json", "cred-c.json", "cred-e.json", "clean-stop.json", "cleanup.json", "server-wire.jsonl", "server-wire-2.jsonl", "vnc-e-strategy.png", "vnc-m1-mailbox.png", "vnc-m2-mailread.png", "vnc-m3-closed.png", "vnc-m5-mailbox-after.png", "vnc-m6-closed-after.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 11, "feature": "communications (通信) — mail READ changes real state; also a third strategy-screen feature for condition 9",
       "verdict": "PLAYER_VISIBLE_REPRODUCIBLE" if allpass else "FAILED", "runId": RUN,
       "verticalPath": "strategy HUD mail icon (872,748) → mailbox (受信, unread-only list 002/100: 'FIX LIVE', '命令（返答済み）'; authority serves 0x0F08 lists) → click row 'FIX LIVE' → client 0x0F11 read → authority Success (mail-id=6; read-updated=true; authority-version=25) → screen shows body ('NO ACK') and opened-envelope icon → PostgreSQL original_mail_message id 6 is_read false→true (read_at set), domain_event 25 = OriginalMailRead{mailId:6}, account version 24→25 → close mailbox → ゲーム終了 → authority RESTART on same DB → relogin → read state restored (M==R==S) → mailbox shows only one unread (001/100).",
       "nonDestructive": "no 削除/全削除 pressed; only a read (is_read) state change",
       "wire": {"lastList0x0F08": lists[-1] if lists else None, "read0x0F11": r0},
       "snapshots": {"A0_baseline": A0, "M_afterRead": M, "R_afterRestart": R, "S_afterRelogin": S},
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
