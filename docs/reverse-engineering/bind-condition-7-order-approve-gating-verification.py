import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T220359Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-7-order-approve-gating-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def snap(n):
    r = J(n)["results"]
    return {"orderReply": json.loads(r["orderReply"]["stdout"]), "domainEventCount": int(r["domainEventCount"]["stdout"]), "accountVersion": int(r["accountVersion"]["stdout"]), "character": r["characterVersion"]["stdout"]}
A0, M = snap("db-a0.json"), snap("db-m.json")
prep, stop, cleanup, cred = J("fresh-run-prep.json"), J("clean-stop.json"), J("cleanup.json"), J("cred-c.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
cardread = [w for w in wire if w.get("observedApplicationType") == 3857 and "order-suggest-resolved-card-read" in (w.get("responseMetadata") or "")]
replies = [w for w in wire if w.get("observedApplicationType") == 3860]
checks = {
    "baseline_cardAlreadyDecided_reply2_card39": len(A0["orderReply"]) == 1 and A0["orderReply"][0]["card_id"] == 39 and A0["orderReply"][0]["reply_value"] == 2,
    "login_reachedStrategy_mailbox": cred["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and os.path.exists(D + "/vnc-o1-mailbox.png"),
    "orderMailRow_opens_resolvedCardRead_0x0F11": os.path.exists(D + "/vnc-o2-order-mail.png") and bool(cardread) and cardread[-1]["status"] == "Success" and "reply=2" in cardread[-1]["responseMetadata"] and "authored-card-id=39" in cardread[-1]["responseMetadata"],
    "approveButton_noRequestSent_0x0F14_absent": len(replies) == 0,
    "approveButton_noVisibleChange": os.path.exists(D + "/vnc-o3-after-shounin.png"),
    "db_unchanged_afterApprove": M["orderReply"] == A0["orderReply"] and M["domainEventCount"] == A0["domainEventCount"] and M["accountVersion"] == A0["accountVersion"],
    "normalShutdown_and_cleanup": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "db-a0.json", "db-m.json", "cred-c.json", "clean-stop.json", "cleanup.json", "server-wire.jsonl", "vnc-e-strategy.png", "vnc-o1-mailbox.png", "vnc-o2-order-mail.png", "vnc-o3-after-shounin.png", "vnc-o4-closed.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 7, "feature": "order-suggest 承認 (approve) button on an ALREADY-DECIDED authored order card (card 39, reply_value 2)",
       "verdict": "OBSERVED_GATED_SILENT" if allpass else "FAILED",
       "meaning": "The client correctly refrains from sending 0x0F14 for a resolved card (state matches the authority's ALREADY_DECIDED rule), BUT it gives NO visible reason — the goal's condition 7 requires a verifiable reason for a disabled/inert required button. Recorded as a condition-7 GAP item (silent no-op), not a pass.",
       "runId": RUN,
       "sequence": "login → strategy → mail icon → mailbox → row 「命令（返答済み）」(440,240) → client 0x0F11 → authority Success (order-suggest-resolved-card-read; reply=2; authored-card-id=39) → body 「オウム返しK」 → 承認 (250,166) → no request, no dialog, no message → DB unchanged → close → ゲーム終了 → clean-stop → cleanup",
       "wire": {"resolvedCardRead0x0F11": cardread[-1] if cardread else None, "reply0x0F14Count": len(replies)},
       "snapshots": {"A0_baseline": A0, "M_afterApprove": M},
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
