# Handoff: original-client manager67 current-card hit-surface owner

## Goal

Close the first-play prelaunch bundle's missing manager67 current-card collector without performing any live or input operation.

## Scope

- target: canonical `G7MTClient.exe` SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- static owner proof, canonical page-table byte verification, offline fixtures/tests
- implementation of one read-only live-capable collector and one offline resolver
- no VM, process attach, debugger, breakpoint, input, screen capture, server/protocol/DB, binary, or lifecycle operation

## Actual work

1. Corrected the false root alias: `moduleBase+0x1E15E2C` leads to a UI registry host; the strategy root is `moduleBase+0x89E638`.
2. Bound manager65's signed `I32(strategyRoot+0x488)` current authority-card ID to manager67's reversed U16 list exactly once.
3. Required the reconciled card to contain command `0x2B`, while preserving the caveat that this is not proven to be a captain portrait.
4. Bound page 2 to click-scanned surface C and page 3 to surface D from canonical page-table bytes.
5. Implemented full double capture of process state, manager/controller/list, action records, recursive UI origin, widget gates, scale, engine rectangle, and owned HWND surface.
6. Implemented an offline client-pixel resolver that never emits an automatic activation point and refuses self-claimed live coordinates.
7. Retained static, fixture, resolution, and aggregate verification evidence.

## Changed files

- `work/20260828-manager67-current-card-hit-surface/scope.md`
- `work/20260828-manager67-current-card-hit-surface/timeline.md`
- `work/20260828-manager67-current-card-hit-surface/src/collect-manager67-current-card.ps1`
- `work/20260828-manager67-current-card-hit-surface/src/resolve-manager67-current-card.ps1`
- `work/20260828-manager67-current-card-hit-surface/tests/fixture-identity.json`
- `work/20260828-manager67-current-card-hit-surface/tests/fixture-ready.json`
- `work/20260828-manager67-current-card-hit-surface/tests/test-collect-manager67-current-card.ps1`
- `work/20260828-manager67-current-card-hit-surface/tests/test-resolve-manager67-current-card.ps1`
- `work/20260828-manager67-current-card-hit-surface/evidence/static-owner-ledger.json`
- `work/20260828-manager67-current-card-hit-surface/evidence/artifact-ledger.json`
- `work/20260828-manager67-current-card-hit-surface/evidence/fixture-capture.json`
- `work/20260828-manager67-current-card-hit-surface/evidence/fixture-resolution.json`
- `work/20260828-manager67-current-card-hit-surface/evidence/verification.json`
- `work/20260828-manager67-current-card-hit-surface/verify.ps1`
- `work/20260828-manager67-current-card-hit-surface/report/manager67-current-card-hit-surface-owner-report.md`
- `docs/handoffs/2026-08-28-original-client-manager67-current-card-hit-surface-owner.md`

## Commands

```powershell
pwsh -NoProfile -File work\20260828-manager67-current-card-hit-surface\tests\test-collect-manager67-current-card.ps1
pwsh -NoProfile -File work\20260828-manager67-current-card-hit-surface\tests\test-resolve-manager67-current-card.ps1
pwsh -NoProfile -File work\20260828-manager67-current-card-hit-surface\verify.ps1
```

The collector and resolver were also run once against retained synthetic fixtures to produce `fixture-capture.json` and `fixture-resolution.json`.

## Evidence and verification

- collector tests: `PASS`, 36 cases / 59 assertions
- resolver tests: `PASS`, 6 cases / 19 assertions
- four decompiler/disassembly source hashes verified
- canonical executable hash verified
- canonical page 2/page 3 table bytes and hashes verified directly
- twelve static collector markers verified
- two published fixture receipts regenerated and byte-compared
- ten implementation/test/fixture/evidence/verifier artifacts hash-bound; verification receipt includes the ledger hash and full hash map
- native surface: `OpenProcess(0x410)`, `ReadProcessMemory`, process handle close, and three HWND query APIs
- forbidden capability hits: 0
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE` after one `REVISE` cycle

## Confirmed facts

- `strategyRoot = moduleBase+0x89E638`.
- `manager67Controller = strategyRoot+0x48C`.
- manager65 bound card ID is signed int32 at `strategyRoot+0x488`; -1 is the proven inactive sentinel.
- manager67 count/current-record fields are controller `+0x620/+0x628` and card `i` is `U16(currentRecord+0x26C+(count-i)*8)`.
- controller `+0x624` is a transient pending hit index and must be -1 in this stable pre-click state; it must not be equated to the reconciled card index.
- page 2 selects `controller+0x88+4*i`; page 3 selects `controller+0xC8+4*i`.
- the safe semantic is an authority card whose action list contains command `0x2B`.

## Inference and Unknowns

- `ORIGINAL_OBSERVED`: none in this unit.
- `ORIGINAL_STATIC`: the structures, mappings, gates, page records, and command-list relation above.
- `INFERRED`: the reconciled card is the intended pre-WARP authority card when all collector gates pass.
- `UNKNOWN`: whether the card is visually a captain portrait or a particular named character.
- `UNSEEN`: fresh original-runtime values, exact live hit region, physical activation, visible WARP, protocol/authority/persistence effects.

## Execution status

- bounded result: `STATIC_SELECTED_AUTHORITY_CARD_WIDGET_OWNER_PASS / LIVE_TARGET_UNBOUND`
- original runtime observed: false
- player visible: `UNSEEN`
- writes: 0
- game inputs: 0
- breakpoints: 0
- live permit issued or consumed: false

## Next start

Start a separate offline unit that integrates this collector/resolver into `original-client-first-play-prelaunch-integration-v2`, then recompute the full prelaunch readiness ledger. Preserve the controlling policy blocker `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH` until its contract is explicitly reconciled.

## Forbidden retries

- Do not reuse fixture card ID 200, rectangle `[248,150,344,190)`, or point `(295,169)` as live coordinates.
- Do not call a self-authored `LIVE_READONLY` JSON file independently bound.
- Do not call the authority card a captain portrait without separate evidence.
- Do not treat static, fixture, or test PASS as runtime, player-visible, authority, persistence, Gate-A, or Gate-B evidence.
- Do not consume the user's one physical activation authority in the integration unit.
