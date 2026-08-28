# Handoff: original-client manager65 live collector hardening

## Goal

Replace the historical manager65 action-0x2B collector with a corrected, canonical-hash/module/HWND-bound, full-double-capture, read-only collector and fail-closed resolver without running the oracle.

## Scope

- static reconciliation, offline fixtures/tests, collector/resolver, evidence sealing
- no VM, guest, debugger, breakpoint, process-memory operation, input, capture, binary/resource, server/protocol/DB, or VM lifecycle operation
- sealed historical collector artifacts remain unchanged

## Actual work

1. Corrected the old strategy-root alias: `0x02215E2C` is the UI registry-host global; strategy root is `moduleBase+0x89E638`.
2. Bound manager65 controller `strategyRoot+0x130` independently to registry slot `+0x198`.
3. Added internal canonical hash and module-base enforcement before/around process lookup.
4. Added exact pre/post PID/start/hash/module/HWND owner/MainWindowHandle/client-surface checks.
5. Added full semantic double capture, recursive UI origin, manager/input gates, record-count crosscheck, widget gates, logical scale, and engine rectangle.
6. Added exact discrete logical-to-client region resolution.
7. Prevented self-claimed live artifacts from emitting a region or activation point.
8. Retained reproducible fixture outputs, static and artifact ledgers, verification receipt, and report.

## Changed files

- `work/20260828-original-client-manager65-live-collector-hardening/**`
- `docs/handoffs/2026-08-28-original-client-manager65-live-collector-hardening.md`

## Reproduction

```powershell
pwsh -NoProfile -File work\20260828-original-client-manager65-live-collector-hardening\verify.ps1
```

Expected: `PASS`, 44 cases / 91 assertions, four upstream hashes, two reproduced fixture artifacts, nine local artifact hashes, live/process-read/input counters zero, permit false.

## Evidence

- `evidence/static-owner-ledger.json`
- `evidence/fixture-capture.json`
- `evidence/fixture-resolution.json`
- `evidence/artifact-ledger.json`
- `report/original-client-manager65-live-collector-hardening.md`
- authoritative verification is the fresh stdout of hash-bound `verify.ps1`; no mutable self-referential final receipt is retained.
- independent read-only review: `APPROVE` after two `REVISE` cycles

## Confirmed facts

- strategy root is inline at `moduleBase+0x89E638`; the UI registry-host pointer is a separate global.
- manager65 controller is `strategyRoot+0x130`; current bound card ID is `controller+0x358 == strategyRoot+0x488`.
- page is statically valid in `1..5`; action count is `1..24`; stable pre-click selected action index is `-1`.
- record action count must equal controller action count.
- manager65 has one direct widget per action, not manager67's C/D surface pair.
- command `0x2B` must occur exactly once and its full widget gates and logical rectangle must be eligible.
- the safe semantic is `CURRENT_AUTHORITY_CARD_ACTION_WIDGET_FOR_COMMAND_0X2B_WARP_NAVIGATION`.
- only exact `SYNTHETIC_FIXTURE` provenance may resolve offline; live, unknown, and missing provenance cannot emit a region.
- null registry-host, registry, and current-character-owner pointers are rejected before derived address reads.

## Inference and Unknowns

- `ORIGINAL_STATIC`: controller, record, widget, gate, and coordinate relations above.
- `ORIGINAL_OBSERVED`: none in this unit.
- `UNKNOWN`: fresh live card/action values and exact original-runtime client region.
- `UNSEEN`: live snapshot, physical activation, visible WARP, wire, authority, persistence.

## Execution status

- bounded result: `OFFLINE_MANAGER65_HARDENED_COLLECTOR_RESOLVER_PASS_LIVE_UNSEEN`
- runtime observed: false
- player visible: `UNSEEN`
- live operations / process reads / inputs / captures / writes: 0
- permit issued or consumed: false

## Next start

Start a separate offline prelaunch-v4 integration unit. Retire `MANAGER65_LIVE_COLLECTOR_NOT_HARDENED`, retain `FRESH_MANAGER65_SNAPSHOT_MISSING`, and add `MANAGER65_ACTION_0X2B_HIT_REGION_INDEPENDENT_BINDING_MISSING`. Recompute blocker order without changing the activation-budget policy blocker.

## Forbidden retries

- Do not edit or promote the sealed historical manager65 receipt.
- Do not reuse the old wrong-root fixture or its `(760,642)` direct-coordinate point.
- Do not reuse the new fixture region `[560,420,656,436)` or point `(607,427)` as live coordinates.
- Do not treat self-claimed `LIVE_READONLY` JSON as independently bound.
- Do not click, attach, install breakpoints, or consume the one-activation authority in the next offline integration unit.
- Do not promote static/offline PASS to runtime, player-visible, authority, persistence, Gate-A, or Gate-B.
