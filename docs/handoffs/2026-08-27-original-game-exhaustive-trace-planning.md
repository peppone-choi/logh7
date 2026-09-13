# Handoff - original game exhaustive trace and implementation planning

- status: `PLAN_PASS`; implementation `NOT_STARTED`; live state `UNSEEN`; independent review `UNSEEN`
- approved design: enumerate every protocol, UI, entity/record, resource, client function, authority and persistence path into one typed evidence graph
- recovery boundary: every unresolved value/rule/population must be classified as recovered, statically recoverable, live recoverable, source conflict, original-server lost, original unimplemented, authoring required, or rights-review required
- character-roster finding: current local legacy candidate has 99 mixed-source named rows and 97 candidate-stat rows; only 12 official VII name-to-face-number facts and 2 pixel-confirmed official portrait mappings are locally documented; this is not a recovered complete original roster
- character-roster output contract: separate `originalConfirmedCharacters`, `canonCandidateCharacters`, and `authoredPlayableCharacters`
- mandatory implementation closure: every gameplay feature generates linked reverse-contract, versioned-contract, authority-server, legacy-gateway, new-client, database/replay, content/admin, and QA/independent-review units; omission requires a reasoned `NOT_APPLICABLE`
- design: `docs/superpowers/specs/2026-08-27-original-game-exhaustive-trace-design.md`
- plan: `docs/superpowers/plans/2026-08-27-original-game-exhaustive-trace-foundation.md`
- subordinate topology plan: `docs/superpowers/plans/2026-08-27-original-world-topology-full-trace.md`
- commits: `acc7830`, `73b44ee`, `334be6f`
- goal documents: updated in working tree; not committed because they contained pre-existing user changes
- target/runtime mutations: none; no VM, process, server, protocol, database, executable, or original-resource action
- next start: execute foundation Task 1, defining normalized trace/recovery/implementation contracts and sixteen domains with failing tests first
- forbidden retry: do not treat legacy candidate rosters or revival behavior as original truth; do not finish at inventory/report generation without creating implementation packages
