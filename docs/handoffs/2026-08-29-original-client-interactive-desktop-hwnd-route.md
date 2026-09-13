# Handoff: original-client interactive desktop HWND route

## Goal and result

Establish one reproducible, no-input route that executes a fresh identity/HWND collector in the actual logged-on guest desktop and stores two complete PID/hash/module/HWND/owner/visibility/client-rectangle observations without changing game, debugger, VM lifecycle, server, protocol, or database state.

Result: `BLOCKED` at `NO_HELPER_RECEIPT / PROGRAM_LOOKUP_NOT_PROVEN`.

One unique source copy and one `runProgramInGuest -interactive` launch attempt were made. VMware returned exit `-1` and `Error: A file was not found`; no started, raw, or diagnostic receipt existed. The helper was not retried. No live HWND claim, promotion, prelaunch eligibility, debugger action, process-memory access, game input, or physical activation is allowed from this unit.

The approved physical WARP activation remains unconsumed.

## Scope actually performed

- Read-only VMware Workstation window, passive VNC/framebuffer, guest session, VMware Tools broker, process, and existing-helper diagnosis.
- TDD for a closed interactive canary receipt, complete A/B observations, external host binding, and fail-closed evaluator.
- One collector source copy to a unique guest path.
- One no-input `runProgramInGuest -interactive` call without `-activeWindow`.
- Three one-time receipt copy-back calls after failure.
- One read-only `fileExistsInGuest` query for the unique copied source.
- Static comparison with the prior successful guest-operation program path.
- Local verification and independent read-only review.

Not performed:

- game mouse/keyboard input, automatic input, wake, restore, focus, or foreground change;
- x32dbg attach, command, or breakpoint installation;
- process-memory read/write or binary patch;
- owned-HWND screenshot or live candidate promotion;
- permit issuance or WARP physical activation;
- VM power/suspend/reset/snapshot/revert;
- server, protocol, or database changes.

## Contract corrections before the live attempt

The first local contract was rejected by independent review because it stored only one process/window summary plus a self-asserted `snapshotStable`, allowed a provenance string to claim live status, and did not mutate every operation counter.

The corrected contract now requires:

- two stored complete snapshots labeled A and B with ordered timestamps;
- evaluator-computed semantic equality of both process/window snapshots;
- exactly one canonical G7MTClient and one canonical x32dbg in the helper's active console session;
- exactly one visible owned HWND and positive client/window rectangles for each;
- unchanged foreground and exact helper/file operation accounting;
- zero process-memory, foreground, debugger, breakpoint, capture, input, permit, VM, server, protocol, and database operations;
- a separate host manifest that recomputes hashes for the raw receipt, collector, prelaunch session receipt, broker receipt, started marker, and diagnostic, and binds the exact one-attempt route;
- no self-promotion: even a structurally bound live receipt remains `INTERACTIVE_HWND_LIVE_CANDIDATE_UNREVIEWED`, with `livePromotionAllowed=false` and `prelaunchEligible=false`.

Fresh local results:

- evaluator: 54 cases / 153 assertions / 39 receipt mutations / 8 binding mutations / 4 support-content mutations;
- collector capability contract: 55 assertions plus PowerShell AST command inventory;
- all ten PowerShell files, including the immutable live collector copy, parse without errors.
- the future route binds one `runId`, the exact absolute PowerShell executable, the ordered argument vector, and the raw/started/diagnostic run and timestamp joins.

## Live attempt evidence

Run ID: `20260829T183100Z-v1`.

Bound route artifact:

- `work/20260829-original-client-interactive-desktop-hwnd-route/evidence/live-20260829T183100Z-v1-vmrun-route.json`
- SHA-256: `3F43C24657BE76E5511DE7DB4A41F5716E90F3D2485242BE4A735BA654CA0424`
- collector SHA-256: `7F10538C055255B1C83ED5E792844C39707317DA0452DC11F860A441259E7DEA`

Observed route facts:

- guest source copies: 1, exit 0;
- helper launch calls: 1, `-interactive=true`, `-activeWindow=false`;
- host `vmrun` command exit: `-1` (the raw route's field name `helperExitCode` is retained as evidence but must not be interpreted as a guest helper exit);
- exact output: `Error: A file was not found`;
- started/raw/diagnostic copy-back calls: 3;
- each copy-back exit: `-1`, exact output `Error: A file was not found`;
- copied-back receipt files: 0;
- physical activations: 0;
- target/game operation counters in the route: 0.

The subsequent read-only `fileExistsInGuest` query returned exit 0 and `The file exists.` for `C:\LOGH7_ORACLE\interactive-canary-20260829T183100Z-v1.ps1`. This observation is preserved in the task execution record but was not retroactively inserted into the earlier route artifact. The exact delivered collector was then recovered without execution and preserved at SHA-256 `7F10538C055255B1C83ED5E792844C39707317DA0452DC11F860A441259E7DEA`.

## Whole-unit operation accounting

`evidence/unit-operation-ledger.json` records the complete command counts recoverable from the task transcript:

- copy host-to-guest / runProgram / copy guest-to-host: 6 / 7 / 8;
- vmrun captureScreen / passive VNC capture: 2 / 1, yielding two all-black artifacts;
- fileExistsInGuest / listProcessesInGuest: 1 / 2;
- killProcessInGuest: 1, limited to owned stuck diagnostic PowerShell PID 5272 in session 0;
- known guest source-file writes: 6;
- exact guest receipt writes and helper processes created before the ledger: `NOT_INSTRUMENTED_BEFORE_UNIT_LEDGER`;
- current interactive canary helper process creation: `UNKNOWN`;
- game/automatic input, foreground change, process-memory access, debugger action, breakpoint, permit, physical activation, VM lifecycle, server, protocol, and database changes: all 0.

The owned diagnostic-helper termination is reported separately and is not hidden inside a claim of “all mutations zero.” It did not target G7MTClient, x32dbg, the server, or VMware lifecycle.

## Proven, inferred, and unknown

Proven:

- `ORIGINAL_OBSERVED`: active console session 1 contained explorer, canonical G7MTClient PID 3448, and x32dbg PID 6548 at the earlier session diagnostic boundary.
- `ORIGINAL_OBSERVED`: same-user VMware Tools agents existed in sessions 0 and 1.
- the unique collector source existed in the guest after the copy.
- the single interactive launch returned before any started marker could be recovered.
- no raw interactive session/HWND receipt exists.
- no helper retry or physical activation occurred.

Inferred:

- `PROGRAM_LOOKUP_NOT_PROVEN` is the narrowest current cause. This attempt used bare `powershell.exe`; earlier successful routes used `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`. Source existence makes a missing copied script less likely, but there is no helper-side diagnostic proving which path VIX failed to resolve.

Unknown:

- whether any guest process object was created before VIX returned; there is no started marker, so `helperProcessCreated` is `UNSEEN`, not zero;
- whether the corrected absolute program path would enter session 1 / WinSta0 / Default;
- fresh client/debugger HWNDs, owners, visibility, rectangles, and foreground;
- root-role collector eligibility, manager65 action `0x2B`, prior manager67 hit-region binding, debugger compatibility, MVB state, permit eligibility, WARP wire, authority, pixels, or persistence.

## Verification

Command:

```powershell
pwsh -NoProfile -File work/20260829-original-client-interactive-desktop-hwnd-route/verify.ps1 -OutputPath work/20260829-original-client-interactive-desktop-hwnd-route/evidence/final-verification.json
```

Fresh result:

- verifier status: `PASS`;
- verdict: `LIVE_HELPER_ROUTE_FAILED_BEFORE_STARTED_MARKER_NO_RETRY`;
- evaluator: 54 cases / 153 assertions;
- capability contract: 55 assertions;
- parsed PowerShell files: 10;
- helper launch calls / helper process created / receipts: 1 / `UNKNOWN` / 0;
- physical activations: 0;
- target/game state-changing operation count: 0;
- owned diagnostic helper terminations: 1;
- known guest source writes: 6; guest helper receipt writes: `NOT_INSTRUMENTED_BEFORE_UNIT_LEDGER`;
- route SHA-256: `3F43C24657BE76E5511DE7DB4A41F5716E90F3D2485242BE4A735BA654CA0424`.
- unit-operation-ledger SHA-256: `31D7B94298C2537BF1AF352C2B2A83B6D5E8729E3C76C27157122A5137FFE73A`;
- final-verification SHA-256: `D7EAE38639DE3104EB47B8799727BDBC3914DB6960B2AA87ABD2BC5A53FACCDD`.

`PASS` describes the fail-closed verifier only. It does not mean the interactive route or original gameplay passed.

Independent re-review: `APPROVE` for this bounded fail-closed closure only. The reviewer reran the local verifier and confirmed that no live promotion, prelaunch eligibility, permit, or physical activation follows from the failed route.

Reviewer-retained qualifications:

- the immutable failed-route JSON retains the misleading field name `helperExitCode`; it is interpreted only as host `vmrun` exit;
- the exact bare executable and argument vector are transcript/timeline evidence, not fields retroactively added to the immutable route JSON;
- pre-ledger helper-process and receipt-write totals remain `NOT_INSTRUMENTED/UNKNOWN`, never zero;
- session-1 `vmtoolsd` same-user ownership is inferred from session context rather than directly captured process-owner evidence. A future successful promotion must capture and join that owner explicitly or retain the inference.

## Changed artifacts

- `work/20260829-original-client-interactive-desktop-hwnd-route/scope.md`
- `work/20260829-original-client-interactive-desktop-hwnd-route/timeline.md`
- `work/20260829-original-client-interactive-desktop-hwnd-route/src/**`
- `work/20260829-original-client-interactive-desktop-hwnd-route/tests/**`
- `work/20260829-original-client-interactive-desktop-hwnd-route/evidence/**`
- `work/20260829-original-client-interactive-desktop-hwnd-route/verify.ps1`
- this handoff.

Shared working manuals were updated but deliberately excluded from the bounded commit because they pre-existed as untracked shared work:

- `report/manual.md`
- `report/mistakes.md`

## Exact next start

Open a new bounded continuation only after the no-retry boundary is explicitly continued. Before any guest launch:

1. extend the route contract to bind the exact executable path `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` and its existence/provenance;
2. use a new unique run ID and new guest/host paths;
3. rerun all local contract tests and independent prelaunch review;
4. issue at most one corrected `runProgramInGuest -interactive` call without `-activeWindow`;
5. if it yields a complete A/B receipt, generate the external host binding, evaluate it, and stop for independent review before any process-memory access, attach, foreground action, or physical input.

## Forbidden retries

- Do not rerun `20260829T183100Z-v1` or reuse any of its guest/host paths.
- Do not pass bare `powershell.exe` to VIX again.
- Do not invent a started/raw/diagnostic receipt or infer session/desktop/HWND from the source-exists result.
- Do not classify this as collector-body failure; the collector body was not evidenced as entered.
- Do not issue a WARP permit, attach x32dbg, install breakpoints, access process memory, foreground a window, or send any input from this unit.
- Do not patch the client, write process memory, change VM lifecycle, or change server/protocol/database state.
