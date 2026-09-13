# Scope

- case: `20260827-original-game-exhaustive-trace-task2-sources`
- plan unit: exhaustive-trace foundation Task 2
- objective: freeze authoritative original inputs and enforce a reproducible, hash-bound x86 PE import-table gate
- allowed: repository-local Python/JSON/tests/docs, read-only raw PE parsing, read-only existing Ghidra evidence, hash verification, and Git commit
- excluded: original executable/resource modification, VM lifecycle or guest action, client launch, debugger attach, process-memory access, player input, server/protocol/database mutation, and later foundation tasks
- completion: initial RED, deterministic generator, exact source/resource/tool/Ghidra binding, focused and aggregate tests green, two independent read-only reviews OK, commit, and handoff
