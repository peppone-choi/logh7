# Handoff: original-client movement breakpoint receipt v2

## Goal and result

Create a closed, fail-closed receipt-v2 envelope that composes the frozen movement receipt v1 with the reviewed four-slot hardware-rearm plan and makes all eight temporal/thread/build/queue evidence gaps mechanically auditable without fabricating a live installation.

- bounded result: `PASS`
- independent review: `APPROVE`
- execution state: `OFFLINE_MOVEMENT_RECEIPT_V2_SCHEMA_PASS_RUNTIME_UNSEEN`
- prelaunch state: `OFFLINE_PRELAUNCH_MOVEMENT_RECEIPT_V2_INTEGRATED_RUNTIME_INSTALL_MISSING_READY_FALSE`
- overall game goal: `INCOMPLETE`

## Scope and actual work

- Kept all v1 artifacts byte-frozen and composed them through hash bindings.
- Added a closed JSON Schema Draft 2020-12 envelope, empty template, synthetic specimen, semantic verifier, mutation suite, v1-v2 migration matrix, and authoritative debugger-semantics evidence.
- Defined and verified exactly eight field groups, ten phases, eighteen commands, nine SET, nine DELETE, and peak four active hardware definitions.
- Cross-bound accepted/rejected hardware-hit ordinals, snapshot/phase events, thread census and lifecycle deltas, complete hardware transcript, and before-resume evidence.
- Added EFLAGS.RF and POP SS/MOV SS suppression evidence, pre-command DR0-DR7/trigger slot, exact local-only DR7 policy, DR6 B-bit/BD/BS/BT policy, and phase-9 queue-decrement evidence.
- Made the semantic verifier validate every actual receipt input against the closed schema, rejecting extra root and nested properties.
- Integrated prelaunch v8 by retiring `MOVEMENT_RECEIPT_TEMPORAL_THREAD_CORRELATION_SCHEMA_MISSING` and introducing `FRESH_MOVEMENT_BREAKPOINT_INSTALL_AND_PER_THREAD_TEMPORAL_RECEIPT_MISSING`, conserving blocker count `12 -> 12`.
- Performed two independent review rounds. The first returned REVISE; all findings and two additional pressure-test fail-open gaps were fixed TDD-first. The final review returned APPROVE with validator writes 0.

## Changed files

- `work/20260829-original-client-movement-breakpoint-receipt-v2/**`
- `docs/handoffs/2026-08-29-original-client-movement-breakpoint-receipt-v2.md`
- shared `report/manual.md` and `report/mistakes.md` were updated with operating lessons and are excluded from the bounded commit

## Reproduction

```powershell
pwsh -NoProfile -File work/20260829-original-client-movement-breakpoint-receipt-v2/verify.ps1
```

## Evidence

- `evidence/movement-breakpoint-receipt-v2.schema.json`
- `evidence/movement-breakpoint-receipt-v2-template.json`
- `tests/fixture-v2-semantic-specimen.json`
- `evidence/v1-v2-migration-matrix.json`
- `evidence/authoritative-debugger-semantics.json`
- `evidence/prelaunch-v8-movement-receipt-v2.json`
- `evidence/artifact-ledger.json`
- `evidence/independent-review.md`
- `evidence/final-verification.json`
- `report/movement-breakpoint-receipt-v2-report.md`

## Verification

- receipt-v2: `78 cases / 101 assertions / 74 mutations`
- prelaunch-v8: `20 cases / 30 assertions / 19 mutations`
- JSON Schema Draft 2020-12: `2/2 PASS`
- artifact ledger: `12/12`, SHA-256 `093108A8F8E50716A132AC1DE740E53DAC03AB26FEECBC2FE78059D8908D234E`
- independent review: `APPROVE`; validator writes `0`
- live/debugger/process-read/write/capture/input/permit operations: `0`

## Confirmed facts

- The offline receipt-v2 schema and verifier mechanically require the eight missing evidence groups and reject the reviewed temporal/thread/DR/queue fail-open mutations.
- The actual receipt path, not only canonical fixtures, is validated against the closed Draft-2020-12 schema.
- A thread can be created or exit between phase snapshots only with one matching, ordered lifecycle event before continuation.
- Reserved DR6 bits may survive capture, but exactly one expected B0-B3 bit is required and BD/BS/BT must be clear.
- Prelaunch v8 has twelve blockers. The first policy blocker remains `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH`; the first technical blocker is `FRESH_RUN_IDENTITY_MISSING`.

## Inference and claim boundary

- Authoritative Windows/Intel and installed x32dbg source evidence supports stopped-event mutation and pre-instruction hardware-breakpoint timing, but actual `G7MTClient.exe` per-thread installation/rearm continuity remains runtime-unseen.
- Synthetic semantic PASS cannot become live evidence. Runtime no-miss and runtime receipt remain `MISSING`.

## Unknown / unverified

- Any fresh oracle run identity, PID/HWND/module base/pointers, listener or heartbeat.
- Actual x32dbg attach, hardware-breakpoint installation, per-thread DR capture, hit, rejected hit, rearm continuity, or queue census.
- The activation-budget versus WARP/destination/confirm stage policy ruling.
- Any physical activation, outbound/inbound result, owned-HWND pixel change, movement authority, persistence, both factions, Gate-A, Gate-B, or end-to-end playability.

## Next start

After explicit user continuation, resolve `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH` as a separate offline policy unit. Do not begin a live run until that ruling and every same-run prelaunch blocker are closed and independently reviewed. A later live unit must use fresh identity and a new permit; it may populate this v2 envelope but may not promote the synthetic specimen.

## Forbidden retries

- Do not reuse any prior permit, run, PID/HWND, module base, pointer, coordinate, or synthetic receipt.
- Do not attach, install breakpoints, read target memory, capture, or send input from this completed offline unit.
- Do not use software/temp INT3, process-memory writes, binary/resource patching, `DBG_REPLY_LATER`, automatic breakpoint commands, automatic click, or automatic retry.
- Do not change VM lifecycle, server, protocol, database, or port ownership.
- Do not call schema PASS movement, player-visible behavior, authority, persistence, both factions, Gate-A, Gate-B, or playability.
