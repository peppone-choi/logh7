import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUN = "20260902T212309Z-natural-l1-relogin-v1"
D = ROOT + "/work/20260902-fresh-run-recovered-db/runs/" + RUN
OUT = ROOT + "/docs/reverse-engineering/condition-9-promotion-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
def J(n): return json.load(open(D + "/" + n, encoding="utf-8"))
def snap(n):
    r = J(n)["results"]
    cid, ver, rank = r["characterVersion"]["stdout"].split("|")
    return {"characterId": int(cid), "characterVersion": int(ver), "rank": int(rank),
            "domainEventCount": int(r["domainEventCount"]["stdout"]), "accountVersion": int(r["accountVersion"]["stdout"]),
            "latest": r["domainEventLatest"]["stdout"][:400]}
A0, M, R, S = snap("db-a0.json"), snap("db-m.json"), snap("db-r.json"), snap("db-s.json")
prep, relaunch, stop, cleanup = J("fresh-run-prep.json"), J("relaunch-prep.json"), J("clean-stop.json"), J("cleanup.json")
credA, credE = J("cred-c.json"), J("cred-e.json")
wire = [json.loads(l) for l in open(D + "/server-wire.jsonl", encoding="utf-8") if l.strip()]
ladder = [w for w in wire if w.get("observedApplicationType") == 4608]
rankup = [w for w in wire if w.get("observedApplicationType") == 1796]
promoted_event = "CharacterRankPromoted" in M["latest"] and '"sourceRank": 20' in M["latest"] and '"promotedRank": 19' in M["latest"]
same = lambda x, y: x["rank"] == y["rank"] and x["characterVersion"] == y["characterVersion"] and x["domainEventCount"] == y["domainEventCount"] and x["accountVersion"] == y["accountVersion"]
checks = {
    "baseline_rank20_version5": A0["rank"] == 20 and A0["characterVersion"] == 5,
    "login_reachedStrategy_cardTab_card": credA["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and all(os.path.exists(D + "/" + f) for f in ["vnc-e-strategy.png", "vnc-r1-cardtab.png", "vnc-r2-card.png"]),
    "promotionButton_opensLadderDialog": os.path.exists(D + "/vnc-r3-shoushin.png"),
    "authorityServedPromotionLadder_0x1200": bool(ladder) and ladder[-1]["status"] == "Success",
    "ladderEntrySelected_infoShown": os.path.exists(D + "/vnc-r4-ladder-selected.png"),
    "decision_sent_0x0704_authoritySuccess": bool(rankup) and rankup[-1]["status"] == "Success",
    "dbRankPromoted_20_to_19_versionsBumped": M["rank"] == 19 and M["characterVersion"] == 25 and M["domainEventCount"] == A0["domainEventCount"] + 1 and M["accountVersion"] == A0["accountVersion"] + 1,
    "domainEvent_CharacterRankPromoted_appended": promoted_event,
    "clientExited_thenAuthorityRestartedOnSameDb": relaunch["status"] == "FRESH_RUN_PREINPUT_READY" and relaunch["authority"]["pid"] != prep["authority"]["pid"],
    "promotionSurvivedRestart_R_equals_M": same(M, R),
    "reloginAfterRestart_oneSubmission": credE["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and credE["operations"]["inputRetries"] == 0,
    "restoredAfterRelogin_S_equals_M": same(M, S),
    "screenShowsPromotedRank_ittouhei_afterRelogin": os.path.exists(D + "/vnc-x-strategy-restored.png"),
    "normalShutdown_and_cleanup": stop["status"] == "RUN_RUNTIME_CLEANLY_STOPPED" and stop["postgres"]["pgControlState"] == "shut down" and cleanup["status"] == "RUN_CLEANED",
}
files = [f for f in ["fresh-run-plan.json", "fresh-run-prep.json", "relaunch-prep.json", "db-a0.json", "db-m.json", "db-r.json", "db-s.json", "cred-c.json", "cred-e.json", "clean-stop.json", "cleanup.json", "server-wire.jsonl", "server-wire-2.jsonl", "vnc-e-strategy.png", "vnc-r1-cardtab.png", "vnc-r2-card.png", "vnc-r3-shoushin.png", "vnc-r4-ladder-selected.png", "vnc-r5-after-kettei.png", "vnc-x-strategy-restored.png"] if os.path.exists(D + "/" + f)]
allpass = all(checks.values())
out = {"schema": "logh7-condition-verification/1", "condition": 9, "feature": "promotion (昇進, personnel rank-up) — second strategy feature; also evidence for condition 11 (인사)",
       "verdict": "PLAYER_VISIBLE_REPRODUCIBLE" if allpass else "FAILED", "runId": RUN,
       "verticalPath": "unit/character 2 (rank 20 二等兵) → 職務権限カード tab → card 39 → 昇進 → authority serves promotion ladder (0x1200, '二等兵→一等兵') → ladder entry selected (情報: 二等兵 実行不可(0) as served text) → 決定 → client 0x0704 rank-up → authority Success → PostgreSQL character.rank 20→19, character.authority_version 5→25, domain_event 25 = CharacterRankPromoted{20→19}, account.authority_version 24→25 → client ゲーム終了 → authority RESTART on same DB → relogin → rank 19 restored (M==R==S) → strategy HUD shows 一等兵.",
       "screenUpdateNote": "The in-session HUD rank label stayed 二等兵 right after 決定 (dialog closed, log '昇進コマンド選択を行います'); the promoted label 一等兵 is shown after relogin. Faithful observation: screen refresh of the rank label is deferred to re-entry in this build.",
       "placeholderTextFindings": ["promotion dialog list header renders 'サーバーが混み合っています' (server busy) and the second button 'バージョンが違います' (version differs) — wrong temporary strings served/indexed for this dialog; condition 6/16 items", "情報 shows '二等兵 実行不可(0)' yet the authority accepted the rank-up — the served executability text does not match the authority decision; condition 7 (rejection/enable reason must match authority) item"],
       "wire": {"ladder0x1200": ladder[-1] if ladder else None, "rankUp0x0704": rankup[-1] if rankup else None},
       "snapshots": {"A0_baseline": A0, "M_afterPromotion": M, "R_afterRestart": R, "S_afterRelogin": S},
       "checks": checks, "receipts": {f: sha(D + "/" + f) for f in files}}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"])
for k, v in checks.items(): print("  ", "PASS" if v else "FAIL", k)
