# update.ini RESOURCE_LOADER unit scope

- Unit: `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`
- Question: What exact original evidence closes `RESOURCE_LOADER` for `RESOURCE:FILE:original-installshield-payload:update.ini`?
- Target SHA-256: `EBB093A34852454DD8D15CA14E95804D9200416B8724CD4F445770B07C17EF7C`
- Target byte size: `124`
- Scope: offline, read-only configuration-file forensics plus hash-bound static consumer/path/parser analysis of `Gin7UpdateClient.exe`; repository changes are limited to importer, graph, tests, adjudication, reproducible evidence, generated trace outputs, documentation, and this work directory.
- Single writer: root Codex agent. Parallel agents are read-only analysts/reviewer.
- Acceptance:
  1. Exact byte/encoding/line-ending/INI grammar and shipped values are reproduced.
  2. The exact `%supdate.ini` resource, path formatter, profile-path flow, read APIs, and write API are bound to the updater hash.
  3. The external updater consumer is not misrepresented as a G7MTClient function-inventory member.
  4. Loader is closed only when exact target identity and consumer callflow join; owner/runtime/player evidence states remain unchanged.
  5. The original extracted file and historically mutated installed file remain distinct evidence objects.
  6. Full deterministic regeneration, aggregate verification, and independent read-only review pass.
- Forbidden: original executable/file writes or execution, process-memory access/write, VM/debugger/input action, automatic retries, server/protocol/database/port/VM-lifecycle mutation, or unrelated worktree changes.
