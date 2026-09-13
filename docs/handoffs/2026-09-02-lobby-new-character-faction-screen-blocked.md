# Handoff: lobby 新キャラクターの作成 path on the recovered account — session picker and faction screen reached, 次へ / 戻る unresponsive (BLOCKED)

Date: 2026-09-02 (KST night). Lane: UI 버튼·카드·공지·활성 조건 + 캐릭터 생성 (goal step 4, completion conditions 2 and 4). Run `20260902T132656Z-natural-l1-relogin-v1` (item114 client from the install directory, patched authority v129, source data = run `121817Z`, account already holding one Alliance character).

## Result

`PARTIAL / BLOCKED` at `FACTION_SCREEN_NEXT_NOT_ACTIVATED`.

| Input | Result |
|---|---|
| 新キャラクターの作成 (122,249), synthetic click | PASS — session picker "プレイするセッションを選んで下さい。", 選択可能セッション数 1/2, rows LOGH7-1 / LOGH7-2 showing 銀河帝国 0人 vs 自由惑星同盟 0人, 経過時間 0日, 戻る (`vnc-f02-new-character.png` 0932F642…CAA3) |
| 戻る (655,582), synthetic click | no change (`vnc-f03-after-back.png`) |
| オリジナルキャラクター抽選 (122,307) while the picker is open | ignored — the left menu is modal-blocked while a sub-panel is open |
| 戻る (655,582), physical VNC click | no change (`vnc-f05-after-physical-back.png`) |
| session row LOGH7-1 (640,270), synthetic click | PASS — client reconnected to the session server (`AwaitPhase1→…→SessionServerReady`, connection 3) and showed the faction screen "所属する勢力を選んで下さい。" with 銀河帝国 selected, 自由惑星同盟, 中止 / 次へ (`vnc-f06-after-session-row.png` 62615C46…36E7) |
| 次へ (762,582), synthetic click | no change (`vnc-f07…`) |
| 次へ, physical VNC click with 800 ms hover | no change (`vnc-f08…`) |
| held physical Enter (keydown, 250 ms, keyup) | no change (`vnc-f09…`) |
| ESC | see `vnc-f10-after-esc.png`; run closed (client process stopped by the lane if the exit dialog did not appear, `stop-own-client.json`; authority + PostgreSQL clean-stopped, `clean-stop.json`) |

Client stayed alive and responding throughout (`census-f01.json`: responding=true, connection Established); no server frame was produced by the 次へ attempts (wire ends at `SessionServerReady`), so the block is client-side gating, not a server rejection.

## Facts and hypotheses

- The codex lane reached the gender screen on 2026-08-30 (run `20260830T065839Z`) with the item1-patched client on a *fresh account with no character*; its handoff says "the first screen … enabled 次へ" and "one 次へ activation showed the gender screen" but records no click receipt or transport. Differences here: client item114 (later patch lineage), account with an existing character in slot 0, session list with two entries, authority v129.
- Hypothesis A (`INFERRED`): the original client disables 次へ when the account already owns a character of the other faction or when the slot policy forbids a second character; the button art does not visibly change between enabled and disabled states on this screen.
- Hypothesis B: a later item patch (item12…item114 lineage) altered the create-flow gate; needs a static diff of the create-screen handler between item1 and item114 variants.
- 戻る on the session picker did not respond to either input path; it may share the same gate or be dormant. `ORIGINAL_UNIMPLEMENTED` vs gated is `UNKNOWN`.
- Session list content (0人, 0日) is the authority's authored session catalogue (`NEW_DESIGN`) and does not reflect the one existing character; goal step 6 (state-dependent UI) applies here.

## Next start

1. Static: locate the faction-screen widget owner and its enable condition (the manager that hosts 中止/次へ; search the create-screen constructor referenced in `work/20260830-login-input-boundary/character-selection-screen-constructor-detailed.txt`) and diff item1 vs item114 around it.
2. Live: repeat with a freshly provisioned account (no character) on the same authority; if 次へ works there, hypothesis A is confirmed and the Empire character must be created on a second account (which is also what BOTH_FACTIONS needs).
3. Fix the session list to show real faction population from PostgreSQL.

## Forbidden retries

- Do not click 次へ or 戻る again on this run's state; three input paths are already sealed.
- Do not treat the session picker counts as original data.
