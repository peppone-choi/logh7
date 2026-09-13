# Controller profile fixes deployed

## Verified current state

Postflight UTC2026-09-12T15:02:54Z (KST2026-09-13): server PID7732/start15:01:50.0391950Z, guest directory `C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T181000Z-departure-v124\server-controller-profile-live-20260912`. DLL SHA256 `99D4E18DBE09176B1F2D393EB72A8BA8E93410C68400B3E6894B051AA533F1A9`. Both47900 listeners owned by7732, outbound PostgreSQL55432 connection present. Schema44 unchanged.

Game PID5968/start11:05:14.5230519Z remains responding and was not restarted. It has no game TCP connection. One-shot display-idle reset again restored a visible capture; `E:/logh7-build/controller-profile-postdeploy-20260913.png` was inspected and shows the game's LOGIN screen, not strategy/tactics. No authentication input or native gameplay verification occurred.

Included: clearing former-controller character frames, selecting a known matching character record beyond empty subordinate snapshots, persisted offline-controller public identity/merit restoration, and live-instance profile-only refresh preserving runtime pose/supplies. Source regression `live-controller-profile-full.trx`:1014 passed,0failed/skipped,exit0. This does not implement chief selection, mission AI/rewards or generic NPC outfit reparenting.

## Preservation and startup evidence

- `E:/logh7-build/controller-profile-backup-20260912.json` and `.dump`: UTC14:59:18Z, old server2080 identity, owned listeners and no inbound game connections checked. Backup SHA `C994F8AE004BA4D9FBDFC5668E04C11A449A7058EE4882A80CD9190E2C4F910E`,138207bytes,221 listing lines.
- Actual restore into NEW host DB `logh7_controller_restore_20260912` at55439, pg_restore exit0. Restored DB retained.
- `E:/logh7-build/controller-profile-restore-check-20260912.json`: new candidate started as test PID32020; before/after schema44 and full character/unit/fleet hashes match. Test server ownership-checked and stopped.
- `E:/logh7-build/server-controller-profile-deploy-20260912.json`: package and backup hashes verified, state still matched backup, old2080 ownership/start checked immediately before stop, no game connections. New7732 ready on both listeners. `characterUnitFleetStatePreserved=true`.
- Character hash NOW INCLUDES achievement (unlike the previous43→44 upgrade check). No existing merit reset was required or performed. Full unit/fleet row hashes also unchanged.
- `E:/logh7-build/controller-profile-postflight-20260912.json`: fresh process/network check after deployment.
- Package `E:/logh7-build/server-controller-profile-live-20260912.zip` SHA `0A5A2CD99090DC4356DE9CD994298E002E6C50578841218B2A5559199477D17C`. Copy session80774 completed before deployment. Previous binaries, databases and receipts retained.

Scripts are in work/20260904-warp-state-reverse/scripts: guest-controller-profile-backup-20260912.ps1, host-controller-profile-restore-check-20260912.ps1, guest-server-controller-profile-deploy-20260912.ps1, guest-controller-profile-postflight-20260912.ps1, guest-reset-display-idle-20260913-b.ps1. Names dated12 mark the build/backup series; the final KST event date is13. Fixed outputs are consumed; do not rerun blindly.

Next: actual login/scene validation remains user-gated under computer-use authentication restrictions. Chief full rank+achievement tie policy still unanswered. Continue independent server/game implementation without treating these results as full playability or marking the whole goal blocked.
