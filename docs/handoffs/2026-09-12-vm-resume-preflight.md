# VM resume preflight

User completed Windows login. Fresh guest receipt `work/20260904-warp-state-reverse/evidence/resume-preflight-20260912.json` at 2026-09-12T10:47:50Z confirms explorer PID1936 in session1. No game/server/PostgreSQL process was returned. This supersedes the earlier login-blocked state, not the independent screen-capture failure.

127.0.0.1:47900 is occupied by PID3112; owner executable not yet identified. Do not kill it. Guest IPv4 202.8.80.179 remains configured. PostgreSQL data and postmaster.pid exist but no55432 listener was returned. A PID file alone is not proof of a running server; inspect its PID and binary before startup, and do not delete it blindly.

Guest packages extend through server-v92-v406, newer than the initially inspected v90 restart script. Existing v404 scripts contain stale PID/start-time gates and must not be replayed. Client hash remains AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F. No game input, process stop, database reset, or server startup occurred in this preflight.

Next: inspect v92 deployment settings and fresh proxy/PostgreSQL ownership; restore the existing database service without replacing saved state, then stage the current tested server with a backup/migration boundary. Screen capture via sky failed twice with SetIsBorderRequired/0x80004002 after fresh window binding. No current coordinates are valid and native visual verification remains unproven.

## Capture recovered through VMware

Host OS is Windows10 Pro22H2 build19045.6466. Microsoft documents GraphicsCaptureSession.IsBorderRequired as introduced at20348; sky's repeated SetIsBorderRequired/E_NOINTERFACE error is consistent with this incompatibility. Its public API exposes no border toggle. Authenticated VMware vmrun captureScreen succeeded; E:/logh7-build/vmware-capture-20260912.png was visually inspected and shows the logged-in guest Windows11 desktop. Added Capture to host-vix51.ps1; a second wrapper capture succeeded. This restores VM observation, not sky's capture implementation or any input-coordinate binding.

## Database resume and credential blocker

Read-only inspection found pg_ctl status3, no55432 listener, stale master PID1812 absent, data major17 and binary17.11. Port47900 owner3112 is svchost.exe; it was not stopped. Initial native startup stderr warning became a PowerShell terminating error; a wrapper receipt in E:/logh7-build/pg-resume-diagnose-20260912.json identified the exact line. The startup script now checks native exit status separately from warnings.

E:/logh7-build/postgres-resume-20260912.log records PostgreSQL PID7396 listening on127.0.0.1:55432, automatic recovery and ready-to-accept-connections at2026-09-12T10:52:31Z. No data/PID file was manually deleted. Host operation handle7298 remained nonterminal on last poll; do not restart the DB merely because that wrapper has not returned.

Backup attempt failed BEFORE SQL or pg_dump: E:/logh7-build/resume-backup-20260912.json reports ProtectedData.Unprotect failed, key not valid for specified state. This followed the user-approved Windows account password reset. A DPAPI impact is suspected; not yet independently proven. No database credential reset, backup success, schema upgrade or latest-server deployment may be claimed. User was asked for approval to temporarily restore the original Windows password and recover credentials before a preservation-aware change. Await that answer; do not repeat account changes without it. Never record plaintext credentials here.

## Credential recovery completed with user approval

User approved recovery and requested agent login. Restoring the original Windows password restored DPAPI access: resume-backup-recovered-20260912.json reports a readable database,42 migrations and a133640-byte custom-format backup verified by pg_restore --list. Then NetUserChangePassword with explicit local computer name changed the password using both old/new values (the null-domain attempt returned2221 and changed nothing). Host encrypted guest credentials were updated. A fresh VIX operation using the new credential again decrypted the unchanged database-secret.dpapi, queried DB and created/listed a second133640-byte backup: E:/logh7-build/resume-backup-preserved-20260912.json. Recorded unit/fleet rows match the first backup receipt, including destroyed and damaged units. No DB-password reset or user-data reset. The credential blocker is resolved; game login is not yet verified.

Current full source regression942PASS/0FAIL/0SKIP: E:/logh7-build/test-results/emergency-base-20260912/emergency-base-full.trx, handle67024 exit0. Host test PostgreSQL55439 is still running pending owned cleanup. Guest PostgreSQL55432 is intentionally retained for server resume. Latest server publish started to E:/logh7-build/server-resume-20260912; do not claim guest deployment from publish alone.

## Latest server deployed; client startup visual boundary

Published self-contained win-x64 package and deployed to new guest server-resume-20260912 directory. E:/logh7-build/server-resume-20260912.json confirms server PID5176/start2026-09-12T11:04:27.9066776Z, DLL SHA256 E927C2D373A6E554F7C3954233CB81ADFEB372A52FD270D94A446AC19DE98F9F, two owned47900 listeners, migration42->43 and identical complete original_grid_unit/original_fleet_unit rows before/after. Older folders and DB backups retained. Host test PG55439 stopped after exact owner verification; guest PG remains running.

Client launched PID5968/start2026-09-12T11:05:14.5230519Z from the hash-checked existing client. Receipt E:/logh7-build/client-resume-20260912.json. VMware captures client-resume-20260912.png and client-resume-ready-20260912.png show black frames, not a login screen. No input or game authentication was performed. Desktop capture previously worked; distinguish app startup/rendering from VMware capture compatibility before changing graphics or restarting. Deployment wrapper handle55906 had not returned on its last poll despite successful guest receipt; do not duplicate server startup.

## Black-frame diagnosis boundary

Fresh client-inspect-20260912.json at11:06:42Z: PID5968/session1 responding, title銀河英雄伝説Ⅶ, HWND655838, CPU47.5625s, no TCP connections. Server wire contains listener-ready only; legacy g7mt-debug.log predates this run and is not current evidence. Host VMware activation and one documented Ctrl+G focus action did not change black captures. These are not proven guest inputs. User has been asked whether the physical VM view is also black; avoid repeating this question or blind input.

Historical E-089-startup-render-progress-and-white-display.md establishes that advancing frame counters do not prove successful presentation:005DD450 calls device virtual+3C Present and does not preserve its HRESULT. It also records ineffective host activate/Ctrl+G attempts. Native-login-render-v231.md records recovery after a separately verified guest foreground and File-menu action, not a proven universal fix. Do not repeat host focus attempts or treat old render fields as current. Next diagnostic should distinguish current guest foreground/renderer/device state and actual presentation, without authentication input or a speculative restart.

## Current read-only renderer sample

E:/logh7-build/startup-memory-20260912.json at11:09:40Z, exact PID5968/start/hash guard, no writes/inputs: world07A40048, idleEnabled1, ready1, windowed1, renderActive1/renderReady1, device07EAA720. Visible render child000C01C4 rect(3,46)-(647,530), parent000A01DE. Frames79->51 over600ms and sampled FPS79.07336 show loop progress, not successful Present. Foreground PID7780 differs from client5968, but the diagnostic did not record its own PID, so diagnostic-induced focus cannot be excluded. Next focus diagnostic must record self PID/session and foreground owner before interpreting the mismatch. No restart or memory modification justified by these data.

## Allied supply deployment

After947 full tests and11 focused guarded supply cases, published server-allied-supply-20260912.zip SHA25625CF140DC4932B4BEF8A0FA4F2B6744D8858B48E3BDBB7C8BC68FE789C9758CB. Fresh backup receipt E:/logh7-build/allied-supply-backup-20260912.json at11:29:35Z confirms old server5176 identity, zero established server connections and listed137973-byte backup (SHA2566CB04A6861492B9483A8BC594516E4346E9A02CF1A677515A3663E150223EF2D).

Deployment stages a new folder, verifies archive/backup, checks connections again before stopping only server5176. E:/logh7-build/server-allied-supply-20260912.json confirms new PID980/start11:30:44.3881805Z, two owned listeners,43->43 migrations and exact unit/fleet row preservation. Staged DLL SHA2563423CBF79829BDBEE17BD77309831A61BC4E3D40EC680DD2A381CBDC2A243838. Immediate Process.Path was null: re-query its executable path before any future identity-gated stop. Old folders/backups retained; client5968 was not restarted or driven. Wrapper handle72434 remained pending on last poll despite completed receipt; do not duplicate deployment. Host test PG55439 stopped after ownership validation.

Separate focus receipt E:/logh7-build/focus-owner-20260912.json identified foreground7780 as OneDrive, classOneDriveReactNativeWin32WindowClass; self5276/session1. One host Alt+Tab attempt did not change black capture. User was asked to select the guest game window; no further blind key attempts or OneDrive authentication actions.
