# Handoff: original-client first-play prelaunch manager65 integration v4

## Goal

Integrate the hardened manager65 action-0x2B collector into the original-client first-play prelaunch contract without self-promoting offline evidence to live readiness.

## Scope and actual work

- Added a v4 gap audit and prelaunch contract layered over sealed v3 evidence.
- Retired only the obsolete manager65 hardening blocker.
- Split fresh manager65 capture from independently bound manager65 hit-region proof.
- Added hash and semantic validation, fresh upstream verifier execution, mutation tests, and an aggregate verification receipt.
- Did not perform VMware guest operations, debugger attach, process-memory reads, captures, or game input.
- Validation used enumerated system-temp files for mutation cases and fixture replay; it made no persistent workspace write beyond the unit artifacts.

## Changed files

- `work/20260828-original-client-first-play-prelaunch-manager65-integration-v4/**`
- `docs/handoffs/2026-08-28-original-client-first-play-prelaunch-manager65-integration-v4.md`

## Commands

```powershell
& 'work\20260828-original-client-first-play-prelaunch-manager65-integration-v4\tests\test-prelaunch-manager65-integration-v4.ps1'
& 'work\20260828-original-client-first-play-prelaunch-manager65-integration-v4\verify.ps1'
```

## Evidence

- `evidence/prelaunch-manager65-integration-v4.json`
- `evidence/ui-movement-gap-audit-v4.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `evidence/independent-review.md`

## Confirmed facts

- Manager65 static owner and offline collector/resolver are `PASS` for `CURRENT_AUTHORITY_CARD_ACTION_WIDGET_FOR_COMMAND_0X2B_WARP_NAVIGATION`.
- Live snapshot is `UNSEEN`; independent live hit region is `UNBOUND`; fixture coordinates are not reusable.
- The current contract has 12 blockers and is not permit- or launch-eligible.
- First technical missing boundary is `WARP_STAGE_OWNER_POINTER_UNBOUND`.

## Inference

- Closing the WARP stage-owner pointer is the next offline technical unit that can advance readiness without consuming the one-activation authority.

## Unknown / unverified

- Fresh PID/HWND/module/pointer state and listener/heartbeat.
- Fresh manager67 and manager65 snapshots and independent hit regions.
- Destination and TextDialog live projections.
- Movement-specific breakpoint receipt schema.
- Any outbound/inbound/pixel result from a physical activation.
- Original-client end-to-end playability and every broader full-game completion condition.

## Execution state

- `OFFLINE_PRELAUNCH_MANAGER65_INTEGRATED_READY_FALSE`
- Live operations: 0
- Game inputs: 0
- Permit issued: false

## Next start

Resolve `WARP_STAGE_OWNER_POINTER_UNBOUND` offline, then revise the activation-budget/stage contract before any live launch. After all dry-run blockers close, obtain an independent live prelaunch review and only then perform the authorized single physical activation.

## Forbidden retries

- Do not reuse a prior permit, run identity, PID, HWND, pointer, or fixture coordinate.
- Do not automatically click or retry.
- Do not issue input before all same-run gates and independent review pass.
- Do not write process memory or patch binary/resources.
- Do not change VM lifecycle, server, protocol, or database in this handoff.
