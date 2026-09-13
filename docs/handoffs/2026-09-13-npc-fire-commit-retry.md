# NPC fire commit retry — 2026-09-13

Status: deployed and postflight verified. Full game goal active; atomic ammunition/damage storage and native validation remain unfinished.

## Reproduced failure

While investigating missile debit, found `PrepareAdvance` committed `_lastShot` before `CommitUnitDamageAsync`. A failed victim write therefore left damage unapplied but consumed the NPC's recharge interval.

Extended `OriginalDamageCommitBoundaryTests.Failed_persistence_prevents_npc_casualty_and_hit_publication`: fail the write at tick120, restore the victim's persistence callback, advance at121, require a fire event and damage25, then require no second fire at122. Red run failed because the recovered tick121 event list was empty. Receipt `E:/logh7-build/test-results/npc-fire-retry-20260913/npc-fire-retry-red.trx`, exit1.

## Fix

`OriginalTacticalNpcController.PrepareAdvance` now separates `Commit` (movement/target/reaction-delay state) and `CommitFire` (successful-fire cooldown). The registry invokes `CommitFire` only after victim damage persistence succeeds. Initial target acquisition still establishes its reaction delay. Already persisted movement is not rolled back on victim-write failure.

This fixes retry timing only. It is not an atomic attacker-supply/victim-damage transaction, durable fire identity, or same-tick replay guarantee. It does not change the original/client wire or invent ammunition costs.

Related NPC and damage-boundary tests:83 passed,0failed,0skipped,exit0. `npc-fire-retry-green.trx` in the same directory. Full isolated-PG suite completed: **1017 passed,0failed,0skipped,exit0**, duration1m52s; `E:/logh7-build/test-results/npc-fire-retry-20260913/npc-fire-retry-full.trx`. Session15788 is terminal. This is source/test verification, not native play or deployment.

The source/test phase made no live changes. Subsequent deployment below replaces that checkpoint.

## Deployment

Self-contained win-x64 Release package `E:/logh7-build/server-npc-fire-retry-20260913`; ZIP SHA256 `BF57D41945DD50B94B360883D8708F63768165F165C24C0AF4E0660C7BAE0567`; DLL SHA256 `C71BF832E4D27AD156C0D0D558B197937066A9E7408CCCCCCC1FE387CD128529`.

Fresh backup `E:/logh7-build/npc-fire-retry-backup-20260913.dump`, SHA256 `AB8F8C09837B5DCD7DE4A5C3A5A54F0C4A2501C10DDBA3CC654AF1523670EAA5`,138207bytes/221 archive entries, UTC16:06:04. Adjacent JSON records PID4760 identity and stable schema44/character/unit/fleet state. Actually restored into retained isolated host55439 DB `logh7_npc_fire_retry_restore_20260913`; candidate startup verified, state hashes unchanged, test PID48644 stopped with ownership validation. Receipt `npc-fire-retry-restore-check-20260913.json` under E:/logh7-build.

Guarded deployment stopped PID4760 only after path/start and no inbound47900 session checks. New **PID3796**, startUTC `2026-09-12T16:08:10.7250065Z`, guest runroot `C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T181000Z-departure-v124`, subdirectory `server-npc-fire-retry-20260913`. Package/DLL hashes verified; schema44→44 and character/unit/fleet state preserved. Receipt `E:/logh7-build/server-npc-fire-retry-deploy-20260913.json`.

Postflight UTC16:08:49 (Sep13 KST), `E:/logh7-build/npc-fire-retry-postflight-20260913.json`: PID3796 responding and owns both47900 listeners; outbound55432 is PostgreSQL, not gameplay. Client PID5968 remains responding with unchanged startUTC11:05:14.5230519Z and no TCP connection. Client not restarted or operated. Old binaries, backups and isolated restored DB retained; no data reset/commit/push. Actual native shot/retry/visual verification remains unproven.
