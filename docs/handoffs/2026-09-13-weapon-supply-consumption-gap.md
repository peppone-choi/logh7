# Weapon supply consumption gap — 2026-09-13

Status: current source gap identified, no implementation/deployment in this unit.

## Current source evidence

`OriginalStaticUnitShipCapabilities.MissileConsumption` exists (ushort, default0), and `OriginalWorldBootstrapCodec` writes it into030B after missile arms/power/angle. Current server symbol search finds only the declaration and serialization use. Neither player shot processing in `NaturalAuthoritySession.cs` nor NPC firing in `OriginalTacticalBattleRegistry.Npcs.cs` reads it for a debit. Do not claim supplies are consumed by missile fire merely because the field is sent or NPC damage works.

Original logger evidence `work/20260904-warp-state-reverse/evidence/static-unitship-logger-exact.txt` around lines806–807 associates `missile_consumption` with expanded static ship offset+0x7A (u16). This is independent of similarly numbered offsets in unrelated structures.

## Manual evidence and limitation

Local OCR `E:/logh7-greenfield/evidence/manual-variants/internet-archive/gin7manual_djvu.txt`, printed page48, lines2340–2352 describes gun attacks consuming the unit's gun-consumption value and missile attacks consuming the unit's missile-consumption value in military supplies. The heading also says attack-related supply reduction was not implemented at that document's date. This is intended-rule/OCR evidence, not proof of later original-server behavior; original page image and later update notes remain to verify.

No gun-consumption capability field has been joined in the current codec; do not reuse missile consumption for gun or beam fire. Do not multiply cost by hull count without evidence. Current authored capabilities do not assign a nondefault MissileConsumption in the inspected server code.

## Implementation boundary to resolve

The debit must be one accepted-shot transaction with damage/cooldown/replay handling, across both player and NPC paths, not an extra non-atomic SQL update after a hit. Insufficient supplies must avoid damage and debit; stale incarnation, storage failure and replay must not double-charge. Publish updated unit supplies and prove restart/reconnect preservation. Recover/load actual costs or explicitly distinguish authored fixtures; a zero default does not prove unlimited original ammunition.

This gap is separate from the deployed NPC range-selection improvement and pending chief/mission design. The full game goal remains active.

## Transaction path inspected

- `PostgresAccountStore.Damage.cs::SaveOriginalUnitDamageAsync` opens its own connection/transaction, locks the victim account and owned grid unit, validates generation/grid/complement, and updates victim damage/morale plus domain event/account version. Identical cumulative damage is an idempotent no-op. It has no attacker identity or ammunition-debit input.
- `OriginalTacticalBattleRegistry.FleetPersistence.cs::PersistFleetDamageAsync` saves one NPC fleet row through its own store with optimistic revision checking. It likewise has no attacker cost input.
- `OriginalTacticalBattleRegistry.Npcs.cs::AdvanceNpcsAsync` currently prepares movement/fire, persists changed pose, commits the prepared AI state, then awaits `CommitUnitDamageAsync` for the victim. Its fire cooldown state is already committed before victim persistence. Adding a supply save to that sequence would not create an atomic shot; a failure could otherwise produce a charged-but-undamaged shot or the reverse.

Design consequence: introduce a single durable shot operation spanning the appropriate attacker and victim rows, with explicit identity/generation, cost, duplicate-shot identity, consistent lock ordering for two accounts, and post-commit in-memory projection/notifications. Preserve separate player-v-player, player-v-NPC, NPC-v-player and NPC-v-NPC test coverage. The current damage API's duplicate cumulative-count behavior must not be mistaken for a complete shot replay key.

No firing-storage refactor was applied in this investigation. Actual failure/retry tests for the combined operation still need to be written; source inspection alone does not establish an observed live split-commit failure.

## Request identity boundary checked

`OriginalTacticalShootShipCommand` carries Time/Wait/Order/unit list/arms/target kind/target ID, not a durable operation UUID. `OriginalClientInnerFrameCodec.Decode` rejects sequence<=previousSequence. `ProcessSessionServerFollowupAsync` consumes that decoded sequence, but `ProcessTacticalRelayAsync` currently receives payload/type/cancellation only. Transport duplicate rejection is not an atomic database-shot identity.

Existing warp code uses a server-generated connection scope plus decoded sequence and payload fingerprint, explicitly treating a fresh sequence/connection as a new intent. Reuse that documented boundary for player shot persistence rather than deduplicating by cumulative damage or payload forever. Do not claim cross-reconnection retries represent the same intent without client evidence. NPC shots need their own stable attempt identifier retained across a failed commit, distinct from the frame sequence path.

Next implementation requires passing player request identity into shot processing, explicit attacker/victim persistence descriptors (the current victim callback alone cannot join both rows), then one transaction for receipt/debit/damage and post-commit projections. No request-identity or atomic-shot API was added in this inspection.

## Configured combat content boundary

Current `OriginalAuthoredPlayableCatalog.TacticalShipCapabilities` explicitly sets BeamArms1/BeamPower25/BeamAngleMask0x34 but omits GunPower, MissilePower and MissileConsumption, leaving all three zero. `OriginalSubordinateShipCatalog` inherits those common capabilities for armed ordinary kinds32/56/119 and clears arcs for authored unarmed support/picket kinds. Its current per-kind mappings do not introduce missile power or cost.

Consequently the successful missile18 range-selection tests intentionally supplied custom capabilities/curves. They prove that code path, NOT missile availability on the deployed battlefield's current ships. Both a correctly sourced or explicitly authored per-kind weapon catalogue and the atomic supply/damage path are needed to make actual missile combat meaningful. Do not claim a live missile launch based on those test fixtures, and do not simply enable nonzero costs while leaving debit unwired.

Next trace the27 arms IDs to their weapon/effect resources and the served kind-to-capability data, then connect the agreed temporary combat data and transactional firing together. Current live files and saves were not changed in this content inspection.

## Ship effect IDs recovered and test fixture corrected

Fresh Ghidra `ship-arms-effect-map-20260913.txt` verifies004C7790: arms0..7→effect1(BEAM),8..11→effect3(GUN),12..15→effect2(MISSILE);16..26/FF→zero in the ship-attacked receiver. Existing E136 connects effect names to model launch-point entries, so actual model anchors must also be verified before visual claims.

Corrected the recent `OriginalNpcWeaponRangeTests` from18 to12, including the curve row, mounted missile ID, fire event and0426 wire assertion. The earlier18 fixture was not a valid visible ship-missile fixture.41 focused tests passed; receipt `E:/logh7-build/test-results/ship-missile12-20260913/ship-missile12-fixture.trx`. Production/default battle data still beam-only, unchanged; no deployment was required for test-only correction. Do not generalize the zero effect mapping to other game receivers or declare those IDs globally unused.
