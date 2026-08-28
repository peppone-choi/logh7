# Scope

## Question

Can a non-writing offline schedule cover MVB01-MVB09 with at most four x86 hardware execution-breakpoint definitions, require every rearm to occur while stopped, and expose rather than assume the remaining runtime no-miss semantics?

## Included

- Official x64dbg command/source provenance for hardware set/delete, four-slot enforcement, all-current-thread application, and new-thread propagation.
- A machine-readable 4-slot schedule, semantic simulator/verifier, synthetic dry-run, and mutation tests.
- An explicit audit of receipt-v1 temporal/thread/correlation gaps.
- Prelaunch v7 integration that closes only the address-slot rearm-plan blocker.
- Independent review, final verification, handoff, and bounded commit.

## Excluded

- VM/guest lifecycle, x32dbg attach or command delivery, actual BP installation, process-memory access, capture, or input.
- Software/temp INT3, run-to/step-over code patch, memory write, binary/resource patch, automatic click/retry, server/protocol/database change, or permit issuance.

## Acceptance

- Initial active set and every paused transition use no more than DR0-DR3.
- MVB01-MVB05 rotate through one slot; MVB06/MVB08/MVB09 are pre-armed and MVB07 is armed before the transport call resumes.
- The schedule requires every next anchor and all-thread phase state to be verified before resume; runtime no-miss proof remains `MISSING` until authoritative debugger semantics and a live per-thread receipt exist.
- Synthetic dry-run cannot become live evidence.
- Missing temporal/per-thread/rejected-hit/queue-census receipt fields remain a named blocker rather than being hidden.
