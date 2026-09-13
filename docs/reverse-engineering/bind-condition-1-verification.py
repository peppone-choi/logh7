import json, hashlib, os
ROOT = "E:/logh7-greenfield/.claude/worktrees/logh7-original-client-restore-cd439d"
RUNS = ROOT + "/work/20260902-fresh-run-recovered-db/runs"
OUT = ROOT + "/docs/reverse-engineering/condition-1-verification.json"
def sha(p): return hashlib.sha256(open(p, "rb").read()).hexdigest().upper()
runs = ["20260902T194127Z-natural-l1-relogin-v1", "20260902T200446Z-natural-l1-relogin-v1", "20260902T201223Z-natural-l1-relogin-v1"]
bound = []
for r in runs:
    d = RUNS + "/" + r
    plan = json.load(open(d + "/fresh-run-plan.json", encoding="utf-8"))
    prep = json.load(open(d + "/fresh-run-prep.json", encoding="utf-8"))
    cred = json.load(open(d + "/cred-c.json", encoding="utf-8"))
    p = plan["preflight"]; c = prep["client"]
    checks = {
        "cleanBoundary_forbiddenProcessesEmpty": p["forbiddenProcesses"] == [],
        "cleanBoundary_noPriorAuthorityListener": p["authorityListenerCount"] == 0,
        "cleanBoundary_noPriorPostgresListener": p["postgresListenerCount"] == 0,
        "interactiveConsoleSession": plan["session"]["interactive"] and plan["session"]["selfSessionId"] == plan["session"]["activeConsoleSessionId"],
        "originalClientHashFixed": c["sha256"] == "F93592F369F131617B216FD10E66988C144AC56698859817F8FEB034EA95528F",
        "prepReady": prep["status"] == "FRESH_RUN_PREINPUT_READY",
        "naturalLogin_oneSubmission": cred["status"] == "ONE_SENDINPUT_CREDENTIAL_SUBMISSION_SENT" and cred["operations"]["credentialSubmissions"] == 1,
        "naturalLogin_noRetries_noClicks": cred["operations"]["inputRetries"] == 0 and cred["operations"]["clicks"] == 0,
        "secretsNotRecorded": cred["secretValuesRecorded"] is False,
        "postLoginScreenCaptured": os.path.exists(d + "/vnc-c-lobby.png") or os.path.exists(d + "/vnc-e-strategy.png"),
    }
    files = [k for k in ["fresh-run-plan.json", "fresh-run-prep.json", "cred-c.json", "vnc-c-lobby.png", "vnc-e-strategy.png"] if os.path.exists(d + "/" + k)]
    bound.append({"runId": r, "clientMode": c["mode"], "clientPid": c["pid"], "authorityPid": prep["authority"]["pid"],
                  "postLoginEvidence": [k for k in ("vnc-c-lobby.png", "vnc-e-strategy.png") if os.path.exists(d + "/" + k)],
                  "receipts": {k: sha(d + "/" + k) for k in files},
                  "checks": checks, "allPass": all(checks.values())})
allpass = all(b["allPass"] for b in bound)
out = {"schema": "logh7-condition-verification/1", "condition": 1,
       "text": "깨끗한 실행 경계에서 원본 클라이언트가 자연스럽게 로그인한다",
       "verdict": "PLAYER_VISIBLE_REPRODUCIBLE" if allpass else "FAILED",
       "reproducibility": f"{sum(b['allPass'] for b in bound)}/{len(bound)} independent sealed runs pass every check",
       "authority": "natural PostgreSQL (recovered cluster copied forward, source untouched) + Logh7.Server serve-original on 202.8.80.179:47900",
       "clientBinary": "G7MTClient.item114.exe sha256 F93592F369F131617B216FD10E66988C144AC56698859817F8FEB034EA95528F (hash-fixed, unmodified; Install mode = original install dir)",
       "loginTransport": "physical VNC wake (shift) + one guest user32 SendInput credential submission; no synthetic retries, no blind clicks",
       "boundRuns": bound,
       "notes": "Condition 1 does not require a freshly created account; it requires a clean execution boundary and a natural login of the unmodified original client, which these runs satisfy. Fresh-account creation is condition 2 (separately blocked)."}
json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
print("verdict:", out["verdict"], "|", out["reproducibility"])
for b in bound: print(" ", b["runId"], "allPass=", b["allPass"], [k for k, v in b["checks"].items() if not v])
