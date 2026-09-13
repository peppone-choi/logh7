# Scope

## Question

How must the current one-physical-activation authority compose with the proven WARP -> DESTINATION -> CONFIRM three-activation UI transaction without expanding authority, reusing the consumed permit, requiring future-created UI before its predecessor activation, or allowing automatic permit chaining?

## Included

- Hash-bound current authority and consumed-permit provenance from prelaunch v8.
- Exact current-thread response-item chronology proving the later broad approval occurred after historical permit consumption.
- Hash-bound three-stage gate contract, evaluator, and final verification.
- Hash-bound WARP owner, SelectGrid/TextDialog, MVB01 callback, and hardware-rearm timing adjudication.
- A fail-closed stage-local authority policy and semantic verifier.
- Explicit current WARP-stage compatibility, full-transaction insufficiency, and two additional physical activations required.
- Prelaunch v9 blocker/lifecycle delta after policy review.
- Mutation tests, aggregate verification, independent review, report, handoff, and bounded commit.

## Excluded

- VM, guest, debugger, process, memory, capture, input, permit issuance, or lifecycle operations.
- Any authority expansion, prior-permit reuse, automatic permit issuance, automatic input, or retry.
- Server, protocol, database, original binary/resource, or replacement-game implementation changes.

## Acceptance

- The one-activation current authority is never rewritten as three activations.
- The full movement transaction remains exactly three ordered, same-run physical activations.
- Only WARP is the current stage; DESTINATION and CONFIRM remain future-created and cannot be preflight prerequisites for the WARP activation.
- After the WARP activation the contract requires stop-and-handoff; no next permit/input is automatic.
- Full-transaction authority remains insufficient with exactly two additional physical activations required.
- The ambiguous mismatch blocker is retired only when replaced by explicit current-stage and full-transaction dispositions.
- All live/input/permit operation counters remain zero.
- Post-WARP MVB01 count zero, phase zero, and unchanged initial DR set remain static expectations, not runtime observations.
