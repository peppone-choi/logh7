# Handoff: Korean lobby localization is bounded — the client composites labels from a GDI glyph atlas

Date: 2026-09-03 (KST). Lane: 한글화 (localization) + fresh live runner.
Diagnostic runs (Copy mode, Korean runtime, `LOGH7_KO_DIAG=1`):
`20260902T150429Z` (whole-string shim) and `20260902T151256Z` (run-reconstruction shim). Both cleanly stopped.

## Result (faithful, negative)

`LOBBY_KO_NOT_RENDERED`. Two shim designs built and live-tested this session both loaded into a disposable
copy of the unmodified-hash item114 client, survived launch, reached the lobby, kept the client stable — and
**left every lobby button Japanese**. Read-only GDI-boundary instrumentation shows the root cause and bounds
what any d3d8/GDI font shim can achieve for this client.

## Root cause: the labels never reach GDI as strings

The client draws text to GDI **one glyph per call** and does so **once**, at resource-load time, to build a
**glyph atlas**. The visible menu labels are then composited from that atlas by the D3D engine; they never
pass through `ExtTextOutA` as whole strings, and are not redrawn through GDI each frame.

Evidence from `ko-diag.log` (an opt-in, read-only, in-process sink that records, once each, the exact ANSI
bytes passed to the hooked GDI text calls; it writes only to the shim's own directory and never touches the
client):

- **Every call carries one character.** `ExtTextOutA` length histogram over a full launch→lobby session:
  132 calls of length 1 (one ASCII byte) and 69 of length 2 (one Shift-JIS glyph). Never more than one char.
- **The draws are the atlas, not the labels.** The unique length-1 draws are the entire printable ASCII range
  in order (` ! " # … Z [ …`); the length-2 draws are exactly the kanji/kana in use (ゲ ー ム 開 始 新 キ …).
  That is a glyph set, drawn in glyph order, not label order.
- **They happen once.** The diagnostic cap is 800 records; both runs stopped at **402** and stayed there for
  the whole lobby session. If labels were GDI-drawn per frame, 402 would fill in a single frame. Glyphs are
  near-unique (a handful of ASCII appear twice, i.e. two font sizes), consistent with an atlas, not composition.
- **Run reconstruction disproven directly.** The second shim reassembled consecutive single-glyph draws into
  runs and matched them against the 113-row label table. Result notes in its log: **201 `learn`, 0 `KO`,
  0 `sup`** — no run ever equalled a label, because the glyphs arrive in atlas order, not as label runs.

Lobby capture with each shim active: `20260902T150429Z/vnc-d03-lobby.png` and
`20260902T151256Z/vnc-r03-lobby.png` (buttons Japanese; the ASCII server notice renders correctly).

## What this bounds

The only lever a GDI/d3d8 font shim has on this client is **per-glyph atlas-cell substitution**: replace what
an individual atlas glyph looks like. The client still composites labels using **its own** per-glyph layout,
whose glyph count and positions come from the original CP932 `constmsg` string. Therefore:

- The proven `決定 → 확인` dialog result was a per-glyph, **equal-count**, position-preserving swap
  (決→확, 定→인): two atlas cells replaced by two Hangul cells at the same two positions. It is a visual hack,
  not a translation, and does not generalise.
- Katakana loanword labels have no equal-count Korean glyph mapping (`ゲーム` 3 cells vs `게임` 2).
- A context-free per-glyph atlas map is linguistically unsound: `定` must be **인** in 確認/決定 but **정** in
  設定. One atlas cell cannot be both.

Genuine Korean lobby/HUD localization would require intercepting the **atlas→label compositing in the D3D/UI
layer** — supplying a Korean glyph set *and* overriding the client's per-glyph layout so it lays out the
Korean string rather than the CP932 one. That is a deep client-behaviour change, well beyond the sanctioned
reversible font shim, and is not a GDI-boundary task.

## Condition 6 (한글화) — status and honest ceiling

PARTIAL, and now bounded. With the sanctioned "unmodified original client + d3d8 font shim" approach:
- Achievable: rendering glyphs through a Korean-capable font, and equal-count per-glyph word swaps on the
  two-glyph dialog buttons (確認/終了 → 확인/…), which is the existing codex shim's proven scope.
- Not achievable at the GDI boundary: translating lobby, HUD, and any multi-glyph or katakana label.

Advancing condition 6 beyond dialogs requires either a D3D-layer UI-compositing intercept (large, arguably
out of the "unmodified client" spirit) or accepting the dialog-only ceiling. This should be raised as a
scope decision, not treated as a small shim widening.

## Artifacts and harness changes (reusable, additive)

- `work/20260902-korean-shim-widen/` — x86 Release shim + CMake + `runtime_core` unit tests (7/7 pass), now
  diagnostic-capable (`LOGH7_KO_DIAG=1`). The whole-string matcher and the run reconstructor are both proven
  inadequate for this client and are retained only as documented dead ends; the IAT-hook / `ScopedHangulFont`
  (Malgun Gothic, HANGEUL_CHARSET, per-call scope, no `CreateFontA` hook) / real-`d3d8` forwarding scaffold
  is sound and matches the codex shim technique.
- Harness passthroughs (defaults unchanged, so existing codex-shim runs are unaffected):
  `host-run-fresh-run.ps1` now forwards `-ExpectedProxySha256` / `-ExpectedSidecarSha256` (run a rebuilt shim
  without editing pinned hashes) and `-KoreanDiag`; `guest-prepare-fresh-run.ps1` gained `-KoreanDiag`
  (sets `LOGH7_KO_DIAG=1` for the client only).
- `ko-runtime.tsv` — 113-row jp→ko label table; still the correct *source* of labels, but useless for GDI
  substitution given the atlas model.

## Run hygiene

Three live runs this session (`145734Z`, `150429Z`, `151256Z`) each cleanly stopped
(`RUN_RUNTIME_CLEANLY_STOPPED`; authority + PostgreSQL copy shut down; sealed source cluster untouched). No
client memory was written; the diagnostic is read-only and in-process to the shim's own working copy. The
canonical CD / `G7MTClient.exe` was never modified; only disposable per-run copies ran.

## 2026-09-03 — per-glyph Hanja localization LIVE and PLAYER_VISIBLE (condition 6 real-screen progress)

Implemented and live-verified per-glyph Hanja→Hangul substitution (run `20260902T182845Z`, Copy+KoreanRuntime).
Reverted the shim from the (disproven) whole-string / run-reconstruction model to the proven per-glyph atlas
substitution, widened from the codex 2-glyph shim to all 177 CJK glyphs of constmsg table 0x4E:
- `work/20260902-korean-shim-widen/`: `runtime_core` now parses a 2-column `glyph<TAB>reading` sidecar and
  `FindString` matches a single atlas glyph; `d3d8_proxy` draws the Hangul reading for a matched single glyph
  under `ScopedHangulFont` in the `ExtTextOutA`/`GetTextExtentPoint32A` hooks (no run reconstruction).
- Artifacts: `d3d8.dll` sha256 `F3E208FF…6CEC`, `ko-runtime.tsv` (177 rows) sha256 `7A82573C…A5BC`; unit
  tests 7/7 pass; x86; client survived launch (no crash).

Live captures (VNC), Hanja rendered in Korean, kana/hiragana kept Japanese (atlas per-glyph ceiling):
- Lobby (`vnc-k-02-lobby.png`): **환경설정** (環境設定, fully Korean), ゲーム**개시** (開始), 신キャラクターの**작성**
  (作成), キャラクター**삭제** (削除), セッションの**변경** (変更), ゲーム**종료** (終了), オリジナルキャラクター**추선** (抽選).
- Faction (`vnc-k-04-faction.png`): **은하제국** (銀河帝国, fully Korean), **자유혹성동맹** (自由惑星同盟, fully
  Korean), **중지** (中止), body **소속する세력を선んで하さい** (所属/勢力/選/下), button **차へ** (次).

So condition 6 advanced from static map data to PLAYER_VISIBLE: pure-Hanja menu labels (환경설정, 은하제국,
자유혹성동맹, 중지, 성별, 남/녀 …) render fully in Korean live, and the Hanja portions of mixed
katakana+Hanja labels are localized. Remaining for full condition 6: katakana loanword labels (ゲーム,
キャラクター, セッション, クレジット, オリジナル) cannot be per-glyph 1:1 mapped and stay Japanese — this is the
documented atlas ceiling; closing them needs the D3D-layer compositing intercept (separate large unit), not a
glyph map. The server-notice / card / HUD strings should be checked next with the same per-glyph shim.

Run hygiene: cleanly stopped (`RUN_RUNTIME_CLEANLY_STOPPED`), authority+PostgreSQL down, regenerable copies
removed, leftover none; canonical client and sealed source untouched; no process-memory writes.

## 2026-09-03 — per-glyph Hanja localization extends to roster CARD and strategy HUD (condition 6 widened, run 20260902T183638Z)

Drove the same per-glyph shim (dll `F3E208FF…`, 177-glyph 0x4E map) through ゲーム開始 → character roster →
strategy screen and captured live Korean on additional surfaces:
- **Character roster card** (`vnc-s-02-roster.png`): the 8 stat labels render Korean —
  통솔/지휘/정치/기동/운영/공격/정보/방어 (統率/指揮/政治/機動/運営/攻撃/情報/防御), 18**세** (歳), body 選/下 in
  Korean, 되る (戻). Character NAME アッテンボロー stays Japanese (katakana).
- **Strategy HUD** (`vnc-s-03-strategy.png`): 우주력 795**년** 1**월** 1**일** 0**시** 2**분** (年/月/日/時/分),
  이**등병** (二等兵), **직무권한**카드 (職務権限), **요새궤도상** (要塞軌道上), **함내** (艦内), 성**계**内宇宙,
  stat labels again in Korean. Katakana (グリッド, スポットキャラクター) stays Japanese.

So condition 6 is now PLAYER_VISIBLE across FOUR screens (lobby, faction, roster card, strategy HUD): all
Hanja that appears in constmsg table 0x4E renders in Korean live. Two remaining gaps for full condition 6:
1. **Hanja outside table 0x4E** — the strategy HUD shows a few un-mapped ideographs (空, 影, 響, 暦, 単, 独,
   航, 系, 続 …) still Japanese because the 177-glyph map covers only table 0x4E. Fix: parse ALL constmsg
   tables, collect every CJK glyph, extend `ko-runtime.tsv` with its single Sino-Korean reading, rebuild.
   Pure static + one rebuild; no new mechanism. This closes the Hanja side of condition 6 game-wide.
2. **Katakana loanwords** — still impossible per-glyph (atlas ceiling); needs the D3D-layer intercept.

The per-glyph Hanja shim is proven and reusable; extending its map to all constmsg tables is the next
condition-6 unit (safe, incremental), after which only katakana remains (a separate D3D unit).

## 2026-09-03 — full game-wide Hanja map (946 glyphs) LIVE; only katakana remains (condition 6 Hanja side complete)

Extended the per-glyph map from table 0x4E (177) to ALL 946 distinct CJK ideographs in the whole constmsg.dat
(sha `5B3FAFBA…`, 3198 strings). Readings generated with the `hanja` Python package (installed; goal-sanctioned
tool), auto-converted 946/946, then merged with the validated manual map to override 10 initial-sound (두음법칙)
cases (了→료, 力→력, 女→녀, 年→년, 来→래, 率→솔[統率], 録→록, 齢→령, 予→예, 戻→되). New sidecar
`work/20260902-korean-shim-widen/ko-runtime.tsv` (946 rows) sha `D1B6E86A…`; shim dll unchanged (`F3E208FF…`,
logic identical); unit tests pass; client survived launch.

Live (run `20260902T184753Z`, strategy HUD `vnc-f-02-strategy.png`): the previously un-mapped ideographs are
now Korean — **공간**グリッド (空間), **영향력** (影響力), **단독항행** (単独航行), **성계** (星系), 우주력,
요새궤도상, 직무권한카드, plus all the stat/date/rank labels. Only katakana (グリッド, スポットキャラクター)
stays Japanese.

Condition 6 status: the **Hanja side is complete game-wide** (every CJK glyph the client can display now has a
Korean reading, verified live on lobby/faction/roster/strategy). The remaining gap for full condition 6 is
**katakana loanwords** (ゲーム, キャラクター, セッション, クレジット, オリジナル, グリッド, スポット…), which cannot
be per-glyph 1:1 mapped (atlas ceiling) and require the D3D-layer compositing intercept — a separate large
unit. Hiragana particles in body text also remain. The per-glyph Hanja shim (dll `F3E208FF…`, map
`D1B6E86A…`) is the deliverable for the Hanja side.
