# Scope

- case: `20260828-original-game-exhaustive-trace-task4-ui`
- plan unit: exhaustive-trace foundation Task 4
- objective: enumerate the hash-fixed original client's static root-mode, manager, widget, menu-row, label, event, handler, enablement, visibility, child-manager, input-source, and render-anchor candidates without promoting runtime or playability claims
- allowed: repository-local Java/Python/JSON/tests/docs, read-only Ghidra export from the clean `ProtocolTrace` project, hash-bound `constmsg.dat` reads, deterministic normalization, independent read-only review, and scoped Git commit
- excluded: VM or guest action, original-client launch, debugger attach, process memory, input, binary patch, Ghidra database mutation, and server/protocol/database/lifecycle change
- completion: RED tests, deterministic whole-program anchor export, explicit unresolved/excluded reconciliation, bounded mode-2 detailed rows, byte-for-byte reproduction, aggregate tests, three independent approvals, commit, and handoff
