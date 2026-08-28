# Original game exhaustive trace foundation baseline report

> Historical baseline. The current reseal is documented in
> `work/20260829-exhaustive-trace-foundation-reseal/report/foundation-reseal-report.md`.
> Its receipt and independent-review state supersede this report for current-state claims;
> this file and its sealed receipts remain unchanged historical evidence.

## Outcome

Foundation Tasks 1-14 now produce a reproducible closed-world enumeration and planning baseline. The bounded result is `PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`; the overall game goal remains `INCOMPLETE`.

The aggregate verifier ran 265 tests with zero failures, errors, skips, expected failures, or unexpected successes; validated 21 source-manifest paths; regenerated the complete dependency chain twice under different temporary roots; strict-loaded each same-run predecessor chain; and byte-compared 32 canonical artifacts against each other and the checked baseline. Receipt: `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`, SHA-256 `D2F4EC9D8A5AFE25387E44039DD0C150D06DAA92D39902C85ADA62B8DC6ECFFE`.

## Reproducibility correction

The first aggregate run exposed an actual defect: authority raw evidence embedded temporary absolute paths in its semantic surface. That changed reconciliation hashes and contaminated graph, coverage, domain, work-package, and recovery bindings even when their meanings were identical. A failing staging-path regression test was added, then the authority semantic surface was changed to bind upstream content hashes, row counts, source-root labels, and tree hashes while retaining absolute paths only for raw validation. Focused authority tests pass 36/36.

`authority-source.json` remains intentionally path-sensitive as a raw transport receipt and is excluded from byte identity. Its semantic surface, normalized authority inventory, reconciliation, and every downstream artifact are path-independent and byte-identical. The checked raw receipt is SHA-256 `3B4412EDA0F87DCAA34D58E76F252AB5A62571710D8408FE6A85FC3909A512B5`, semantic surface `64075B19897CDFD77253AE53A94D86E052E1C0990043D1C6C62EF338F20D073D`.

## Baseline counts

- Six inventory rows: protocol 547, UI 422, entity 237, resource 2,194, function 11,497, authority 1,102; total 15,999.
- Graph: 35,685 nodes, 92,053 edges, graph structural orphans 0, unresolved-reference nodes 6,378.
- Domain assignment: 15,999 unique primary keys, unassigned 0, duplicate primary 0.
- Coverage: 15,999 `UNKNOWN`, closed vertical traces 0, evidence gaps 25,609, missing-boundary occurrences 153,601.
- Structural coverage fatal: 1, exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Routing unresolved: 15,317.
- Recovery: 16,269 subjects; 16,267 actionable; 2 recovered; 60 authoring packages; unaccounted 0.
- Recovery dispositions: static 15,085; live 17; server-lost 1,105; authored 60; recovered 2; conflict/unimplemented/rights-review 0.
- Confirmed gameplay features: 0. Candidate gameplay packages: 1. Candidate MOVE_GRID units: 8; `INFERRED`, `UNADJUDICATED`, `coveragePromotion=false`.
- Actual live-action units: 0. Automatic retries: 0. Runtime mutations: 0.

The 17 live-recoverable subjects are not 17 executed live slices. The eight candidate MOVE_GRID units are one reverse unit plus seven target-bearing implementation-planning units, not eight implemented features.

## Hash-bound downstream artifacts

| Artifact | Bytes | SHA-256 | Internal surface |
|---|---:|---|---|
| `graph.jsonl` | 102,935,897 | `CA9955E4FE095B4A9B88049E3BB26F846471A36D6F31EB1440F1E83C742B85EA` | `65B553753259DF830C5C2B86098CED589574A3C6E47868F3DE8613159B8BC6D0` |
| `coverage.json` | 38,641,608 | `7901AE67470CBEC1482A502218DC186FAF7ECFA464427A3B12F794B46322CC71` | `E2DCDAA88A069457ACEFBC43339AD1E54AF943C3E43A63ADE3ED3FBD0C090557` |
| 16 domain packages | 44,707,134 total | receipt contains every file hash | package set `34D67A361BDF67A2FB69935618E350394B7A4346E1B355F3A182E7BDA8C77C77` |
| `domain-plan-inputs.json` | 81,454,264 | `D1472C3B570CB9307C296A423764F79DBCE62A5E5C99C3B232368484FDC22DF4` | `4B7C9DE9E683C682F0D5712430B99C2A22B4D9D30A6EE356137B574A1855E35A` |
| `recovery.json` | 32,962,026 | `7EE58EC93524CA0B1E5FE5D795F13FB4265ABC6DC69DE4EBCA0C32D38270124C` | `8D6DA16D7112512923EB253758211B4D2D34C678C4B19DC6A9D0B0ED21230BAA` |

## First dependency-ordered unit

The deterministic first unit is `RECOVERY:D01:RESOURCE_LOADER:1DFEC1FA0ADCE4B0`, path ``RESOURCE:FILE:original-installshield-payload:_____p_y_`__vii_____t_c_g.url``. It is `NOT_STARTED` and `NOT_AUTHORIZED_BY_BASELINE`. Task 14 does not start it.

## Evidence limits

The foundation enumerates and routes work; it does not implement the game. Player-visible behavior, original-client playability, authority decisions, PostgreSQL persistence/replay, both-faction gameplay, all commands/content, Windows/Linux delivery, administration, and complete character/world content remain unproved. All 15,999 source rows retain coverage verdict `UNKNOWN`.

The sealed aggregate receipt retains its truthful pre-review value `UNSEEN`. After sealing, independent metrics/document and verifier-safety reviews returned `APPROVE`, and an independent full verifier execution ran exactly once with a fresh no-replace temp receipt and returned `APPROVE`. The preserved execution receipt is `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-independent-verification.json`, SHA-256 `EE453CCE7AE62407256AA4A83A896BDCB479A9EC5D39FE899B2AD14175CEAB02`; the review index is `foundation-independent-review.json`. This approval is scoped only to Task 14 baseline reproducibility.

## Forbidden retries and promotions

- Do not automatically start the selected first unit; stop after this handoff and wait for user direction.
- Do not launch or change the VM, original EXE, debugger, process memory, original server/protocol/database, port, physical input, or VM lifecycle from this baseline.
- Do not reuse a consumed live permit. Any future live slice needs fresh PID/HWND/state and its own receipt, at most one authorized physical semantic action, no auto-click, and no automatic retry.
- Do not rerun until hashes happen to match, overwrite checked artifacts before comparison, erase or skip unresolved rows, mask a changed audit exit, ignore a new fatal, promote candidate evidence, auto-author gaps, or retrospectively edit sealed receipts.
- On mismatch, stop at the first exact path/hash/count delta and preserve the failure. Do not loop blindly.
