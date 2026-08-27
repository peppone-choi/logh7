# Scope

- case: `20260827-original-game-exhaustive-trace-task3-protocol`
- plan unit: exhaustive-trace foundation Task 3
- objective: enumerate the frozen original client's statically exported 16-bit protocol surface and conserve every raw opcode, name-string, and stream-contract candidate
- allowed: repository-local Java/Python/JSON/tests/docs, read-only Ghidra headless export, one isolated derived Ghidra project creation from the hash-fixed original after baseline contamination, source/hash verification, and Git commit
- excluded: VM or guest action, client launch, debugger attach, memory access/write, physical input, original binary patching, and server/protocol/database mutation
- completion: initial RED, hash-bound deterministic exporter, normalized inventory, zero-unaccounted reconciliation, aggregate tests, independent read-only reviews, commit, and handoff
