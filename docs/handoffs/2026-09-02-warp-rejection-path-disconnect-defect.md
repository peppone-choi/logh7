# Handoff: WARP rejection path — authority rejects, but drops the session instead of returning a reason

Date: 2026-09-02 (KST evening). Lane: 이동·WARP 수직 경로 (거부 다리) + UI 버튼·거부 이유. Run `20260902T130018Z-natural-l1-relogin-v1`, source data = run `20260902T121817Z` (unit 2 already at cell 102, `pg_control` sha256 `3B57F67F…128B`).

## Result

`AUTHORITY_REJECTION_OBSERVED / PLAYER_VISIBLE_REASON_FAIL` — the explicit-rejection leg of the vertical path is proven at the wire and database boundaries but fails at the client-visible boundary because the current authority closes the session on rejection.

Sequence (one input each): Shift wake → credential → ゲーム開始 → character → 職務権限カード → card → ワープ航行 → the other grid object (after recentring it is the left one at (430,388)) → 確認 dialog → 決定.

| Boundary | Observation |
|---|---|
| client command | 0x0B01 sent (type 2817, 48-byte payload) at 13:02:05.98Z |
| authority decision | `SessionServerReady → Rejected`, status `Invalid`, `errorCode=original.move-grid.MOVE_GRID_DESTINATION_NOT_LEGAL` (source 102 has no legal destination in the `NEW_DESIGN` minimal world) |
| authority follow-up | `connection-closed connectionId=3 finalState=Rejected` in the same millisecond — the session socket was dropped |
| PostgreSQL | unchanged: unit 2 at 102, authority_version 25, 1 move command, 25 domain events (`db-inspect-r01.json`) |
| client screen | modal **切断** dialog "サーバーから切断されました。ゲームを終了します。" with 決定 (`vnc-r06-after-kettei.png` BCC9D0A7…9857); the rejection reason is not shown |
| client after 決定 | process exits (see `click-r-disconnect-decide.json`, `census-r-exit.json`, `vnc-r07-after-disconnect-decide.png`) |
| run closure | `clean-stop.json` |

Other captures: `vnc-r01-login.png`, `vnc-r02-lobby.png`, `vnc-r03-world.png` (camera centred on cell 102 with the other cell to the left, as in the reconnect proof), `vnc-r04-chooser.png`, `vnc-r05-confirm.png`.

## Defect for the authority lane (codex worktree, `apps/server/Logh7.Server/OriginalGateway/NaturalAuthoritySession.cs` + `OriginalMoveGridAuthority.cs`)

A rejected 0x0B01 currently ends the session. For goal steps 6 and 8 ("승인 또는 명시적 거부", "거부 이유가 서버 권위와 일치") the session must stay open and the client must receive a protocol-level rejection it can display. What the original server sent for an illegal move is `UNKNOWN`; the next static-analysis unit is the client's handler for the 0x0B01 response/0x0B07 family (look for a status/result field in the response parser and the message-table row used for a failed move, e.g. a `constmsg` row shown by the WARP command result path) so that the authority can answer with an `ORIGINAL_STATIC`-backed rejection frame instead of closing the socket. Until then, every illegal move looks like a network failure to the player.

Also worth recording: the 確認 text promises MCP consumption (320/80) but the HUD shows MCP 0 and the authority does not model MCP; the accepted move earlier today therefore ignored a resource precondition that the original UI implies.

## Facts

- Grid-object screen coordinates depend on the unit's cell: with the unit at 102 the two objects are at (430,388) and (513,388); with the unit at 101 they were at (513,388) and (596,388).
- The fresh-run scripts now accept `-SourceRunId`, `-ExpectedSourcePgControlSha256`, `-PostgresRuntimeZipPath`, `-ServerZipPath`, `-AccountSecretRoot`, so any cleanly stopped run can seed the next run.

## Forbidden retries

- Do not treat the 切断 dialog as a rejection display; it is a disconnect.
- Do not re-send the same illegal move expecting a different answer; the authority is deterministic.
