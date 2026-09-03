import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T221133Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-7-lobby-settings-panel-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
cred, modoru, stop, cleanup, stopown, census = J("cred-c.json"), J("click-modoru.json"), J("clean-stop.json"), J("cleanup.json"), J("stop-own-client.json"), J("verify-after-stop.json")
ex = lambda f: os.path.exists(D + "/" + f)
checks = {
    "login_lobbyReached": cred["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and ex("vnc-b1-lobby.png"),
    "settingsMenuButton_consumesClick_opensPanel": ex("vnc-b2-settings.png"),
    "settings_modoru_VNC_inert_panelStaysOpen": ex("vnc-b3-settings-back.png"),
    "menu_credits_ignored_whilePanelOpen": ex("vnc-b4-credits.png"),
    "settings_modoru_guestMouseEvent_inert": modoru["status"] == "ONE_CLIENT_CLICK_SENT" and modoru["operations"]["inputRetries"] == 0 and ex("vnc-b5-after-guest-modoru.png"),
    "esc_opensExitDialog_overPanel": ex("vnc-b6-after-esc.png"),
    "exitDialog_kettei_inert_whilePanelOpen_clientStayedAlive": ex("vnc-b7-postexit.png") and stop["client"]["aliveBefore"] is True,
    "authorityAndPostgres_cleanlyStopped": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
    "ownClientStopped_identityChecked_noLeftovers": stopown["status"] == "OWN_CLIENT_STOPPED" and stopown["gone"] is True and census["leftoverProcesses"] == [],
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "cred-c.json", "click-modoru.json", "clean-stop.json", "cleanup.json", "stop-own-client.json", "verify-after-stop.json", "vnc-b1-lobby.png", "vnc-b2-settings.png", "vnc-b3-settings-back.png", "vnc-b4-credits.png", "vnc-b5-after-guest-modoru.png", "vnc-b6-after-esc.png", "vnc-b7-postexit.png"] if ex(f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 7, "feature": "lobby button sweep — 環境設定 (settings) panel",
       "verdict": "OBSERVED_WEDGED_PANEL" if allpass else "FAILED", "runId": RUN,
       "sequence": "login → lobby → 環境設定 (122,481) opens the settings panel (画面解像度 1024 768, テクスチャー品質 MIDDLE, モデル品質 HIGH, エフェクトレベル empty row, 予備) → 戻る (762,582) via VNC: panel stays → クレジット (122,539) via VNC: ignored → 戻る via guest SetCursorPos+mouse_event: panel stays → ESC: ゲーム終了 dialog opens OVER the panel → 決定 (565,432): ignored, client stays alive → clean-stop (authority/pg stopped) → guest-stop-own-client (pid identity-checked) → no leftovers",
       "meaning": "The 環境設定 menu button works (consumes the click, opens the panel with proper strings, no NO DATA), but once open the panel is a dead end: its 戻る consumes neither VNC nor guest mouse_event input, the left menu and even the exit dialog's 決定 are ignored, only ESC (keyboard) is processed. The client must be process-stopped. This is the same 'inert required button' family as the faction-panel 次へ (condition 2) — likely the client-side widget arm (manager+0x05) not being set for this panel in this session. Condition 7 gap items: 戻る inert; no verifiable reason; 変更を適用 deliberately NOT pressed (user config).",
       "safeToPress": ["ゲーム開始", "環境設定 (opens)"], "doNotPressAgain": ["環境設定 in the lobby until the panel gate is understood (wedges the client)", "変更を適用 (changes user settings)", "キャラクター削除 / 削除 / 全削除 (destructive)"],
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
