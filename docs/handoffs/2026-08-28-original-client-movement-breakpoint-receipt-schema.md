# Handoff: original-client movement breakpoint receipt schema

## Goal

Define a fail-closed, reproducible receipt contract that can later bind one original-client WARP input sequence to payload construction, outbound `0x0B01`, inbound `0x0B07`, state application, matching queue completion, and owned-HWND evidence.

## Scope and actual work

- Re-read the goal, oracle manual, mistakes ledger, prior WARP-owner handoff, and existing x32dbg receipt precedents.
- Parallel-audited the requested seven addresses and prelaunch delta.
- Corrected pre-execution register semantics for `0x004B7EDE` and `0x004B7EE3`.
- Proved the original seven anchors stop before expected-opcode comparison/dequeue and added `0x004BDD91` plus `0x004BDDE2`.
- Authored strict JSON Schema, empty not-live template, static ledger, synthetic-only specimen, semantic verifier, mutation tests, and prelaunch v6 integration.
- Performed no live or external state-changing operation.

## Changed files

- `work/20260828-original-client-movement-breakpoint-receipt-schema/**`
- `docs/handoffs/2026-08-28-original-client-movement-breakpoint-receipt-schema.md`
- shared `report/manual.md` and `report/mistakes.md` updated with operating lessons but excluded from the bounded commit

## Reproduction

```powershell
pwsh -NoProfile -File 'work/20260828-original-client-movement-breakpoint-receipt-schema/verify.ps1'
```

## Evidence

- `evidence/movement-breakpoint-static-ledger.json`
- `evidence/movement-breakpoint-receipt.schema.json`
- `evidence/movement-breakpoint-receipt-template.json`
- `evidence/prelaunch-v6-movement-receipt.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `evidence/independent-review.md`

## Confirmed facts

- Primary anchors MVB01-MVB07 bind handler, payload, opcode selection, transport send, inbound dispatch, and state application.
- Completion anchors MVB08-MVB09 bind inbound/expected comparison and queue-count decrement.
- A breakpoint at `0x004B7EDE` or `0x004B7EE3` sees pre-instruction state; later anchors must prove assigned values.
- MVB02 pre-call stack is `[ESP]=1`, `[ESP+4]=0x3B`, `[ESP+8]=payloadPtr`.
- No recovered wire correlation ID exists; single-outstanding queue correlation remains an explicit evidence basis, not an original protocol fact.
- The prior permit remains `CONSUMED_NO_RETRY` and non-reusable.

## Unknown / unverified

- A non-writing, independently reviewed x32dbg hardware-breakpoint rearm plan for nine definitions with at most four concurrent hardware slots.
- Any fresh breakpoint installation or hit receipt.
- Full payload codec and semantic meaning of the builder `+0x08` field/padding.
- Live server acceptance, authority, persistence, player-visible movement, both factions, and end-to-end original-client playability.

## Execution state

- `OFFLINE_MOVEMENT_RECEIPT_SCHEMA_PASS_REARM_PLAN_MISSING_RUNTIME_UNSEEN`
- Prelaunch: `OFFLINE_PRELAUNCH_MOVEMENT_RECEIPT_SCHEMA_INTEGRATED_READY_FALSE`
- First policy blocker: `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`
- First technical blocker: `MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING`
- Live operations: 0; process-memory reads: 0; game inputs: 0; permit issued: false.

## Next start

Create and independently review a non-writing x32dbg hardware execution-breakpoint rearm plan for MVB01-MVB09. It must prove the exact stage-to-stage rearm schedule, maximum concurrent DR slots, no missed transition window, no software INT3/code-byte write, no input, and a dry-run receipt. Do not attach or launch the VM in that unit.

Separately, the one-authorized-activation versus WARP/DESTINATION/CONFIRM three-stage contract mismatch remains unresolved and must not be bypassed.

## Failed approaches and manual assessment

- A 60-character fixture hash, JSON parsing of PowerShell artifacts, and a self-scanning capability deny-list each failed before publication; all are recorded with corrections in `report/mistakes.md`.
- `report/manual.md` now records pre-execution BP semantics, the two completion anchors, correlation limits, and the hardware/software breakpoint conflict.

## Forbidden retries

- Do not install software INT3 breakpoints or perform any process-memory/code-byte write.
- Do not install nine hardware breakpoints simultaneously; the architecture has only four hardware execution slots.
- Do not reuse the prior permit, PID/HWND/module base, pointer, coordinate, or synthetic specimen.
- Do not click, auto-retry, launch/alter the VM, or change server/protocol/database state.
- Do not promote schema, fixture, pixels, client send, or inbound apply to authority/persistence/player-visible/Gate status.
