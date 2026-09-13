# Handoff: fresh sealed run on the recovered PostgreSQL reaches login → lobby → character → strategy → planets

Date: 2026-09-02 (KST evening). Lane: fresh VM/session/HWND live runner + PostgreSQL 권위 상태·재접속 (goal immediate steps 2, 4-partial, 5-partial).

## Result

`PLAYER_VISIBLE_PASS / AUTHORITY_OBSERVED / PERSISTENCE_RESTORED_ACROSS_REBOOT` for run `20260902T121817Z-natural-l1-relogin-v1`.

Starting from the WAL-recovered, cleanly stopped source cluster of run `20260902T083838Z…` (validated independently earlier today), a fresh sealed run copied that cluster forward, started PostgreSQL 17.11 on 127.0.0.1:55432, deployed the v128 authority (`Logh7.Server.exe` sha256 `D214CF57…7DB`, migration 0011 `9750CEFD…92B`), launched the unmodified-hash item114 original client from its install directory, and then, one input at a time, reached:

| Step | Input (exactly one) | Player-visible result | Authority wire |
|---|---|---|---|
| launch | none | 650x533 window, white until wake | `listener-ready` |
| wake | physical Shift via VNC | full login surface (logo, starfield, ID/パスワード, ログイン/終了) | none |
| credential | guest SendInput: Backspace, login, Tab, password, Enter | BOTHTEC / MPS / MiCROVISION splash → lobby | conn 1 `LoginAcceptedSent` (type 28672); conn 2 `AwaitLobbyLogin → LobbyReady`, roster 8195/8197 (21,272 B) |
| lobby | none | 8 lobby buttons; **server notice `LOGH7 RESTORE 2026-09-02 FRESH RUN A` shown in サーバーからのお知らせ** | — |
| ゲーム開始 (122,191) | one click | roster: アッテンボロー ダスティ 18歳, 統率84 指揮73 政治77 機動88 運営62 攻撃82 情報70 防御69; slot 2 キャラクターがいません | — |
| character card (650,300) | one click | strategy world: HUD ダスティ・アッテンボロー 二等兵, 宇宙暦795年1月1日, two 空間グリッド objects | `LobbyRedirectSent` (8201) → session server 127.0.0.2:47900 `SessionServerReady`; bootstrap 772/774/788/786/778(28,024 B)/784/782/796/776/780/768/3840/3842/3846(29,920 B) |
| system cell 101 (514,385) | one click | selection, clock advances | heartbeat 768 only |
| 星系内宇宙 (725,700) | one click | unchanged frame, clock advances | — |
| HUD slot 22 (618,722) | one click | **four orbit rings around the left celestial object + tooltip 駐留又は停泊している惑星／要塞のステータス表示** (identical scene to item117) | — |

The roster/HUD ability values equal the PostgreSQL row read by the independent validation (`[84,77,62,70,73,88,82,69]`, character 2, rank 20, grid unit 2 at cell 101 with card 39). Therefore the same character survived VM reboot → WAL recovery → fresh server process → original-client re-login (completion conditions 3 and 13, character/grid portion).

## Facts established about the client and the guest

- The client (class `Afx:400000:…`, DINPUT8 loaded) does not draw its first frame on synthetic activation. `SetForegroundWindow`, synthetic `mouse_event` on the title bar, and cursor moves left the window white (PrintWindow, GDI and VNC all white; main thread spinning in Sleep, `IsHungAppWindow=false`). One physical key through VMware's VNC (`vncdo --server 127.0.0.1::6001 key shift`) produced the login surface within 2.5 s. After that, guest `SendInput` and `SetCursorPos+mouse_event` worked for every field and button.
- VIX `RunProgramInGuest` with `VIX_RUNPROGRAM_ACTIVATE_WINDOW` alone still runs in session 0. The login must use `VIX_LOGIN_IN_GUEST_REQUIRE_INTERACTIVE_ENVIRONMENT` (0x08) — what `vmrun -interactive` does — to reach console session 1. Running helpers with flag 0 under that login avoids raising a Windows Terminal window over the game.
- Install layout is `C:\LOGH7_ORACLE\{exe,data,doc}`; the client resolves `..\data` relative to its exe directory. The item118 Korean-runtime copy (`korean-client\G7MTClient.exe` with only 3 files) had no `..\data`; its 0.93 s crash in `FUN_00522010` (resource-row lookup) is therefore better explained by the missing data root than by the `CreateFontA` hook. `guest-prepare-fresh-run.ps1 -ClientMode Copy` builds `<run>\client\exe` + a `<run>\client\data` junction for the next Korean attempt.
- The guest has no gateway or DNS (202.8.80.179/32 only); the client makes no external connection before login.
- `127.0.0.1:47900` remains the iphlpsvc portproxy; the authority binds `202.8.80.179:47900` and the session server `127.0.0.2:47900` without conflict.
- The item118 WER entry in `ReportQueue` (created 08:39Z) is periodically re-touched by WER upload retries; its recent mtime is not a new crash.

## Files (this worktree, `work/20260902-fresh-run-recovered-db/`)

- `fresh-run-vix.cs` — VIX wrapper with interactive-login option (sha256 `513C396D…9616`)
- `host-run-fresh-run.ps1`, `guest-prepare-fresh-run.ps1` (`A3FD2228…ACF7`), `guest-capture-desktop.ps1`
- `host-step.ps1` — stage/verify/run one guest step under interactive login, copy back
- `guest-focus-and-capture.ps1`, `guest-printwindow-capture.ps1`, `guest-cursor-nudge-capture.ps1`, `guest-title-click-capture.ps1`, `guest-client-census.ps1`, `guest-module-census.ps1`, `guest-net-census.ps1`, `guest-wer-peek.ps1`
- `guest-submit-credential.ps1` — one SendInput credential submission from the run's DPAPI account secret (values never recorded)
- `guest-click-point.ps1` — exactly one click at frame coordinates with identity/foreground preflight
- `runs/20260902T121637Z…/` — sealed failed attempt (`FRESH_RUN_NOT_IN_INTERACTIVE_SESSION:0/1`), never reused
- `runs/20260902T121817Z-natural-l1-relogin-v1/` — this run: `fresh-run-prep.json` (sha256 `40E12180…403F`), `host-summary.json`, `server-wire.jsonl`, `server.stdout`, step receipts `step-*-host.json`, click receipts `click-*.json`, `credential-01.json`, captures `preinput-desktop.png`, `focus-01.png`, `pw-01*.png`, `nudge-01*.png`, `title-01*.png`, `vnc-01.png` … `vnc-09-after-slot22.png` (VNC framebuffer), censuses `census-01.json`, `modules-01.json`, `net-0*.json`, `wer-01.json`

Capture hashes: login surface `vnc-02-after-shift.png` `3EE2A96C…D0C8`; lobby with notice `vnc-04-lobby.png` `56523EC4…09FA`; roster `vnc-05-after-gamestart.png` `7D7CF950…FFCB`; strategy `vnc-06-after-character.png` `C52D5CDC…ABE6`; planets `vnc-09-after-slot22.png` `5598FE46…08A9`.

## Operation accounting

- guest launches: PostgreSQL 1, authority 1, client 1; process stops 0
- physical VNC inputs: 1 key (Shift), 1 click on the OneDrive notification close button (non-game window)
- synthetic guest inputs: 1 title-bar click (non-client), 3 cursor moves, 38 key events (one credential submission), 5 game clicks (ゲーム開始, character card, cell 101, 星系内宇宙, HUD slot 22)
- input retries: 0; source database writes: 0; secrets recorded: 0

## Current processes and ports (guest, run 20260902T121817Z)

- client `G7MTClient.item114.exe` PID 4156, HWND `0x00000000000303F4`, fullscreen 1024x768, session 1, connected to 127.0.0.2:47900
- authority `Logh7.Server.exe` PID 2088 listening 202.8.80.179:47900 (session bind 127.0.0.2), receipt `server-wire.jsonl`
- PostgreSQL copy on 127.0.0.1:55432, data `…\20260902T121817Z-natural-l1-relogin-v1\postgres-data` (password rotated in memory only)
- host VNC 127.0.0.1:6001 (vmware-vmx), `vncdo` at `C:\Users\user\AppData\Roaming\Python\Python311\Scripts\vncdo.exe`

Update: the run continued into the WARP lane and was closed cleanly at 12:51Z (client exited through ゲーム終了, authority and PostgreSQL stopped; see `2026-09-02-warp-vertical-path-first-pass.md`). PIDs 4156/2088/8676/8760 are dead; do not reuse them.

## Not proven (at the time of this handoff)

- WARP / destination / confirm / `0x0B01`-`0x0B07` movement, authority mutation, DB persistence of a move, reconnect after a move (goal step 8) — closed later in the same run; see `2026-09-02-warp-vertical-path-first-pass.md`.
- Korean runtime pixels (`PLAYER_VISIBLE_KO`) — needs a Copy-mode run with the data junction.
- Both factions, tactical, and everything after goal step 8.

## Next start and forbidden retries

Next: in this live run, open 職務権限カード → card 39 → WARP (manager65 action 0x2B) → destination cell 102 → confirm, capturing after each click and reading `server-wire.jsonl` for the move command; then read `original_grid_unit`/`original_grid_move_command` in the run's PostgreSQL copy; then log out, restart the authority on the same data, re-login and confirm cell 102.

- Do not expect pixels from a freshly launched client before one physical VNC input.
- Do not launch a copied client without a `data` sibling.
- Do not run guest launchers with VIX login options 0 and expect session 1.
- Do not reuse run `20260902T121637Z` or its stage directory.
