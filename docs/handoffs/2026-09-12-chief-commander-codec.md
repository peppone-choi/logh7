# Chief commander notification boundary

LATEST: all source changes documented below are now deployed; older UNDEPLOYED labels describe earlier checkpoints. See [2026-09-12-mission-merit-deployment.md](2026-09-12-mission-merit-deployment.md): live PID2080, schema44, restored-backup upgrade and existing character/unit/fleet preservation verified. Chief selection/mission execution/native play remain incomplete.

## Follow-up: fresh self-query merit

Final full regression: `E:/logh7-build/test-results/mission-result-20260912/live-merit-refresh-full.trx`, 1012 passed, zero failed/skipped, exit0, 96 seconds. Release win-x64 self-contained publish also exit0. Candidate `E:/logh7-build/server-mission-merit-20260912` contains44 migrations; DLL SHA256 `AAF58DFA76F44481FFAC455F1D3E60913FF5D1426391D53269040C0F70EB88CC`. Zip `E:/logh7-build/server-mission-merit-20260912.zip` SHA256 `1BA538F58B5ABAA2EB43DE6A6C707A2F99A01AABD82DDA5A5BD49E192679275F`. Candidate only; no guest copy, backup or deployment of this build yet.

0322 self queries previously reread PCP/MCP but retained cached Rank/Achievement. They now refresh both from the same owned storage row before encoding0323 and publishing the current participant. Invalid wire-range rank is rejected. Other character fields are not refreshed by this change.

Extended rank-command integration assertions confirmed0704/0705/0706 already publish current participant merit through the existing processing wrapper; no additional publication was added. `live-merit-refresh-red-run.trx`: 1 failed on self-query rank20 instead of7, 3 rank-command checks passed. `live-merit-refresh-green.trx`: all4 passed. Earlier red build had a nullable test expression error, not behavioral RED.

Still UNDEPLOYED. Next live deployment must include pending migration0044 (source44 vs last live43), a fresh backup, current ownership/connection checks and character-data preservation checks; do not reuse old fixed-path deployment receipts. NPC chief tie choice remains unanswered, but this does not block deploying independently verified corrections.

Latest VM recovery: see [2026-09-12-vm-display-wake.md](2026-09-12-vm-display-wake.md). CLI one-shot display idle reset restored black capture to the game login screen; no restart or login was performed.

## Follow-up: typed participant commander merit

Fresh full regression: `E:/logh7-build/test-results/mission-result-20260912/participant-merit-full.trx`, 1012 passed, zero failed/skipped, exit0, 91 seconds. UNDEPLOYED, no native verification.

`OriginalTacticalParticipantSnapshot.CommanderMerit` now holds an immutable characterId/rank/achievement record, nullable when the controlling character's information is unavailable. Constructor rejects a merit record belonging to a different character. Authored fleet and primary-NPC creation and own-player publication populate it. Movement, supply, same-controller update and casualty projections preserve it; changing controller drops old merit rather than misattributing it. Persisted fleet restoration preserves authored merit or uses the current local player when that player controls the unit; another player's unavailable merit remains null.

Tests: participant-merit-red.trx failed4/4 on absent metadata; participant-merit-green.trx passed4/4. Expanded transition checks first had inaccessible internal-method compile errors (not RED); participant-merit-transition-red-run.trx then failed8/9 on missing preservation/player data/identity rejection. participant-merit-transition-green.trx passed9/9, including actual movement, supply, same/new controller and own-player publication.

Chief selection/0431 emission/mission authorization still not connected. Asked user asynchronously about fully tied rank+achievement: retain incumbent, otherwise lowest character ID as explicit temporary rule. No reply yet; do not claim this is recovered or approved. Other characters' refreshed authority merit and offline NPC reward persistence remain incomplete.

## Follow-up: independent NPC initial rank and achievement

Complete regression after this change: `E:/logh7-build/test-results/mission-result-20260912/npc-merit-full.trx`, 1007 passed, zero failed/skipped, exit0, 85 seconds. Still UNDEPLOYED and native UI unverified.

General fleets now expose `commanderRank`/`commanderAchievement`; legacy primary-NPC templates expose `enemyRank`/`enemyAchievement`. Both character projection factories override the viewer's Rank/Achievement using these content fields. Legacy catalogs default to the existing authored starting rank20 and merit0; these are authored initial values, NOT recovered original NPC data. No live catalog, database or server was changed/deployed.

`OriginalNpcCharacterMeritTests` enters the scene and requests encrypted0323 NPC character responses with two different viewer rank/merit values. It checks both default and explicitly configured values for both NPC paths. `npc-merit-red-content.trx` failed4/4 on inherited viewer rank4; `npc-merit-green.trx` passed4/4. Earlier npc-merit-red and npc-merit-red-restored include fixture setup errors (missing initial character restore / wrong catalog for primary NPC) and are not clean RED proof.

Remaining: NPC achievement mutation/persistence and typed participant rank/merit for chief election; other character fields still use the source-based projection and require independent NPC identity work. Do not claim the entire NPC character profile is viewer-independent. No chief-election integration or native verification in this change.

## Follow-up: mission target entity and side validation

Final complete suite after both changes: `E:/logh7-build/test-results/mission-result-20260912/mission-targets-full.trx`, 1003 passed, zero failed/skipped, process exit0, 82 seconds. UNDEPLOYED; native mission selection remains UNVERIFIED.

`ProcessMissionAsync` now resolves defence1 and occupation2 through local tactical bases plus base-information affiliation. It preserves the original sender tag (defence1, occupation0), rather than treating defence's tag as a ship-table selector. Defence requires same power/camp; occupation requires a different side. Unknown/nonlocal bases and living ships masquerading as base targets are rejected. Interception3 now requires a hostile squadron. Sources: official update04 lines109/114/119, plus previously recovered state19 sender tags.

Isolated PostgreSQL red/green: mission-base-red.trx 9 failed (including valid bases rejected and ships incorrectly accepted); mission-base-green.trx 9 passed. mission-intercept-red.trx 1 failed/1 passed proves friendly targets were accepted. These are command validation changes, not a capture/reward implementation. Ownership currently comes from the served catalog, as elsewhere in this server; persistent dynamic capture remains absent.

Next chief-election hazard found by inspecting current source: `CreateBattlefieldCommanderCharacter` and `CreateTacticalEnemyCharacter` use `source with` based on the viewer and do not override Rank or Achievement. Thus their public character projections inherit viewer merit/rank. Do not use those projections to elect NPC chiefs. `OriginalBattlefieldFleet` currently has Commander name but no independent rank/achievement fields, and `OriginalTacticalParticipantSnapshot` has no typed rank/achievement. This requires independent authoritative NPC identity data and propagation before election, not unconditional current-player selection.

## Follow-up: mission visibility restored at the server boundary

Official local update04 archive (`evidence/manual-variants/wayback/update04-20050204043916.utf8.txt`, lines 88-89) says all same-faction tactical participants see the instruction. `ProcessMissionAsync` now distributes the unchanged 0421 frame to same-power/camp participants in the current grid, not just the selected units. `Publish` gained an opt-in tactical-only filter; other notification callers retain their existing behavior. This changes visibility only, not online player control or NPC objectives.

Integration fixture now checks unselected ally delivery and exclusion of enemy, other-grid and strategic observers, plus unchanged command body and no duplicate delivery. Initial RED included a fixture interaction: scene entry changes strategic observers to tactical after combat-start notification. Final fixture subscribes observers after scene setup. `mission-broadcast-red-isolated.trx` demonstrated the missing ally delivery (1 failed/3 passed); `mission-broadcast-green-isolated.trx` passed 4/4. Earlier `mission-broadcast-green.trx` is a FAILED intermediate receipt, not final success.

Fresh complete suite: `E:/logh7-build/test-results/mission-result-20260912/mission-broadcast-full.trx`, 992 passed, zero failed/skipped, exit 0, 65 seconds. Changes remain UNDEPLOYED and native visibility remains UNVERIFIED. Chief election/permission, base target semantics, mission execution and rewards are still incomplete. Do not interpret broadcast acceptance as authorized chief-only command completion.

Implemented `OriginalWorldBootstrapCodec.EncodeNotifyTacticsChiefCommander` for 0x0431: power byte, camp byte, network-order character ID (six-byte body). Original reader reference: 004A8120, documented in mission-effect-gap-20260912.md and chief-commander-reader-20260912.txt.

Verified missing-method RED in `E:/logh7-build/test-results/mission-result-20260912/chief-codec-red.trx`. After implementation and replacing reflection with a direct call, `chief-codec-green.trx` reports 136 codec tests passed, zero failed/skipped; process exit 0. Fixture checks byte order, field order and absence of expanded-object padding.

Not deployed. This does not implement chief election, faction-wide mission distribution, AI objectives or mission rewards. Next integration must use original rank/achievement eligibility and authoritative participants; do not assign the current player unconditionally. Previous deployment and migration boundaries remain in 2026-09-12-service-observer-deployment.md.

VM read-only check: vmrun list reports oracle-win11-hd-re running; captureScreen succeeded but E:/logh7-build/vm-cli-current-20260912-check1.png is black. No login, input, restart or native mission verification occurred in this continuation.
