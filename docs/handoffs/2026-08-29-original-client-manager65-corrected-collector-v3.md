# Handoff: original-client manager65 corrected collector v3

## Goal and result

Replace the superseded manager65 action-`0x2B` collector with a bounded, live-capable read-only collector and independent offline evaluator that remove two disproven launch blockers without fabricating a live WARP receipt.

- bounded result: `PASS`
- independent review: `APPROVE` after one `REVISE` round
- execution state: `OFFLINE_CORRECTED_MANAGER65_ACTION_0X2B_COLLECTOR_V3_PASS_RUNTIME_UNSEEN`
- overall game goal: `INCOMPLETE`

## Scope and actual work

- Kept the sealed historical manager65 units immutable and created `work/20260829-original-client-manager65-corrected-collector-v3`.
- Separated `U32(moduleBase+0x1E15E2C)` UI mode/registry host from inline strategy owner `moduleBase+0x89E638`.
- Removed strategy-owner `+0/+4/+0xF4` builder/handler/WARP mode interpretations.
- Bound manager65 through owner `+0x130` and registry `+0x198`, and manager67 through owner `+0x48C` and registry `+0x1A0`; manager67 must be dormant during the manager65 WARP-action stage.
- Replaced engine-viewport/full-HWND equality with a nonempty engine viewport contained by the stable owned HWND. The action region is inverse-resolved from the logical widget rectangle and current scale into owned-HWND client pixels.
- Added run ID, capture timestamps, an externally expected identity-receipt SHA, complete A/B snapshots, exact nested semantic validation, and zero-operation counters.
- Added PowerShell 5.1 compatibility, create/replace atomic receipt publication, 7 tests, and 62 mutation subtests.
- Performed no VM, guest, debugger, target process, capture, input, permit, server, protocol, database, binary, or resource operation.

## Changed files

- `work/20260829-original-client-manager65-corrected-collector-v3/**`
- `docs/handoffs/2026-08-29-original-client-manager65-corrected-collector-v3.md`
- shared `report/manual.md` and `report/mistakes.md` were updated with operating lessons and are excluded from the bounded commit if they contain unrelated prior work

## Reproduction

```powershell
pwsh -NoProfile -File work/20260829-original-client-manager65-corrected-collector-v3/verify.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File work/20260829-original-client-manager65-corrected-collector-v3/verify.ps1
```

Both commands return `PASS` with 7 tests, 62 mutations, 4 static source hashes, canonical executable verification, 150 fixture reads, and all target mutation counters zero.

## Evidence

- `evidence/static-source-ledger.json`
- `evidence/fixture-capture.json`
- `evidence/fixture-evaluation.json`
- `evidence/independent-review.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `report/original-client-manager65-corrected-collector-v3.md`

## Confirmed facts

- The old strategy-owner mode fields and full-HWND engine-viewport equality were false blockers.
- The v3 collector uses only seven read/query native APIs and `OpenProcess(0x0410)`; it has no process-write, input, debugger, VM, server, protocol, or database mutation API.
- The synthetic corrected fixture yields one eligible command `0x2B`, manager67 dormant, a client region `[560,420,656,436)`, and fixture-only safe point `(607,427)`.
- A self-asserted `LIVE_READONLY` provenance cannot become an offline fixture PASS or a live binding.
- The evaluator externally binds capture, collector, run ID, and identity-receipt hashes and rejects a different valid 64-hex identity digest.
- Every result preserves `automaticActivationPoint=null`, live/WARP/launch/permit eligibility false, and the existing interactive-HWND blocker.

## Inference and claim boundary

- The corrected formulas and widget semantics are static/fixture evidence with partial prior runtime structural corroboration. They do not prove a current live action rectangle.
- Allowing HUD client pixels outside the engine viewport is correct for the offline model because the viewport is contained inside the larger owned HWND, but any live hit region still requires fresh current-scale replay and independent binding.
- Offline candidate PASS is not WARP, packet, authority, persistence, pixel movement, both-faction play, Gate-A, or Gate-B evidence.

## Unknown and unverified

- Fresh interactive owned HWND, PID/start/module, listener, heartbeat, foreground, debugger attach, and initial movement-breakpoint state.
- A corrected live manager65 capture, independent live hit-region receipt, and live prelaunch review.
- Stage-gate v1 remains incompatible with the current one-activation WARP authority because it hard-requires three activations; no adapter may fabricate three.
- Physical WARP, destination, confirm, outbound `0x0B01`, inbound `0x0B07`, server authority, persistence, or player-visible movement.

## Next start

Create a separate offline WARP stage-gate v2 contract. It must accept an externally supplied expected stage-local activation budget instead of hard-coding three, consume the corrected manager65 v3 capture only through an independently reviewed external binding bundle, preserve future destination/confirm as not-created, and emit no activation point or permit from current evidence. Do not execute a live route in that unit.

## Forbidden retries and promotions

- Do not edit or promote the sealed v1/v2 manager65 collectors or their old fixtures.
- Do not reuse the old live manager65 receipt, PID/HWND, root fields, rectangle, or fixture safe point.
- Do not infer manager67 and manager65 are simultaneously active.
- Do not require engine viewport equality with the full HWND.
- Do not let a run ID, provenance string, or self-reported eligible flag create live authority.
- Do not attach, install breakpoints, read target memory, capture, send input, issue a permit, alter VM lifecycle, or change server/protocol/database state from this completed offline unit.
- Do not call this result original-client playability, movement, authority, persistence, both factions, Gate-A, or Gate-B.
