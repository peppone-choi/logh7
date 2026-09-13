# Scope: first-play prelaunch integration audit v2

## Question

Do the post-v1 WARP route, manager65, destination projection, TextDialog, wire, foreground, and consumed-permit artifacts compose into a safe one-activation launch contract; if not, what is the exact first missing boundary?

## Allowed

- offline artifact/hash inspection
- deterministic contract, verifier, tests, report, independent review

## Forbidden

- VM, guest, debugger, attach, breakpoint, process-memory, input, capture, server, protocol, DB, or binary mutation
- reuse of any prior permit, PID, HWND, pointer, or fixture coordinate

## Acceptance

Pass only if every incorporated artifact is hash-bound, stale unsafe collectors are explicitly rejected from live use, current user authority remains distinct from permit eligibility, blockers are closed and ordered, all operation counters stay zero, and independent review approves the audit.
