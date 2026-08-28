# Original-client first-play prelaunch integration audit v2

## Result

`OFFLINE_PRELAUNCH_INTEGRATION_AUDIT_PASS / READY_FALSE`

The later destination projection and corrected TextDialog collectors close two offline preparation gaps from the v1 contract. They do not make the full WARP activation sequence live-ready.

## Current boundary

The current thread contains authority for one live oracle and one physical activation. That authority is preserved separately from permit eligibility and does not bypass prelaunch gates. The existing full WARP proof contract requires ordered WARP-action, destination, and confirmation activations, so the one-activation budget cannot prove the complete sequence. This is `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`.

This three-stage conclusion is bound to the separately verified `ORIGINAL_CLIENT_FIRST_PLAY_THREE_STAGE_GATE` contract (`maximumPhysicalActivations=3`), not inferred only from the v1 binding list.

The first technical gap is `MANAGER67_CURRENT_CARD_COLLECTOR_MISSING`. Static manager `0x67` ownership is known, but there is no fail-closed collector for the current instance/count/card widget/gate/rectangle. The selected captain-card exact widget collector is also missing. The later manager65 collector is fixture-verified but not live-hardened: it does not internally enforce the canonical hash, bind module base, double-capture, read the active/visible gate, or recheck the HWND surface after capture.

Destination projection and TextDialog tails have double-capture offline resolvers. Their fresh original-runtime snapshots remain `UNSEEN`. The scoped WARP stage-owner pointer is also not live-bound to the fixed TextDialog manager.

## Movement instrumentation

Static anchors exist for the handler, payload-ready call, expected `0x0B07`, outbound `0x0B01`, transport send, inbound dispatch, and state-apply return. No movement-specific breakpoint IDs, capture schema, sequential command ordinal, payload digest, or `0x0B01 -> 0x0B07` correlation receipt exists. Information-menu BP01-BP14 cannot be reused as movement proof.

The reproducible gap audit binds the manager67, captain-card, visible-WARP, stage-gate, information-menu breakpoint, destination, and TextDialog sources by SHA-256 and verifies that the fourteen existing breakpoint addresses have zero intersection with the seven movement anchors.

## Evidence boundary

- Static/offline preparation: `PARTIAL`.
- Runtime, player-visible movement, authority, persistence, both factions, Gate-A, Gate-B: `UNSEEN/NOT_PROVEN`.
- Prior live-v3 permit: `CONSUMED_NO_RETRY` and not reusable.
- This unit: no VM, guest, attach, breakpoint, process memory, input, capture, server/protocol/DB, or binary operation.
