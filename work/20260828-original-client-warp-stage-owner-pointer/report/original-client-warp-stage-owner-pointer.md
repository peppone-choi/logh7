# Original-client WARP stage-owner pointer

## Outcome

The static ownership gap is closed as `STATIC_WARP_STAGE_MANAGER_OWNER_PASS`. The original client is still not launch-ready or proven playable: the fresh stage-owner snapshot remains `UNSEEN` and requires independent same-run binding.

## Proven static chain

`manager65 command 0x2B`
→ `FUN_004F93C0(this=moduleBase+0x89E2E0)`
→ active flow `U32(moduleBase+0x89E2F8)`
→ flow command `+0x28=0x2B` and card `+0x20==strategyRoot+0x488`
→ exact six-child WARP flow
→ child index 2 `TextDialog`, vtable `moduleBase+0x275780`
→ constructor tuple `(builder=4, variant=0, managerIndex=3)`
→ callback `0x005725E0`
→ cached manager `U32(TextDialog+0x58)=moduleBase+0x8A292C`.

The same manager owns layout `+0x37C=4`, confirmation/cancellation widgets, and waiting/terminal state `+0xDE0`.

## Implemented collector

The new collector binds fresh executable/PID/HWND/module identity, double-captures the active owner and child vector, checks the exact vtable sequence, verifies the authority-card ID, TextDialog parameters/opcode, cached manager equality, layout and waiting state, and rechecks the owned HWND surface. It exposes only read-only process-memory APIs and never promotes its own live provenance.

## v5 prelaunch delta

- Resolved static blocker: `WARP_STAGE_OWNER_POINTER_UNBOUND`.
- Introduced runtime boundary: `FRESH_WARP_STAGE_OWNER_SNAPSHOT_MISSING`.
- Blocker count remains 12.
- First policy boundary remains `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`.
- First technical boundary is now `MOVEMENT_SPECIFIC_BREAKPOINT_RECEIPT_SCHEMA_MISSING`.

## Verification

- Collector: 22 cases / 37 assertions.
- v5 contract: 13 cases / 23 assertions.
- Fixture replay: PASS.
- Hash-bound artifacts: 12.
- Live operations, process-memory reads, game inputs, permit issuance: 0.
- Independent review: APPROVE after one REVISE cycle.

This is static/offline evidence only, not player-visible, wire, authority, persistence, both-factions, Gate-A, Gate-B, or full-game completion evidence.
