# Handoff: original-client movement hardware-breakpoint rearm plan

## Goal

Define and independently review a non-writing x32dbg hardware execution-breakpoint schedule for MVB01-MVB09 using at most four concurrent x86 DR definitions, without attaching, installing breakpoints, accessing the target process, or consuming input/permit authority.

## Scope and actual work

- Re-read the controlling goal, oracle manual, mistakes ledger, and prior movement receipt handoff.
- Parallel-audited the formal schedule, official/local x32dbg commands and binaries, and prelaunch v7 delta.
- Hash-bound the installed x32dbg.exe, x32dbg.dll, x32gui.dll, `commithash.txt`, and exact installed source commit.
- Defined a ten-phase canonical active-membership schedule with peak four definitions.
- Added a semantic simulator, 38 mutation cases, and a hash-bound synthetic dry run.
- Distinguished the structural schedule requirement from two unbound external debugger semantics and retained runtime no-miss as `MISSING`.
- Expanded the future receipt-v2 gap list from five to eight auditable fields.
- Integrated prelaunch v7 while preserving blocker count/order and the consumed prior permit.
- Received one `REVISE`, corrected all four findings, then received independent read-only `APPROVE` with validator writes 0.
- Performed no VM, guest, debugger, process, capture, input, server/protocol/database, or permit operation.

## Changed files

- `work/20260828-original-client-movement-hardware-rearm-plan/**`
- `docs/handoffs/2026-08-29-original-client-movement-hardware-rearm-plan.md`
- shared `report/manual.md` and `report/mistakes.md` updated with operating lessons but excluded from the bounded commit

## Reproduction

```powershell
pwsh -NoProfile -File 'work/20260828-original-client-movement-hardware-rearm-plan/verify.ps1'
```

## Evidence

- `evidence/hardware-rearm-plan.json`
- `evidence/hardware-rearm-dry-run.json`
- `evidence/official-x64dbg-hardware-sources.json`
- `evidence/prelaunch-v7-hardware-rearm.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `evidence/independent-review.md`

## Confirmed facts

- Nine anchor definitions fit a static schedule with peak four concurrent active memberships.
- Initial membership is MVB01/MVB06/MVB08/MVB09; MVB01 rotates through MVB02-MVB05; MVB07 replaces MVB05 before the schedule permits transport resume; MVB06-MVB09 remain through completion; MVB09 cleanup leaves zero definitions.
- The locally installed x32dbg commit is `9c8ca1cae0b6d56cc44f31fddcb10e3b02ffbb87`; `commithash.txt` and all three x32 debugger binaries are hash-bound.
- Official command/source evidence supports address-qualified `bphws`/`bphwc`, four-slot enforcement, and reference-source all-thread/new-thread programming behavior.
- The prior hardware-rearm-plan blocker is statically resolved.
- Prelaunch v7 remains `READY_FALSE`, has twelve blockers, and keeps the prior permit `CONSUMED_NO_RETRY`.

## Inference and claim boundary

- The schedule requires stopped mutations and next-anchor verification before resume. It does not prove that Windows/x32dbg actually preserved all-thread suspension or pre-instruction execute-HWBP semantics in the target run.
- Reference source indicates all-thread programming, but the installed source retains a multi-thread hardware-BP TODO. Runtime all-thread behavior remains `UNSEEN`.
- `active` is canonical membership, not a DR0-DR3 slot map.

## Unknown / unverified

- Authoritative debugger semantics for all-thread suspension until continuation and execute-HWBP pre-instruction timing.
- Any fresh breakpoint installation, per-thread DR state, hit, rejected-hit, or phase transition receipt.
- Receipt-v2 schema and verifier for the eight missing fields.
- Unique pending expected-`0x0B07` queue census and full payload codec.
- Movement, server authority, persistence, owned-HWND pixel change, both factions, Gate-A, Gate-B, and end-to-end original-client playability.

## Verification result

- Hardware schedule: 39 cases / 59 assertions / 38 mutations.
- Prelaunch v7: 21 cases / 33 assertions / 20 mutations.
- Fresh plan trace equals stored dry-run trace/phase/transition/peak.
- Current-unit artifact hashes: 10/10.
- Independent review: `APPROVE`; validator writes 0.
- Live/debugger/process-read/write/capture/input/permit operations: 0.

## Execution state

- `OFFLINE_HARDWARE_REARM_PLAN_PASS_RECEIPT_SCHEMA_GAP_RUNTIME_UNSEEN`
- Prelaunch: `OFFLINE_PRELAUNCH_HARDWARE_REARM_PLAN_INTEGRATED_RECEIPT_V2_MISSING_READY_FALSE`
- First policy blocker: `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`
- First technical blocker: `MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING`

## Next start

Create movement breakpoint receipt schema v2 and its fail-closed verifier entirely offline. It must add exactly the eight missing receipt fields: hit thread/event ordinal; temporal phase ledger; per-thread DR state; rejected-hit log; unique pending `0x0B07` census; rearm-plan version/hash; installed debugger trio plus commit-file binding; and per-phase delete/set command/results with pre/post active sets and explicit before-resume verification. Do not attach or launch in that unit.

The activation-budget versus WARP/destination/confirm stage mismatch remains a separate policy blocker and must be resolved before any later live input.

## Forbidden retries

- Do not reuse the prior permit, run, PID/HWND, module base, pointer, coordinate, or synthetic dry run.
- Do not treat the schedule booleans, source TODO, `bplist`, or reference-source iteration as a live per-thread or no-miss receipt.
- Do not use software/temp INT3, empty-address `bphwc`, DR-slot-number dependencies, singleshoot, automatic breakpoint commands, process-memory writes, or code-byte patches.
- Do not click, auto-retry, attach, install breakpoints, launch/alter the VM, or change server/protocol/database state in the receipt-v2 schema unit.
- Do not promote static schedule, client send/apply, queue completion, or pixels to authority, persistence, player-visible semantics, Gate-A, Gate-B, or playability.
