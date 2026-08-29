# Gin7UpdateClient RESOURCE_LOADER unit scope

- Unit: `RECOVERY:D01:RESOURCE_LOADER:627680C75CFF6DA7`
- Question: What exact original evidence closes `RESOURCE_LOADER` for `RESOURCE:FILE:original-installshield-payload:gin7updateclient.exe`?
- Target SHA-256: `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`
- Target byte size: `1060864`
- Scope: offline, read-only static PE triage and updater/process-callflow analysis; repository changes are limited to the importer, graph, tests, adjudication, reproducible evidence, generated trace outputs, documentation, and this work directory.
- Single writer: root Codex agent. Parallel agents are read-only analysts/reviewer.
- Acceptance:
  1. Hash-bound PE/import/version/packing assessment and static updater-role receipt.
  2. Updater process-image role is separated from G7MT runtime asset loading.
  3. `BootFirst -> Gin7UpdateClient -> G7MTClient` process relations are represented without a duplicate semantic edge.
  4. Conflicting dual-owned launch evidence fails closed.
  5. No evidence-state, playability, authority, persistence, or faction promotion.
  6. Full deterministic regeneration, aggregate verification, and independent read-only review pass.
- Forbidden: original executable writes or execution, process-memory access/write, VM/debugger/input action, automatic retries, server/protocol/database/port/VM-lifecycle mutation, or unrelated worktree changes.
