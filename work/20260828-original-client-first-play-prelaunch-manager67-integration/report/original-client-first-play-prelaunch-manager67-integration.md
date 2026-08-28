# Original-client first-play prelaunch manager67 integration v3

## Result

- offline integration audit: `PASS`
- state: `OFFLINE_PRELAUNCH_MANAGER67_INTEGRATED_READY_FALSE`
- permit eligible: false
- launch eligible: false
- live operations: 0
- game inputs: 0
- permit issued: false

This unit layers a v3 delta over the sealed v2 contract. It does not edit or reinterpret the prior v2 receipt.

## Closed static gaps

The independently approved manager67 collector closes:

- `MANAGER67_CURRENT_CARD_COLLECTOR_MISSING`
- `SELECTED_CAPTAIN_CARD_WIDGET_COLLECTOR_MISSING`

The second historical name is not retained as a current semantic claim. The proven object remains `AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT`.

The integration directly binds the manager67 verification receipt and its artifact ledger, rehashes all ten manager67 artifacts, and cross-checks the verification receipt's full hash map.

## Remaining live boundaries

Closing the static gaps does not produce a live click coordinate. Two runtime boundaries replace them:

- `FRESH_MANAGER67_AUTHORITY_CARD_SNAPSHOT_MISSING`
- `MANAGER67_AUTHORITY_CARD_HIT_REGION_INDEPENDENT_BINDING_MISSING`

They remain separate because the resolver intentionally rejects a self-claimed `LIVE_READONLY` JSON artifact and emits no region until an independent binding step succeeds.

## Readiness recomputation

There are still twelve ordered blockers. The first policy blocker remains:

`ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`

The first next offline technical blocker is now:

`MANAGER65_LIVE_COLLECTOR_NOT_HARDENED`

The current authority allows one physical activation while the inherited WARP sequence contract still describes three ordered activation stages. This unit does not collapse or bypass that mismatch.

## Verification

- mutation tests: 16 cases / 28 assertions
- v3 bound artifacts: 5
- manager67 artifact hashes reverified: 10
- local integration artifact hashes: 5
- production write capability hits: 0
- forbidden/live capability hits: 0
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE`

## Boundaries

- original runtime: `UNSEEN`
- player-visible WARP: `UNSEEN`
- outbound/inbound wire observation: `UNSEEN`
- authority and persistence: `UNSEEN`
- Gate-A/Gate-B: not passed

The next bounded offline unit is manager65 live collector hardening. It must not run the VM or consume the physical activation authority.
