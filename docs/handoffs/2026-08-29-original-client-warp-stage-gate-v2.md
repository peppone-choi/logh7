# Handoff: original-client WARP stage-gate v2

## Goal and result

Replace the incompatible three-activation stage-gate v1 with a closed WARP-only offline audit that preserves the current one-activation authority and cannot promote the manager65 fixture into a live binding or input coordinate.

- bounded result: `PASS`
- independent review: `APPROVE`
- execution state: `OFFLINE_WARP_GATE_V2_AUDIT_PASS_READY_FALSE`
- overall game goal: `INCOMPLETE`

## Scope and actual work

- Kept stage-gate v1 immutable and created `work/20260829-original-client-warp-stage-gate-v2`.
- Bound the current authority as WARP-only maximum/consumed/remaining `1/0/1`, automatic and retry budgets zero, and failure reuse false.
- Bound 13 fixed manager65-v3, prelaunch-v10, activation-policy, and authority-record roles through source hashes, actual file hashes, and a separately supplied expected-manifest SHA-256.
- Cross-checked manager65 capture/evaluation/collector/ledger/review/final-verification and prelaunch-v10 root/review/final/operation evidence.
- Required WARP, DESTINATION, and CONFIRM to remain `NOT_CREATED` and consumed zero. Future-stage binding, pointer, rectangle, or timestamp fields are rejected.
- Preserved the manager65 fixture only as an offline command-`0x2B` candidate. No rectangle, safe point, activation cell, binding digest, point, or permit is copied into the gate output.
- Added 8 tests and 83 mutation subtests; independent review reran them and returned `APPROVE`.
- Performed no live, VM, guest, debugger, target-process, capture, input, permit, server, protocol, database, binary, or resource operation.

## Changed files

- `work/20260829-original-client-warp-stage-gate-v2/**`
- `docs/handoffs/2026-08-29-original-client-warp-stage-gate-v2.md`
- `.gitattributes` receives unit-local LF pins before commit
- shared `report/manual.md` and `report/mistakes.md` were updated but remain outside the bounded commit because they include prior untracked work

## Reproduction

```powershell
pwsh -NoProfile -File work/20260829-original-client-warp-stage-gate-v2/verify.ps1
```

Expected receipt:

- 8 tests / 83 mutation subtests;
- 13 external source roles verified;
- stage-local authority maximum/consumed/remaining `1/0/1`;
- stages created `0`;
- activation eligible `false`;
- activation point `null`;
- permit issued `false`;
- all state-changing operations `0`.

## Evidence

- `evidence/warp-stage-gate-v2-contract.json`
- `tests/current-offline-gate-input.json`
- `tests/expected-source-hashes.json`
- `evidence/current-offline-evaluation.json`
- `evidence/independent-review.json`
- `evidence/artifact-ledger.json`
- `evidence/final-verification.json`
- `report/original-client-warp-stage-gate-v2.md`

## Confirmed facts

- Stage-gate v1's three-activation requirement cannot represent the current authority and is superseded for WARP preactivation.
- Current authority permits at most one physical WARP activation and does not authorize DESTINATION or CONFIRM.
- The existing manager65 v3 artifacts are internally consistent but synthetic/offline-only.
- Prelaunch v10 remains blocked before attach/input with fresh owned HWND missing and every state-changing operation counter zero.
- The current audit output contains no live binding digest, coordinate, activation point, automatic point, or permit.
- The first readiness blocker remains `FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION`.

## Inference and claim boundary

- Command `0x2B` is retained as offline semantic preparation only. It is not a current live widget binding.
- The full technical movement transaction still needs WARP, DESTINATION, and CONFIRM, but this does not create authority for the latter two inputs.
- Offline audit PASS does not prove WARP, packet transmission, server authority, persistence, pixels, both factions, Gate-A, Gate-B, or playability.

## Unknown and unverified

- Fresh interactive HWND, client/run identity, listener, heartbeat, foreground, debugger compatibility and attach, initial per-thread DR state.
- Corrected live manager65 capture, manager67 prior-stage binding, independent live hit-region subject/review, and new stage permit.
- WARP activation and post-WARP SelectGrid/destination creation.
- DESTINATION and CONFIRM authority; two additional physical activations remain unapproved.
- Outbound `0x0B01`, inbound `0x0B07`, queue completion, movement authority, persistence, and player-visible movement.

## Next start

Create a separate offline `WARP_EXTERNAL_LIVE_BINDING_SUBJECT_V1` contract. It must define the future same-run H1 identity -> H2 corrected live capture -> H3 evaluation -> H4 independently recomputed hit-region subject -> H5 independent review -> H6 bundle chain, plus exact full client rectangle, 3x3 replay-safe manual point, and 1x1 activation cell. With current evidence it must emit `NOT_CREATED/eligible=false` and no point. Do not execute a live route in that unit.

## Forbidden retries and promotions

- Do not reuse stage-gate v1's maximum-three authority or adapt current authority to three.
- Do not copy the fixture region `[560,420,656,436)` or point `(607,427)` into any live binding.
- Do not create DESTINATION or CONFIRM state before a successful authorized WARP and fresh post-WARP observation.
- Do not reuse the consumed historical permit, stale PID/HWND, old manager receipt, pointer, or rectangle.
- Do not let source-declared hashes replace the externally supplied expected-hash root.
- Do not attach, install breakpoints, read target memory, capture, send input, issue a permit, alter VM lifecycle, or change server/protocol/database state from this completed offline unit.
- Do not call this result original-client playability, movement, authority, persistence, both factions, Gate-A, or Gate-B.
