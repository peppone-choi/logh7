# Original-client activation-budget stage policy v1

## Result

- Bounded result: `PASS`.
- Execution state: `OFFLINE_ACTIVATION_POLICY_AND_PRELAUNCH_V9_PASS_RUNTIME_UNSEEN`.
- Independent read-only review: `APPROVE`.
- Live, guest, debugger, process-memory, capture, input, and permit operations: `0`.

## Question resolved

The prior prelaunch contract compared one authorized physical activation with the full three-activation movement transaction and exposed only `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`. This unit resolves that ambiguity without expanding authority: the current slice authorizes only WARP, then requires read-only post-WARP evidence and immediate stop-and-handoff. DESTINATION and CONFIRM remain outside the slice, so full movement still needs exactly two additional physical activations.

## Authority chronology

The actual append-only Codex session was read-shared and two unique user response items were recomputed from their raw JSONL lines:

1. Initial one-run/one-activation grant: line 14345, ordinal 14344, message `msg_01a040f6-6fda-78a2-a493-deba1fe79bc3`, timestamp `2026-08-27T02:04:47.45Z`, raw-line SHA-256 `0D50F121CF981D8DC37985051962752D427A69BDACEB2D152FAA89B0C93929B6`.
2. Historical permit consumption: `2026-08-27T03:45:18.6619092Z`, `CONSUMED_NO_RETRY`, SHA-256 `6DEB344D029C4315865AE44495F0B8A73AF6E5A4BA55D9649154FDC1B2C5B570`.
3. Later broad approval: line 23327, ordinal 23326, message `msg_01a04341-060c-7501-8694-90a25198f52e`, timestamp `2026-08-27T12:45:29.996Z`, raw-line SHA-256 `98757CB2D1E2125F801B55076177755D11715AFB08B862A49BBDDF573F47E556`.

The ordering is exact. Historical accounting remains granted/consumed/remaining `1/1/0`; the post-consumption approval is adjudicated as a fresh current `1/0/1` run allowance. The earlier one-physical-activation cap, automatic/retry budget zero, write/patch/lifecycle/server/protocol/DB prohibitions, single-writer rule, and prelaunch gates remain controlling. The broad approval extends compatible read-only instrumentation to MVB01-MVB09 but does not issue a permit.

## Stage and breakpoint timing

The bound stage contract is verified at field level:

- WARP: `maxConsumption=1`, predecessors `[]`.
- DESTINATION: `maxConsumption=1`, predecessors `[WARP=1]`.
- CONFIRM: `maxConsumption=1`, predecessors `[WARP=1, DESTINATION=1]`.

Direct source ledgers bind `FUN_00581C80` to the WARP flow, SelectGrid as its first child, TextDialog as the confirmation stage, SendWarp as child 3 (`moduleBase+0x276AEC`), MVB01 as `WARP_SEND_CALLBACK_ENTRY`, and the initial active hardware membership as MVB01/MVB06/MVB08/MVB09. Therefore after WARP and before DESTINATION, MVB01 accepted hits `0`, receipt phase `0`, and unchanged initial membership are valid only as `STATIC_EXPECTED_NOT_RUNTIME_OBSERVED`.

## Prelaunch v9 disposition

- Retired policy blocker: `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`.
- Authorized allocation: WARP `1`, DESTINATION `0`, CONFIRM `0`.
- Remaining scoped prelaunch blockers: `8`.
- Remaining post-WARP evidence items: `7`.
- Deferred full-transaction boundaries: `4`.
- First missing/technical boundary: `FRESH_RUN_IDENTITY_MISSING`.
- First authority boundary: `FULL_MOVEMENT_TRANSACTION_AUTHORITY_INSUFFICIENT_TWO_ADDITIONAL_PHYSICAL_ACTIVATIONS_REQUIRED`.
- `launchEligible=false`, `permitEligible=false`, `permitIssued=false`.

## Verification

`pwsh -NoProfile -File work/20260829-original-client-activation-budget-stage-contract-v1/verify.ps1`

- Activation policy: 55 cases / 69 assertions / 54 mutations.
- Prelaunch v9: 38 / 52 / 37.
- Bound prelaunch v8: 20 / 30 / 19.
- Bound stage gate: 9 / 19.
- Unit artifacts: 11/11 hashes verified.
- Artifact-ledger SHA-256: `F4D5A04ED91CB13407BC9836172DC642E22F4CC0493FB985B5B24C6196B6CA94`.
- Forbidden executable capability hits: `0`.

The independent validator reran the aggregate verifier, independently recomputed chronology and source bindings, returned `APPROVE`, and wrote no files.

## Claim ceiling

This PASS closes an offline policy mismatch only. It does not prove a fresh runtime identity, breakpoint installation, WARP input, SelectGrid creation, owned-HWND capture, MVB hit, outbound `0x0B01`, inbound `0x0B07`, queue completion, movement pixels, authority mutation, persistence, both factions, Gate-A, Gate-B, or proper playability.
