# NPC weapon range selection — 2026-09-13

Status: deployed to VM and postflight verified. Native gameplay remains unverified. Full gameplay goal remains active.

**Fixture correction:** the original tests below used18 in the missile slot. Fresh original004C7790 export (`ship-arms-effect-map-20260913.txt`) confirms ship missile effects use12..15;16..26 generate no ship-attacked launch effect in that receiver. Thus old18 receipts prove selection/ID propagation/damage only, not a valid visible ship missile. Tests now use12 and pass41 focused cases (`E:/logh7-build/test-results/ship-missile12-20260913/ship-missile12-fixture.trx`,0failed/0skipped,exit0). No production or deployed capability data changed in this fixture correction. Original historical receipts are retained, not rewritten.

## Reproduction and fix

The authored temporary `OriginalTacticalNpcController.SelectWeapon` previously selected the first powered family with any positive entry anywhere in its eight-bin table. A sparse beam curve with a positive value only at distance bin6 therefore still selected beam ID1 against a target at distance3, even when missile ID18 had a positive current-distance entry.

`OriginalNpcWeaponRangeTests.Npc_uses_effective_missile_instead_of_beam_with_zero_hit_at_target_distance` failed before the production edit: expected18, actual1. Red receipt: `E:/logh7-build/test-results/npc-weapon-range-20260913/npc-weapon-range-red.trx` (one failure, exit1).

The production method now receives current planar distance and prefers the first powered, mounted, nonempty-arc weapon with a positive current-distance entry. If no weapon qualifies at the current distance, it preserves the prior first-usable-family approach fallback. Family order among effective weapons and cooldowns are unchanged. The shared table is cloned once per selection instead of once per family.

This is an **authored AI improvement**, not recovered original-server AI. Existing damage is still the authored deterministic damage model; this edit does not recover original hit probability or change global player shot validation. It does not complete sparse-curve fallback movement, mission AI, offline settings, or chief authorization.

## Verification

- Initial green run: new reproduction plus existing NPC AI tests,39 passed/0 failed/0 skipped, exit0. `npc-weapon-range-green.trx` in the same directory.
- Added registry integration cases for NPC-v-player and NPC-v-NPC: require missile18 in the fire event and 0x0426 wire notification, plus target damage25. These use controlled authored curves, not an assertion that current live ships all have those curves.
- Full suite: **1017 passed,0 failed,0 skipped, exit0**, duration1m52s, including the real isolated PostgreSQL tests (`LOGH7_RETURN_BASE_TEST_DB` at host55439). Receipt: `E:/logh7-build/test-results/npc-weapon-range-20260913/npc-weapon-range-full.trx`. Session53941 is terminal; do not wait on it or restart it. No VM/server restart or live gameplay verification has occurred for this source change.

## Next

Test this change in the original client. The offline-button resource/code parity investigation is in `2026-09-13-offline-direction-sender-trace.md`; mission AI and offline settings are still incomplete.

## Verified deployment

- Self-contained win-x64 Release package: `E:/logh7-build/server-npc-weapon-range-20260913`; ZIP SHA256 `240617E2DE45407B701ED42736629B2F0484F38C91A3806F16FE9E32A95174B2`; DLL SHA256 `2C1E67DBDB92ADFCB12271CC17756C49C970B14F5F6CC52E7EB48DF5F2CEDF43`.
- Fresh operating DB backup: `E:/logh7-build/npc-weapon-range-backup-20260913.dump`, SHA256 `84037526E981C1C3C0E098E3463A7CDC4F86210700322E77468DA9F857F01B15`,138207 bytes,221 archive-list lines. Adjacent JSON captures old server PID7732 identity and no inbound game sessions.
- Actually restored that backup into retained isolated DB `logh7_npc_weapon_restore_20260913` on host55439. Candidate startup and before/after schema44 plus character/unit/fleet hashes match; temporary test PID28604 was ownership-checked and stopped. Receipt `E:/logh7-build/npc-weapon-range-restore-check-20260913.json`. New restore-check script also explicitly fails if the candidate listener does not become ready.
- Deployment receipt `E:/logh7-build/server-npc-weapon-range-deploy-20260913.json`: stopped old PID7732 only after exact path/start revalidation and no inbound47900 sessions. New server **PID4760**, startUTC `2026-09-12T15:43:29.5922850Z`, guest path `C:\Users\logh7-oracle\AppData\Local\Temp\logh7-l1\20260906T181000Z-departure-v124\server-npc-weapon-range-20260913\Logh7.Server.exe`. Expected package and DLL hashes verified. Schema44→44 and character/unit/fleet state preserved.
- Postflight UTC15:44:13 (`2026-09-13` KST): `E:/logh7-build/npc-weapon-range-postflight-20260913.json`. PID4760 owns both47900 listeners and has an outbound PostgreSQL55432 connection. Game PID5968 remains responding, same startUTC11:05:14.5230519Z, no game TCP connection. No game restart or input.
- Old binaries, all backups and restored DBs retained; no gameplay reset, commit or push.
