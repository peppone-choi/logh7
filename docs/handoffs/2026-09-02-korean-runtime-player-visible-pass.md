# Handoff: Korean runtime PLAYER_VISIBLE_KO — 決定 renders as 확인 on the live 確認 dialog

Date: 2026-09-02 (KST evening). Lane: 한글화·폰트·레이아웃 + fresh live runner. Run `20260902T125221Z-natural-l1-relogin-v1` (Copy mode, Korean runtime).

## Result

`PLAYER_VISIBLE_KO_PASS / CLIENT_SURVIVED_LAUNCH` — the fixed x86 `d3d8.dll` proxy (sha256 `B5AA1848…942128`, no `CreateFontA` hook) with sidecar `ko-runtime.tsv` (`0A8959DD…2956`) ran inside a disposable copy of the unmodified-hash item114 original client, survived launch (no 0.93 s crash), reached the WARP 確認 dialog in the same input sequence as the Japanese baseline, and rendered the confirm button as **확인** while the rest of the dialog (取消し, the Japanese body text, card text, HUD) stayed intact. The game-exit dialog (ゲーム終了) also showed 확인 for its confirm button.

## Why item118 crashed and this run did not

The item118 copy (`korean-client\G7MTClient.exe` + 3 files) had no `..\data` sibling; the install layout is `C:\LOGH7_ORACLE\{exe,data,doc}` and the client resolves resources relative to its exe directory. `guest-prepare-fresh-run.ps1 -ClientMode Copy` builds `<run>\client\exe\G7MTClient.exe` (item114 copy, cursor.txt, String.txt, G7MTOracle.exe) plus junctions `<run>\client\data → C:\LOGH7_ORACLE\data` and `<run>\client\doc → C:\LOGH7_ORACLE\doc`, then adds `d3d8.dll` and `ko-runtime.tsv` and launches with `LOGH7_KO_RUNTIME=1`. The 32-bit module census (`modules-k01.json`) shows both `…\client\exe\d3d8.dll` (proxy) and `C:\WINDOWS\system32\d3d8.dll` (forwarded) loaded. Whether the old proxy's `CreateFontA` hook would also have crashed with a data root present is `UNKNOWN`; the fixed proxy is the one that ran.

## Sequence (one input each) and evidence

| Stage | Receipt | VNC capture sha256 |
|---|---|---|
| launch (Copy + Korean) | `fresh-run-prep.json` D1382A0B…EB5B; client PID 3784, HWND 0x703E8, authority PID 2660 | — |
| physical Shift wake | `vnc-shift-receipt.json` | `vnc-k01-login.png` F603EEBF…5A11 (login surface, proxy active) |
| credential (38 key events) | `credential-k01.json` | `vnc-k02-lobby.png` 43CA8DD5…4A0A |
| ゲーム開始 / character | `click-k-gamestart.json`, `click-k-character.json` | `vnc-k03-roster.png` 90A1B665…B3F4, `vnc-k04-world.png` F3CF7E9F…1946 |
| card tab / card | `click-k-card-tab.json`, `click-k-card-39.json` | `vnc-k05-card.png` 23E75630…B0B2 (Japanese text intact) |
| ワープ航行 / destination | `click-k-warp-action.json`, `click-k-destination-cell.json` | **`vnc-k06-confirm-dialog.png` 9DDCE059…C239 — 확인 / 取消し** |
| 5x crops for review | — | `vnc-k06-confirm-buttons-5x.png` 46D91658…1E00 vs baseline `vnc-k06-baseline-japanese-buttons-5x.png` 83E89E64…DEF0 |
| 取消し (no move sent) | `click-k-warp-cancel.json` | `vnc-k07-after-cancel.png` 7936A6A2…23B5 |
| ESC → ゲーム終了 dialog | `vnc-esc-k` | `vnc-k08-exit-dialog.png` D148A8E5…450B (확인 / 取消し) |
| 확인 → normal exit | `click-k-exit-confirm.json`; client gone; wire last event `connection-closed`; 0x0B01 count 0 | `vnc-k09-after-exit.png` E8F806AE…29E4 |
| run closure | `clean-stop.json` `RUN_RUNTIME_CLEANLY_STOPPED` (authority 2660 stopped, PostgreSQL `shut down`) | — |

Authority wire (`server-wire.jsonl`, 42 lines): LoginAcceptedSent 12:54:06Z, LobbyReady, LobbyRedirectSent 12:54:30Z, SessionServerReady, bootstrap, heartbeats, connection-closed after the exit click. No move command was sent in this run; the database copy stayed at cell 101.

## Facts

- Glyph scope: the shim maps only the CP932 glyphs 決 and 定 at the GDI measure/draw boundary; on these two dialogs the whole word 決定 is the button label, so the visible result is a clean two-glyph 확인 rendered from Malgun Gothic with slightly wider advance than the original MS Gothic glyphs (the button still fits). Mixed strings containing 決 or 定 elsewhere would be partially translated; none was on the surfaces captured here.
- The Korean button is fully clickable at the same coordinates (565,484 / 565,436) and produces the same wire behaviour as the Japanese baseline.
- No `Logh7KoRuntimeStatus` read-back was performed from outside the process; the visible pixels are the acceptance evidence.

## Next

- Widen the overlay from the single `constmsg.0062.0000` row to the lobby buttons, HUD labels, card text and server notice through the stable-key sidecar, verifying each surface by VNC capture in the same fresh-run lane (≤ 12 inputs from launch to the WARP dialog).
- Replace the hard-coded CP932 glyph mapping with per-string mapping so mixed Japanese strings containing 決/定 are not partially translated.
- Server notice: the notice is ASCII-only in the launch contract (`^[\x20-\x7E]+$`); a CP932/Korean-capable notice path is needed for goal step 5 in Korean.

## Forbidden retries

- Do not launch a copied client without the `data` junction.
- Do not judge Korean pixels from GDI/PrintWindow captures before the first physical wake; use VNC captures.
- Do not reuse PIDs 3784/2660 or run `20260902T125221Z` for further input.
