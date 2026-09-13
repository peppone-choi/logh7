# Reassigned ships must not serve the former controller's character

LATEST: these fixes are now deployed. See [2026-09-13-controller-profile-deployment.md](2026-09-13-controller-profile-deployment.md), live PID7732/schema44, full existing character (including achievement), unit and fleet preservation verified. Earlier UNDEPLOYED notes are historical checkpoints. Native verification remains pending.

## Persisted offline-controller restoration

Latest full regression after live-instance correction: `E:/logh7-build/test-results/mission-result-20260912/live-controller-profile-full.trx`,1014 passed,zero failed/skipped,exit0,108seconds. The updated candidate below matches this source; live server still uses the earlier mission-merit build.

Further live-instance gap reproduced and fixed: existing NPCs were skipped during scene restore, so a new observer still received old rank20 instead of stored7. `live-controller-profile-red.trx` failed2/3 (both existing-instance variants); `live-controller-profile-green.trx` passed3/3. Scene restoration now refreshes only CharacterFrame/CommanderMerit on matching unit/generation/character/side, preserving current pose, supplies, damage, corps and AI state. Tests deliberately retain runtime X15 and supplies47 distinct from older storage values.

Updated Release win-x64 self-contained candidate: `E:/logh7-build/server-controller-profile-live-20260912`, DLL SHA `99D4E18DBE09176B1F2D393EB72A8BA8E93410C68400B3E6894B051AA533F1A9`; zip SHA `0A5A2CD99090DC4356DE9CD994298E002E6C50578841218B2A5559199477D17C`. No guest deployment yet. For the next44→44 deployment, preservation hashes MUST INCLUDE achievement now that the live column exists; do not reuse the prior43→44 query that deliberately excluded the new column. Fresh backup and ownership checks required.

Intermediate pre-live-refresh full suite `offline-controller-full.trx` passed1014/1014, exit0,99seconds. Intermediate candidate folder `E:/logh7-build/server-controller-profile-20260912` DLL `B1B4BB6FB7BDBA27A8E25B34A2C593C6DEFF06A86C9D5602367B260EC00C0C54`; zip SHA `5676B8EF7593A840779D5CE777BB6398CC44671B1BF9512E480128B6A80D69CB`. DO NOT DEPLOY that intermediate package: it lacks the subsequent live-instance refresh fix. It is retained as evidence.

Fleet scene restoration now reads the actual persisted controller's public character record even when that controller has no session in the registry. New `PostgresFleetUnitStore.ReadControllerCharacterAsync` joins the exact fleet unit/grid/generation/revision/controller to the current controller incarnation, character/account identity and current card. It excludes injured controllers and mismatched faction, and does not select account secrets or PCP/MCP. Card absence retains existing authored WorldCardId fallback, not an inferred original role.

`ProjectStoredCharacter` is a pure extraction of the existing character mapper; the own-character wrapper still updates own balances, while foreign controller projection cannot overwrite them. Fleet restoration now supplies the controller's0323 frame, flagship unit ID and typed merit instead of an empty frame/null merit. This is scene-restore behavior; dynamic cross-session authority refresh and offline AI/reward logic remain separate work.

Real PostgreSQL test extends three existing assignment/rollback/restart cases: fresh observer registry has no owner session;0322 now returns the stored controller with rank7/achievement1234, while the stored987PCP/654MCP are zero in the public frame. `offline-controller-red.trx` failed3/3 at the previously invalid query; `offline-controller-green.trx` passed3/3. Additional exact-binding rejection checks cover other grid, changed generation/revision and another controller ID.

Still UNDEPLOYED after live PID2080/schema44. No migration added by this change.

Fresh full regression: `E:/logh7-build/test-results/mission-result-20260912/assignment-character-full.trx`,1014 passed,zero failed/skipped,98 seconds. This source correction is not in the already-deployed mission-merit package.

## Finding and change

`PrepareControlAssignment` changed Ship.Character but retained CharacterFrame for the previous character. A0322 query could therefore return0323 with the wrong character ID if a lower-unit-ID subordinate sorted before the actual controller. It could also falsely succeed when the new controller had no public character record at all.

Changing controller now clears the stale character frame, alongside the already-invalidated merit record. Same-controller updates preserve the frame.0322 participant lookup skips empty character frames and continues to the actual known controller record. No fictitious replacement character is created.

`OriginalNpcAssignmentCharacterTests` drives real registry assignment then encrypted session0322. RED2/2: absent controller incorrectly Success; present controller requested2 but response carried1. After fix, assignment-character-green.trx9/9 passed (new two cases plus existing assignment projection/batch checks). These tests are server-boundary proof, not native label proof.

UNDEPLOYED after the previous mission-merit deployment. Live remains PID2080/schema44 as documented in2026-09-12-mission-merit-deployment.md. Generic NPC-to-NPC outfit reparenting is still explicitly unimplemented; this change must not be confused with implementing0420's native fleet-target operation. Command RequestId semantics have not been newly traced here, so no character-ID restriction was inferred from that name.
