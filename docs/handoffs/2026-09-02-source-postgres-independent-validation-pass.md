# Handoff: recovered source PostgreSQL independent validation PASS

Date: 2026-09-02 (KST evening). Lane: PostgreSQL 복구·권위 상태·재접속 (goal immediate step 1).

## Goal and result

Goal step 1 asked to close the current PostgreSQL recovery with a clean validation and to confirm that account, character, grid and movement data survive a restart.

Result: `INDEPENDENT_SOURCE_DB_VALIDATION_PASS` on validation `20260902T120245Z-source-db-independent-validation-v6`, with `sourceMutated=false`.

The WAL-recovered source cluster of run `20260902T083838Z-natural-l1-relogin-v1` was copied to a throwaway directory inside the guest, the copy was started once on `127.0.0.1:55433` from the `shut down` state, 31 read-only queries ran with exit code 0 and empty stderr, the copy was cleanly stopped and deleted, and the sealed source `global/pg_control` and `pg_hba.conf` hashes were re-verified unchanged afterwards.

Persisted state proven (all values read from the copy of the recovered cluster):

| Item | Value |
|---|---|
| schema_migration rows | 11, `0001_natural_authority_d02` … `0011_original_grid_unit`; 0011 sha256 `9750CEFD…FA92B` matches the sealed pin |
| account | 1 row, `normalized_login=t8405ba3`, status active, authority_version 24 |
| character | 1 row, `character_id=2`, faction 2, sex 1, blood 3, rank 20, face 1000001, ability_values `[84,77,62,70,73,88,82,69]` |
| original_grid_unit | 1 row, unit 2, character 2, authority_card 39, current_cell 101, authority_version 5 |
| original_grid_move_command | 0 rows; grid orphans 0 |
| domain_event | 24 rows, latest `OriginalMessengerMessageSent` (event 24), `OriginalOrderSuggestReplied` (23), `OriginalMailRead` (22, 21, 20) |
| original_mail_message | 6 rows; original_messenger_message 1; original_order_suggest_reply 1; original_character_lottery_entry 1; character_delete_command 3 |

The ability values equal the HUD numbers visible in the last live capture of run `20260902T071235Z` (統率84 政治77 運営62 情報70 指揮73 機動88 攻撃82 防御69), so the recovered row is the same character the original client last rendered. Name columns are stored as the original CP932 wire bytes and appear as mojibake through the console; that is expected and was not altered.

Restart survival: the cluster went `shut down` → started → served every table → `shut down` again with `pg_ctl stop` exit 0, `postgres.exe` count 0, listener gone, no `postmaster.pid`.

## Root cause of the blocked pinned Item120 finalizer

The pinned host runner `host-run-item120-clean-validation.ps1` was executed once with `-Execute` (finalization `20260902T113611Z-item120-clean-validation-v1`). It failed in 6.7 s at guest stage `ITEM120_CLEAN_VALIDATION_FORBIDDEN_RUNTIME`. Read-only guest census then established:

- `127.0.0.1:47900 LISTENING` is owned by PID 2952 `svchost.exe -k NetSvcs -p -s iphlpsvc` (Windows IP Helper), created at guest boot `2026-09-02T10:30:04Z`.
- `netsh interface portproxy show v4tov4` contains one rule: `127.0.0.1:47900 → 192.168.203.1:47900`. IP Helper is the service that implements portproxy. This is the standing host↔guest authority forwarding rule, not a running LOGH7 authority server.
- `Logh7.Server.exe`, `postgres.exe`, `G7MTClient*.exe` were not running; no 55432 listener; source `postmaster.pid` absent; source `pg_control` = sealed clean hash `348153D848E464C416983638B69EA84508C35B0598BA3BD46467DFC4BF94BC09`.
- The successful item117 authority bound `202.8.80.179:47900` (guest LAN address), so the loopback portproxy does not conflict with the real authority listener.

Therefore the guest finalizer's gate `netstat … ':47900\s+.*LISTENING'` is a false positive whenever the portproxy rule exists after a reboot. A second latent failure was also found: the finalizer expects binaries under `<source>\pgctl-runtime\bin`, but the source run contains only `pg-runtime-full.zip` (sha256 `37E1C5CF…91E599`); that directory does not exist, so `ITEM120_CLEAN_VALIDATION_RUNTIME_HASH_INVALID` would follow even after the port gate is fixed.

Minimal fixes for the owner of the pinned scripts (not applied here, to preserve the codex lane's hash pins):

1. Replace the raw `:47900 LISTENING` test with a process-identity test: fail only if the listener's owning PID is `Logh7.Server.exe` (or any non-`svchost.exe`/non-`iphlpsvc` owner), and record the portproxy rule instead of treating it as a runtime.
2. Extract `pg-runtime-full.zip` to a fresh temp bin (verify the five pinned binary hashes there) instead of requiring a pre-existing `pgctl-runtime\bin`.
3. Recreate the empty PostgreSQL directories that robocopy/Copy-Item drop (`pg_commit_ts`, `pg_dynshmem`, `pg_logical\mappings`, `pg_logical\snapshots`, `pg_notify`, `pg_replslot`, `pg_serial`, `pg_snapshots`, `pg_stat_tmp`, `pg_tblspc`, `pg_twophase`, `pg_wal\archive_status`, `pg_wal\summaries`) before starting any copied cluster. The source data directory itself only contains `base, global, pg_logical, pg_multixact, pg_stat, pg_subtrans, pg_wal, pg_xact`.

## Actual execution and corrections (all attempts sealed, none reused)

| Attempt | Result | Cause | Correction |
|---|---|---|---|
| pinned Item120 finalizer `20260902T113611Z` | guest `FORBIDDEN_RUNTIME` | portproxy on 47900 | diagnosed; pinned scripts left untouched |
| v1 `20260902T114507Z` | `COPY_NOT_SHUT_DOWN` | `-join` flattened pg_controldata lines | per-line parse |
| v2 `20260902T114603Z` | `COPY_START_FAILED:1` | missing empty PostgreSQL dirs, no log captured | recreate dirs, capture pg_ctl/log |
| v3 `20260902T114808Z` | host timeout, guest PowerShell hung | copy under `C:\ProgramData` owned by Administrators → `PANIC: could not open file "global/pg_control": Permission denied` under pg_ctl's restricted token; receipt write hung because Get-Content objects were serialized with provider metadata | copy under `C:\Users\logh7-oracle\AppData\Local\Temp`, cast log lines to strings; hung PID 9848 stopped, v3 temp removed |
| v4 `20260902T120026Z` | server started and stopped cleanly but reported failure | `Start-Process -PassThru` with redirection returns `$null` ExitCode unless the handle is cached | `$null = $start.Handle` |
| v5 `20260902T120123Z` | all required queries PASS, optional query on nonexistent `original_grid_cell` failed | catalog tables are server-authored, not in DB | replaced optional queries |
| v6 `20260902T120245Z` | `INDEPENDENT_SOURCE_DB_VALIDATION_PASS` | — | — |

Every attempt verified afterwards that the source `pg_control` and `pg_hba.conf` hashes were unchanged and that no `postgres.exe` or 55433 listener survived.

## Changed files (this worktree, branch `claude/logh7-original-client-restore-cd439d`)

- `work/20260902-source-db-independent-validation/guest-validate-source-db-copy.ps1` — sha256 `FD826947173CE6E8983AAD6545196F65B54009288B16608442EFA5949C4985A5`
- `work/20260902-source-db-independent-validation/host-run-validation.ps1` — sha256 `D9F0384CD00524554B151E877AEE97C690920E6EA266946FEB63F5CAC4DA32F5`
- `work/20260902-source-db-independent-validation/evidence/20260902T120245Z-source-db-independent-validation-v6.json` — sha256 `7C9F54E9D2066C6D766F3E0CF5935209DA01427FB066E0BA1260EED7DBDF8102` (PASS receipt)
- `work/20260902-source-db-independent-validation/evidence/*.json` — sealed failed attempts v1, v2, v4, v5, the v3 post-mortem census, and read-only guest censuses (runtime, PID 2952, portproxy, postgresql.conf)
- this handoff

Files written into the codex lane worktree `E:\logh7-greenfield\.worktrees\natural-authority-d02` as a sealed failed attempt of its own pinned runner (not edited otherwise): `work/20260829-natural-authority-d02/l1-live/20260902T093054Z-natural-l1-relogin-v1/20260902T113611Z-item120-clean-validation-v1-host-clean-validation.json` and `…-guest-error.json`; plus read-only census outputs under `work/20260902-item120-clean-validation-live/`. No pinned script, server, migration, client, VM lifecycle or source database file in that worktree was modified.

## Reproduction

```powershell
pwsh -NoProfile -File work/20260902-source-db-independent-validation/host-run-validation.ps1 -ValidationId (([datetime]::UtcNow.ToString('yyyyMMddTHHmmssZ')) + '-source-db-independent-validation-v7')
```

Requires the running VMX `E:\logh7-vms\oracle-win11-hd-re\oracle-win11-hd-re.vmx`, the DPAPI guest secret `E:\logh7-vms\oracle-win11-hd\.secrets\guest.dpapi` (decrypted in host memory only), and the pinned native VIX wrapper `session-recovery-vix.cs` (sha256 `9232250A…4258`). Each run copies the source once (67,016,885 bytes, 1,335 files), uses port 55433, and deletes its copy.

## Facts, inferences, unknowns

Facts (`ORIGINAL_OBSERVED` for the guest, `RUNTIME_OBSERVED` for the DB): everything in the tables above; guest boot `2026-09-02T10:29:45Z`; interactive console session 1 active (explorer/vmtoolsd session 1); guest free RAM at census 508 MB of 8,191 MB; free disk ≈1.75 GB; guest user `logh7-oracle` holds the Administrator role, so `postgres.exe` must be launched through `pg_ctl` (restricted token) and its data copy must be readable by the non-admin token.

Inferences: the item118 crash-era `postgres.log` lines (`win32 error 1450`, `FATAL: out of memory` at 17:57 KST) predate the WAL recovery and are not reproduced by the recovered cluster; the recovered cluster is fit to be copied forward into a fresh gameplay run.

Unknown: whether the guest's low free memory affects a concurrent authority + client + PostgreSQL run (the earlier 1450 errors suggest pressure); whether the codex lane intends to keep the pinned finalizer or accept this independent validation as the Item120 closure.

## Current processes, VM, ports

- VM `oracle-win11-hd-re` running, guest IP `202.8.80.179`, VMware Tools running.
- Guest: no `G7MTClient*.exe`, `Logh7.Server.exe`, `postgres.exe`; `127.0.0.1:47900` = iphlpsvc portproxy (standing); no 55432/55433 listener.
- Host: `vmware-vmx` PID 20588 holds VNC `127.0.0.1:6001`; no host node/dotnet authority listener.
- Source run `20260902T083838Z…` in guest `%TEMP%\logh7-l1` is intact and cleanly stopped.

## Next play blocker and next start

Goal step 2: a fresh sealed run that copies this recovered cluster forward, deploys the v128 authority (`logh7-server-win-x64.zip` sha256 `DE6456A1…1A97B`) and the disposable original client, and reaches the login screen with a fresh PID/HWND. Two variants are ready to be sequenced: (a) without the Korean `d3d8.dll` proxy to re-establish the item117 baseline on the recovered DB, then (b) with the fixed proxy `B5AA1848…942128` to close `PLAYER_VISIBLE_KO`. The codex lane's Item121 host runner is the pinned path for (b); it will hit the same 47900/pgctl-runtime gates until the fixes above are applied by its owner.

Next start for this lane: build the fresh-run wrapper from the last successful generated wrapper `guest-wrapper-v112-successful-generated.ps1` (sha256 `38C562E6…DFA9`), pointing its `previousData` at the recovered source and recreating the empty directories, and launch exactly once.

## Forbidden retries and exaggerations

- Do not start the sealed source cluster in place; any start changes `pg_control` away from `348153…` and breaks the codex lane's pinned prestate.
- Do not treat a `:47900` listener as an authority server without checking the owning process; do not remove the portproxy rule to satisfy a gate.
- Do not place a PostgreSQL data copy under `C:\ProgramData` when created by an elevated token.
- Do not serialize `Get-Content` output with `ConvertTo-Json` without casting to `[string]`.
- Do not call this PASS a live original-client, Korean-runtime, movement, authority, reconnect, Gate-A or Gate-B result; it proves persistence and restart survival of the recovered database only.
