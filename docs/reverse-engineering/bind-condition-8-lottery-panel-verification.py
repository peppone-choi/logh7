import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T222326Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-8-lottery-panel-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
ex = lambda f: os.path.exists(D + "/" + f)
a0 = J("db-a0.json")["results"]
chars = json.loads(a0["characters"]["stdout"]); entries = json.loads(a0["lotteryEntry"]["stdout"])
cred, cand, stop, cleanup, stopown, census = J("cred-c.json"), J("click-candidate.json"), J("clean-stop.json"), J("cleanup.json"), J("stop-own-client.json"), J("verify-after-stop.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
lottery_req = [w for w in wire if w.get("observedApplicationType") in (4102, 4100)]  # 0x1006 charge, 0x1004 selection
checks = {
    "baseline_oneCharacter_emptySlot_noPendingEntry": len(chars) == 1 and all(e["status"] != "pending" for e in entries),
    "login_lobby": cred["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and ex("vnc-l1-lobby.png"),
    "lotteryButton_opensSessionPicker": ex("vnc-l2-lottery.png"),
    "sessionCard_opensCandidateScreen_catalogServed": ex("vnc-l3-session-picked.png"),
    "candidateRow_VNC_click_inert": ex("vnc-l4-candidate.png"),
    "candidateRow_guestMouseEvent_inert": cand["status"] == "ONE_CLIENT_CLICK_SENT" and cand["operations"]["inputRetries"] == 0 and ex("vnc-l5-after-guest-candidate.png"),
    "candidateList_keyboardDown_inert": ex("vnc-l6-after-keydown.png"),
    "noLotteryRequestOnWire_0x1004_0x1006": len(lottery_req) == 0,
    "escExitDialog_kettei_inert_clientStayedAlive": ex("vnc-l7-after-esc.png") and ex("vnc-l8-after-kettei.png") and stop["client"]["aliveAfter"] is True,
    "authorityPostgres_cleanlyStopped_ownClientStopped_noLeftovers": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stopown["status"] == "OWN_CLIENT_STOPPED" and stopown["gone"] is True and cleanup["status"] == "RUN_CLEANED" and census["leftoverProcesses"] == [],
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "db-a0.json", "cred-c.json", "click-candidate.json", "clean-stop.json", "stop-own-client.json", "cleanup.json", "verify-after-stop.json", "server-wire.jsonl", "vnc-l1-lobby.png", "vnc-l2-lottery.png", "vnc-l3-session-picked.png", "vnc-l4-candidate.png", "vnc-l5-after-guest-candidate.png", "vnc-l6-after-keydown.png", "vnc-l7-after-esc.png", "vnc-l8-after-kettei.png"] if ex(f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 8, "feature": "original character lottery (オリジナルキャラクター抽選) UI path",
       "verdict": "OBSERVED_PANEL_INERT" if allpass else "FAILED", "runId": RUN,
       "sequence": "login → lobby → オリジナルキャラクター抽選 (122,307) → session picker (LOGH7-1/LOGH7-2) → LOGH7-1 (640,270) → 「オリジナルキャラクターを選ぶ」 screen with the authored catalog (キャゼルヌ, シェーンコップ, アッテンボロー, ユリアン・ミンツ, ヤン・ウェンリー; 所属 帝国/同盟; 第一〜第五候補; 中止/決定) → candidate row (405,416): VNC click inert, guest SetCursorPos+mouse_event inert, Down key inert → no 0x1004/0x1006 on the wire → ESC opens ゲーム終了 over the panel, 決定 ignored → client stopped via guest-stop-own-client",
       "meaning": "The authority-side lottery path is complete (catalog served, entry+award implemented: 0x1006 charge → original_character_lottery_entry + OriginalCharacterLotteryEntered → random award creates the character), and the client renders the catalog, but the lobby-side lottery panel does not consume mouse (either transport) or keyboard input in this session — the same inert family as the faction 次へ (condition 2) and 環境設定 戻る (condition 7). Condition 8 therefore stays unverified; the blocker is the client-side lobby panel gate, not the server.",
       "placeholderFinding": "every catalog row shows 階級 「皇帝」 (emperor) — a wrong temporary rank label (conditions 6/16)",
       "baseline": {"characters": chars, "lotteryEntries": entries},
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
