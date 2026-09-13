# Strategy / assignment integration handoff — 2026-09-13

## Scope and status

User requested handoff, commit, push, merge, then wait. This is a checkpoint of accumulated original-client server work, NOT completion of the persistent whole-game objective. No native client or server restart/deployment is part of this integration. Preserve the existing VM and both dirty worktrees.

Read `2026-09-13-strategy-command-current-audit.md` first for latest detailed findings; `2026-09-03-celestial-type-placeholder-finding.md` retains the historical investigation. The current code depends on accumulated server/storage/migrations through0046; committing only the latest assignment files would omit dependencies.

## Implemented and verified in this checkpoint

- Assignment0C0B decoder/encoder: signed unit/troop/supply changes and two independent unsigned warehouse result snapshots. Original writer00553FC0/reader00558B70; body43+5*sum(six counts), ship/troop limits99/24.
- Warehouse transfer can participate in a caller-owned transaction, preserving ACL checks, canonical replay and atomic stock/event/receipt writes.
- Paid assignment storage combines stock transfer and160MCP through the existing command-point policy. Duplicate/concurrent replay is charged once; current balances/version returned on replay; generic unpaid receipts cannot stand in for paid assignment.
- Real PostgreSQL tests cover insufficient points, late failure after real charge, rollback/retry, eight concurrent duplicate submissions and deficit-only substitution.
- Manual crew projection for regular kinds32/56/119 is5/1/4 respectively. This is static client data projection, not full crew consumption implementation.

## Exact verification boundary

- Latest broader PostgreSQL filter: warehouse/assignment/warp/fleet-repair/command-point83PASS,0FAIL,0SKIP; session28809 terminaled4355, isolated host database `logh7_assignment_tx_20260913`, port55439.
- Integration preflight full protocol test discovery/run:922PASS,0FAIL,216SKIP,total1138 (0c527b). Database environment intentionally unset because E: free space was under0.5GB. Skipped database tests are NOT a full DB-suite pass.
- No actual native assignment command, shared-pool flow or full-game completion verified. Latest observed VM processes: game5968 and server2120. Process presence does not establish login/TCP/rendering.

## Next implementation gates

1. Shared patrol/ground warehouse mapping: original Kind0=fleet,1=transport,2=patrol,3=ground,4=garrison. Allocation selection deduplicates Kind2/3. Resolve both0326 queries and0C0B writes to the same shared stock while preserving requested wire identity. Existing warehouse ACL alone does not establish original role permissions.
2. Shared-pool design approval pending: preserve existing warehouses, add explicit base/power/camp/kind mapping, do not auto-merge stock. `brainstorming` design gate was invoked; no explicit human approval yet. The user's commit/merge request does not approve this separate feature design.
3. Partial-hull policy approval pending: proposed authored complete-units-first / remaining-hulls-last policy, conserving hulls. Original client sends unit delta and BoatNumber0; original partial packing rules remain unknown. Do not interpret0as zero transferred hulls.
4. Restore authoritative card role selectors and target Kind validation. Current static card catalog is authored card39-centered; constmsg labels are not original authority IDs. See `../reverse-engineering/assignment-manual-permissions.json` for original manual matrix/resource consumers.
5. Connect the native handler, response projection and fresh TCP/native run; then resume other strategy/tactical/NPC/content gaps in the broader handoffs.

## Operations / preservation

- Builds use E:/logh7-build/nuget and E:/logh7-build/tmp through NUGET_PACKAGES/TMP/TEMP.
- E: free was443293696bytes after cancelled size diagnostic. Do not delete schemas, dumps, installed game resources or VM files without scoped authorization.
- Host size query session18917/backend42588 was deliberately cancelled and is terminal; it produced no database-size result. Do not poll again.
- Compiled artifacts, original game binaries, VM data, secrets, crash dumps and raw bulk evidence are excluded from this source checkpoint. They remain local. Unrelated worktree changes must remain untouched.
- Persistent objective remains incomplete. After requested integration, wait for the user rather than automatically resuming development.
