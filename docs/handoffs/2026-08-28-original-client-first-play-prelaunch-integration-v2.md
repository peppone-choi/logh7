# Original-client first-play prelaunch integration v2 handoff

## Goal and scope

Reconcile the original first-play pre-permit contract with every later WARP UI, destination, TextDialog, wire, foreground, and permit artifact, without running the oracle.

## Actual work

- Bound ten integration artifacts plus eight gap-audit source artifacts by declared, hard-coded, and recomputed SHA-256.
- Split static/offline preparation from fresh live binding.
- Audited all five v1 UI bindings and all seven movement instrumentation requirements.
- Preserved current one-run user authority without converting it into permit or launch eligibility.
- Recorded the three-stage-versus-one-activation contract mismatch.
- Added a deterministic v2 contract, verifier, sixteen mutation cases, and twenty-five mechanically counted assertions.

## Changed files

- `work/20260828-original-client-first-play-prelaunch-integration/**`
- `docs/handoffs/2026-08-28-original-client-first-play-prelaunch-integration-v2.md`

## Reproduction

```powershell
pwsh -NoProfile -File work/20260828-original-client-first-play-prelaunch-integration/verify.ps1
```

Expected: aggregate `PASS`, 16/25 tests, contract state `OFFLINE_PRELAUNCH_AUDIT_PASS_READY_FALSE`, blocker count 12, live operations 0, game inputs 0, permit false.

## Confirmed facts

- Destination projection and corrected TextDialog have offline double-capture/resolver preparation but no fresh live snapshot.
- Manager `0x67` current card and selected captain-card exact hit surfaces still lack collectors.
- The existing manager65 collector is offline-tested but lacks five live-hardening requirements.
- All seven movement instrumentation entries lack movement-specific BP/receipt/correlation bindings.
- The prior live-v3 permit is `CONSUMED_NO_RETRY`.
- Current authority allows one physical activation, while the full v1 WARP proof sequence requires three ordered activations.

## Inference and unknowns

- A later run could be scoped only to an already-prepared confirmation stage, but that would not prove the full physical WARP sequence; no such pre-positioned state is currently bound.
- Fresh PID/HWND/module/listener/heartbeat, manager/card pointers, destination matrices, TextDialog topology, debugger foreground, wire events, and pixels remain `UNSEEN`.

## Execution state

`OFFLINE_PRELAUNCH_INTEGRATION_AUDIT_PASS / READY_FALSE`

First policy boundary: `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`.

First technical boundary: `MANAGER67_CURRENT_CARD_COLLECTOR_MISSING`.

## Independent review

The first review returned `REJECT` and identified unsealed semantic fields, missing provenance for UI/movement gaps, insufficient three-stage evidence binding, and constant capability counters. Those defects were corrected. The second read-only review recomputed every bound hash and semantic check and returned `APPROVE` with validator writes 0.

## Next start

Start one offline unit `MANAGER67_CURRENT_CARD_AND_SELECTED_CAPTAIN_HIT_SURFACE_OWNER`:

1. statically bind current manager67 instance, count, selected index, and exact selected-card widget owner;
2. implement a canonical-hash/module/HWND-bound, double-capture, read-only collector with active/visible and coordinate-frame gates;
3. keep manager65 correction and the movement receipt schema as later separate units;
4. do not run the VM or consume the one-activation authority.

## Forbidden retries

- Do not interpret one activation as the three-stage WARP sequence.
- Do not reuse the consumed permit, information-menu BP01-BP14, fixture points, historical PID/HWND/pointers, or old TextDialog raw rectangles.
- Do not click, retry, attach, write process memory, patch, or change VM/server/protocol/DB state from this handoff.
