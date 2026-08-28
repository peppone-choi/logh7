# Exhaustive trace foundation current-state reseal

## Question

Can the Task 10-14 exhaustive-trace foundation be regenerated from the current
worktree after binding the exact Ghidra program database, without deleting or
mutating any Ghidra repository data?

## In scope

- Bind the exact manifest-selected Ghidra program database by safe relative path.
- Re-run the exhaustive-trace tests and aggregate foundation verifier.
- Regenerate deterministic checked artifacts only when their provenance binding changes.
- Reconcile stale Task 10-14 plan/report/handoff state with current evidence.
- Obtain a separate read-only independent review of the final bounded diff and receipt.

## Out of scope

- Ghidra repository deletion, compaction, migration, or analysis mutation.
- VM, original-client, debugger, input, server, protocol-wire, or database operations.
- Promotion of any coverage row, feature, gameplay, authority, persistence, or faction claim.
- Starting the next recovery unit.

## Acceptance

- The exact bound `db.1.gbf` hash matches the frozen manifest while an unbound
  `db.2.gbf` may coexist without participating in provenance.
- The full exhaustive-trace test suite passes.
- The aggregate foundation verifier reproduces checked artifacts from two fresh roots.
- The one acknowledged feature-ledger fatal remains explicit.
- Current documentation and independent review bind the new receipt and hashes.
