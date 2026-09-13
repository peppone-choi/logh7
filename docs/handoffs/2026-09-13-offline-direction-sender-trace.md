# Offline direction sender trace — 2026-09-13

Status: PARTIAL static reverse engineering. No server change or deployment in this unit. Full gameplay goal remains active.

## Fresh evidence

All evidence below is under `work/20260904-warp-state-reverse/evidence/`, exported read-only from Ghidra `E:/logh7-greenfield/work/ghidra-input-consumption`, project `Unit10Input`, program `g7mtclient.exe`.

- `offline-direction-request-switch-current-20260913.txt`: `FUN_004B78A0` computes `(param_3 & 0xffff) - 1` before its switch. Case `0x74` therefore means **caller selector 0x75 / 117**, and selects wire type `0x0F18` at `004B8389`. Do not mistake the normalized switch case for caller selector 0x74.
- `offline-direction-send75-20260913.txt`: `005449C0 PUSH 0x75` belongs to a seven-argument `00519AC0` UI layout call, not request submission. Earlier `005372C0` / `00537439` scalar hits are also layout constants.
- `offline-direction-pending-uses-20260913.txt`: `0x35837E` has 113 instruction uses. The sender's byte is shared across command branches, not an offline-direction-specific payload anchor.
- `offline-direction-type-uses-20260913.txt`: seven scalar hits for `0xF18`; `offline-direction-ui-handlers-20260913.txt` resolves `0050B946` and `0050BAE1` as array element size in construction/destruction, and `004E835A` as diagnostic size output. These are NOT UI settings handlers despite the exploratory filename.

## Previously recovered input evidence, retained boundaries

`offline-direction-before-logger-20260913.txt` identifies reader `00480860`: two stream slot-0x1C reads followed by five one-byte reads into expanded offsets +8 through +C. `offline-direction-fields-20260913.txt` gives labels strategy, commanded, suggested, announcement, tactics. This is a 13-byte packed input body versus a 16-byte expanded object; it does not yet prove request writer symmetry or accepted enum values.

`offline-direction-vtable-binding-20260913.txt`: input vtable `0066D240`, installed at factory +0x264. The type-to-input-module join is still incomplete. `004803F0` is a no-op logger, NOT a binary reader; do not use its many xrefs as codec evidence.

## Next concrete trace

### Resource-first follow-up

Fresh resource tracing found a concrete HUD anchor:

- `docs/reverse-engineering/constmsg-catalog.json`, group 0 row 179 (`0xB3`): `オフライン時の各種設定`.
- `offline-tooltip179-uses-20260913.txt` and `offline-tooltip-hud-20260913.txt`: `FUN_00519C50` places tooltip 0xB3 on part ID **8**, in both of its layout tables. The two layouts use IDs `[7,8,0,9,2]` and `[11,7,9,2,0,8,10,12]` respectively.
- Same builder selects icon table `00785EF8 + partId * 0x50`. Part 8 starts at `00786178`, confirmed by `offline-hud-icon8-20260913.txt`. `offline-hud-resource-name-20260913.txt` resolves its image to `/../data/image/icon/system_icon_parts.tga`; first three state descriptors are `(x=144,y=36,w=36,h=36)`, fourth `(180,36,36,36)`.
- Builder first calls `005024E0(part,1)`, then calls it with a layout-specific flag. Part 8's flag is zero in both arrays. `offline-hud-enable-setter-20260913.txt` proves this setter writes part+0x15; **the flag's visibility/input semantics and any later re-enabling remain unverified**. Do not yet claim the original feature is disabled or absent.
- `offline-hud-builder-callers-20260913.txt` has one direct caller, `0054EB94` in `FUN_0054E760`. `offline-hud-owner-init-20260913.txt` confirms window ID **0xB**, layout 0 for init parameter 1, layout 1 for parameter 2.

Next inspect the window-0xB / part-8 event handler, the readers of part+0x15, and later mutations. This resource-to-HUD join is a stronger next anchor than scanning protocol-number literals. No AI behavior or runtime setting change is proven by these static findings.

### Input gate and owning update function recovered

- `offline-part-input-gates-20260913.txt`: `005025C0` returns part+0x15. `005015F0` in `offline-window11-events-20260913.txt` rejects normal input when this getter returns zero. Therefore the builder's part-8 zero flag is an **ordinary input-disable gate**, not merely an unknown display flag. The pre-existing event queue `00501ED0` is checked first, so this is not proof that every possible synthetic/queued event is impossible.
- `offline-window11-handler-20260913.txt`: `00535300` stores window 0xB at this+0x10. `offline-window11-vtable-20260913.txt` resolves its direct caller to `004FC57B`, object at owner+0x1506DE4. The filename says vtable but this is a direct call, not a vtable binding.
- `offline-window11-tick-20260913.txt`: `00535390` is the corresponding update function. It loops part IDs 0..12 and queries event kind 9; action branches exist for IDs 0, 2 and 11 only. There is **no part-8 action in this function**, and no re-enable of part 8 here. This does not exclude another common dispatcher.
- `00535380` is just reset(this+0x10=0), not an update. `00535550` is static initialization cleanup registration, not a click handler. `0051A370` is the main/lobby sequence function; its numeric state 8 is unrelated to HUD part 8. Exploratory `offline-hud-event-handler-20260913.txt` must not be treated as the offline setting callback.
- `offline-main-window-dispatch-20260913.txt` exports `0050D230`; its decompiler has stack/type degradation. Use instruction evidence for any further part/window argument claim. Export session49173 completed exit0; no process remains to wait on.

Implementation consequence: server-only 0x0F18 support would not by itself make the currently traced offline button work. The UI initially blocks ordinary input, and its own update has no action for this button. Before a client patch, finish the common dispatch/event registration trace and recover the intended settings UI or explicitly author a replacement within the user's implementation scope. Do not blindly enable the icon and call that a fix.

### Common-loop join and current server verification

`offline-window11-tick-callers-20260913.txt` and `offline-common-hud-update-20260913.txt` now join the **same owner+0x1506DE4** object to `004FD35D -> 00535390` in the common HUD update `004FD100`. This confirms the recovered init and tick belong together. The immediate part-8 event candidate scan found no new offline callback (`offline-part8-event-candidates-20260913.txt`); it is a bounded linear scan, not proof that all indirect handlers are absent.

Current source review: `OriginalTacticalNpcController` already implements authored autonomous target acquisition, movement, turning and firing. `NaturalAuthoritySession.Mission.cs` still only validates/echoes/relays missions; it does not assign NPC mission execution. Do not confuse NPC combat existing with offline mission AI being complete.

Fresh scoped regression: `dotnet test ... --no-restore --filter FullyQualifiedName~OriginalNpcAiTests`, E:-resident cache/temp, **38 passed, 0 failed, 0 skipped, exit0**. Receipt: `E:/logh7-build/test-results/offline-trace-20260913/offline-trace-npc-ai-20260913.trx`. Read tests include hosted autonomous 0x0426 notification without client attacks, NPC-v-player and NPC-v-NPC damage, executing-operation hold/resume, and death rejecting movement. This is source/host-test evidence only, not VM gameplay or deployment verification. No server source change, live deployment or native input in this unit.

1. Trace the caller of generic request submission with selector 117, including data-driven selectors (immediate scalar scan did not identify it). Follow the settings dialog resource/string callbacks rather than treating every matching number as a command.
2. Join the output serializer and input module selection to type 0x0F18, then establish each UI option's byte values and actor identity.
3. Only then implement actor-scoped persistence, response/current-state restore, and offline tactical AI policy. The five settings must not collapse into one unconditional autonomous flag.
4. Prove user departure/reconnect, NPC mission following and setting-off behavior through server state and original-client observation; static codec evidence alone is insufficient.

## VM observation in preceding unit

### Fresh live-file parity, 2026-09-13 KST

`ExportProgramIdentity.java` produced `offline-analysis-program-identity-20260913.txt`: Ghidra imported the installed original, SHA256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`, image base00400000, x86 LE32. The item115 scoped sample has SHA256 `AE7E8B7F479D37FF430FC7D28EBA0B4D086D8798FE3BB979470F3FB8B4906912`, so do not call those files identical.

CLI receipt `E:/logh7-build/client-identity-20260913.json`, UTC15:31:57 (Sep13 KST), confirms responding client PID5968, original startUTC11:05:14.5230519Z, and server PID7732/startUTC15:01:50.0391950Z at the controller-profile-live path. Server owns both47900 listeners; client has no TCP connection. EXE hashes are separate from the server DLL hash in the deployment receipt.

Copied the guest client read-only to `E:/logh7-build/G7MTClient-live-identity-20260913.exe`, verified SHA256 `AEF3827602CD13A395618BDEF0F48F44BCB8ED60F4FA9C2017F2D2E128660F2F` against guest receipt. `compare-offline-client-code-20260913.ps1` resolves each VA through each PE32's section table and compares bytes; receipt `E:/logh7-build/offline-client-code-parity-20260913.json` proves all nine selected ranges identical: reader, request switch, HUD builder/init/tick, input setter/getter/dispatcher, and part8 icon descriptor table. This transfers the selected static findings to the currently executing client's **on-disk file**, not its running memory or every other function. No client restart, authentication input or gameplay occurred.

CLI `vmrun list` found the expected running VM. One-shot interactive guest `SetThreadExecutionState(2)` restored a black capture to the game's login screen. Host image: `E:/logh7-build/vm-cli-awake-20260913-c.png`. No VM/game/server restart, login input, gameplay, or new live server identity verification occurred in this unit.
