# Scope: first-play prelaunch manager67 integration v3

## Question

Does the independently approved manager67 bound-authority-card collector close the two prior static UI collector gaps while preserving the policy blocker and replacing them with an explicit fresh-live snapshot boundary?

## Allowed

- offline artifact/hash inspection
- deterministic v3 contract, verifier, mutation tests, report, independent review

## Forbidden

- VM, guest operation, debugger, attach, breakpoint, process-memory read, input, capture
- server, protocol, database, VM lifecycle, binary, resource, or prior sealed-artifact mutation
- promotion of fixture geometry or self-claimed live evidence

## Acceptance

Pass only if the sealed v2 contract/receipt and manager67 verification/ledger are hash-bound, both static manager67 gaps become one offline collector PASS, a new fresh-live manager67 blocker remains, the activation-budget policy blocker stays first, the next technical blocker is recomputed, all counters remain zero, and independent review approves.
