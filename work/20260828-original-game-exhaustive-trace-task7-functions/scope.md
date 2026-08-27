# Task 7 scope - original client function inventory

## Authorized work

- Read the frozen `g7mtclient.exe` semantic Ghidra database without analysis or writes.
- Export every Ghidra-defined function, replace the external namespace with the one-entry-wider raw PE-import surface, and bind structured mentions from the closed protocol, UI, entity, and resource inputs.
- Normalize every internal non-thunk function individually; group only frozen PE imports and internal thunks by deterministic rules.
- Preserve unresolved direct targets, indirect calls, and unresolved structured upstream references.
- Test, reproduce, independently review, document, and commit this bounded unit.

## Explicitly outside this unit

- VM, process, debugger, memory, input, original executable, server, protocol, database, port, or lifecycle mutation.
- Runtime reachability, original-client playability, player-visible HUD, correct flagship selection, world delivery, command completeness, both-faction play, persistence, Gate-A, or Gate-B proof.
- Semantic names inferred from adjacency, size, function shape, `nearestPriorFunction`, or transitive callers.

## Completion condition

All 12,044 Ghidra-defined functions are conserved, with the 451 Ghidra externals replaced by 452 raw PE imports to form a 12,045-member inventory surface. Every surface address and every raw wrapper candidate is represented exactly once, all candidate inputs reconcile with zero unaccounted candidates, two read-only exports and two normalizations reproduce byte-identically, tests and source gate pass, and three read-only reviewers approve.
