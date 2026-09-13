# Handoff: original-client first-play prelaunch manager67 integration v3

## Goal

Integrate the independently approved manager67 bound-authority-card collector into the first-play prelaunch audit without editing the sealed v2 artifacts or running the oracle.

## Scope

- offline artifact/hash inspection and deterministic v3 delta
- no VM, guest, debugger, breakpoint, process-memory read, input, capture, binary/resource, server/protocol/DB, or VM lifecycle operation
- prior v2 contract and verification remain immutable inputs

## Actual work

1. Bound the sealed v2 contract and receipt by recomputed SHA-256.
2. Bound the manager67 verification and artifact ledger, rehashed all ten manager67 artifacts, and cross-checked the receipt hash map.
3. Closed the two prior static collector gaps.
4. Replaced them with separate fresh-snapshot and independent-hit-region live boundaries.
5. Preserved the activation-budget mismatch as the first policy blocker.
6. Recomputed manager65 live collector hardening as the next offline technical boundary.
7. Implemented a schema-v3 contract, gap audit, semantic verifier, mutation tests, aggregate capability audit, artifact ledger, and report.

## Changed files

- `work/20260828-original-client-first-play-prelaunch-manager67-integration/**`
- `docs/handoffs/2026-08-28-original-client-first-play-prelaunch-manager67-integration-v3.md`

No sealed v2 file was changed.

## Reproduction

```powershell
pwsh -NoProfile -File work\20260828-original-client-first-play-prelaunch-manager67-integration\verify.ps1
```

Expected: `PASS`, 16 cases / 28 assertions, 12 blockers, five v3 artifacts, live operations 0, game inputs 0, permit false.

## Evidence

- v3 contract: `evidence/prelaunch-manager67-integration.json`
- v3 UI/movement delta: `evidence/ui-movement-gap-audit-v3.json`
- local artifact ledger: `evidence/artifact-ledger.json`
- aggregate receipt: `evidence/final-verification.json`
- report: `report/original-client-first-play-prelaunch-manager67-integration.md`
- independent read-only review: `APPROVE`

## Confirmed facts

- manager67 current-card and exact page-selected authority-card surface collectors exist and pass offline verification.
- The selected object is an authority card with action `0x2B`; captain portrait identity is not proven.
- A fresh manager67 snapshot and its independently bound hit region are separate runtime requirements.
- The existing one-activation authority does not satisfy the inherited three-stage activation contract.
- v3 remains permit- and launch-ineligible.

## Inference and Unknowns

- `ORIGINAL_STATIC`: manager67 current authority-card owner, page-selected surface, widget gates, and command-list relation.
- `ORIGINAL_OBSERVED`: none in this unit.
- `UNKNOWN`: named character/captain portrait identity and fresh runtime geometry.
- `UNSEEN`: live manager67 snapshot, independent hit region, manager65 snapshot, destination/TextDialog snapshots, foreground probe, WARP pixels, wire, authority, persistence.

## Execution status

- state: `OFFLINE_PRELAUNCH_MANAGER67_INTEGRATED_READY_FALSE`
- blockers: 12
- first policy boundary: `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`
- first next offline technical boundary: `MANAGER65_LIVE_COLLECTOR_NOT_HARDENED`
- runtime observed: false
- player visible: `UNSEEN`
- live operations / process reads / inputs / captures / writes: 0
- permit issued or consumed: false

## Next start

Start one offline unit `MANAGER65_LIVE_COLLECTOR_HARDENING` that adds canonical hash enforcement, module-base binding, full double capture, widget active/visible gates, and post-capture HWND surface recheck to the old manager65 action-0x2B collector. Preserve its existing offline facts but do not run the VM.

## Forbidden retries

- Do not modify the sealed v2 contract or review retrospectively.
- Do not collapse the fresh manager67 snapshot and independent hit-region binding into one self-asserted receipt.
- Do not restore `selected captain` as a proven current semantic.
- Do not reuse fixture card ID, rectangle, safe point, historical PID/HWND/pointer, or consumed permit.
- Do not click, attach, install breakpoints, or consume the one-activation authority in the next offline hardening unit.
- Do not promote this integration PASS to runtime, player-visible, authority, persistence, Gate-A, or Gate-B.
