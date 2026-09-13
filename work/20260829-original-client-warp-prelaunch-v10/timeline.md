# Timeline

- 2026-08-29: unit opened from activation-budget stage-policy v1 handoff.
- 2026-08-29: read-only host audit confirmed the approved VM is running, VNC 6001 is listening, private node server PID 30216 is listening on 47900, and no 47900 ESTABLISHED connection exists.
- 2026-08-29: managed DPAPI guest route was recovered from the prior authorized session without printing or persisting plaintext; `listProcessesInGuest` confirmed current G7MTClient PID 3448 and x32dbg PID 6548.
- 2026-08-29: TDD RED started for a parameterized fresh-run identity collector/evaluator.
- 2026-08-29: fresh identity, guest/host port-47900 ownership, application heartbeat, and manager65/manager67 read-only captures were collected without input, attach, breakpoint installation, permit issuance, or VM lifecycle changes.
- 2026-08-29: live manager captures contradicted the collector fixtures: `moduleBase+0x89E638` begins with manager-106 pointers, while `U32(moduleBase+0x1E15E2C)` remains the UI mode/registry host. Static and historical live sources resolved these as complementary objects, not replacement roots.
- 2026-08-29: TDD added a closed root-role evaluator and a read-only dual-object collector. Tests passed `17 cases / 42 assertions / 16 mutations` and collector capability contract `17 assertions`.
- 2026-08-29: the first root-role live attempt stopped before `OpenProcess` because the previously observed HWND was not current in the guest-operation session. No process-memory read occurred in that attempt.
- 2026-08-29: a noninteractive identity diagnostic proved the guest-operation process was in session 0 and could not enumerate the client/debugger desktop. `-activeWindow -interactive` failed before launching the helper; `-activeWindow` alone still launched in session 0. Repetition stopped.
- 2026-08-29: current technical boundary is `FRESH_OWNED_HWND_NOT_OBSERVABLE_FROM_AVAILABLE_GUEST_OPERATION_SESSION`; WARP authority remains unconsumed and no foreground, attach, MVB installation, or activation occurred.
- 2026-08-29: first independent review returned REVISE for synthetic self-promotion, mutually exclusive manager stage semantics, incomplete operation counters, repository-writing tests/verifier, and open-schema gaps.
- 2026-08-29: closed-schema root-role evaluator now forces WARP/launch/live promotion false, treats manager65 as current active stage and manager67 as dormant prior-stage structure, rejects both-active state, timestamps live captures, and uses temporary test outputs.
- 2026-08-29: operation attempt ledger records 13 guest workload attempts and 292 read-only memory reads; exact VMware transport-call count remains `NOT_INSTRUMENTED_BEFORE_THIS_LEDGER` rather than fabricated. Every state-changing counter is zero.
- 2026-08-29: second independent read-only review returned APPROVE after two no-output fresh verifier runs with repository write count 0, 17/17 bound hashes, nested-source rehash, and 92 aggregate assertions.
