# Handoff: fresh disposable account provisioned and logged in; empty-roster error text and 次へ block on the create flow (BLOCKED)

Date: 2026-09-02 (KST night). Lane: 캐릭터 보유·추첨·권한 + UI 활성 조건 (completion conditions 2, 4). Run `20260902T134841Z-natural-l1-relogin-v1` (patched authority v129, source data `121817Z`, `-ProvisionNewAccount`).

## Result

`ACCOUNT_PROVISION_PASS / LOGIN_PASS / CREATE_FLOW_BLOCKED` and `EMPTY_ROSTER_CLIENT_ERROR_TEXT` observed.

- `guest-prepare-fresh-run.ps1 -ProvisionNewAccount` runs `Logh7.Server.exe account-provision-disposable --secret <run>\account-secret.dpapi --receipt <run>\account-receipt.json` inside the run (DB connection via `LOGH7_DB_CONNECTION`, DPAPI for the guest user); account rows went 1 → 2 (`fresh-run-prep.json` `accountRowsAfterProvision=2`, `account-provision.stdout` `account-provisioned`). Secret values were never recorded.
- The original client logged in with the new account (`LoginAcceptedSent → LobbyReady`, `credential-h01.json`), so natural account creation → login is now a repeatable one-command path.
- ゲーム開始 on the character-less account: instead of an empty roster the notice panel shows `セッションサーバーの不具合につき、只今キャラクターを表示することができません。恐れ入りますが、少々お待ちください。` (`vnc-h02-roster.png` 91D09217…EDA9). The lobby roster requests 8195/8197 were answered `Success`; the client therefore treats the authority's zero-character roster shape as a failure. This is an authority-side `NEW_DESIGN` gap: the empty roster must be encoded the way the original server did (`UNKNOWN`; needs the client's roster parser / message-table check for the row that selects this text).
- 新キャラクターの作成 → session picker → LOGH7-1 row → session-server login → faction screen, exactly as with the character-holding account; 次へ (synthetic click) again did nothing (`vnc-h05-after-next.png` 0B549D47…4E06). Hypothesis A of `2026-09-02-lobby-new-character-faction-screen-blocked.md` (blocked by an existing character) is therefore **refuted**; the block is independent of account state.

## item1 control run (run `20260902T135324Z`) — hypothesis B refuted too

Ran the create flow with `G7MTClient.item1.exe` (sha256 `FCAC7942…7563`, the exact client that reached the gender screen on 2026-08-30) on a fresh account against the same v129 authority. Result: identical block — session picker and faction screen reached, but the faction panel's **both** bottom buttons 中止 (553,582) and 次へ (762,582) do nothing (`vnc-i04`, `vnc-i06`, `vnc-i07`), including after an explicit Empire-radio click at (599,315). In the same run the left lobby menu and the session-picker row responded normally. So the create-screen gate is not the item12→item114 patch lineage.

The remaining difference from the 2026-08-30 success is the server build. On 08-30 the create screens advanced with the codex server (zip `D845712F…3A7B`, dll `FA85EC91…9C04`) and the 08-30 wire shows the faction/gender transitions needed **no** server message after `SessionServerReady`. Today the transition also produces no wire and no visible change. Because the faction panel's buttons are inert while sibling panels work, the block is in the faction panel's own hit-test/active-manager gating, and the environmental variable that changed it is the session-server login response or session-catalogue shape the client received.

## Remaining hypotheses for the 次へ block

- C (leading): the client's faction/create panel is only armed when the session-server login response matches what the 08-30 build sent. Compare `OriginalSessionServerCodec.EncodeLoginOk`/`EncodeCharacterContext` and the `ProcessSessionServerLogin` response between the 08-30 server (dll `FA85EC91…9C04`) and v129, and check whether the client expects a 0x0203 CharacterContext or 0x0205 GameLogin exchange before the panel arms. The 08-30 wire ended at `SessionServerReady resp 16`, same as today, so the difference may be in the 16-byte response body, not the message count.
- D (less likely): the buttons need a hover→press the synthetic and single VNC paths do not reproduce; a multi-step physical mouse move was not tried.
- The faction-screen widget owner and its enable condition (search `work/20260830-login-input-boundary/character-selection-screen-constructor-detailed.txt`) must be read statically to settle C vs D.

## Static probe result (2026-09-02, host pefile+capstone on `G7MTClient.item1.exe`)

- The faction/session-picker UI text is NOT in the executable; it is `constmsg.dat`-driven, so there is no string anchor for the button handler. Confirmed: `所属する勢力を選んで下さい`, `次へ`, `中止`, `プレイするセッションを選んで下さい`, `セッションサーバーの不具合…` all return no match in the exe.
- The session-server input opcodes 0x201/0x203/0x204/0x205/0x206 are not compared as immediates; they are dispatched by `sub eax,0x200` + an 8-entry descriptor table at `0x00766EF0` (functions `FUN_0044F060`/`FUN_0044F0C0` read `table[idx*4+4]` and `table[idx*4+0x24]`; `FUN_0044F120` iterates all 8). This is codec registration for 0x200..0x207, not the UI-arming gate.
- Reaching the faction-panel arming condition therefore needs a dedicated widget-manager RE pass: from the 0x0201-accepted consumer, follow the manager that constructs the create screen and find the field it latches (likely a byte in the 0x0201 body or a session-context value) that enables 中止/次へ. Anchor: the descriptor table `0x00766EF0`, entry index 1 (0x201).
- This is a multi-step RE unit, not a quick fix; the block is fully localized (faction panel not armed; not client-patch-dependent) but its exact gate is UNRESOLVED. The buttons must not be poked again on live runs until the gate is known (goal: no blind input repetition).

## Server-build static diff (2026-09-02, ilspycmd on v5 vs v128 `Logh7.Server.dll`) — hypothesis C refuted

The 08-30 build that reached the gender screen is server v5 (`Logh7.Server.dll` sha256 `FA85EC91…9C04`, zip `logh7-server-world-handoff-v5-win-x64.zip` `D845712F…3A7B`). Decompiled v5 and v128 (`8BB4CA65…6624`) and compared the create-flow path:

- **`ProcessSessionServerLogin` response is byte-identical** in v5 and v128: both `return EncodeApplicationResponse(OriginalSessionServerCodec.EncodeLoginOk(), type, includeLobbyPrefix: true)`. `EncodeLoginOk()` is the constant `[0x00,0x00,0x00,0x00,0x02,0x01,0x01]` in both. The 0x0201 accepted response the client latches on is the same. **Hypothesis C is refuted: the session-server login response is not the difference.**
- `ProcessLobbyFollowupAsync` differs only in the session catalogue: v5 sends one record `OriginalLobbySessionRecord(1,1,"LOGH7","UC796",0)`; v128 sends two `(1,1,"LOGH7-1","0",0)` and `(2,1,"LOGH7-2","0",0)`. The session-selection response uses the same `EncodeSessionLoginOk(ip,port,handoffToken)` encoder in both. The character-list branch (8195) differs (v5 rejects populated lists as not-implemented; v128 encodes them) but is not on the create path before the faction screen.
- The faction→gender transition is client-side (no wire in either build's 08-30 trace or today's runs).

Conclusion: with the session-login response proven identical and the client byte-identical (item1), the create-flow block is NOT caused by the session-login wire. The only remaining wire difference is the session-record label/count (`"UC796"` → `"0"`, 1 → 2 sessions). So the gate is either (1) a client-side input/focus behavior of the faction panel that the tried injection paths (synthetic SetCursorPos+mouse_event, physical vncdo click, held Enter, radio click) do not trigger and that the unrecorded 08-30 transport did, or (2) the session-record 4th field (`"0"` vs `"UC796"`) putting the create context in an inert state.

Cheapest decisive next tests (either settles it):
1. Change v128's session records' 4th field back to a `UC796`-style value and re-run the create flow (one server edit + one run). If 次へ then works, the field is the gate.
2. Reduce v128's catalogue to a single session and re-run. If 次へ works, the count/selection is the gate.
Both are one-line edits to `ProcessLobbyFollowupAsync` in the authority lane, far cheaper than widget-manager RE, and directly test the only remaining wire difference.

## Single-session test result (2026-09-02, run `20260902T141138Z`) — wire fully ruled out

Built a test authority (dll `6ECF04DA…EAE3`, zip `logh7-server-v130-single-session-test-win-x64.zip` `48352A0A…8E65`) that emits exactly the v5 catalogue — one record `OriginalLobbySessionRecord(1,1,"LOGH7","UC796",0)` — everything else v128. Provisioned a fresh account, reached the session picker (now `選択可能セッション数 1/1`, `vnc-j01`) and the faction screen (`vnc-j02`), then 次へ: **still inert** (`vnc-j03`, `vnc-j04`). A gradual hover approach (multi-step VNC move into the button rect, then click) also did nothing (`vnc-j04`).

Therefore both remaining wire hypotheses are refuted: neither the session-record label/field nor the session count/selection gates the faction panel. Combined with the identical 0x0201 response, **the create-flow block is not in the server wire at all.** It is a client-side property of the faction/create panel: its 中止/次へ buttons do not consume any injected input (synthetic SetCursorPos+mouse_event, fast VNC click, gradual-hover VNC click, held Enter, radio click), while sibling panels (lobby menu, session-picker rows, WARP/ゲーム終了 dialogs) do.

Definitive next unit (client RE, not server): reverse the faction/create-screen widget manager to learn why its buttons reject injected clicks — candidate causes are a DirectInput-only mouse path for that panel, a modal/focus flag, or a per-button enable predicate reading client-internal create-session state. Anchor: the 0x0201 consumer via descriptor table `0x00766EF0` index 1, and the create-screen constructor in `work/20260830-login-input-boundary/character-selection-screen-constructor-detailed.txt`. Do not run more create-flow input permutations until that RE identifies the mechanism.

Test-server artifacts (throwaway, in `work/20260902-notify-message-codec/`): `logh7-server-v130-single-session-test-win-x64.zip`. The v130 source edit lived only in the scratch build tree and is not part of any committed server.

## constmsg localization of the faction screen and why inline RE dead-ends (2026-09-02)

Parsed `constmsg.dat` (magic HFWR, 120 tables, 3200 null-terminated CP932 strings from stringBase 0x1F0, 120-u32 table directory at 0x10; sha256 `5B3FAFBA…5C383C`). The create/faction screen strings live in **table 0x4E (78)**: 銀河帝国 = row 45, 自由惑星同盟 = row 46, 次へ = row 47, 中止 = row 80. (Reusable decoder logic recorded here; no repo tool existed.)

Searched `G7MTClient.item1.exe` for any immediate referencing table 0x4E, rows 45/46/47, or the global string indices 2474–2476: **zero hits** for all of them (only row 0x50=80 appears, unrelated). Therefore the faction-screen labels are not inline constants; they are supplied by a data-driven widget-descriptor table that the create-screen manager iterates. The button enable/hit-test logic that rejects injected clicks lives in that widget manager + its descriptor data, not in a string-anchored function.

## Manager input-dispatch gate located (2026-09-02, static RE of item1/item12)

Followed the widget-manager input path (dumps `input-sampler-decomp.txt`, `item12-exact-control-hit-decomp.txt`):

- `FUN_00500820` = shared cursor reader (`this+0x134`/`+0x138` = cursor x/y), input owner object `0x022142A8`.
- `FUN_00501ED0` = widget-list manager: count at `manager+0x3F4`, handle list at `+0x470`, widget records (0x34 bytes each) at `+0x4E8`.
- **`FUN_005015F0` is the manager's per-event hit-test/dispatch.** Order: (1) test `[event+0x08]` — if 0, log `0x779BA8` and return 0 (event not enabled); (2) `FUN_00501ED0`; (3) `FUN_005024A0` (0x501640) then `FUN_005025C0` (0x501650) — the input-boundary gates the manual notes describe as "reads `widget+0x15` after checking `widget+0x08`"; only if both pass does it read the cursor (`FUN_00500820` @0x501669) and hit-test.

So a click is consumed only when the manager/widget input-enable flags at `+0x08` (and `+0x15`) are set. The most likely cause of the faction/create panel ignoring injected clicks while sibling managers respond is that the create-screen manager (or its 中止/次へ widgets) has `+0x08` clear at that moment — a client-internal "panel not accepting input" state, independent of the mouse-injection method (which is why all five input paths failed identically).

Decisive next probe (read-only, allowed): at the live faction screen, `ReadProcessMemory` the create-screen manager and its 中止/次へ widget records; read `+0x08` and `+0x15` on the manager and each button widget, and compare with a responsive manager (lobby menu) captured the same way. If the faction widgets' `+0x08` is 0 while the lobby's is 1, the gate is confirmed and the fix is to find what sets it (the create-screen constructor / the 0x0201 or session-selection consumer that should arm the panel). This needs one live run parked at the faction screen plus the existing read-only memory-collector tooling; do it before any client patch.

## Live read-only RPM probe of the faction-panel widgets (2026-09-02, run `20260902T143340Z`)

Built a read-only process-memory collector (`work/20260902-fresh-run-recovered-db/guest-rpm-widget-flags.ps1`; OpenProcess VM_READ + ReadProcessMemory only, no writes/input). Validated the addresses first: the manager dispatch signature (`FUN_005015F0`), input owner `0x022142A8`, and uiRoot ptr `0x02215E2C` are byte-identical in item1 and item114 (item114's +4096 size is unrelated trailing data), so the offsets apply to the live item114 client.

Live at the faction screen: `uiRoot = 0x05442830`, `registry = U32(uiRoot+0x0C) = 0x09122020`, cursor read back correctly (640,270). Dereferencing uiRoot-relative slots found two clean 3-widget managers:
- `0x05442920` (slot 0xE4): 3 widgets, widget[1] `flag@0x15 = 46`.
- `0x05442A00` (slot 0x1C4): 3 widgets, widget[1] `flag@0x15 = 47`.

`0x15`=46 and 47 are exactly constmsg table 0x4E rows 46 (自由惑星同盟) and 47 (次へ) — so these managers ARE the faction-panel controls, and the byte at record `+0x15` carries the widget's constmsg row. In both, **every widget's `+0x08` byte is 0** — consistent with the FUN_005024A0/FUN_005025C0 input gate rejecting them.

Caveat (faithful reporting): this is strong but not yet conclusive. The collector's `+0x3F4` count / `0x34` stride model is only reliable for these small panels; managers where `+0x3F4` read 33 (0x21, a common field value) are false positives whose "records" are misaligned string data. No clean *responsive* widget array (e.g. the always-visible left lobby menu) was captured with the same model, so `+0x08=0` is not yet proven to be THE discriminator versus a responsive panel. The rect offset within the 0x34 record was not identified (widget[1] holds pointer-like dwords, likely label/handler pointers, not an inline rect).

## Baseline capture refutes the +0x08 evidence (2026-09-02, run `20260902T144129Z`)

Captured the same collector at the LOBBY (menu responsive) and at the FACTION screen in one run. The two captures are **identical**: the same 6 objects, and the two count=3 objects (flag15 = 46/47) are present with `+0x08=0` at BOTH screens — including the lobby, before any create navigation. Therefore these objects are screen-independent (a fixed prototype/template set near uiRoot), NOT the live faction-panel widgets, and their `+0x08=0` is **not** the responsive-vs-inert discriminator. The prior "strong evidence" reading is retracted: the RPM harness reads memory correctly, but its manager-enumeration model (`uiRoot`/`registry` slot deref → `+0x3F4` count → `+0x4E8` records) does not locate the active-screen managers or the always-visible lobby menu (no clean 8-button manager appears at either capture). `+0x15` carrying the constmsg row is real, but on template objects.

Honest status of the gate: `FUN_005015F0`/`FUN_005024A0`/`FUN_005025C0` gating on widget `+0x08`/`+0x15` is established statically, but which live object the faction panel uses — and whether its `+0x08` differs from a responsive panel — is NOT yet determined, because the live active-manager set was not located. Confirming it requires correctly modelling how the client tracks the active screen's managers (the `FUN_005015F0` `this` per event), not the fixed uiRoot template table this collector found.

Next (finish the structure model, then confirm): correct the widget-record layout AND the live-manager enumeration (find how the event loop selects the `this` manager for `FUN_005015F0`; the fixed uiRoot-relative objects are templates, not live panels) (true count field, stride, rect, and the +0x08/+0x15 semantics) by reading `FUN_00501ED0`/`FUN_005024A0`/`FUN_005025C0` precisely; capture a responsive manager (lobby menu, 8 buttons) with the corrected model in the same run; if faction widgets' +0x08=0 while lobby's +0x08≠0, the gate is confirmed and the fix is to find the create-screen constructor code that should set +0x08 on the faction widgets (and why it doesn't fire for this session's entry path). The read-only harness and validated top-level pointers are in place for that next-session pass.

Net: the create-flow block is a genuine data-flow RE unit — locate the create-screen manager's widget-descriptor array (labels resolve to constmsg table 0x4E rows 45–47/80), find how it builds 中止/次へ and what enables/hit-tests them, and why that path ignores injected mouse input while sibling managers (lobby menu, session rows, WARP/exit dialogs) do not. Anchors: constmsg table 0x4E; the session-server descriptor table `0x00766EF0` (0x201 consumer); `work/20260830-login-input-boundary/character-selection-screen-constructor-detailed.txt`. This is not completable as a quick fix and must not be substituted with more live input permutations.

## Guest disk incident (recorded)

During run `133824Z` the guest disk filled (VIX error 8 = disk full): `logh7-l1` holds > 25 GB of earlier lane runs. Only this lane's regenerable artifacts were deleted (extracted runtimes, deployments, client copies with junctions removed non-recursively, derived DB copies of runs 125221Z/130018Z/132053Z/132656Z/133824Z/134841Z); source states `083838Z` and `121817Z` were kept; `C:\LOGH7_ORACLE\data` attributes verified unchanged (0 files with Normal attribute, all Archive). Receipts: `runs/guest-cleanup-receipt-20260902.json`, per-run `cleanup.json`. Free space after: ≈1.6 GB. The older codex-lane run roots were not touched; disk pressure remains a shared blocker for further fresh runs.

## Next

1. Authority: encode the zero-character roster so the client shows an empty list (find the message-table row for the error text to learn the trigger; compare with the 08-30 server build that reached the faction screen).
2. Static diff of the faction-screen handler between `G7MTClient.item1.exe` and `G7MTClient.item114.exe`; try the item1 client on the current authority as a control.
3. Keep `-ProvisionNewAccount` as the standard way to open BOTH_FACTIONS once creation works.

## 2026-09-03 — client-variant regression ruled out (static, decisive)

Tested the prior "Next #2" hypothesis (item1 vs item114 faction-screen handler diff / item1 control run)
statically instead of spending a live run. Result: **the block is not a client-code difference.**

- `G7MTClient.item1.exe` (sha256 `fcac7942…7563`, 3,956,736 B) vs `G7MTClient.item114.exe`
  (sha256 `f93592f3…528f`, 3,960,832 B) differ in **773 bytes** across `.text`/`.rdata` — item114 is a
  substantially patched client, not merely item1 + trailing data.
- Mapped every differing byte to a VA. The seven UI input-gate functions are **byte-identical** (0 differing
  bytes in the first 0x400 of each): `FUN_00500820` (cursor reader), `FUN_005015F0` (per-event dispatch),
  `FUN_00501640`/`FUN_00501650`, `FUN_00501ED0` (widget-list), `FUN_005024A0`, `FUN_005025C0` (input gate).
- The 773 differences cluster elsewhere (largest at VA `0x66acd5` 550 B, `0x58d561` 136 B, `0x51adc0` 70 B) —
  the login/input-boundary patches this work dir exists to build, none inside the UI gate path.

Conclusion: since these gate functions are identical and demonstrably consume clicks for sibling managers
(lobby menu, session rows, WARP/exit dialogs) in item114, running item1 would not change the faction-panel
behaviour. The 次へ block is a **runtime state/data-flow** condition on the create-screen manager (its widgets'
`+0x08` not armed for this entry path), not a patched-code regression. Drop the item1 control run; the open
unit remains: locate the live active-screen (create) manager object and find the constructor/consumer that
should arm its `中止`/`次へ` widgets (`+0x08`), and why that does not fire for this session's entry path.

## 2026-09-03 — full-heap widget-manager scan does NOT locate the live faction manager (read-only, negative)

Built a read-only full-committed-memory scanner (`work/20260902-fresh-run-recovered-db/guest-rpm-heap-widget-scan.ps1`;
OpenProcess VM_READ|QUERY + VirtualQueryEx + ReadProcessMemory only) that treats every 4-aligned offset in
every committed readable region as a candidate manager base and reports objects whose widget records
(+0x4E8, 0x34 stride) carry constmsg-0x4E faction rows (帝国=45, 同盟=46, 次へ=47, 中止=80) with the
enable byte +0x08. Ran it in one run at the LOBBY (responsive) and at the FACTION screen (帝国 pre-selected;
`vnc-s04-faction.png`), reaching the faction screen only through responsive clicks (新キャラクター作成 →
session row), never poking 中止/次へ.

Result: the model does **not** isolate the live faction manager.
- Both scans returned an identical 639 candidates (210–211 MB, ~590 regions); 209 "plausible" (containing both
  帝国 45 and 同盟 46) at each; **zero** present at the faction screen but not the lobby.
- The matched objects' `+0x08` bytes are arbitrary large values (47, 16, 82, 149, 196, 255…), not the `{0,1}`
  enable flag. They are coincidental byte hits (rows 45/46/47/80 = ASCII `- . / P`, common in `.data`/mapped
  regions; the `+0x3F4` count-field model matches noise), and the strongest clusters sit in MEM_IMAGE `.data`
  near the static descriptor table (`0x0076Bxxx`), i.e. static descriptors, not live heap managers.

Conclusion: structural heap scanning for the (`+0x3F4` count / `+0x4E8` 0x34-records / `+0x08` enable /
`+0x15` row) model cannot find the create screen's live active manager — the model is either incomplete or the
active manager is tracked by a pointer this signature does not expose. Combined with the earlier findings
(the seven UI input-gate functions are byte-identical across item1/item114, and the cursor path is shared and
works globally since the lobby responds), the block is neither client-patch nor cursor-path nor server-wire.

Remaining tractable route (heavier, next session): a live *read-only debug attach* that sets an execution
breakpoint on the shared cursor reader `FUN_00500820` or the hit-test entry and, on a click over a RESPONSIVE
panel vs the inert 次へ, captures the manager `this` (register/stack) each path uses — that yields the live
faction manager address directly, from which `+0x08`/the enable predicate can be read. This needs a debugger
(hardware breakpoints via debug registers, no memory writes) rather than the RPM-only tooling, and must still
avoid process-memory writes and blind input. Do not run further create-flow input permutations first.

## 2026-09-03 — gate model CORRECTED by static disasm of FUN_005015F0 (the prior +0x08/+0x15 read was the wrong field)

Disassembled `FUN_005015F0` (the shared per-manager hit-test; confirmed shared: it is the sole caller of the
widget-list walker `FUN_00501ED0`@0x501631, the gate `FUN_005024A0`@0x501640, `FUN_005025C0`@0x501650, and the
cursor reader `FUN_00500820`@0x501669) and the two gate functions. Corrected model:

`FUN_005015F0(this = manager[ecx], arg1 = ctx[esp+0x2c])`:
1. entry gate: `if (ctx->byte[0x08] == 0) { log 0x779BA8; return 0; }` — the `+0x08` checked here is on the
   **ctx/event argument**, NOT on a widget record.
2. `FUN_00501ED0(ctx, …)`: walks the widget list **on ctx** — count `ctx+0x3F4`, handle array `ctx+0x470`
   (4B entries), records `ctx+0x4E8` (0x34B = 13 dwords each). (Layout re-confirmed from the disasm.)
3. per-manager gate `FUN_005024A0`: `return manager->byte[0x05]` (this=manager). `if 0 → skip (0x501a7a)`.
4. `FUN_005025C0(ctx)`: `if ctx->byte[0x08] != 0 return ctx->byte[0x15] else 0`.
5. only then reads the cursor via the input owner `0x022142A8`.

**Consequence — the earlier RPM conclusions were reading the wrong object.** The "widget `+0x08`/`+0x15`"
the prior probes and template scan read are record bytes on the ctx widget array; the actual input-enable the
faction panel fails is **`manager->+0x05`** (a separate object, the hit-test `this`) and/or **`ctx->+0x08`**
(the widget-holder header). Neither was ever read live. The heap-scan negative result is consistent: it keyed
on record `+0x08`, which is not the gate.

`manager->+0x05` is written by the setter at `0x5024b0` (67 call sites across the UI = per-screen arm/disarm).
The create/faction screen's arming call is one of those 67; the block is either that the faction manager's
`+0x05` is never set (its arming caller does not run on this entry path) or that `ctx->+0x08` on the faction
widget-holder is 0.

Revised next unit (read-only, precise):
1. Read-only debug attach (hardware BP via debug registers, no memory writes; WOW64 context since the client
   is 32-bit) on `FUN_005015F0`@0x5015F0; collect each hit's `ecx` (manager) and `[esp+0x2c]` (ctx) at the
   faction screen. The faction manager is the `this` whose ctx widget records carry `+0x15` ∈ {45,46,47,80}.
2. For that manager read `+0x05`; for its ctx read `+0x08`. Compare with a responsive manager (lobby menu).
3. Whichever is 0 is the gate; trace back to the `0x5024b0` setter call (or the ctx `+0x08` writer) that
   should arm it on the create path, and why it does not fire for this session's session-server entry.
The RPM-only heap scan cannot do this (it cannot tie a manager `this` to its ctx); the debug attach can.

## 2026-09-03 — read-only hardware-breakpoint attach probe CRASHES the client (negative); live-manager capture not safe with current tooling

Built a read-only debug-attach probe (`work/20260902-fresh-run-recovered-db/guest-hwbp-manager-probe.ps1`):
DebugActiveProcess + a HARDWARE execution breakpoint on `FUN_005015F0` via debug registers (DR0/DR7 through
WOW64 thread context; no target memory writes), capturing each hit's manager `this` (Ecx) and ctx (stack
arg2) to read `manager+0x05` and the ctx widget rows. Two live attempts at the faction screen; both **crashed
the client** (WER: `EventType=BEX`, `Sig[7]=c0000005` access violation on `G7MTClient.item114.exe`).

Cause: `DebugActiveProcessStop` does not clear the debuggee's hardware breakpoints, so any thread left with
DR7 set faults (unhandled `EXCEPTION_SINGLE_STEP`) once the debugger is gone. The client spins threads during
the probe (13 `CREATE_THREAD` events in ~10 s), so arm/disarm races leave a straggler (`disarmFailed` recorded
one). Adding suspend-based arm/disarm (suspend thread → write DR → resume, with post-write verify) did not
prevent the second crash. On a 32-bit D3D game this attach path is repeatably fatal (2/2); repeating it is
unsafe and burns a run each time.

Secondary observation: with the BP armed for ~10 s while only hovering the mouse over responsive panels,
`FUN_005015F0` fired **zero** times (`singleStep=0`). So the hit-test dispatch runs on actual click events,
not on hover/frame — a bare capture needs a real click delivered while attached, which compounds the crash risk.

Net for condition 2 this session: the gate FIELD is settled (static) — per-manager input enable is
`manager->+0x05` (getter `FUN_005024A0`, setter `0x5024b0` with 67 call sites), with a secondary `ctx->+0x08`
check — but LOCATING the live faction manager is blocked on both available read-only routes: RPM heap-scan
cannot tie a manager to its ctx, and hardware-BP attach crashes the client. 

Safest remaining route (pure static, no live run): classify the 67 callers of the `+0x05` setter `0x5024b0`
by the screen/manager each arms (via their enclosing function and the constmsg rows / vtable they reference),
identify the create/faction-screen arming caller, and check statically whether it is on the session-server
(0x0201) entry path the client actually takes — i.e. why the faction manager's `+0x05` is never set for this
entry. That avoids the client-crash risk entirely and is the recommended next unit. A live debug capture, if
ever retried, must stop the whole process and re-enumerate every thread to clear DR7 atomically before detach.

## 2026-09-03 — static call-chain for the +0x05 arm/disarm (safe RPM route found; no debugger needed)

Grouped the 67 `0x5024b0` (`manager+0x05` setter) call sites into 22 enclosing functions. One is a dedicated
**arm/disarm toggle** `FUN_0053c090(this, enable)`: it writes the `enable` arg to `+0x05` of FOUR sub-objects
of `this` — `this+0x24`, `this+0x14e822c`, `this+0x14e8b2c`, `this+0x14e942c` (four panels toggled together;
the huge offsets mean `this` is a large context object, not an array). Its four callers:

| caller | enable | this |
|---|---|---|
| `0x52c4d1` | 1 (arm) | global `[0x00cb0038]` (armed only if that global is non-null) |
| `0x52c71c` | 1 (arm) | global `0x00cb0038` |
| `0x53a23c` | 0 (disarm) | local `esi` |
| `0x53a840` | 1 (arm) | local `esi` |

So a fixed global at **`0x00CB0038`** is (a candidate for) the create/faction screen context whose four
panels' input-enable `+0x05` this toggle arms. This gives a **crash-free, read-only** confirmation path that
avoids the debugger entirely:

Next unit (safe, RPM only — no hardware BP, which crashes this client 2/2):
1. At the live faction screen, RPM-read the pointer at `0x00CB0038`; if non-null, read that object's
   `+0x24`, `+0x14e822c`, `+0x14e8b2c`, `+0x14e942c` sub-pointers and each sub-object's `+0x05` byte.
2. Compare with a responsive screen (lobby). If the faction panels' `+0x05` are 0 (or `0x00CB0038` is null),
   the arm toggle `FUN_0053c090(…,1)` never ran for this entry — confirming the gate and localizing the fix
   to why the `0x52c4d1`/`0x52c71c` arm path (or the `esi` arm at `0x53a840`) is skipped on the session-server
   (0x0201) entry.
3. Statically, check whether `FUN_0053c090`'s arm callers are reachable from the 0x0201 consumer
   (descriptor table `0x00766EF0` index 1); the disarm caller `0x53a23c` vs arm `0x53a840` share a local
   `this`, so find what sets that `this` and its arm/disarm branch condition.

Other dense arm-toggle functions (screen constructors, for reference): `0x51a370` (12 arms), `0x52f700` (10),
`0x54a3d0` (6), `0x543570`/`0x5444c0`/`0x549480`. `FUN_0053c090` is the strongest faction-screen candidate
(exactly four panels). This whole chain was found statically; the `0x00CB0038` RPM read is the next safe step.

### offset correction (byte-verified) — the four arm targets are FIXED absolute addresses

`FUN_0053c090`'s `this` is the constant `0x00CB0038` (caller `0x52c4d1`: `mov edx,0xCB0038; mov ecx,edx`), a
`.data` global (0x75e000..0x3353000). Byte-verified loads: `8B4E24` = `[this+0x24]`, `8B8E2C824E01` =
`[this+0x014E822C]`, `[this+0x014E8B2C]`, `[this+0x014E942C]`. So the four armed sub-objects are pointers at
FIXED absolute addresses (crash-free RPM reads, no debugger):
`p0=*(0x00CB005C)`, `p1=*(0x01FB8264)`, `p2=*(0x01FB8B64)`, `p3=*(0x01FB9464)`; the gate is each `*(pN)+0x05`.
RPM these at the faction screen vs lobby: any that is 0 (or pN null) is the un-armed faction panel.

### RPM result (run 20260902T162633Z) — the global 0x00CB0038 arm path is NOT the faction gate (excluded)

Ran the crash-free RPM probe `guest-rpm-arm05-probe.ps1` (read-only) at the lobby and at the faction screen.
Both states: all four pointers `*(0x00CB005C)`, `*(0x01FB8264)`, `*(0x01FB8B64)`, `*(0x01FB9464)` are **null**
(so `+0x05` unreadable). The `0x00CB0038` context header is zero except `+0x20 = 0x63`. Since these panels are
unallocated on BOTH screens, `FUN_0053c090`'s global arm path (callers `0x52c4d1`/`0x52c71c`, this=0x00CB0038)
is not the create/faction screen and is **excluded**. No client crash (RPM is safe, unlike the hardware-BP).

Remaining candidate: the LOCAL-`this` arm/disarm pair — `FUN_0053c090` callers `0x53a840` (arm, ecx=esi) and
`0x53a23c` (disarm, ecx=esi), both inside the same enclosing function; that `esi` is not a fixed address, so
confirming it needs the live `this` — which reintroduces the debugger problem. Next unit: statically resolve
where that `esi` comes from (its constructor / the field that holds it) and whether the arm branch `0x53a840`
is reached on the 0x0201 session-server entry, or find another of the 22 setter functions whose armed objects
are reachable at fixed addresses for a safe RPM check. Do NOT use the hardware-BP attach (crashes the client).

## 2026-09-03 — faction-screen gate LOCALIZED to a fixed global state field (safe RPM confirmable)

Traced the faction-screen handler chain statically (item1):
- `0x4ff1cd: mov ecx,0x00C9E638; call FUN_004fd100` — the top-level context is the FIXED global **`0x00C9E638`**.
- `FUN_004fd100(this=0xC9E638)` is the frame dispatcher; it calls the faction handler as
  `lea ecx,[this+0x11a00]; call FUN_00539ce0` → **factionCtx = 0xC9E638 + 0x11A00 = `0x00CB0038`** (exactly the
  global whose four panel pointers RPM already read as null — consistent: the panels are unallocated because
  the handler bails before arming them).
- `FUN_00539ce0(this=factionCtx)` gates its input processing + panel arm on, at entry:
  `mov eax,[this+0x24]; test byte[eax+4]; jz bail`, then **`cmp dword[this+0xA08],3; jne bail`**, then
  `call FUN_0053c1e0; cmp al,1; je bail`. Only past all three does it run the hit-test (`push 2; call
  FUN_005015F0`) and the panel arm/disarm (`FUN_0053c090`). So the faction buttons are inert whenever
  `*(0x00CB0038+0xA08) != 3` (or the `+0x24`/`FUN_0053c1e0` sub-conditions fail).

This is a FIXED-address gate → confirmable read-only, no debugger:
- gate: `*(0x00CB0A40)` (= factionCtx+0xA08) must equal 3.
- also read `*(0x00CB0038+0x24)` then `+4`, and `*(0x00CB0038+0x14E0)` (dispatcher sets it to 3 at 0x4fd276).
Compare faction vs lobby. Whichever sub-condition is false localizes the fix to the code that should set it on
the 0x0201 session-server create entry. `FUN_0053c1e0` (the third gate) is the next function to read.

### RPM result (run 20260902T163435Z) — 0x00CB0038 subsystem is inactive on BOTH screens (excluded as faction)

Crash-free RPM at lobby AND faction: `*(0x00CB0038+0xA08)=0`, `*(0x00CB0038+0x14E0)=0`, `*(0x00CB0038+0x24)=null`
in both. So the whole `0x00CB0038` subsystem (topCtx `0x00C9E638` + `0x11A00`, handler `FUN_00539ce0`) is
uninitialised regardless of screen — it is NOT the faction screen. The static chain
`FUN_0053c090`→`FUN_00539ce0`→`0x00CB0038` (reached via the 4-panel arm-toggle guess) followed the wrong
subsystem. Excluded, no crash.

State of condition 2 after this session: the gate FIELD is confirmed (`manager+0x05`, getter `FUN_005024A0`),
but the faction screen's live manager/context is still unlocated by safe means — fixed-address RPM found only
inactive/unrelated globals, and the only route that captures the live `this` (hardware-BP attach) crashes the
client. Next safe unit: identify the faction subsystem from the RENDER side — find the function that draws the
帝国/自由惑星同盟 radios or the 中止/次へ buttons (their bitmaps / hit rectangles) and back-trace its context
global, rather than guessing from the 22 arm-toggle functions; or enumerate the other sub-handlers the frame
dispatcher `FUN_004fd100` calls (`this+0x50d8`→`FUN_005794d0`, `this+0x6f54`→`FUN_0052f700`, etc.) and RPM
each sub-context's `+0xA08`-style state at the faction screen to find the one that activates there.

### RPM ctx-scan (run 20260902T163948Z) — the faction screen is NOT in the main frame dispatcher

Enumerated the 8 sub-handler contexts of the frame dispatcher `FUN_004fd100` (topCtx `0x00C9E638`) and RPM-read
each context header at lobby vs faction. Result: no context activates on faction entry — only `0x00CA3710`
(`FUN_005794d0`, 3 non-zero header bytes) and `0x00CB0038` (`FUN_00539ce0`, 1) are non-zero, IDENTICAL on both
screens; the other six are all-zero on both. So the create/faction screen is **not** a sub-handler of the main
game frame loop (topCtx `0x00C9E638`). The whole `0x5024b0`-setter → arm-toggle → main-dispatcher static
thread this session does not reach it.

Corrected direction for the next session (avoids the wrong subsystem and the client-crashing debugger):
the faction screen is driven by the SESSION-SERVER connection (a second socket / context), reached only after
the 0x0201 session-server login. Start from the 0x0201 consumer (descriptor table `0x00766EF0` index 1) and
statically trace the screen it constructs/activates to that context's global; then RPM that context's state at
the faction screen (crash-free) to find the `+0x05`/enable gate. The main-frame contexts above are excluded.

## 2026-09-03 — session-server message names decoded (protocol asset) + new server-side hypothesis

The table `0x00766EF0` is the session-server message NAME table (string pointers, not handlers; xref only from
the name/log helper `0x44f151`, and `sub eax,0x200` at `0x44f071`/`0x44f0d1`). Byte-verified names by index:

| opcode | name |
|---|---|
| 0x200 | SSLoginRequest |
| 0x201 | SSLoginOK |
| 0x202 | SSLoginNG |
| 0x203 | SSCharacterIDRequest |
| 0x204 | SSCharacterIDResponce |
| 0x205 | SSGameLoginRequest |
| 0x206 | SSGameLoginOK |
| 0x207 | GlobalChat |

So after the session-server login (0x200→0x201) the create flow expects a **character-ID exchange
(0x203 SSCharacterIDRequest → 0x204 SSCharacterIDResponce)** and a **game login (0x205 → 0x206)**. Prior wire
traces ended at `SessionServerReady` (0x201 SSLoginOK) with nothing after. New leading hypothesis (supersedes
the "purely client-side gate" reading for a server-testable one): the faction panel's `+0x05` arm is triggered
by receipt of `0x204 SSCharacterIDResponce` (or `0x206`), which the authority (v128/v129) never sends — so the
panel never arms and 次へ is inert. This is consistent with all prior facts (no wire on 次へ click = the click
is never hit-tested because the panel is not armed; the panel is not armed because the expected session-server
follow-up never arrived).

Next unit (two cheap checks, then fix):
1. From a faction-screen run, inspect `server-wire.jsonl` for whether the client SENDS `0x203
   SSCharacterIDRequest` after 0x201 (client→server). If it does and the server is silent, the gate is a
   missing server `0x204` response.
2. Statically find the client's session-server RECEIVE dispatcher (jump-table on the opcode; not the name
   table) and the `0x204`/`0x206` handlers, and confirm which one arms the faction panel (`manager+0x05` /
   `FUN_005024A0` setter path). 
3. If confirmed, implement the authority's `0x203→0x204` (and `0x205→0x206`) exchange in `Logh7.Server`
   (a `NEW_DESIGN` for the lost original server rule), then re-run: the faction 次へ should arm and advance.
This is now a joint client-RE + authority unit with a concrete, server-side testable fix path — no debugger.

### session-server class vtable located (next-session anchor)

`FUN_0044F060` (opcode→`this+4+idx*4` array lookup, `this`=session-server object) has no direct callers — it
is a virtual method. Its address sits in a vtable at **`.rdata 0x0066D13C`** (adjacent slots: `0x0066D13C`
=`FUN_0044F060`, `0x0066D140`=?, `0x0066D144`=`FUN_0044F0C0`). Next-session chain: scan back from `0x0066D13C`
to the vtable head, find the constructor that writes `[obj+0]=vtableHead` → that `obj` is the session-server
context; read its `+4` array (the 8 opcode entries), and check the `0x204`/`0x206` entries and whether their
handler arms the faction panel (`manager+0x05` via the `FUN_005024A0` setter path). Combined with the wire
check (does the client send `0x203 SSCharacterIDRequest`?), this settles whether the fix is an authority
`0x203→0x204` / `0x205→0x206` exchange. All static/RPM — no client-crashing debugger.

### vtable-head via 0-boundary is imprecise — use RTTI next

`FUN_0044F060`'s vtable slot is `.rdata 0x0066D13C`, but walking left to a null dword lands at `0x0066C028`
(1094 slots up) — several vtables are packed with no null separators, so that is a table-group start, not this
class's head; its refs `0x00403B3C`/`0x00403BB7` are likely a different class. To pin the session-server
class precisely, use RTTI: the dword at (its real vtable head − 4) is the Complete Object Locator → type
descriptor name (expect an `SS`/session-server class name). Scan `.rdata` near `0x0066D13C` for the COL
pointer (a `.rdata`/`.data` address, not code) that precedes the run of code pointers containing `0x0066D13C`;
from the COL read the class name and the constructor that installs the vtable, then that object is the
session-server context. Then read its `+4` opcode array (0x204/0x206 handlers) and tie to the faction arm.

## 2026-09-03 — authority ALREADY implements the full session-server exchange (server-side hypothesis REFUTED)

Two live facts + one static fact this round:
- **Session server is a SEPARATE listener** `127.0.0.2:47900` (wire `listener-ready`:
  `sessionBindAddress/sessionAdvertiseAddress=127.0.0.2`); `server-wire.jsonl` logs only the LOBBY listener
  (`202.8.80.179:47900`), so faction-screen (session-server) traffic is NOT in that file.
- A run that stayed at the lobby (session-row click missed) showed `127.0.0.2:47900` in LISTEN with **no
  ESTABLISHED** connection and no session/character lines in `server.stdout` — i.e. no session-server
  connection was made (consistent with not reaching the faction screen).
- **Authority source (`natural-authority-d02/.../NaturalAuthoritySession.cs`) already handles the whole
  session-server protocol**: `OriginalSessionServerCodec` defines `LoginAccepted 0x0201`,
  `CharacterContextRequest 0x0203`→`CharacterContextResponse 0x0204`, `GameLoginRequest 0x0205`→
  `GameLoginAccepted 0x0206`. Lines 418-440 answer `0x0203` with `EncodeCharacterContext` (worldCharacterId,
  or 0 for an empty account); lines 442-486 answer `0x0205` with `EncodeGameLoginOk` + world-entry pushes.

So the earlier "authority never sends 0x204/0x206" hypothesis is **refuted** — the server does respond. The
blocker is narrowed to the CLIENT/session-server link: (a) is a session-server connection to `127.0.0.2:47900`
even established when the faction screen shows, and (b) does the client send `0x0203` and arm the faction
panel on `0x0204`? These need SESSION-SERVER wire visibility, which the lobby-only `server-wire.jsonl` lacks.

Next unit: add session-server wire logging (or confirm whether NaturalAuthoritySession already logs the
`127.0.0.2` connection under a different sink), reach the faction screen for real (the session-row click is
timing-sensitive — verify each nav step by capture), and capture the `127.0.0.2` exchange: 0x200→0x201, then
whether 0x203/0x204/0x205 flow. If the client never opens `127.0.0.2` or never sends 0x203, the gate is in the
client's session-server connect/handshake on the create path, not the panel arm. (Authority session-server
protocol is confirmed complete; do not re-investigate it.)

## 2026-09-03 — session-server link IS established; blocker precisely localized to client 0x201 post-processing

Reached the faction screen for real (run 20260902T165843Z, `vnc-n04-faction.png`) and captured live TCP +
wire:
- **Session-server TCP is established**: client (pid 4872) `127.0.0.1:49996 → 127.0.0.2:47900 ESTABLISHED`,
  authority (pid 6172) the other end. So the create screen runs over a live session-server connection.
- **`server-wire.jsonl` DOES log the session-server connection** as `connectionId 3` (the earlier "lobby-only"
  reading was because the earlier run never reached the faction screen). Connection 3 sequence:
  outerControl 52→53, 54, then `observedApplicationType 32` → AwaitLobbyLogin, then
  **`observedApplicationType 512` (=0x200 SSLoginRequest) → SessionServerReady** with a 16-byte response,
  then **silence** — the client sends nothing more on the session-server socket.
- Authority handles 0x200 by consuming the handoff token → `SessionServerReady` → `EncodeLoginOk()` =
  `[00,00,00,00,02,01,01]` (7-byte stub, 16 bytes with the lobby prefix). It is ready to answer 0x203→0x204
  and 0x205→0x206 but the client never sends them.

**Blocker (precise):** after receiving `0x201 SSLoginOK`, the client shows the faction screen but neither arms
the panel (`manager+0x05`) nor sends `0x203 SSCharacterIDRequest`. Two candidates:
(A) the client's `0x201` receive-handler should auto-send `0x203` (or arm the panel) but a field it needs is
    absent from the 7-byte stub `EncodeLoginOk` (original SSLoginOK likely carried more) — server-fixable; or
(B) the panel arm / `0x203` send is gated on a `次へ` press, which is itself un-armed — client-side.
The total silence after 0x201 (no auto follow-up) leans toward (A).

Next unit: RE the client's session-server RECEIVE handler for `0x201 SSLoginOK` (the vtable at `.rdata
0x0066D13C`; `FUN_0044F060` returns the per-opcode entry from `sessionObj+4+idx*4`, idx1 for 0x201) — does it
parse the response body and auto-issue `0x203`, or set the faction panel's `+0x05`? If it reads response
fields the stub lacks, extend the authority `EncodeLoginOk` to the original shape (a `NEW_DESIGN` informed by
the client's parser) and re-run. Session-server TCP + 0x200/0x201 are confirmed live; authority 0x203/0x204/
0x205/0x206 are confirmed implemented — focus only on the client's 0x201 post-processing.

### client 0x201 static trace hits a data-driven wall (WM_LBUTTONDOWN false lead)

The only `cmp eax,0x201` (`0x65395e`) is a WINDOW-MESSAGE preprocessor: it pairs 0x201 (WM_LBUTTONDOWN) with
0xA1 (WM_NCLBUTTONDOWN) and 0x100..0x108 (keyboard), not the session-server opcode. So the client's
session-server `0x201 SSLoginOK` handler is NOT reachable via an immediate opcode compare — session-server
dispatch is data-driven (the `FUN_0044F060` `sessionObj+4+idx*4` array / the `.rdata 0x0066D13C` vtable), with
no immediate anchor, and the image appears built without RTTI (no COL before the vtable run), so the class
cannot be pinned by type name either. The safe read-only routes are now exhausted for this specific step: RPM
needs a fixed address (the session object is heap/vtable-reached), and the hardware-BP attach crashes the
client.

Recommended next units (either avoids the static wall):
1. **Empirical server experiment (own lane, no codex conflict):** in the scratch authority
   (`work/20260902-notify-message-codec/server-scratch`), extend `OriginalSessionServerCodec.EncodeLoginOk`
   from the 7-byte stub to progressively richer shapes and re-run; watch whether the client then auto-sends
   `0x203` (visible as a new `connectionId 3` frame after SessionServerReady in `server-wire.jsonl`) or arms
   `次へ`. This directly tests hypothesis (A) without needing the client's parser, and is fully reversible.
2. **Safe runtime capture of the session dispatch:** develop a non-crashing observation of `sessionObj+4`
   (e.g. find the session object via its socket/FD field rather than a debugger) to read the per-opcode
   entries; only if (1) is inconclusive.
Confirmed this session and NOT to be re-done: session-server TCP + 0x200/0x201 are live; authority
0x203/0x204/0x205/0x206 are implemented; the client goes silent after 0x201; the gate is the client's 0x201
post-processing which depends on the SSLoginOK body.

### grounded hypothesis for the empirical fix — session-server SSLoginOK zero prefix vs lobby's code

Compared the two LoginOK encoders in the authority:
- **Lobby** `OriginalLobbyCodec.EncodeLoginOk(code)` = 9 bytes `[code(4)][0x0201 BE][00 00 00]` — the first 4
  bytes carry the real lobby/session code. The client processes this and the lobby is fully responsive.
- **Session-server** `OriginalSessionServerCodec.EncodeLoginOk()` = 7 bytes `[00 00 00 00][0x0201 BE][01]` —
  the first 4 bytes are ZERO (a stub); `EncodeGameLoginOk` is the same shape with `0x0206`.

Grounded hypothesis (no longer blind): the client needs the SSLoginOK's leading 4-byte field (a session
code/token) to issue `0x203 SSCharacterIDRequest`; the authority sends zeros, so the client goes silent after
0x201. This matches the lobby-vs-session asymmetry (lobby has code and works; session-server has zeros and
stalls) and the wire (client silent right after SessionServerReady).

First experiment next session (own scratch authority lane, reversible): make
`OriginalSessionServerCodec.EncodeLoginOk` echo a non-zero leading code — candidates in priority order:
(1) the client's own `HandoffToken` from `DecodeLogin` (echo-back), (2) the lobby `_lobbyCode`,
(3) the `_lobbySelectionValue`. Rebuild the scratch server, deploy, reach the faction screen, and watch
`server-wire.jsonl` for a new `connectionId 3` frame after SessionServerReady (client sending 0x203) or the
`次へ` arming. If any code value unblocks it, that identifies the field; then port the fix and validate the
full create→persist→relogin path (conditions 2, then 4 via a second faction on a second account).

## 2026-09-03 — empirical server experiment: SSLoginOK leading code does NOT arm the faction panel (hypothesis A refuted)

Built the scratch authority (`work/20260902-notify-message-codec/server-scratch`, self-contained win-x64,
migration 0011 hash matches so the harness accepts it), added an env-selected `EncodeLoginOk` leading 4-byte
code (`LOGH7_SS_LOGINOK`: token=client HandoffToken, conn=pending wire token, sel=session selection, one=1),
and wired it through `host-run-fresh-run.ps1` / `guest-prepare-fresh-run.ps1` (`-SsLoginOk`, additive). Deployed
via `-HostServerZipPath` with matching exe/dll/zip hashes; the experiment server ran (zip sha `7C9A3E92…`).

Two runs to the faction screen:
- `-SsLoginOk token` (run 20260902T172116Z): reached faction, `次へ` still inert; wire still shows connection 3
  = 0x200→SessionServerReady(0x201,16B) then silence.
- `-SsLoginOk sel` (run 20260902T172742Z): `sel` = the session selection value, definitely non-zero; reached
  faction, `次へ` STILL inert.

Since a definitely-non-zero leading code (`sel`) did not arm `次へ` and produced no new client frame, the
faction-panel arm is **NOT triggered by the SSLoginOK leading 4-byte field** — hypothesis A is refuted, and
hypothesis B is confirmed: the faction/`次へ` arm is a CLIENT-internal UI condition, independent of the
session-server `0x201` response content. The client accepts 0x201 (it renders the faction screen) but arms the
panel on some other client-side state. Server-response manipulation cannot unblock it.

Net: the create-flow blocker is now firmly a CLIENT UI-arm problem (not server-side). The reusable experiment
harness (scratch authority build → `-HostServerZipPath` + `-SsLoginOk`) remains available but the SSLoginOK
body is excluded. Remaining route (next session): a crash-free runtime observation of the client's faction
panel object (its `+0x05`) — e.g. locate the session/UI object via a non-debugger handle, since RPM heap-scan
and hardware-BP both failed. Do NOT pursue further SSLoginOK body variants (leading-code path is disproven).

## 2026-09-03 — the decisive experiment is a v5-server reproduction (08-30 armed 次へ; v128 does not)

Re-read the 08-30 success handoffs (`natural-authority-d02/docs/handoffs/2026-08-30-item1-{first,second}-create-screen.md`):
- On 08-30 the first create (faction) screen **enabled 次へ**, and one `次へ` activation showed the gender
  screen; "no additional server application message was needed between these two UI screens." Client item1
  (SHA `FCAC7942…`, identical to today's), server **v5** (dll `FA85EC91…`, zip `D845712F…`).
- So on v5 the faction panel WAS armed; on v128/v129 it is not — with the SAME client. Yet earlier static
  diffs claimed the v5 vs v128 `0x201` SSLoginOK and `0x200A` session-login responses are byte-identical, and
  this session's server experiment showed the SSLoginOK leading code is irrelevant. That is a contradiction:
  something in v5 vs v128 (or the DB/handoff state they produce) arms the panel, and it is NOT the fields
  compared so far.

Decisive next experiment: **run the v5 server on the current fresh-run harness** and see if 次へ arms.
- v5 zip found and hash-verified: `natural-authority-d02/work/20260830-login-input-boundary/
  logh7-server-world-handoff-v5-win-x64.zip` (`D845712F…`); its `Logh7.Server.exe` = `D214CF57…` (same as the
  harness default, a shared self-contained host), `Logh7.Server.dll` = `FA85EC91…`.
- Blocker to running it: v5 ships only migration `0001`; the harness requires `0011_original_grid_unit.sql`
  (`FRESH_RUN_MIGRATION0011_INVALID`). Options: (a) add `0011` into a v5 working-copy zip and pass its exe/dll
  hashes + the 0011 hash (watch for a re-apply conflict on the already-0011 recovered DB), or (b) add a
  `-SkipMigrationCheck`-style additive switch to `guest-prepare` for this experiment only.
If v5 arms 次へ where v128 does not, diff v5 vs v128 `Logh7.Server.dll` on the create/session-handoff path to
find the exact behavioural difference (the earlier "byte-identical response" comparison missed something —
likely the `0x2009`/`0x200A` session-selection handoff or a lobby-redirect field, not the `0x201` body). If v5
also fails to arm, the variable is the DB/account initial state or the input method, not the server.
This is the single highest-value next unit for condition 2; the SSLoginOK-body path is disproven and excluded.

## 2026-09-03 — v5 08-30-condition reproduction: 次へ STILL inert (server AND input method excluded)

Reproduced the 08-30 success conditions as closely as possible and drove the create flow:
- v5 server (dll `FA85EC91…`, the exact 08-30 build) via `-HostServerZipPath` + `-SkipMigrationCheck` (v5 ships
  only migration 0001; recovered DB already has 0011 so v5 leaves it alone). v5 ran on the recovered DB fine.
- First tried the recovered (character-holding) account: v5 rejected the lobby roster with
  `original.lobby.populated-character-codec-not-implemented` and the client showed the 切断 (disconnect) dialog.
  So v5 only supports EMPTY accounts — exactly the 08-30 setup.
- Then `-ProvisionNewAccount` (v5 accepts `account-provision-disposable`): fresh empty account, v5 catalogue
  showed the single `LOGH7 / UC796` session (identical to 08-30, vs v128's LOGH7-1/LOGH7-2), reached the
  faction screen (帝国 default selected).
- `次へ` activation attempts on this exact-08-30-condition screen: **mouse click, Enter, Space, Tab+Space —
  ALL inert.** No transition, no wire.

Also decisive: the 08-30 credential receipt shows `"transport":"VNC physical key events"`, `"clicks":0` — 08-30
used keyboard only, no mouse. Yet keyboard (Enter/Space/Tab+Space) is inert here too.

Conclusion: with server (v5 == 08-30), client (item1 == 08-30), account (fresh empty), and session catalogue
(UC796) all matched to 08-30, and every input method (mouse + keyboard) inert, the ONLY remaining difference
from 08-30 is the **database** (this run copies the recovered `121817Z` cluster that v128 created; 08-30 used
its own era DB) — or the 08-30 "次へ activated" observation was not reliably reproducible. Server response,
server build, client build, account state, session catalogue, and input method are all now EXCLUDED as the
cause of the un-armed faction panel.

Next unit (highest value): reproduce with an 08-30-era / empty freshly-initialised database (not the v128
recovered cluster) under v5, to isolate whether the DB is the last variable; in parallel, the client-side
faction-panel arm (`manager+0x05`) still needs a crash-free runtime read (non-debugger). If a clean empty DB
under v5 arms 次へ, the blocker is a DB/authority-state precondition the recovered cluster does not satisfy;
if it still fails, the 08-30 record itself is suspect and the arm is a client-only condition to be found by
runtime observation.

## 2026-09-03 — 08-30 success is GENUINE (gender capture verified); reproduction still fails → last variables

Verified `20260830T065839Z/second-create-screen.png`: it is a real gender screen (`性別を選んで下さい。`, 男/女,
中止/次へ). So the 08-30 "次へ activated → gender screen" record is trustworthy — on 08-30 the faction 次へ
worked. Yet this session, matching 08-30's server (v5), client (item1), account (fresh empty), session
catalogue (UC796) and trying every input method, 次へ stays inert. Since the faction screen itself is reached
normally (login/session/UI all fine), a DB-schema difference is an unlikely cause of the arm failure.

Two residual differences from 08-30 remain, for the next session:
1. **Input transport end-to-end.** 08-30's `credential-submission-receipt.json` shows the WHOLE flow used
   `"transport":"VNC physical key events"`, `clicks:0` — keyboard only, no mouse, for the entire session
   including (implicitly) `次へ`. This session used `guest-submit-credential` (user32 SendInput) for the
   credential and VNC for `次へ`. It is a stretch that credential transport affects the later faction arm, but
   it is a real uncontrolled difference; reproduce 08-30 exactly = do credential AND `次へ` purely via vncdo
   physical key events (the 08-30 sequence: empty-id backspace prime, held login chars, Tab, held password,
   Enter), then on the faction screen use keyboard only.
2. **DB** (recovered v128 cluster vs 08-30-era DB) — weaker now that the faction screen renders fine.

Highest-value next unit: replay the EXACT 08-30 input transport (all vncdo physical keys, zero mouse) under v5
+ fresh account, since that is the one procedure difference not yet reproduced. If 次へ then arms, the blocker
was the input path all along (the client's faction panel ignores SendInput-tainted sessions or mouse, and
needs the physical keyboard route); if it still fails with byte-for-byte 08-30 procedure, escalate to a
crash-free client-side runtime read of the faction manager `+0x05`. Everything else (server build/response,
client build, account, session catalogue) is excluded and must not be re-tested.

## 2026-09-03 — 08-30 SUCCESS wire == today's FAILURE wire → condition 2 is purely client-side (server fully excluded)

Read the 08-30 success wire (`20260830T065839Z/server-wire-second-screen-pass.jsonl`). Its connection 3
(session server) is: `oc52→53, oc54, oc48 app32 →AwaitLobbyLogin, oc48 app512 (0x200) →SessionServerReady
(0x201, 16B)` — then NOTHING. The faction→gender transition on 08-30 produced no further wire, byte-for-byte
the SAME session-server flow as this session's failing runs. So on 08-30 the 次へ worked and reached the gender
screen with the identical wire where today it is inert.

Therefore, with server build (v5), client (item1), account (fresh), session catalogue (UC796), AND the wire
all identical between the 08-30 success and today's failure, the faction-panel arm is **entirely client-side
and server-independent** — the server is now fully excluded (not just its response content). 08-30's 次へ input
method was never recorded, and every input method tried this session is inert, so the difference is a client
runtime state that 08-30 happened to have and today's runs do not, with no recorded server/DB/input cause.

Definitive remaining route (only one left): a crash-free, non-debugger runtime read of the live faction
manager's `+0x05` (and the code that should set it) to see the arm condition directly — RPM needs the object's
address (heap/vtable-reached; RTTI absent), and the hardware-BP attach crashes the client, so the open task is
a safe way to obtain the faction manager pointer at runtime (e.g. via a UI/registry global reachable read-only,
or a minimal single-shot breakpoint that restores DRs atomically before detach). Everything else — server
build/response/wire, client build, account, session catalogue, and input method families — is EXCLUDED and
must not be retested. Condition 2 is a client-UI-arm RE problem, fully localized, awaiting a safe live probe.

## 2026-09-03 — hardware-BP probe DOUBLY excluded (crashes 3/3 AND blocks input while attached)

Improved the read-only hardware-BP probe (disarm now re-enumerates ALL process threads from a fresh snapshot,
retry loop) and ran it while clicking the faction buttons to trigger `FUN_005015F0`:
- `hits=0, singleStep=0` — the BP never fired even with 8 faction/lobby clicks during attach. Cause: while a
  debugger is attached, the client only runs between `ContinueDebugEvent` calls, so its window message pump
  does not process the injected VNC clicks in time to reach the hit-test. A debugger attach and live input
  are mutually exclusive here.
- `disarmed=0` and the client (pid 2676) was GONE afterwards — crash 3/3. The all-threads disarm still
  reported zero cleared (the enumerate-at-detach path did not apply the DR writes), leaving DR7 set → crash.

So the hardware-BP route is doubly disqualified: it crashes this 32-bit D3D client every time, AND it freezes
input so it cannot capture a click-driven hit anyway. It must not be retried.

The ONLY viable remaining approach for the faction manager `+0x05` is a read that needs NO debugger and NO
process suspension: find the faction manager pointer through a stable read-only path and RPM it while the
client runs normally. Candidate anchors (all read-only, crash-free) for next session:
- the session-server object (its socket to `127.0.0.2:47900` is a live handle; walk from the client's socket
  table / the `FUN_0044F060` vtable `0x0066D13C` instance to the create-screen UI it owns), or
- the input owner global `0x022142A8` / uiRoot `0x02215E2C` — but model the ACTIVE-screen manager list
  correctly this time (previous scans found only fixed templates; the live create manager is reached via the
  session-server context, not uiRoot).
Condition 2 remains: server fully excluded, client faction-panel `+0x05` arm unresolved, and the safe
runtime-read tool (non-debugger) is the sole open task. Do not retry hardware breakpoints or SSLoginOK-body
or input-method variants.

## 2026-09-03 — no global anchor for the faction manager: safe observation is structurally impossible without new tooling

Static check: of the 200+ callers of `FUN_005015F0` (this=manager), NONE loads `this` from a global
(`mov ecx,[abs]`); every caller passes `this` in a register from an active-screen manager-list walk. So the
faction manager object has:
(a) no fixed/global address to RPM (RPM needs an address; heap-scan by structure fails because the manager
    struct signature — distinct from the widget-holder ctx — is unknown), and
(b) it is only in-scope during the client's own manager-list iteration, reachable live only by a debugger,
    which crashes this client (3/3) AND freezes input during attach.

Therefore the faction-panel `+0x05` arm cannot be observed by any safe tool available this session. Condition 2
is fully localized (client-side arm, server/input/account/session all excluded) but its mechanism cannot be
read without a NEW technique. Concrete options for a future session, in order of safety:
1. **Static full trace of the session-server→create-UI chain**: from the session-server class (vtable
   `0x0066D13C`, `FUN_0044F060`) follow the frame/input method that calls `FUN_005015F0` for the faction
   screen, to the container global that holds its manager list; if that container is a fixed address, RPM its
   manager entries' `+0x05` safely (no debugger). This is the highest-value pure-static next unit.
2. A cooperative in-client instrumentation via the SAME sanctioned d3d8 shim already used for localization
   (it runs in-process, read-only) — extend it to read the active manager list / `+0x05` and log it, avoiding
   an external debugger entirely. This reuses proven-safe tooling (the shim never crashed the client).
3. A kernel/VM-level read that does not use the user-mode debug API (out of current tooling scope).

Excluded and not to retry: hardware-BP attach, SSLoginOK-body variants, input-method families, server build.
