# Scope

## Question

Can a closed receipt-v2 envelope compose the frozen movement receipt v1 with the reviewed four-slot rearm plan and make all eight temporal/thread/build/queue evidence gaps mechanically auditable without fabricating a live installation?

## Included

- Frozen v1 receipt artifact bindings and explicit v1-to-v2 migration matrix.
- Closed JSON Schema Draft 2020-12 v2 envelope, empty template, synthetic specimen, semantic verifier, mutation tests, and schema validation.
- Plan/build bindings, accepted-hit debug-event ledger, temporal phase execution ledger, per-thread DR state, rejected-hit log, and unique pending-queue census.
- Prelaunch v8 blocker delta after the v2 schema is independently reviewed.
- Independent review, final verification, handoff, and bounded commit.

## Excluded

- VM/guest/debugger/process access, breakpoint commands or installation, memory access, input, capture, permit issuance, or runtime evidence.
- Any v1 artifact edit or synthetic-to-live promotion.
- Software INT3, process-memory write, binary/resource patch, automation, VM lifecycle, or server/protocol/database change.

## Acceptance

- v1 remains byte frozen and referenced by actual SHA-256.
- All eight missing field groups are required and semantically cross-correlated.
- Phase membership matches the exact ten-phase plan with peak four; command/result evidence is explicit and non-automatic.
- Debug-event ordinals are strictly increasing; rejected hits never advance a phase.
- Every required phase has a complete per-thread DR receipt and exactly one pending `0x0B07` correlation candidate.
- Empty and synthetic documents remain runtime-no-miss `MISSING`, live-ineligible, and permit-free.
