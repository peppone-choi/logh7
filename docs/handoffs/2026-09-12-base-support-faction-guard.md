# Base-support faction guard - 2026-09-12

## Reproduced and fixed

ProcessBaseSupportAsync checked that the requested base existed in the current grid but did not check its faction. An encrypted041B request from an Empire player to an Alliance base (and vice versa) healed the player's damage25 to0 in the real encounter registry.

New OriginalBaseSupportAuthorityTests first reproduced the missing refusal, then asserted the actual encounter state: two enemy cases failed with expected damage25/actual0. Two same-faction healthy cases retained the existing REPAIR_NOTHING_DAMAGED response.

Added a pre-effect faction check shared by041B/041C. BaseInformation must identify the base as the player's power; absent affiliation does not authorize use. Refusal is visible and leaves the session open. This is a restoration-authority eligibility rule, not recovered original role/command-rights parity. Same faction alone is still not a complete commander/card authorization model; camp, capture ownership and base command roles need separate handling.

## Verification

- Focused4PASS/0FAIL after two behavioral RED failures.
- Full932PASS/0FAIL/0SKIP using the isolated host PostgreSQL; test handle52053 completed exit0 and was resumed rather than restarted after user interruption.
- TRX: E:/logh7-build/test-results/base-authority-20260912/base-authority-{red,state-red,green,full}.trx.
- Repair enemy-state preservation is directly tested. Supply uses the same guard but no separate end-to-end supply test was added in this unit.
- No guest deployment, native input, live healing, data reset or password operation. Do not call this native gameplay proof.

Current implementation: NaturalAuthoritySession.BaseSupport.cs. Tests: OriginalBaseSupportAuthorityTests.cs. Preserve all unrelated existing work.

## Next

Verify actor-id authorization (the base-support decoder currently discards the header id), base role/camp/capture ownership and positive permitted support effects. The Windows interactive login issue remains separate from these available source tasks. The complete playability goal remains ACTIVE.

## Follow-up: actor binding implemented

The next test reproduced actual repair with forged header actor0 and999: damaged25/destroyed10 became10/10. Actor2 (the authenticated session character) served as the positive control. ProcessBaseSupportAsync now retains the decoded actor field and rejects mismatch before restoring state or taking any effect. The shared check covers041B/041C; direct behavior tests here exercise041B.

Two forged-actor RED failures, one valid-actor pass before correction; after correction all7 base-support tests pass with0fail/0skip. A permitted repair still produces damaged10/destroyed10, so no destroyed hulls revive. Test receipts: E:/logh7-build/test-results/base-actor-20260912/actor-red.trx and actor-green.trx. This follow-up ran the focused suite only; the932-test full result above predates the actor change. No new PostgreSQL process, deployment or native action. Remaining: base role/camp/capture ownership, supply-specific negative/positive tests, persisted and native support effects. Goal ACTIVE.

## Full regression including supply refusals

Fresh full suite: 938 PASS, 0 FAIL, 0 SKIP; process handle89935 completed exit0. Receipt: E:/logh7-build/test-results/base-support-final-20260912/base-support-full.trx. This supersedes the earlier focused-only verification boundary for actor binding.

Three additional encrypted041C cases verify actor0/999 and enemy-base rejection before stock operations. They do not establish successful persisted supply, resource consumption, base command roles, or native UI effects. Valid041B still preserves destroyed hulls. No guest deployment, game input or live state reset occurred in this verification run. Full game playability remains unproven; goal ACTIVE.

## Supply-path audit after full regression

Source inspection confirms041C reaches CompleteSupplyAsync, which calls SupplyOwnOriginalUnitAsync, applies the returned current unit, publishes the participant and pushes EncodeUnits. The PostgreSQL primitive updates supplies and command history in one transaction. Existing OriginalTacticalSupplyPostgresTests exercise this primitive directly, not encrypted041C through the session; this is still a missing end-to-end test.

Separate actionable discrepancy: ProcessTacticalSupportAsync in NaturalAuthoritySession.Support.cs accepts a base performer for0414 when ProjectTacticalBases finds it and HasOriginalEmergencySupplyGrantAsync returns true. This branch has no BaseInformation.Power check. The grant lookup keys account/character/unit/grid, not base identity or faction. Thus041C's new faction guard does not cover this alternate base supply route. Source evidence only: reproduce with an encrypted0414 request and a granted enemy base before fixing; preserve the friendly granted-base positive control and legitimate supply-vessel path. No original role-parity claim follows from this observation.

## Follow-up: alternate base route eligibility

Added four encrypted0414 cases: Empire/Alliance cross-faction bases must reject as SUPPLY_BASE_NOT_FRIENDLY; same-faction bases without grants retain SUPPLY_BASE_NOT_GRANTED. Before the fix, both enemy cases reached the grant check instead (2FAIL/2PASS). Added the same basic BaseInformation.Power eligibility check before that grant lookup. Focused base-support suite now14PASS/0FAIL/0SKIP. Receipts: E:/logh7-build/test-results/emergency-base-20260912/emergency-base-{red,green}.trx.

Boundary: these cases use absent grants; they establish validation order, not an actual unauthorized refill or a successful granted-base transaction. Granted positive supply, fleet support-vessel regression, full-suite regression after this last change, and native verification remain pending. The earlier938 full result predates this0414 change. No guest deployment or credential changes in this unit.

## Later full and replay evidence

Full suite after0414 guard:942PASS/0FAIL/0SKIP, emergency-base-20260912/emergency-base-full.trx. This source was subsequently deployed in server-resume-20260912 (see VM resume handoff).

Strengthened existing OriginalTacticalSupplyPostgresTests with test-only later consumption to23 following an accepted refill to100. Replaying the original fingerprint returns and preserves23, with one supply history row and one supply event. All3 supply PostgreSQL tests pass: E:/logh7-build/test-results/supply-replay-20260912/supply-replay.trx. This characterizes existing correct persistence behavior; no production fix was needed. No live DB change, session-to-DB supply proof or native refill proof follows. Isolated host PG stopped after exact ownership verification.

## Encrypted041C positive integration

Added Base_supply_wire_request_refills_persisted_unit_and_returns_command_echo using real PostgreSQL and real NaturalAuthoritySession from the authenticated/world-entered test boundary. It loads the character through0205, joins via0F02, submits hand-encoded041C actor/target=self, base2/grid102, and verifies supplies23->100 inDB, one history row, unchanged1600/1600CP, successfully decrypted041C echo and a nonempty update-push collection. All4 supply tests pass, E:/logh7-build/test-results/supply-wire-20260912/supply-wire.trx. No production code change. This closes the basic positive session-to-DB path, not native login, decoded update-field correctness, emergency-granted0414, allied-target supply or full original timing/range/resource semantics. Host test PostgreSQL55439 is running after this unit; guest runtime unchanged.

Follow-up also decrypts the sole0325 update and asserts one record, correct unit ID and supplies100 at frame offset38 (record offset30). Focused4PASS receipt supply-wire-fields.trx. Final full suite943PASS/0FAIL/0SKIP, supply-integration-full.trx, handle85681 exit0. Host test PostgreSQL55439 stopped after exact ownership verification. These follow-ups change tests only; deployed production binary remains current. Allied-unit supply and multi-target base commands remain deliberately unimplemented limitations, not covered by the own-unit success case. Native visible execution remains pending.

## Execution-duration fidelity investigation

Source audit:0413 repair commits damage before RecordTacticalCommand;0414 supply commits/apply-pushes supplies before RecordTacticalCommand. The latter records an execution lock until appliedAt+DurationTicks (1800 for these commands). Thus current behavior is immediate effect plus an action lock, not a delayed completion effect. Constmsg labels alone do not prove whether original effects should be immediate, continuous or completion-time. Do not call this timing parity or change it to an invented delay. Next reverse trace: command echo/permission state and progress UI use sites, cancellation behavior and actual original effect notification timing. This limitation is separate from041C, whose duration is not recovered. Own-unit supply integration tests verify current execution semantics only.

## Priority correction from original target evidence

Re-read support-command-target-rules-v399.md:0414 palette case22 uses mask0981, whose bh09 admits node0800 (allied fleet) but not0400 (player flagship).0413 mask0D8F admits both. Existing own-unit-only0414 handler therefore does not implement the ordinary target that the native UI offers. Earlier own-unit041C success is a different command and cannot justify0414 playability. This target mismatch is a more concrete priority than unresolved duration semantics.

Storage discovery: OriginalFleetUnitRecord already has Supplies (default100); PostgresFleetUnitStore reads/writes it and RepairOrdinaryUnitsAsync can consume it to0. No new supplies column is needed. Implement friendly ordinary-fleet supply through generation/revision-guarded persistence plus registry update and notifications; test destroyed/hostile/stale targets and normal support-vessel selection. Do not route another player's target through SupplyOwnOriginalUnitAsync or merely remove the target guard.

## Ordinary supply persistence implementation in progress

Added PostgresFleetUnitStore.Supply.cs / SupplyOrdinaryUnitAsync as a trusted persistence primitive, not command authorization. SQL changes supplies and revision only, guarded by unit/outfit/grid/generation/revision/power/camp/kind, living hulls and supplies<100. Tests assert the whole returned row: successful23->100 changes only supplies/revision; stale revision and destroyed unit leave every field unchanged. Behavioral RED1FAIL/2PASS against the no-effect stub; implemented SQL then7 supply tests PASS. Receipts E:/logh7-build/test-results/ordinary-supply-20260912/ordinary-supply-{red,green}.trx. No migration needed.

NOT yet wired to0414: registry must prepare an NPC supplies snapshot, commit persistence, update its binding revision and publish0325 to observers under the grid lease. Then session must accept original allied ordinary target and retain hostile/destroyed/stale guards. Add session/registry/restart tests before deployment. Current guest server does not include this new primitive. Host test PostgreSQL55439 remains running for continuation; no live data changes.

##0414 ordinary target connected, focused verification only

Real encrypted0414 test with supply vessel2113929484 and allied ordinary target2113929482 first failed as SUPPLY_TARGET_NOT_CONTROLLED (allied-wire-red.trx). Added PrepareSupplyUpdate, CommitFleetSupplyAsync and session target branch: resolve living friendly target, require persisted ordinary NPC binding and matching generation, prepare snapshot, commit supplies-only SQL, advance binding revision, apply snapshot and publish0325 with target entry frames. Snapshot uses encounter casualties so supply does not project stale undamaged values. Own-unit/base paths remain unchanged.

Focused8PASS, allied-wire-green.trx: ordinary target23->100 inDB and registry, complete persistent row equality except supplies/revision. Not yet tested: hostile/stale command-layer cases, observer payload contents, restart, and full suite after this wiring. Do not deploy until these checks; current guest server still runs the prior943-test production version. Duration/range fidelity and other-player flagship supply remain separate unknowns/limits. Host test PostgreSQL55439 remains available.

Observer and fresh-session extension passes8 tests: allied-observer-reconnect.trx. A real registry subscription receives0325 naming the allied target with supplies100; a fresh session/registry backed by PostgreSQL restores100. This is queued protocol delivery and fresh session restoration, not a second native client or OS/server-process restart. Full suite running under handle67543, allied-supply-full.trx; resume that handle rather than restart if interrupted. Hostile/stale command-layer and casualty-in-push tests are still required before deployment.

Full suite handle67543 completed947PASS/0FAIL/0SKIP. Then expanded the existing wire test into four cases: living friendly main fleet succeeds; hostile fleet, concurrently advanced DB revision, and destroyed fleet refuse with unchanged full DB row/supplies23 and no observer notification. Success0325 explicitly preserves damaged25/destroyed10 while supplies becomes100. Focused11PASS/0FAIL/0SKIP, allied-guards.trx, handle33260 exit0. This extends tests only; production code matches the947 full run. These guards are verified in real encrypted session/DB flow, not just storage tests. No deployment yet; live client visual/login issue is unchanged. Remaining fidelity includes original service range/performer occupancy/duration, other-player flagship vs ordinary-unit semantics, and native UI evidence.

## Range predicate refinement from preserved decompile

Read actual beam-range-static-chain-v77.json/functions[targetfilter] rather than only the v401 prose: FUN_004F1180 returns success immediately when the current window enable byte is0. Otherwise it computes normalized target direction relative to source heading, indexes the55-slot radius table and accepts strictly radius>distance. v401 observed enable7/radius1 in6slots while REPAIR was armed, not a universal supply policy. Therefore a hardcoded radius1/39degree server restriction is not justified yet. The preserved rangebuilder is004C7820 (weapon group3/4/5), NOT service initializer004C76E0 referenced by guest-service-window-v404.ps1. Need service-builder decompile and constants used by004DC2B0/004DC320 before reproducing bearing indexing. No range behavior changed in this unit; current server still lacks this original service-window check.
