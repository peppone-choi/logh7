# Exhaustive trace foundation inventory summary

## Baseline status

The bounded foundation baseline is `PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`. The overall game-reimplementation goal is `INCOMPLETE`.

This current summary is bound to `work/20260829-gin7updateclient-resource-loader/evidence/foundation-verification.json`, SHA-256 `04FE7ABB5047051DB3A3A5B9BC527A5FEE7191ECC22578365C18852C230610CE`. The immutable author receipt records 295/295 tests, 32 deterministic artifacts from two fresh complete regeneration roots, 104 protected inputs, unchanged source trees, and independent review as `UNSEEN` at author-run time. A later separate final-state read-only review returned `APPROVE` with blocking findings 0; review receipt SHA-256 `897AAB5A23A1782BF36AF102227EEF709DD91B05F7A1857EE0285808724068B4`. Older receipts and reviews remain immutable historical evidence and do not approve this current state.

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

- Graph: 35,686 nodes and 92,056 edges.
- Graph structural-orphan count: 0, defined by unrepresented source rows + dangling edges + unaccounted joins. This is distinct from unresolved-reference nodes (6,378).
- Coverage verdicts: 15,999 `UNKNOWN`; closed vertical traces: 0.
- Evidence gaps: 25,603. Expanded missing-boundary occurrences: 153,595. These are different denominators.
- Coverage structural fatal count: 1, exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Routing-unresolved rows: 15,317. This is not the recovery-actionable count.
- Confirmed gameplay features: 0. `FEATURE:MOVE_GRID` remains one `INFERRED`, `UNADJUDICATED`, candidate package with `coveragePromotion=false` and eight candidate units.

The graph file is 102,939,563 bytes, SHA-256 `666FAB83E7EAC44CC0A47B1686474B60F46865E144055D3D5D546FAFB7E5AF13`, graph surface `53555073297BED90DF03FA77FF571A58C74E22DCB15E881D7A9DD1C915BAD171`. Coverage is 38,640,366 bytes, SHA-256 `D6A9B3DC3271257590AD831B02445C40AAF2FE826BF3491EEB5C3C30820BF99D`, surface `1947F3E05C124FB781863EE4FF981FCC62B15D4ECF10EAEB187193C963B9D3D3`.

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

- Unit: `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`
- Path: `RESOURCE:FILE:original-installshield-payload:update.ini`
- Boundary: `RESOURCE_LOADER`
- Disposition: `RECOVERABLE_STATIC`
- State: `NOT_STARTED`
- Authorization: `NOT_AUTHORIZED_BY_BASELINE`

This is the mechanical first unit from domain dependency order and deterministic tie-breaking. It is not a recommendation, original-feature proof, or permission to start automatically.

The preceding TXT terms unit `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B` is closed only for the G7MTClient `RESOURCE_LOADER` boundary as `NOT_APPLICABLE`; its next same-row boundary is `RECOVERY:D01:RESOURCE_OWNER:2676D028DBA8EC74`. No evidence-state boolean was promoted.

The primary-client unit `RECOVERY:D01:RESOURCE_LOADER:E346F47C94A6E543` is also closed only for the G7MTClient runtime-asset `RESOURCE_LOADER` boundary as `NOT_APPLICABLE`. It preserves the distinct static relation `Gin7UpdateClient.exe LAUNCHES_PROCESS G7MTClient.exe`; runtime launch and playability remain unobserved. Its next same-row boundary is `RECOVERY:D01:RESOURCE_OWNER:C03D5586366AB472`.

The updater unit `RECOVERY:D01:RESOURCE_LOADER:627680C75CFF6DA7` is closed only for the G7MTClient runtime-asset `RESOURCE_LOADER` boundary as `NOT_APPLICABLE`. The updater is a Windows PE process image with static network/file-update capability and a configurable default game launch, not a runtime game asset. The existing updater-to-client launch relation remains one corroborated edge with source-owned and target-owned evidence; actual network success, update application, runtime launch, and playability remain unobserved. Its next same-row unit is `RECOVERY:D01:RESOURCE_OWNER:516E30687184B38B`.

## Unproved completion claims

Player-visible gameplay, authority, persistence, both factions, unmodified original-client playability, clean-room implementation, PostgreSQL replay, deployment, rights clearance, and a complete original character roster remain unproved. The current greenfield `originalConfirmedCharacters` population is not established as a complete roster.
