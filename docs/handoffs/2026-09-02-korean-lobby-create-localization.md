# Handoff: Korean localization for the lobby / character-creation string table (constmsg 0x4E)

Date: 2026-09-02 (KST night). Lane: 한글화·폰트·레이아웃 (goal §한글화, completion condition 6). Offline unit; no VM, no server, no shared-worktree edit.

## Result

`LOCALIZATION_CONTENT_READY / PLAYER_VISIBLE_UNSEEN` — all 115 rows of constmsg table 0x4E have a grounded Korean draft.

While reverse-engineering the create-flow block I decoded `constmsg.dat` (magic HFWR, 120 tables, 3200 CP932 strings, table directory of 120 u32 at 0x10, string data from 0x1F0; sha256 `5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C`). Table **0x4E (78), global indices 2429–2543** is the entire lobby + character-creation + lottery + environment-settings string table. It is the localization source for every screen this session drove or was blocked on: the 8 lobby buttons, the session picker, the faction/type/gender/name/face/ability/registration create screens, the lottery, and the settings panel.

Produced `work/20260902-korean-lobby-create-localization/constmsg-0x4E-ko.json` (sha256 `03B83DA3…389A`): 115 rows, each with stable key `constmsg.004e.NNNN`, group 78, row, global index, exact CP932 `jp` source (`provenance: ORIGINAL`), a Korean `ko` draft (`koProvenance: AUTHORED_TRANSLATION`, `status: DRAFT`), and the source byte length. `missingKoRows` is empty (all 115 translated).

Highlights grounding earlier live work:
- Row 68 `決定` → `확인` — matches the already-proven d3d8 shim mapping (`2026-09-02-korean-runtime-player-visible-pass.md`).
- Rows 0–7 are the 8 lobby buttons (ゲーム開始…ゲーム終了); rows 44–47 the faction screen (所属する勢力…/銀河帝国/自由惑星同盟/次へ); row 80 `中止`; row 8 サーバーからのお知らせ (the server-notice title seen live).
- Row 12 is the long character-move warning; rows 108–110 the lottery odds legend (white/yellow/red = high/mid/low).

## How this plugs in

The existing codex localization manifest is `content/localization/ko-KR.json` in the natural-authority-d02 worktree (46 rows, same `key/group/row/jp/ko/provenance/status` shape). This file is a proposed additive contribution for that manifest — its keys extend the `constmsg.004e.*` namespace and its `jp` bytes are verifiable against `constmsg.dat`. It is NOT edited into the codex worktree here (that lane owns the file); merging is a codex-lane step.

The d3d8 Korean-runtime shim currently maps only CP932 `決`/`定` at the GDI boundary. To render these strings it must move from per-glyph to per-string mapping keyed by the sidecar (`ko-runtime.tsv`) rows; this JSON is the data those sidecar rows should carry.

## Not proven

- No pixels: these Korean strings have not been rendered by the client (the shim does not yet consume table 0x4E). `PLAYER_VISIBLE_KO` stays limited to the 決定→확인 case until the shim is widened and captured live.
- `ko` values are AUTHORED_TRANSLATION drafts, not reviewed; length-vs-widget-width fit is unverified for the longer rows (12, 57, 79) which may need wrapping or abbreviation on the original non-Unicode render path.

## Next

1. Widen the d3d8 shim to per-string mapping and feed it the 0x4E rows via the sidecar; verify each lobby/create screen by VNC capture in the fresh-run lane (localization is unblocked and independent of the faction-button RE).
2. Decode the remaining constmsg tables the same way (the strategy HUD, tooltips, error/rejection texts including the move-reject text authored in `2026-09-02-warp-rejection-visible-reason-pass.md`, and celestial/fleet/character names) and extend the manifest.
3. Merge into the codex `content/localization/ko-KR.json` manifest.
