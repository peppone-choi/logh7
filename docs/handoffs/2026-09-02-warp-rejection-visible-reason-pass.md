# Handoff: rejected WARP now shows a reason in the original client (0x0500 NotifyInvalidMessage) — PLAYER_VISIBLE_PASS

Date: 2026-09-02 (KST night). Lane: 이동·WARP 수직 경로 (거부 다리) + 프로토콜. Run `20260902T132053Z-natural-l1-relogin-v1`, source data = run `121817Z` (unit 2 at cell 102).

## Result

`AUTHORITY_PROVEN / PLAYER_VISIBLE_PASS / PERSISTENCE_PROVEN (unchanged)` for the explicit-rejection leg:

```text
ワープ航行 → other grid (430,388) → 確認 → 決定
→ 0x0B01 → authority: MOVE_GRID_DESTINATION_NOT_LEGAL
→ session stays SessionServerReady, responds with 0x0500 {error=1, msg="指定グリッドにはワープできません"} (56-byte frame)
→ client: 確認 dialog closes, strategy message window shows the text in orange (vnc-e03-after-kettei.png 3CBA1CBC…288C; 4x crop vnc-e03-log-window-4x.png 339243CE…C805)
→ no 切断 dialog, client TCP 127.0.0.2:47900 still Established, client alive/responding
→ PostgreSQL unchanged: unit 2 at 102, 1 move command, 25 events (db-inspect-e01.json)
→ ESC → ゲーム終了 決定 → client exit, wire connection-closed, authority + PostgreSQL clean-stopped (clean-stop.json)
```

Wire line: `type=2817 SessionServerReady->SessionServerReady status=Success responseMetadata=move-grid-reject=MOVE_GRID_DESTINATION_NOT_LEGAL;notify-invalid-message-error=1;design=new responsePayloadLength=56`.

## What was built (all outside the codex worktree)

- `work/20260902-notify-message-codec/OriginalNotifyMessageCodec.cs` — encoder for 0x0500 / 0x0501 (spec: `2026-09-02-notify-invalid-message-wire-static.md`), `vectors.json` test vectors.
- `work/20260902-notify-message-codec/session-soft-reject.patch` — unified diff against the codex worktree's `NaturalAuthoritySession.cs` (dirty state as of 20:29 KST): the two authority-rejection branches of `ProcessMoveGridAsync` now call `RejectMoveGridVisibly`, which records `move-grid rejected-<code>`, keeps the session state, and returns a Success result carrying the 0x0500 frame with `ResponseMetadata=move-grid-reject=…`. Malformed frames and ownership violations still use `Invalid` (close).
- Message table (`AUTHORED_PLACEHOLDER`, NEW_DESIGN codes): 1 DESTINATION_NOT_LEGAL 指定グリッドにはワープできません; 2 SOURCE_STALE 現在位置が更新されました。もう一度選択してください; 3 CARD_NOT_AUTHORIZED この職務権限カードではワープできません; 4 ACTION_NOT_AUTHORIZED このコマンドは実行できません; 5 UNIT_NOT_OWNED この部隊は指揮できません; 0xFF fallback コマンドは拒否されました. Korean sidecar rows are still to be added.
- Build: scratch copy of `apps/server/Logh7.Server` + props + `db/migrations`, `dotnet publish -c Release -r win-x64 --self-contained` with `MSBuildSDKsPath` cleared (the host environment points it at a Scoop path that does not hold the 10.0.301 SDK). Output `logh7-server-notify-reject-v129-win-x64.zip` sha256 `BD30BF6C…96CD`, `Logh7.Server.exe` `D77CE918…49A4`, `Logh7.Server.dll` `BADD0AF2…3634`, migration 0011 unchanged `9750CEFD…92B`.
- `host-run-fresh-run.ps1` gained `-HostServerZipPath` (stages a host-built zip into the guest and verifies its hash inside the guest) and `-ExpectedServerZip/Exe/DllSha256` overrides.

## Facts

- The strategy-mode message window (manager 0x6F/9 path) renders 0x0500 text immediately and colours it differently from client-side log lines; UTF-16BE units carry Japanese correctly.
- The 確認 dialog closes on the client's own accord after the 0x0B01 send regardless of the outcome; the outcome is conveyed only by 0x0B07 (moved) or, now, 0x0500 (rejected).
- The 0x0500 `reserved` byte was sent as 0 and accepted.

## Next

- Authority lane: apply `session-soft-reject.patch` + codec to the real worktree, add unit tests from `vectors.json`, replace placeholder texts with `constmsg` rows if the original client has them, add Korean sidecar rows, and extend the same soft-rejection to other commands (rank-up, mail, messenger) so no legal-usage rejection ever closes the session.
- Exercise `MOVE_GRID_SOURCE_STALE` live (two clients or a stale confirm) and the Korean overlay for the rejection text.

## Forbidden retries

- Do not build from the `.claude\worktrees` path with the inherited `MSBuildSDKsPath`; clear it first.
- Do not treat the placeholder texts as `ORIGINAL_*`.
