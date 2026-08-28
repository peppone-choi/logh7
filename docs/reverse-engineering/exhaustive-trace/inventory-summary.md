# Exhaustive trace foundation inventory summary

## Baseline status

The bounded foundation baseline is `PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`. The overall game-reimplementation goal is `INCOMPLETE`.

This current summary is bound to `work/20260829-exhaustive-trace-foundation-reseal/evidence/foundation-verification.json`, SHA-256 `2ECF55C94C0D1FBF1D43BD9B9F021B9F76C6122CA7A72386BEDB715E539E9864`. The immutable author receipt records 285/285 tests, two fresh complete regeneration roots, and independent review as `UNSEEN`. A later separate final-state full execution and two read-only review lanes returned `APPROVE`: independent receipt SHA-256 `A629DC9BB0758312B563C523A73660EC4E179CF979646A9FD20CE8B91BCB7FA1`, review index SHA-256 `8312104E52CABF205C42DDB62D74E3F4AA1C7A820E220273EF6DB1517DF483A4`. The older Task 14 receipt and review remain immutable historical evidence and do not approve this reseal.

The reseal explicitly binds `work/ghidra-exhaustive-protocol/ProtocolTrace.rep/idata/00/~00000000.db/db.1.gbf` at SHA-256 `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`. A later `db.2.gbf` revision is preserved but excluded from authority; it was created by a non-read-only diagnostic run and is not silently selected or deleted.

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

- Graph: 35,686 nodes and 92,055 edges.
- Graph structural-orphan count: 0, defined by unrepresented source rows + dangling edges + unaccounted joins. This is distinct from unresolved-reference nodes (6,378).
- Coverage verdicts: 15,999 `UNKNOWN`; closed vertical traces: 0.
- Evidence gaps: 25,606. Expanded missing-boundary occurrences: 153,598. These are different denominators.
- Coverage structural fatal count: 1, exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Routing-unresolved rows: 15,317. This is not the recovery-actionable count.
- Confirmed gameplay features: 0. `FEATURE:MOVE_GRID` remains one `INFERRED`, `UNADJUDICATED`, candidate package with `coveragePromotion=false` and eight candidate units.

The graph file is 102,938,427 bytes, SHA-256 `0F1617D1D8C40C854F9A825CED4B69CA1D4FC47CCB316C6075A612D7A46C5F10`, graph surface `28C275C99FEBDB9F993729FEE1A2BCA34B8A7797038A6C61AE758A2A9A325918`. Coverage is 38,640,987 bytes, SHA-256 `FDF8C398BFD8A93EC88DA4C0033EC31F4277AED182FAD24E6FA662697E6D0F3D`, surface `D0A93944721FEF320B197ECACBA62AEF734CC013416D491AE482024EBEDAFB31`.

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

- Unit: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`
- Path: ``RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt``
- Boundary: `RESOURCE_LOADER`
- Disposition: `RECOVERABLE_STATIC`
- State: `NOT_STARTED`
- Authorization: `NOT_AUTHORIZED_BY_BASELINE`

This is the mechanical first unit from domain dependency order and deterministic tie-breaking. It is not a recommendation, original-feature proof, or permission to start automatically.

## Unproved completion claims

Player-visible gameplay, authority, persistence, both factions, unmodified original-client playability, clean-room implementation, PostgreSQL replay, deployment, rights clearance, and a complete original character roster remain unproved. The current greenfield `originalConfirmedCharacters` population is not established as a complete roster.
