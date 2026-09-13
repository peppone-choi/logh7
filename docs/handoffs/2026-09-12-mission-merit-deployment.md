# Mission/merit fixes deployed with restored-backup upgrade verification

Subsequent source-only correction: [2026-09-12-control-character-projection.md](2026-09-12-control-character-projection.md). It is NOT included in this deployment. Do not confuse its test result with a live client/server update.

## Current live state (supersedes earlier deployment notes)

At 2026-09-12T14:39:50Z postflight: server PID2080, start2026-09-12T14:38:59.7081900Z, guest folder `C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T181000Z-departure-v124\server-mission-merit-20260912`. DLL SHA256 `AAF58DFA76F44481FFAC455F1D3E60913FF5D1426391D53269040C0F70EB88CC`. Both expected listeners47900 are owned by2080; PostgreSQL connection55432 is outbound, not a game connection.

Game PID5968/start2026-09-12T11:05:14.5230519Z remains responding. It was not restarted and has no game TCP connection. Last visual observation before deployment was the login screen restored by display-idle reset; no login or native mission validation was performed.

Live schema is now44. The pending chief codec, faction-wide tactical mission visibility, defence/occupation/interception target validation, NPC independent initial rank/achievement, typed participant merit and fresh self-query merit fixes are now deployed. Chief election,0431 emission, chief-only authorization, mission AI/rewards and complete playability remain unfinished. Full rank+achievement tie policy still awaits user reply.

## Backup and upgrade evidence

- New guest backup and host copy: `E:/logh7-build/merit-backup-20260912.dump`; SHA256 `730CE540A26E97227DE53BC3A7648C23AC8A0E35466E6FA9D2378BCF31DA39A8`,137973bytes,221 archive listing lines.
- `E:/logh7-build/merit-backup-20260912.json`: UTC14:35:04Z, old server1724 identity matched, two owned listeners and no inbound game connections; character/unit/fleet hashes stable across backup.
- Actual host restore: new DB `logh7_merit_restore_20260912` on isolated PostgreSQL55439, pg_restore --exit-on-error --no-owner --no-privileges exit0. No old DB was overwritten.
- `E:/logh7-build/merit-restore-check-20260912.json`: restored hashes match backup; candidate test server22548 upgraded43→44, preserved existing character/unit/fleet hashes and created achievement0. Test server then ownership-checked and stopped. Restored DB is retained.
- `E:/logh7-build/server-mission-merit-deploy-20260912.json`: old1724 path/start verified again, package/backup hashes verified, live data matched backup immediately before deployment, no inbound game connections. Old1724 stopped; new2080 started. Live43→44 and character/unit/fleet preservation PASS. Old binaries/backups retained.
- `E:/logh7-build/merit-postflight-20260912.json`: independent fresh process/listener/client check above.
- Package `E:/logh7-build/server-mission-merit-20260912.zip` SHA256 `1BA538F58B5ABAA2EB43DE6A6C707A2F99A01AABD82DDA5A5BD49E192679275F`. Copy session56264 completed exit0 before deploying.
- Source full regression before publishing: live-merit-refresh-full.trx1012PASS,0failed/skip,exit0.

Preservation checks cover complete JSON rows of original_grid_unit/original_fleet_unit and all preexisting character columns (exclude new achievement; validate it separately as0). They do not claim a byte-for-byte check of every database table or native game correctness.

Scripts in work/20260904-warp-state-reverse/scripts: guest-merit-backup-20260912.ps1, host-merit-restore-check-20260912.ps1, guest-server-mission-merit-deploy-20260912.ps1, guest-merit-postflight-20260912.ps1. Host PowerShell lacks Get-NetTCPConnection; host checks used .NET IPGlobalProperties. Guest cmdlet is available. Do not rerun fixed-destination scripts or reuse consumed receipts.
