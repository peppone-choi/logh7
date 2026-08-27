# Task 11 sixteen-domain routing report

## Outcome

The bounded Task 11 routing contract is `PASS`. Exactly 15,999 source rows have one scheduling primary across D01-D16. Semantic uncertainty is not hidden: 15,317 rows remain in `crossDomainUnresolved`. The input coverage gate remains `STRUCTURAL_FATAL` solely because the complete feature/reachability ledger is absent.

## Routing contract

- Primary domain is deterministic work ownership, not automatic semantic proof.
- Proven seeds use closed typed selectors: entity type, direct protocol semantic identity, exact bound UI labels, typed resource categories, and exact authority source keys.
- A one-wave graph propagation requires an approved relation, semantic/structural edge class, `PROVEN`, `HIGH`, approved join basis, and approved provenance.
- Candidate, name-match, calls, mentions, low-confidence, inferred, unresolved, and source-conflict edges never prove a route.
- No-signal, multi-domain, candidate-only, and conflicting rows receive an explicit provisional primary and remain in the unresolved ledger with candidates, basis, reason, and evidence.
- Configured hard dependencies alone form the acyclic execution DAG. Graph-derived inter-domain edges are retained as labeled soft dependencies, including their candidate or unresolved disposition.

## Package set

- Files: exact canonical `D01.json` through `D16.json`, 44,707,134 bytes total.
- Primary assignments: 15,999, unique 15,999, missing 0, duplicate 0.
- Unresolved: 15,317, exactly equal to every non-PROVEN primary row.
- Secondary references: 4,282.
- Cross-domain dependencies: 3,132.
- D04: 75 primary, 1,342 secondary; required system, planet, fortress, grid, special-body and grid-record rows present.
- D04 plan: `docs/superpowers/plans/2026-08-27-original-world-topology-full-trace.md`, SHA-256 `06801FD8671A8DA5F3E9FD61C55BB33377369F29377A0EC15921D45F246F4D25`.

## Hashes and reproducibility

- Domain config: `A5733017C423EF9EB2F67F44FD7A03AB617DC207E6AA7CC7B8503B51326E7379`.
- Routing policy: `126841E77AD381935BFBED25B7F84CA6E65E3E8CCA1CBDAD45BCEE590CBE3732`.
- Route surface: `5B5F2C2BAE79333945417F0F8B9FD50933B7920DA30A0C84ADC5D21036D95D0B`.
- Package set: `948D5B84EBB6B3B1472CF83C9E675F5E04008BFF140F326C5C486B8306E182DA`.
- Checked/run-a/run-b directory hash surface: `C51061E80CE18F9C5659CA1E354F7102E86CFB9F05070A72B25A0C7867330E81`.
- All three sets contain exactly 16 corresponding byte-identical files.

## Verification

- Focused domain tests: 14/14 PASS.
- Complete exhaustive-trace suite: 230/230 PASS.
- Canonical JSON, strict live graph/coverage/config reproduction, hard DAG, exact file set, tamper/missing/extra checks, and reparse rejection pass.
- Transactional CLI generation stages and verifies the complete directory before publishing it.
- Independent read-only review: initial `REJECT` findings became RED tests; final verdict `APPROVE`, validator writes 0.

## Limits and next start

This unit does not make the original client playable and does not prove any feature, authority path, persistence path, both-faction behavior, Gate-A, or Gate-B. Foundation Task 12 consumes these sixteen packages and emits implementation plus live-validation units; it must retain the feature-ledger fatal rather than treating routing as coverage closure.
