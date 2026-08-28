# Exhaustive trace foundation inventory summary

## Baseline status

The bounded foundation baseline is `PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`. The overall game-reimplementation goal is `INCOMPLETE`.

This summary is bound to `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`, SHA-256 `D2F4EC9D8A5AFE25387E44039DD0C150D06DAA92D39902C85ADA62B8DC6ECFFE`. That sealed pre-review receipt honestly records independent review as `UNSEEN`. A later separate full execution and two read-only scoped reviews returned `APPROVE`; the preserved independent execution receipt is SHA-256 `EE453CCE7AE62407256AA4A83A896BDCB479A9EC5D39FE899B2AD14175CEAB02`, indexed by `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-independent-review.json`.

## Six inventories

| Inventory | Rows |
|---|---:|
| Protocol | 547 |
| UI | 422 |
| Entity | 237 |
| Resource | 2,194 |
| Function | 11,497 |
| Authority/persistence obligations | 1,102 |
| **Total** | **15,999** |

Every source row has one unique primary domain assignment. Unassigned rows: 0. Duplicate primary assignments: 0.

## Graph and coverage

- Graph: 35,685 nodes and 92,053 edges.
- Graph structural-orphan count: 0, defined by unrepresented source rows + dangling edges + unaccounted joins. This is distinct from unresolved-reference nodes (6,378).
- Coverage verdicts: 15,999 `UNKNOWN`; closed vertical traces: 0.
- Evidence gaps: 25,609. Expanded missing-boundary occurrences: 153,601. These are different denominators.
- Coverage structural fatal count: 1, exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Routing-unresolved rows: 15,317. This is not the recovery-actionable count.
- Confirmed gameplay features: 0. `FEATURE:MOVE_GRID` remains one `INFERRED`, `UNADJUDICATED`, candidate package with `coveragePromotion=false` and eight candidate units.

The graph file is 102,935,897 bytes, SHA-256 `CA9955E4FE095B4A9B88049E3BB26F846471A36D6F31EB1440F1E83C742B85EA`, graph surface `65B553753259DF830C5C2B86098CED589574A3C6E47868F3DE8613159B8BC6D0`. Coverage is 38,641,608 bytes, SHA-256 `7901AE67470CBEC1482A502218DC186FAF7ECFA464427A3B12F794B46322CC71`, surface `E2DCDAA88A069457ACEFBC43339AD1E54AF943C3E43A63ADE3ED3FBD0C090557`.

## Recovery and authoring

| Disposition | Subjects |
|---|---:|
| `RECOVERED_ORIGINAL` | 2 |
| `RECOVERABLE_STATIC` | 15,085 |
| `RECOVERABLE_LIVE` | 17 |
| `ORIGINAL_SERVER_LOST` | 1,105 |
| `AUTHORING_REQUIRED` | 60 |
| `SOURCE_CONFLICT` | 0 |
| `ORIGINAL_UNIMPLEMENTED` | 0 |
| `RIGHTS_REVIEW_REQUIRED` | 0 |
| **Total** | **16,269** |

Actionable subjects: 16,267. Authoring packages: 60. Unaccounted recovery subjects: 0. A `RECOVERABLE_LIVE` subject is a prospective recovery disposition, not an executed live slice; actual live-action units in this baseline: 0.

## Per-domain generated units

`Recovery reverse` counts source-row recovery contracts. `Candidate reverse` and `candidate implementation` are planning units for the unconfirmed MOVE_GRID candidate; they are not implemented or confirmed gameplay.

| Domain | Recovery reverse | Candidate reverse | Total reverse engineering | Confirmed implementation | Candidate target-bearing implementation | Live action |
|---|---:|---:|---:|---:|---:|---:|
| D01 | 11,499 | 0 | 11,499 | 0 | 0 | 0 |
| D02 | 473 | 0 | 473 | 0 | 0 | 0 |
| D03 | 43 | 0 | 43 | 0 | 0 | 0 |
| D04 | 75 | 0 | 75 | 0 | 0 | 0 |
| D05 | 1,441 | 0 | 1,441 | 0 | 0 | 0 |
| D06 | 31 | 1 | 32 | 0 | 7 | 0 |
| D07 | 88 | 0 | 88 | 0 | 0 | 0 |
| D08 | 58 | 0 | 58 | 0 | 0 | 0 |
| D09 | 12 | 0 | 12 | 0 | 0 | 0 |
| D10 | 60 | 0 | 60 | 0 | 0 | 0 |
| D11 | 89 | 0 | 89 | 0 | 0 | 0 |
| D12 | 2 | 0 | 2 | 0 | 0 | 0 |
| D13 | 14 | 0 | 14 | 0 | 0 | 0 |
| D14 | 130 | 0 | 130 | 0 | 0 | 0 |
| D15 | 1,180 | 0 | 1,180 | 0 | 0 | 0 |
| D16 | 804 | 0 | 804 | 0 | 0 | 0 |
| **Total** | **15,999** | **1** | **16,000** | **0** | **7** | **0** |

The candidate package has eight units total: one reverse-contract unit and seven target-bearing implementation-planning units. No candidate unit is proof of implementation.

## First deterministic unit

- Unit: `RECOVERY:D01:RESOURCE_LOADER:1DFEC1FA0ADCE4B0`
- Path: ``RESOURCE:FILE:original-installshield-payload:_____p_y_`__vii_____t_c_g.url``
- Boundary: `RESOURCE_LOADER`
- Disposition: `RECOVERABLE_STATIC`
- State: `NOT_STARTED`
- Authorization: `NOT_AUTHORIZED_BY_BASELINE`

This is the mechanical first unit from domain dependency order and deterministic tie-breaking. It is not a recommendation, original-feature proof, or permission to start automatically.

## Unproved completion claims

Player-visible gameplay, authority, persistence, both factions, unmodified original-client playability, clean-room implementation, PostgreSQL replay, deployment, rights clearance, and a complete original character roster remain unproved. The current greenfield `originalConfirmedCharacters` population is not established as a complete roster.
