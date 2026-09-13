# Progress matrix — closed play vertical paths as of 2026-09-02 22:55 KST

Scope: goal `docs/goals/logh7-original-client-full-playability-goal-2026-09-02.md`. Progress is counted only by vertical paths closed live on the unmodified-hash original client against the natural PostgreSQL authority, not by documents or code.

## Current game screen and possible inputs

No client is running; all of this lane's runs are cleanly stopped. The last live screens were the faction-choice screen of the create flow on a fresh account (run `134841Z`, `vnc-h05-after-next.png`) and, before that, the soft-rejection message "指定グリッドにはワープできません" in the strategy message window (run `132053Z`). From the strategy screen the inputs proven to consume correctly are: 職務権限カード tab, card 運営, ワープ航行, grid pick, 確認 決定/取消し, ESC → ゲーム終了 決定/取消し, HUD slot 22, 星系内宇宙, system cell click; in the lobby: ゲーム開始, character card, 新キャラクターの作成, session-picker row. Last success: rejected WARP shown as a visible reason without disconnect (run `132053Z`). Last failure: 次へ / 戻る on the create-flow screens do not respond to any input path (runs `132656Z`, `134841Z`).

## Vertical-path matrix (goal §전체 기능 범위, both factions required for BOTH_FACTIONS)

| # | Path | Highest state today | Result | Evidence run |
|---|---|---|---|---|
| 1 | account preparation (disposable account, DPAPI secret) | PERSISTENCE_PROVEN + LOGIN | PASS (account t8405ba3 from 08-30; new account provisioned and logged in today via `-ProvisionNewAccount`) | validation v6, 134841Z |
| 2 | login on the original client | PLAYER_VISIBLE + AUTHORITY_PROVEN | PASS | 121817Z, 125221Z, 130018Z |
| 3 | character creation without fixture | faction screen reached on fresh account AND with the item1 client that worked on 08-30; both faction-panel buttons (中止/次へ) inert while sibling panels respond (hypotheses A and B refuted); empty roster shows a client error text | BLOCKED | 134841Z, 135324Z |
| 4 | character selection → world entry | PLAYER_VISIBLE + AUTHORITY_PROVEN | PASS | 121817Z (+ relaunch) |
| 5 | re-login shows the same character | PERSISTENCE_PROVEN | PASS | 121817Z relaunch |
| 6 | server restart → PostgreSQL restore → reconnect | PERSISTENCE_PROVEN | PASS (character, grid) | 121817Z relaunch |
| 7 | strategy screen, system interior, planets/orbits | PLAYER_VISIBLE | PASS (fixed 4-node scene, NEW_DESIGN) | 121817Z |
| 8 | server notice → connected client | PLAYER_VISIBLE | PASS (ASCII only) | 121817Z, 125221Z, 130018Z |
| 9 | authority card / HUD command → WARP → confirm → authority → DB | AUTHORITY_PROVEN + PERSISTENCE_PROVEN | PASS (101→102) | 121817Z |
| 10 | WARP rejection with visible reason | PLAYER_VISIBLE + AUTHORITY_PROVEN | PASS with patched authority v129 (placeholder Japanese text; run 130018Z on v128 still shows the disconnect defect) | 132053Z |
| 11 | game exit (last exit button) cancel / confirm | PLAYER_VISIBLE + wire connection-closed | PASS | 121817Z, 125221Z |
| 12 | Korean overlay on a live surface | PLAYER_VISIBLE (決定→확인, 2 dialogs) | PARTIAL, now BOUNDED — GDI substitution cannot localize the lobby: the client draws each glyph once to build a GDI atlas and composites labels in D3D, so labels never reach GDI as strings (see `2026-09-03-korean-lobby-per-glyph-renderer-blocker.md`) | 125221Z; diag 150429Z, 151256Z |
| 13 | lobby buttons 新キャラクター作成 / 抽選 / 削除 / セッション変更 / 環境設定 / クレジット | 新キャラクター作成 → session picker → faction screen reached today; 次へ/戻る unresponsive on the character-holding account (BLOCKED, see `2026-09-02-lobby-new-character-faction-screen-blocked.md`); other buttons not re-run today | PARTIAL | 132656Z + codex handoffs |
| 14 | mail / messenger / order-suggest | codex lane items 69–115 | PARTIAL, not re-run today | codex handoffs |
| 15 | celestial types (sun/planet/fortress/black hole/neutron) with real models | STATIC_MAPPED partial | UNSEEN | — |
| 16 | button enable/disable by state with reasons | ENUMERATED | UNSEEN | — |
| 17 | both factions in one session | — | UNSEEN | — |
| 18 | tactical entry / combat / retreat / occupation | — | UNSEEN | — |
| 19 | production / supply / repair / logistics / politics / personnel / diplomacy / economy | — | UNSEEN | — |
| 20 | growth / ranking / win-loss | codec only (rank-up) | UNSEEN live | — |
| 21 | exhaustive file analysis ledger (≈16k rows) | ENUMERATED | UNSEEN | — |

Closed live today (PASS rows 1, 2, 4, 5, 6, 7, 8, 9, 10, 11): 10 of 21 listed paths, all Alliance-side only, none BOTH_FACTIONS, none INDEPENDENTLY_REVIEWED. Row 10 depends on the patched authority v129, which the authority lane has not yet adopted.

## Final completion conditions (goal §최종 완료 조건)

| Cond. | Status |
|---|---|
| 1 natural login | PASS (seven runs today, two accounts) |
| 2 character creation without fixture | BLOCKED (fresh account provisioned and logged in; faction screen reached; 次へ unresponsive on item114; empty roster shows a client error text) |
| 3 same character after logout | PASS |
| 4 Empire + Alliance in one session | UNSEEN (second account exists; Empire character creation blocked at 次へ) |
| 5 all celestial bodies typed/modelled | UNSEEN |
| 6 all HUD/cards/notice in Korean, no NO DATA | PARTIAL, BOUNDED — dialog buttons only; lobby/HUD labels are composited in D3D from a GDI glyph atlas and cannot be translated by a GDI font shim (`2026-09-03-korean-lobby-per-glyph-renderer-blocker.md`); widening needs a D3D-layer intercept or a scope decision |
| 7 all buttons incl. last exit with authority-matching enable/reasons | PARTIAL (exit PASS; WARP rejection reason PASS on patched v129, pending adoption by the authority lane; other buttons/reasons unverified) |
| 8 lottery or NEW_DESIGN path end-to-end | codex lane partial, not re-run |
| 9 all strategy features | PARTIAL (WARP only) |
| 10 all tactical features | UNSEEN |
| 11 economy/politics/etc. change state | UNSEEN |
| 12 both factions meet/fight | UNSEEN |
| 13 restart restores state | PASS for account/character/grid |
| 14 authority = two clients | UNSEEN |
| 15 zero unanalysed files | UNSEEN |
| 16 zero stubs/placeholders/skips | UNSEEN |
| 17 independent reproduction | UNSEEN |

The goal is not complete. Next visible targets, in order: unblock the create flow — proven server-independent; static gate FUN_005015F0/5024A0/5025C0 on widget +0x08/+0x15 found; live RPM harness built but its manager-enumeration finds only fixed uiRoot templates (lobby=faction identical), so the live faction panel object is not yet located — next is modelling the event loop's live `this` manager; encode the zero-character roster so the client shows an empty list; authority lane adopts the soft-rejection patch; Korean overlay widened to lobby/HUD/cards; celestial type/model mapping from `0x031C/0x031D`.
