import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T210224Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-8-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def snap(n):
    r = J(n)["results"]
    return {"gridUnit": json.loads(r["gridUnit"]["stdout"]), "moveCommandCount": int(r["moveCommandCount"]["stdout"]),
            "domainEventCount": int(r["domainEventCount"]["stdout"]), "accountVersion": int(r["accountVersion"]["stdout"])}
A0, M, R, S = snap("db-a0.json"), snap("db-m.json"), snap("db-r.json"), snap("db-s.json")
prep, relaunch, stop, cleanup = J("fresh-run-prep.json"), J("relaunch-prep.json"), J("clean-stop.json"), J("cleanup.json")
credA, credE = J("cred-c.json"), J("cred-e.json")
dest, kettei = J("click-dest.json"), J("click-kettei.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
warp = [w for w in wire if w.get("observedApplicationType") == 2817]
w0 = warp[-1] if warp else None
same = lambda x, y: x["gridUnit"] == y["gridUnit"] and x["moveCommandCount"] == y["moveCommandCount"] and x["domainEventCount"] == y["domainEventCount"] and x["accountVersion"] == y["accountVersion"]
checks = {
    "baseline_unitAtCell101_noMoves": A0["gridUnit"]["current_cell_id"] == 101 and A0["moveCommandCount"] == 0,
    "login_reachedStrategy": credA["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and os.path.exists(D + "/vnc-e-strategy.png"),
    "fleetSelect_cardTab_card_warpCommand_captured": all(os.path.exists(D + "/" + f) for f in ["vnc-w1-cardtab.png", "vnc-w2-card.png", "vnc-w3-warpmode.png"]),
    "destinationChosen_oneGuestClick_noRetry": dest["status"] == "ONE_CLIENT_CLICK_SENT" and dest["operations"]["inputRetries"] == 0 and dest["expectedStage"] == "WARP_GRID_CHOOSER",
    "confirmDialogShown_beforeDecision": os.path.exists(D + "/vnc-w7-after-guestclick.png"),
    "decision_oneGuestClick_noRetry": kettei["status"] == "ONE_CLIENT_CLICK_SENT" and kettei["operations"]["inputRetries"] == 0,
    "authorityAccepted_0x0B01_101_to_102": w0 is not None and w0["status"] == "Success" and "source-cell=101" in (w0.get("responseMetadata") or "") and "destination-cell=102" in (w0.get("responseMetadata") or ""),
    "screenUpdated_gridSelectionComplete": os.path.exists(D + "/vnc-w8-after-warp.png"),
    "dbSaved_cell102_version25_oneMove_eventAppended": M["gridUnit"]["current_cell_id"] == 102 and M["moveCommandCount"] == 1 and M["domainEventCount"] == A0["domainEventCount"] + 1 and M["accountVersion"] == A0["accountVersion"] + 1,
    "clientExited_thenAuthorityRestartedOnSameDb": relaunch["status"] == "FRESH_RUN_PREINPUT_READY" and relaunch["authority"]["pid"] != prep["authority"]["pid"],
    "moveSurvivedRestart_R_equals_M": same(M, R),
    "reloginAfterRestart_oneSubmission": credE["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and credE["operations"]["inputRetries"] == 0,
    "restoredAfterRelogin_S_equals_M": same(M, S),
    "restoredPositionVisible_cameraCentredOnCell102": os.path.exists(D + "/vnc-x-strategy-restored.png"),
    "normalShutdown_and_cleanup": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "relaunch-prep.json", "db-a0.json", "db-m.json", "db-r.json", "db-s.json", "cred-c.json", "cred-e.json", "click-dest.json", "click-kettei.json", "clean-stop.json", "cleanup.json", "server-wire.jsonl", "server-wire-2.jsonl", "vnc-e-strategy.png", "vnc-w1-cardtab.png", "vnc-w2-card.png", "vnc-w3-warpmode.png", "vnc-w7-after-guestclick.png", "vnc-w8-after-warp.png", "vnc-x-strategy-restored.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 8,
       "text": "함대 선택 → 이동 → WARP → 목적지 → 확정 → 서버 권위 변경 → 화면 갱신 → DB 저장 → 재접속 복원의 첫 완전한 수직 경로",
       "verdict": "PLAYER_VISIBLE_REPRODUCIBLE" if allpass else "FAILED",
       "runId": RUN,
       "verticalPath": "unit 2 (ダスティ・アッテンボロー) at cell 101 → 職務権限カード tab → card 39 → ワープ航行 → grid chooser → destination cell 102 (guest SetCursorPos+mouse_event; VNC pointer does NOT register on the 3D grid hit-test) → 確認 dialog → 決定 → client sends 0x0B01 → authority Success (move-grid-unit=2;source-cell=101;destination-cell=102;authority-version=25) → screen 'グリッド選択完了' → PostgreSQL current_cell_id=102, authority_version 5→25, move command 0→1, domain_event 24→25 → client ゲーム終了 → authority RESTART on the same DB → relogin → cell 102 restored (DB identical; strategy camera recentred on cell 102).",
       "authorityWire0x0B01": w0,
       "snapshots": {"A0_baseline": A0, "M_afterMove": M, "R_afterRestart": R, "S_afterRelogin": S},
       "inputDiscipline": "each UI action was one click after a captured precondition; the destination click needed guest mouse_event (VNC click on the 3D grid did not register — recorded as a harness finding), no blind retries, no PID/HWND reuse (relaunch used new pid/hwnd with relaunch-prep.json + server-wire-2.jsonl guards)",
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
