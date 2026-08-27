# Exhaustive trace foundation Task 1 report

## Verdict

`PASS` for foundation Task 1. The repository now has one immutable contract vocabulary for the six closed-world inventories, reachability, evidence progression, execution verdicts, recovery/authoring disposition, and mandatory implementation targets. The exact D01-D16 domain registry is machine-readable and tested.

## Implemented boundary

- `InventoryRow` rejects unsupported provenance, untyped inventory/reachability values, string evidence-state keys, and non-boolean state values.
- All nine evidence states exist independently and default to false; no later state implies an earlier state.
- `TraceNode` and `TraceEdge` require evidence, copy ordered evidence into tuples, and reject scalar, unordered, or non-text evidence.
- Canonical JSON handles the contract dataclasses and enums deterministically, rejects lossy mapping keys/collisions, and rejects NaN/Infinity.
- SHA-256 hashes exact bytes and returns uppercase hex.
- D01-D16 IDs and slugs exactly match the approved plan.

## TDD and review evidence

The initial focused run failed because the production package did not exist. Minimal implementation produced the first green run. Two read-only reviewers then found six integrity/determinism issues; each correction received a failing regression test before the minimal fix. The final fresh discovery run reports 14 tests, 14 passed, 0 failed. Both read-only reviews ended `OK` with zero workspace writes.

## Boundary

This unit does not enumerate any original-game protocol, UI, entity, resource, function, or authority row yet. It only supplies the contracts that make those later inventories fail-closed and comparable. Task 2 source freezing and PE import-gate work remains `NOT_STARTED`.

No VM, original client, original resource, server, protocol, database, port, or external runtime was touched.
