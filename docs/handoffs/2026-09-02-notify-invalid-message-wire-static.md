# Handoff: original generic server→client error/notice messages 0x0500 NotifyInvalidMessage and 0x0501 NotifyError (ORIGINAL_STATIC)

Date: 2026-09-02 (KST night). Lane: 프로토콜 정적 분석 → 이동·WARP 거부 다리. Follows `2026-09-02-warp-rejection-path-disconnect-defect.md`.

## Result

`STATIC_MAPPED / CODEC_SPEC_READY` — the original client has two generic server→client text messages that the current authority never sends. Using them lets the authority reject a command (e.g. an illegal WARP) while keeping the session open and showing the player a reason, instead of closing the socket (which the client renders as 切断 "サーバーから切断されました。ゲームを終了します。").

Binary analysed: `G7MTClient.item1.exe` (sha256 `FCAC7942…7563`, a 1-byte-patched copy of the canonical `BD19263C…6E16`; the analysed functions are outside the patch). Tools: pefile + capstone on the host; existing Ghidra decompile dumps in `work/20260826-mode2-index8-gate-writer/mode2-index8-gate.txt`.

## Stream reader vtable (calibrated on the proven 0x0B07 layout)

`Input_NotifyMovedGrid::input_from_stream` (`0x0044B460`) reads `time,id,grid,base` with `vtable+0x1C`, `mode` with `vtable+0x20`, `count` with `vtable+0x24` — matching the server codec's `u32,u32,u32,u32,u16,u8` big-endian layout. Therefore `+0x1C` = u32 BE, `+0x20` = u16 BE, `+0x24` = u8.

## 0x0500 NotifyInvalidMessage

- Parser `Input_NotifyInvalidMessage::input_from_stream` at `0x004391F0` (log string `0x00765118` "msg_size[%d] is over than 128").
- Wire body after the 2-byte type:

| offset | width | field | source |
|---|---|---|---|
| 0 | u16 BE | `error` | `call [vt+0x20]` into `+0` |
| 2 | u8 | raw byte (helper `0x00610420(dest=+2, count=1, 0, 2)`, meaning `UNKNOWN`, keep 0) | |
| 3 | u8 | `msg_size` ≤ 128 | `call [vt+0x24]` into `+3` |
| 4 | u16 BE × msg_size | `msg[]` | loop at `0x00439330`, `call [vt+0x20]` per char |

- Text encoding: 16-bit code units; the client's other wire strings (e.g. messenger sender name bytes `30C0 30B9 30C6…` = ダスティ…) are UTF-16BE, so `msg` is UTF-16BE (`INFERRED` from that family, not yet pixel-verified for this opcode).
- Dispatcher case `0x500` (decompile `FUN_004B8B00` family, "NotifyInvalidMessage OK" at `0x0076FF50`): copies 0x104 bytes to `DAT_00448600+…`, clamps `msg_size` at `+3` to 0xFF, writes a u16 terminator at `+4+2*len`, then by UI mode `*DAT_02215E2C`: mode 1 → requires manager 9; mode 2 → managers 0x6F and 9; mode 3 → manager 0x56; each present manager gets `FUN_00501E30(0x17, FUN_00502780(0,0), &message)` — an event 0x17 carrying the message to the mode's message window. If the manager is absent it logs `戦術用メッセージウインドウが必要` ("tactical message window required") and drops the message.
- Debug dump format (`0x00439580`): `_INF:NotifyInvalidMessage# error=<u16> msg[<len>]={…}`.

## 0x0501 NotifyError

- Parser `Input_NotifyError::input_from_stream` at `0x00439650` (log `0x00765168` "error_msg_size[%d] is over than 128").
- Body: `u8 error_msg_size` (≤128) at `+0`, then `error_msg_size × u16 BE` at `+2`.
- Dispatcher case `0x501` ("NotifyError OK" `0x0076FEEC`): copies 0x102 bytes to `DAT_00448704+…` and logs the text; no message-window post was seen in the decompiled branch, so its player-visible effect is `UNKNOWN`. Prefer 0x0500 for visible rejections.

## Related opcode families seen in the same dispatcher (for the protocol ledger)

`0xB00`(→`0xB0B`), `0xB01` request (→`0xB07` NotifyMovedGrid), `0xB02` (0x18 + variable), `0xB03` (0x14 + variable), `0xB04`/`0xB05` (0x24), `0xB06` (0x164), `0xB08` NotifyLeaveOutGrid, `0xB09` NotifyEnterGridBegin, `0xB0A` NotifyEnterGridEnd; `0x424` NotifyTurnedShip, `0x425` NotifyWarpedShip, `0x426` NotifyAttackedShip; `0x70A` NotifyCardLoss, `0x70B` NotifyCardLossMovedSpot; `0x420` CommandChangeAuthority, `0x421` CommandMission, `0x422` CommandEmergencySupply, `0x704` CommandRankUp, `0x706` CommandRankDown, `0x709` CommandCardResignation, `0x901` CommandWithdrawalPlan, `0x902` CommandAnnouncement. The sender table at `0x004B7E5E…` pairs each request with its expected reply (`EBX`), e.g. `0xB01→0xB07`, `0xB00→0xB0B`, `0x408→0x430`.

## Server change proposal (authority lane; `NaturalAuthoritySession.ProcessMoveGridAsync` and the server loop)

1. Add `OriginalNotifyMessageCodec.EncodeInvalidMessage(ushort error, string text)` and `EncodeError(string text)` (reference implementation: `work/20260902-notify-message-codec/OriginalNotifyMessageCodec.cs` in this worktree, with test vectors in `vectors.json`).
2. For authority rejections that are legal protocol usage (`MOVE_GRID_DESTINATION_NOT_LEGAL`, `MOVE_GRID_SOURCE_STALE`, `MOVE_GRID_CARD_NOT_AUTHORIZED`, `MOVE_GRID_ACTION_NOT_AUTHORIZED`, replay), return a *Success* session result whose primary payload is the 0x0500 frame (lobby prefix as for other session-server pushes) and keep `State = SessionServerReady`. Reserve `Invalid` (close) for malformed frames and ownership violations.
3. Map the error code to `error` (u16, NEW_DESIGN table, e.g. 1 = destination not legal, 2 = source stale) and the text to a `constmsg`-backed Japanese string plus the Korean sidecar row; until an original row is identified, use an `AUTHORED_PLACEHOLDER` text and mark it in the localization manifest.
4. Record the rejection in `server-wire.jsonl` with `responseMetadata=reject=<code>` so the live receipt shows approval vs rejection explicitly.

Live acceptance for the next run: repeat the rejection scenario of run `130018Z`; expect the strategy message window to show the text, no 切断 dialog, session still `SessionServerReady`, DB unchanged.

## Unknown / not proven

- The meaning of the raw byte at `+2` of 0x0500 and the exact rendering path/length limit of the message window; which `constmsg` rows the original server used for move failures.
- Whether 0x0501 is ever displayed.
