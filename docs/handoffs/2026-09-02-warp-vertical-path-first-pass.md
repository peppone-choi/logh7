# Handoff: first complete WARP vertical path — input → authority → PostgreSQL → server restart → reconnect

Date: 2026-09-02 (KST evening). Lane: 이동·WARP와 첫 명령 수직 경로 (goal immediate step 8) + PostgreSQL 권위 상태·재접속. Run `20260902T121817Z-natural-l1-relogin-v1` (continuation of `2026-09-02-fresh-run-recovered-db-strategy-planets-pass.md`).

## Result

`AUTHORITY_PROVEN / PERSISTENCE_PROVEN / PLAYER_VISIBLE_PASS (position after reconnect)` for one WARP of unit 2 from cell 101 to cell 102 on the recovered database, followed by an authority restart on the same database and a reconnect that shows the unit at cell 102.

```text
HUD 職務権限カード tab (727,577)
→ card 運営 (820,512) opens: 情報 text, actions 昇進 / ワープ航行
→ ワープ航行 (822,283): "Please choose the grid.", range circle, distance line, log ※ ワープ航行コマンド選択を行います。
→ right grid cell (596,388): 確認 dialog "このグリッドにワープを試みます。通常はコマンドポイント320MCP消費、指示されている作戦目標に近接する場合は80MCP。よろしいですか？" 決定 / 取消し, log ※ グリッド選択完了しました。
→ 決定 (565,484)
→ client sends 0x0B01 (type 2817, 31-byte body) 60 ms later
→ authority: move-grid-unit=2; source-cell=101; destination-cell=102; authority-version=25; design=new; Success; response 48 B + 0x0B07 notification 64 B
→ PostgreSQL (run copy): original_grid_unit.current_cell_id 101→102 (authority_version 25); original_grid_move_command 1 row (action 43=0x2B, outcome moved, request_fingerprint 63c2ff46…d924); domain_event 25 OriginalGridUnitMoved; account authority_version 25
→ stop client 4156 + authority 2088, keep PostgreSQL, rotate DB password, restart Logh7.Server (PID 8760) on the same data, relaunch client (PID 8676, HWND 0x2042C)
→ physical Shift wake → credential → LoginAcceptedSent → LobbyReady (roster 1776/21272 B) → LobbyRedirectSent → SessionServerReady → world bootstrap (…, 3846 29,920 B, messenger-character-count=2)
→ strategy view now centred on the RIGHT grid object (camera moved one cell relative to the pre-move frame); ワープ航行 chooser draws its origin from that centred cell with the neighbour cell to its left → position 102 player-visible
→ chooser left without a command (no 0x0B01 in server-wire-2.jsonl; move command count still 1)
```

## Evidence (this worktree, `work/20260902-fresh-run-recovered-db/runs/20260902T121817Z-natural-l1-relogin-v1/`)

| Stage | Receipt | Capture (VNC framebuffer) sha256 |
|---|---|---|
| card tab | `click-authority-card-tab.json` | `vnc-10-after-card-tab.png` E9B4C973…AB0B |
| card 39 actions | `click-authority-card-39.json` | `vnc-11-after-card39.png` 78D7024E…12B |
| WARP chooser | `click-warp-action.json` | `vnc-12-after-warp.png` 36B70436…814C |
| destination + 確認 | `click-warp-destination-cell.json` | `vnc-13-after-destination.png` 098AF2EC…E00A |
| 決定 → 0x0B01 | `click-warp-confirm-kettei.json`; wire line 47 in `server-wire.jsonl` | `vnc-14-after-confirm.png` 86537D87…3ACA7 |
| DB after move | `db-inspect-01.json` (HBA restored, sqlWrites 0) | — |
| authority restart + relaunch | `relaunch-prep.json`, `server-wire-2.jsonl`, `server-2.stdout` | `vnc-15-relaunch-login.png` E3D7A0BC…0FEF1 |
| re-login → lobby → roster → world | `credential-02.json`, `click-relaunch-gamestart.json`, `click-relaunch-character.json` | `vnc-16-relaunch-lobby.png` 3A582C1E…CE5A, `vnc-17-relaunch-roster.png` E5906F83…1798, `vnc-18-relaunch-world.png` BFF5E225…4E54 |
| position after reconnect | `click-relaunch-card-tab.json`, `click-relaunch-card-39.json`, `click-relaunch-warp-action.json` | `vnc-19-relaunch-warp-origin.png` 637F760D…DD57 |
| chooser exit without command | `click-relaunch-choose-left-cell.json` (hit the current cell, "00 LY", no dialog), `click-relaunch-warp-cancel.json` (empty space), `vnc-esc-receipt.json`, `db-inspect-02.json` | `vnc-20…`, `vnc-21…`, `vnc-22-after-esc.png` |

Scripts added: `guest-db-inspect.ps1` (trust window on the run copy only, read-only SELECTs, HBA restored and re-verified), `guest-restart-authority.ps1` (stop own PIDs, keep PostgreSQL, rotate password in memory, restart authority, relaunch client), `guest-submit-credential.ps1` / `guest-click-point.ps1` gained `-PrepFileName` / `-WireFileName`.

## Exit paths and run closure (goal step 4, last exit button)

- Physical ESC in the strategy screen opens the ゲーム終了 dialog ("ゲームを終了してもよろしいですか？" 決定 / 取消し at (565,436) / (639,436)); it does not cancel the WARP grid chooser.
- 取消し: one click → dialog closed, game continues (`click-game-exit-cancel.json`, `vnc-23-after-exit-cancel.png` 94190B63…9C13).
- 決定: one click → client process 8676 gone within 7 s, authority wire `connection-closed` 110 ms after the click (`server-wire-2.jsonl` last line, final state `SessionServerReady`), desktop capture `vnc-25-after-exit-decide.png` 89DA7FCD…FCFC.
- Run closure: `guest-clean-stop.ps1` stopped authority 8760 and the run's PostgreSQL (`pg_ctl -m fast stop` exit 0, `pg_control` = `shut down`, sha256 3B57F67F…128B); receipt `clean-stop.json`, `RUN_RUNTIME_CLEANLY_STOPPED`. The run's data directory (unit at cell 102, 25 events) is preserved for reuse as a source.

## Facts

- The original client's WARP UI path is: authority card panel → card → ワープ航行 → grid pick → 確認/決定. The 0x0B01 request carries `time, wait, id, card, pcp, mcp, grid, erange, base, mode` (server decoder `OriginalMoveGridCodec`); the authority's minimal world accepts only unit 2 / card 39 / 101→102 / action 0x2B (`OriginalMoveGridAuthority`, `NEW_DESIGN`).
- The 確認 text quotes MCP costs (320 / 80) while the HUD shows MCP 0; the current authority does not check MCP, so the move was accepted. Whether the original server would have rejected it for MCP is `UNKNOWN` (`ORIGINAL_MANUAL` check pending).
- After a move the strategy camera recentres on the unit's cell on the next world entry; the pre-move frame had the unit's cell at screen x≈513 with the other cell to the right, the post-reconnect frame has the other cell to the left. Screen coordinates of grid objects therefore depend on the unit's position and must be re-derived per frame; the item117 coordinates (514,385) mean "the centred cell", not "cell 101".
- Server restart on the same PostgreSQL data restores account/character/grid state without event replay problems: login, roster, world bootstrap and messenger count (2) all served by the new process.
- Two authority restarts of the same `Logh7.Server.exe` (v128, `D214CF57…7DB`) ran in this run; both wire files are preserved.

## Not proven / next

- Visible fleet marker animation for the move itself (the fixed 4-node interior scene did not change); a strategy-map fleet sprite at cell 102 was not separately captured.
- MCP consumption, wait/time semantics of 0x0B01, `erange`, `base`, `mode` meanings; multi-cell destinations; rejection paths (`MOVE_GRID_DESTINATION_NOT_LEGAL` etc.) not exercised live.
- Korean runtime (`決定` → `확인`) on this exact 確認 dialog: the next Korean attempt should target this dialog with the Copy-mode client (data junction), because the dialog is now reachable in ≤7 inputs from launch.
- Both factions, logout via the in-game exit path (this run used process stop for the restart leg), tactical.

Next start: (1) Copy-mode fresh run with the fixed Korean proxy and the data junction; expect `PLAYER_VISIBLE_KO` on the 確認 dialog; (2) exercise a rejected move (destination not legal) and confirm the client's visible rejection text; (3) capture the strategy-map fleet marker at 102 vs 101.

## Forbidden retries

- Do not treat (514,385)/(596,388) as fixed cell ids; derive them from the current frame.
- Do not send 決定 twice for one move; the second 0x0B01 with a stale source cell is expected to be rejected (`MOVE_GRID_SOURCE_STALE`) and must be recorded as a rejection test, not a retry.
- Do not reuse PIDs 4156/2088 (stopped) or 8676/8760 after this run ends.
