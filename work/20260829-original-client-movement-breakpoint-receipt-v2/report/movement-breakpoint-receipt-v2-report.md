# Original-client movement breakpoint receipt v2 report

## Result

The offline receipt-v2 contract is `PASS` and independently approved. It closes the prior schema-level temporal/thread/build/queue evidence gap, but it does not prove that a breakpoint was installed or hit in `G7MTClient.exe`. Runtime continuity and the runtime receipt remain `MISSING`; prelaunch remains `READY_FALSE`.

## Contract delivered

- A closed JSON Schema Draft 2020-12 envelope composing the frozen v1 receipt without editing it.
- Eight required field groups for hit thread/event identity, temporal rearm phases, per-thread DR state, rejected hits, pending `0x0B07` census, plan binding, debugger-build binding, and exact per-phase commands/results.
- Ten exact phases with eighteen manual commands, nine SET and nine DELETE operations, peak four active definitions, and explicit before-resume evidence.
- Global ordinal and census correlations across accepted/rejected hardware hits, thread lifecycle, snapshots, phase triggers, and the raw hardware-event transcript.
- EFLAGS.RF, POP SS/MOV SS suppression audit, pre-command DR0-DR7, physical trigger slot, DR6 B bits, exact DR7 policy, and queue-decrement timing.
- Closed-schema validation of every actual receipt input rather than only canonical publications.
- A prelaunch-v8 delta replacing one static schema blocker with one runtime receipt blocker while preserving twelve blockers.

## Verification

- receipt-v2: `78 cases / 101 assertions / 74 mutations`
- prelaunch-v8: `20 cases / 30 assertions / 19 mutations`
- JSON Schema: `2/2 PASS`
- ledger: `12/12`, SHA-256 `093108A8F8E50716A132AC1DE740E53DAC03AB26FEECBC2FE78059D8908D234E`
- independent review: `APPROVE`, validator writes `0`

## Claim boundary

No VM, guest, debugger, target process, memory, capture, input, server, protocol, database, permit, or lifecycle operation occurred. The empty template and synthetic specimen remain non-live. This unit does not prove movement, transport delivery, server authority, persistence, player-visible pixels, both factions, Gate-A, Gate-B, or original-client playability.
