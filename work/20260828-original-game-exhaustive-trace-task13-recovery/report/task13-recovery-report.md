# Task 13 recovery and authoring ledger report

## Outcome

Task 13 is `PASS` for deterministic recovery adjudication and authoring-boundary construction. It does not recover a complete original roster, clear rights, close structural coverage, make the original client playable, or complete the clean-room game.

The canonical artifact is `evidence/exhaustive-trace/recovery.json` (32,962,026 bytes), SHA-256 `8841E034F51D526E21E612D0C4D32B2FD88F2913DB3D1D1A7473A68363C324B7`, ledger surface `56AF329E2B8FBF1CD8026014B56B8DDBF335A4C4A9609277478E8B798C010FEF`.

## Conservation and disposition

- 15,999 inventory rows, 230 entity fields, 37 entity populations, and 3 goal-required character datasets produce 16,269 unique recovery subjects.
- 16,267 subjects remain actionable; 2 are recovered original facts; unaccounted subjects: 0.
- Dispositions: RECOVERABLE_STATIC 15,085; RECOVERABLE_LIVE 17; ORIGINAL_SERVER_LOST 1,105; AUTHORING_REQUIRED 60; RECOVERED_ORIGINAL 2; SOURCE_CONFLICT 0; ORIGINAL_UNIMPLEMENTED 0; RIGHTS_REVIEW_REQUIRED 0.
- All eight disposition buckets remain explicit, including zero-count buckets.
- 25,609 coverage gaps are attached to their source subjects and are not double-counted as recovery subjects.
- The structural fatal `FEATURE_REACHABILITY_LEDGER_ABSENT` remains open.

## Evidence and authoring boundary

Recoverable rows require both evidence and a falsifier. Live rows also prohibit retries and writes. Lost and authored rows preserve ordered research history. Authored records use a closed field-level schema: an `ORIGINAL` field requires a confirmed-fact reference and evidence references, while new values must remain `NEW_DESIGN` or `AUTHORED_PLACEHOLDER` with an approval owner.

The three required character datasets are kept separate: `originalConfirmedCharacters` and `canonCandidateCharacters` are `RECOVERABLE_STATIC`; `authoredPlayableCharacters` is `AUTHORING_REQUIRED`. No authored value may be presented as an original fact.

## Character correction

The hash-bound character boundary records 99 legacy named candidates, 97 candidate statistic rows, and 12 official name-to-face facts. Two official portrait references survive, but only one strict pixel mapping is confirmed. The stale two-confirmed-mapping plan claim is retained as a `SOURCE_CONFLICT`, not promoted. The O-group records 513 decoded slots and 397 usable slots; these counts are not a complete confirmed roster.

The general and Japanese web stages found contemporary evidence for four special-slot original characters, but no authenticated complete roster or original server source in that bounded search. The original/manual/runtime stage remains blocked until its evidence is imported into the greenfield source manifest; user adjudication and authored replacement remain not attempted.

## Verification

- Focused Task 13 tests: 15/15 PASS.
- Full exhaustive-trace suite: 260/260 PASS.
- Python compile, LF policy, and `git diff --check`: PASS.
- Checked and run-a artifacts are byte-identical at the canonical SHA-256.
- Independent read-only review initially rejected generic research claims, a permissive authoring schema, and an unbound character boundary. These became RED tests and were fixed. Final verdict: `APPROVE`; validator writes: 0.
- Runtime actions: 0. No VM, original EXE, memory, server, protocol, database, port, input, or lifecycle mutation occurred.

## Next start

Proceed from the remaining foundation plan after Task 13. Any implementation must consume the recovery ledger without converting recoverable, lost, authored, candidate, conflict, or zero-count states into original facts. The structural feature-reachability fatal and player-visible original-client/clean-room gameplay claims remain open.
