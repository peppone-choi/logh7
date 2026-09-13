# Timeline

- 2026-08-29: unit opened from commit `f219ff4` and its no-retry handoff. No live or guest operation is permitted in this unit.
- 2026-08-29: TDD RED was observed because the corrected-route evaluator and future preflight collector did not exist.
- 2026-08-29: the first GREEN exposed an invalid compressed `Where-Object role-eq...` filter; explicit role script blocks corrected the zero-count false rejection.
- 2026-08-29: independent design audit required stronger program identity, ordered path reuse checks, full process/module/owner SID tuples, direct vmtoolsd owner lookup, collector source/sealed/round-trip equality, freshness, and explicit one-attempt authority separation.
- 2026-08-29: the status model was changed so even an externally bound live preflight can only become `STRUCTURALLY_READY_AUTHORITY_MISSING`; it cannot authorize or launch a retry.
- 2026-08-29: first final review returned `REVISE` because runId, program-copy provenance, exact session 1, full nested identities, interactive arguments, history, and path-absence evidence were not fully joined.
- 2026-08-29: the contract was revised to bind the same runId through raw receipt, host binding, preflight command, interactive command, program-copy receipt, and path-absence receipt; it now requires exact nested schemas, session 1, canonical paths, owner lookup/name/SID, module identity, immutable history, and ordinal interactive arguments.
- 2026-08-29: final local tests passed 47 cases / 103 assertions and 30 collector capability assertions. Aggregate verification passed with zero VM/guest operations, helper launches, inputs, or physical activations.
- 2026-08-29: second final review returned `REVISE` because an empty history could omit the known v1 attempt; schemaVersion values, nonblank owner/SID, nonzero module bases, and PID uniqueness also needed hard gates.
- 2026-08-29: history now requires exactly one exact-key v1 entry with all four immutable guest paths. All support schema versions, owner/SID syntax, nonzero modules, and distinct positive PIDs are enforced. Final local tests passed 57 cases / 123 assertions plus 30 collector capability assertions.
- 2026-08-29: third independent final review returned `APPROVE` for the bounded offline prelaunch contract only and explicitly confirmed that no VM/guest execution is authorized.
