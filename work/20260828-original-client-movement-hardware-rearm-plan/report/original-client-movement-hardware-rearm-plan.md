# Original-client movement hardware-breakpoint rearm plan

## Result

`OFFLINE_HARDWARE_REARM_PLAN_PASS_RECEIPT_SCHEMA_GAP_RUNTIME_UNSEEN`

The MVB01-MVB09 address-slot schedule is now statically closed without software INT3, code-byte writes, process-memory writes, singleshoot, automatic hit-command rearm, or more than four simultaneous x86 hardware execute breakpoints. This is an offline plan and synthetic dry-run only. No debugger, VM, guest, target process, input, capture, or permit operation occurred.

## Exact schedule

| Paused phase | Active membership before resume |
|---|---|
| Initial | MVB01, MVB06, MVB08, MVB09 |
| MVB01 | MVB02, MVB06, MVB08, MVB09 |
| MVB02 | MVB03, MVB06, MVB08, MVB09 |
| MVB03 | MVB04, MVB06, MVB08, MVB09 |
| MVB04 | MVB05, MVB06, MVB08, MVB09 |
| MVB05 | MVB06, MVB07, MVB08, MVB09 |
| MVB06-MVB08 | unchanged |
| MVB09 | empty; remain stopped for sealing |

MVB03 and MVB04 are adjacent instruction boundaries, so the schedule requires MVB03 deletion and MVB04 installation before resume. MVB07 must be installed at paused MVB05 before the transport call executes. Rejected/background hits must never rotate membership. These are plan requirements, not observed debugger behavior.

## Tool and claim boundary

The official x64dbg command contract uses `bphws address, x, 1` and address-qualified `bphwc address`. The plan binds the locally installed x32dbg commit `9c8ca1cae0b6d56cc44f31fddcb10e3b02ffbb87` and x32dbg.exe SHA-256 `822028F0755DBA773E445EAF57FDB3DBA84C9550AC7BDAD2AFA449912B5FBA41`.

Reference source indicates programming across current threads and propagation on thread creation. The installed source also retains a multi-thread hardware-breakpoint TODO. Authoritative evidence for “all debuggee threads remain stopped until continuation” and “execute HWBP stops before instruction execution” is not bound in this unit. Therefore thread behavior is `STATIC_SOURCE_INDICATES_ALL_THREAD_PROGRAMMING_RUNTIME_UNSEEN`, runtime no-miss proof is `MISSING`, and `bplist` or source inspection cannot become a live per-thread DR receipt.

Official references:

- https://help.x64dbg.com/en/latest/commands/breakpoint-control/SetHardwareBreakpoint.html
- https://help.x64dbg.com/en/latest/commands/breakpoint-control/DeleteHardwareBreakpoint.html
- https://github.com/x64dbg/x64dbg/blob/9c8ca1cae0b6d56cc44f31fddcb10e3b02ffbb87/src/dbg/commands/cmd-breakpoint-control.cpp

## Prelaunch v7 delta

Resolved blocker:

- `MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING`

New first technical blocker:

- `MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING`

Receipt v1 cannot honestly record hit thread/debug-event ordinal, temporal rearm phase, per-thread DR state, rejected hits, a unique pending expected-`0x0B07` queue census, plan version/hash, installed debugger binaries/commit-file binding, or each phase's exact commands/results and pre/post active set before resume. Prelaunch v7 preserves twelve blockers and remains `READY_FALSE`; the first policy blocker is still `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`.

## Verification

- Hardware rearm suite: 39 cases / 59 assertions / 38 mutations.
- Prelaunch v7 suite: 21 cases / 33 assertions / 20 mutations.
- Synthetic schedule: 10 phases / 9 transitions / peak 4 slots.
- Trace SHA-256: `FE3E9046EA15DE5E64F1E7CF1159E3D6F3B72CC94C14AB1200E87B2F7243D8CA`.
- Hash-bound current-unit artifacts: 10; prelaunch v7 additionally binds the prior v6 final verification and artifact ledger.
- Live operations, debugger commands, breakpoint installations, process-memory reads/writes, captures, game inputs, and permit issuance: 0.

This unit does not prove breakpoint installation, runtime hits, movement, server authority, persistence, player-visible change, both factions, Gate-A, Gate-B, or original-client playability.
