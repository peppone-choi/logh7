# 2026-09-12 Current-state reconciliation and warp supply preservation

## Verified this turn

The conversation resumed from v249, but current source has substantial subsequent work. Do not reimplement or revert it based on the old transcript. Read `2026-09-09-command-points-and-live-hostile-fleet.md` and subsequent evidence under `work/20260904-warp-state-reverse/evidence/` before selecting the next gameplay task. That handoff records user approval of the authored CP policy on September9, deployment, player fire, withdrawal, completion and subsequent tactical work. Those are historical receipts, not a fresh observation of the currently running guest.

Current source contains migrations through0043 and code registering all35 tactical command types as handled. This registration is NOT evidence that all effects, rights, timing, native rendering, NPC AI or multiplayer content are complete. `every-body-recovered-v409.md` is also behind source: base-support and further handlers were modified after that note. Inspect actual handlers/tests instead of inferring remaining tasks from the old unhandled list.

## Warp invariant verified

Extended the real PostgreSQL test `OriginalWarpCommandPointPostgresTests.A_warp_spends_its_military_points_in_the_move_transaction_and_only_once` with test-owned nondefault supplies23:

- accepted move returns Supplies23;
- retry returns Replayed, without a second320MCP charge;
- current owned DB row still has Supplies23.

Current NaturalAuthoritySession rejects a Replayed result before applying it in both strategic and tactical warp paths. Thus the historical replay object's default supply field is not applied by these paths. No production correction was warranted from the inspected evidence. Do not claim the historical object itself carries current supplies or that every possible caller is safe.

Full current project built and tested with isolated PostgreSQL enabled: **928PASS / 0FAIL / 0SKIP, exit0**. TRX `E:/logh7-build/test-results/reconcile-20260912/reconcile-20260912-full.trx`. This is server/test evidence, not new native gameplay proof. No prior changes were reverted.

## Runtime / preservation

Only the isolated host PostgreSQL at127.0.0.1:55439 was started after checking it stopped and the port free. Exact runtime `E:/logh7-build/return-base-postgres-runtime-v91/pgsql/bin/postgres.exe`, data `E:/logh7-build/return-base-pgdata-v91`, PID36576. Stop normally after final verification; retain schemas/logs. No guest input, process termination, restart, deployment, healing, stock grant or respawn this turn. September8 guest PID/HWND values are stale and must not be used without live revalidation.

## Next start

Use the newer handoff and inspect the latest live-input/read-only scripts to identify current guest ownership/version. Revalidate process/start/hash/HWND before any observation or input. Verify the latest implemented tactical effects with original-client input, authoritative event/DB evidence and client-visible state together. Keep original facts distinct from authored policies, and do not mark full playability from928tests or35 registered handlers. Goal remains ACTIVE.
