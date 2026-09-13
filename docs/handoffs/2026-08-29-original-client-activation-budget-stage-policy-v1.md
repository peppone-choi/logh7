# Original-client activation-budget stage policy v1 handoff

## Goal

Resolve `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH` without expanding the user's one-physical-activation authority, reusing the consumed historical permit, or treating static timing expectations as live observations.

## Scope

- Offline authority chronology and run accounting.
- WARP -> DESTINATION -> CONFIRM activation cardinality and predecessor validation.
- WARP/SelectGrid/TextDialog/MVB01/rearm timing source binding.
- Stage-local prelaunch v9 routing, mutation tests, aggregate verification, and independent read-only review.
- No VM, guest, debugger, process-memory, capture, input, permit, server, protocol, DB, binary, or resource operation.

## Result

- Bounded result: `PASS`.
- Independent review: `APPROVE`.
- State: `OFFLINE_ACTIVATION_POLICY_AND_PRELAUNCH_V9_PASS_RUNTIME_UNSEEN`.
- `launchEligible=false`, `permitEligible=false`, `permitIssued=false`.
- Live/input/permit operations: `0`.

## Actual work

1. Bound the initial explicit grant and the later broad approval to unique current-thread user response items using exact message/turn IDs, UTC timestamps, sequence ordinals, line numbers, exact text, and raw JSONL-line SHA-256 values.
2. Ordered them mechanically around the immutable historical permit-consumption timestamp.
3. Conserved historical run accounting as granted/consumed/remaining `1/1/0` and current post-consumption reauthorization as `1/0/1`.
4. Retained one physical activation, automatic/retry zero, all enumerated prohibitions, no permit reuse, and no prelaunch-gate bypass.
5. Verified each WARP/DESTINATION/CONFIRM stage has `maxConsumption=1` and the exact predecessor list.
6. Bound WARP factory/SelectGrid, TextDialog, SendWarp child[3], MVB01 callback role, child[3] equality requirement, and initial hardware-rearm membership.
7. Classified post-WARP MVB01=0, phase=0, and unchanged initial set as `STATIC_EXPECTED_NOT_RUNTIME_OBSERVED` only.
8. Replaced the ambiguous mismatch with a WARP-only current allocation and the explicit full-transaction authority blocker requiring two additional physical activations.
9. Ran mutation suites and aggregate verification, then obtained read-only independent `APPROVE` with zero validator writes.

## Changed files

Bounded unit:

- `work/20260829-original-client-activation-budget-stage-contract-v1/scope.md`
- `work/20260829-original-client-activation-budget-stage-contract-v1/timeline.md`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/current-thread-authority-record.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/authority-message-chronology.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/activation-budget-stage-policy.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/stage-mvb-timing-adjudication.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/prelaunch-v9-stage-policy.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/artifact-ledger.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/independent-review.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/evidence/final-verification.json`
- `work/20260829-original-client-activation-budget-stage-contract-v1/src/verify-activation-budget-stage-policy.ps1`
- `work/20260829-original-client-activation-budget-stage-contract-v1/src/verify-prelaunch-v9-stage-policy.ps1`
- `work/20260829-original-client-activation-budget-stage-contract-v1/tests/test-activation-budget-stage-policy.ps1`
- `work/20260829-original-client-activation-budget-stage-contract-v1/tests/test-prelaunch-v9-stage-policy.ps1`
- `work/20260829-original-client-activation-budget-stage-contract-v1/verify.ps1`
- `work/20260829-original-client-activation-budget-stage-contract-v1/report/original-client-activation-budget-stage-policy-v1.md`

Shared operating records, deliberately excluded from the bounded commit because they contain prior untracked user work:

- `report/manual.md`
- `report/mistakes.md`

Handoff:

- `docs/handoffs/2026-08-29-original-client-activation-budget-stage-policy-v1.md`

## Verification command and evidence

Command:

```powershell
pwsh -NoProfile -File work/20260829-original-client-activation-budget-stage-contract-v1/verify.ps1
```

Fresh expected receipt:

- Activation policy: `55 cases / 69 assertions / 54 mutations`.
- Prelaunch v9: `38 / 52 / 37`.
- Bound prelaunch v8: `20 / 30 / 19`.
- Bound stage gate: `9 / 19`.
- Artifact hashes: `11/11`.
- Artifact-ledger SHA-256: `F4D5A04ED91CB13407BC9836172DC642E22F4CC0493FB985B5B24C6196B6CA94`.
- Forbidden executable capability hits: `0`.
- Independent review artifact SHA-256: `31FA451A9A6E353A8D5633AD373E3D034590008F78428C480AD24B8B5B236876`.

Evidence:

- `evidence/authority-message-chronology.json`
- `evidence/stage-mvb-timing-adjudication.json`
- `evidence/activation-budget-stage-policy.json`
- `evidence/prelaunch-v9-stage-policy.json`
- `evidence/independent-review.json`
- `evidence/final-verification.json`

## Confirmed facts

- Historical permit remains `CONSUMED_NO_RETRY` and is not reusable.
- A later user approval exists after that consumption and is hash-bound to the actual session line.
- Current run accounting is one fresh run remaining, but it is authority rather than an issued permit.
- Normal movement requires three ordered same-run physical activations.
- The current allocation is WARP `1`, DESTINATION `0`, CONFIRM `0`.
- Full movement needs exactly two additional physical activations.
- MVB01 is the later SendWarp child callback, not the initial WARP click boundary.
- The first current technical blocker is `FRESH_RUN_IDENTITY_MISSING`.

## Inferences and claim ceiling

- Post-WARP MVB01 count zero, phase zero, and unchanged initial DR membership are static expectations derived from bound flow and rearm sources.
- They are not runtime observations and cannot support an MVB hit, packet, queue, movement, authority, persistence, both-faction, Gate-A, Gate-B, or playability claim.

## Unknown and remaining work

- Eight scoped prelaunch blockers remain.
- Seven post-WARP evidence items remain.
- Four full-transaction boundaries remain deferred.
- Fresh PID/HWND/module/manager/card/action/breakpoint/foreground state is not collected for a new run.
- Actual WARP activation, SelectGrid transition, owned-HWND post-WARP pixels, and zero-hit/phase/DR receipt remain `UNSEEN`.
- DESTINATION, CONFIRM, outbound `0x0B01`, inbound `0x0B07`, queue completion, and player-visible movement remain outside current authority and `UNSEEN`.

## Next start

Open a distinct prelaunch-v10 WARP-stage unit. Re-read this handoff, then perform only the approved fresh read-only prelaunch sequence needed to close `FRESH_RUN_IDENTITY_MISSING` and the remaining seven scoped blockers. Do not issue or consume a permit until the exact fresh PID/HWND/module/listener/heartbeat, manager67/manager65 snapshots and independently bound hit regions, initial MVB per-thread DR receipt, foreground probe, and independent live-prelaunch review all pass in the same run.

## Forbidden retries and promotions

- Do not reuse the historical consumed permit or any old PID/HWND/module/pointer/rectangle.
- Do not treat general approval as an automatic permit or as more than one physical activation.
- Do not click WARP before every scoped prelaunch blocker passes and an independent live-prelaunch review exists.
- Do not automatically click, retry, chain permits, or continue to DESTINATION/CONFIRM.
- Do not write process memory, patch the binary, change VM lifecycle, or change server/protocol/DB state.
- Do not promote offline PASS or static expectations to runtime, player-visible movement, authority, persistence, both factions, Gate-A, Gate-B, or proper playability.
