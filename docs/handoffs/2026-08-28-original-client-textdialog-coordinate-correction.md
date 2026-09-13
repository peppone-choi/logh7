# Original-client TextDialog coordinate correction handoff

## Goal and scope

Correct the disproved confirmation/cancellation coordinate model and produce a read-only, replay-verifiable collector/resolver without performing a live oracle run or input.

## Performed

- Reconciled two independent static reviews of UI parent resolution and scale math.
- Replaced manager `+0x7C/+0x80` origin use with root-local cache `+0xDBC/+0xDC0` plus recursive context-parent summation.
- Added widget `+0x18` active/visible gate and exact optional-transform gate.
- Added module-base binding, double memory capture, pre/post owned-HWND surface sampling, engine/HWND rectangle equality, topology equality, context cycle/depth/id guards, and scale validation.
- Added exact client-pixel enumeration with forward `trunc(pixel*scale)` replay and 3x3 safe-margin validation.
- Added negative tests for torn snapshots, cache mismatch, inactive widget, invalid transform gate, null pointer, invalid scale, tiny hit region, and untrusted live self-claim.

## Changed files

- `work/20260828-original-client-textdialog-coordinate-correction/**`
- `docs/handoffs/2026-08-28-original-client-textdialog-coordinate-correction.md`
- Upstream static evidence used: `work/20260828-textdialog-coordinate-frame-owner/ExportTextDialogCoordinateFrame.java` and `textdialog-coordinate-frame.txt`.

## Commands and evidence

- `powershell -File work/20260828-original-client-textdialog-coordinate-correction/verify.ps1`
- Collector tests: 11 cases, 28 assertions.
- Resolver tests: 5 cases, 17 assertions.
- No live, VM, input, write, patch, server, protocol, or DB operation occurred.

## Facts, inference, and unknowns

- Static fact: `FUN_00507090` recursively sums context local origins; parent lookup uses ids `0..0x72` and registry slots.
- Static fact: widget hit testing is half-open and uses initialization, optional-transform, hit-test, and active/visible gates.
- Static fact: client-to-logical conversion is x87 round-to-zero `trunc(pixel*float32Scale)`.
- Inference: the scoped TextDialog is likely parentless, but the collector supports and tests a parent chain.
- Unknown: live parent id, scale bits, context/widget pointers, and hit rectangles remain unobserved.
- Unknown: the fixed manager slot is not proven to be the WARP dialog without a separately bound stage owner.

## Independent review

The first read-only review returned `REJECT` because module base and post-capture HWND/client-surface binding were absent. Those findings became three negative tests and fail-closed blockers. The second review reran verification and returned `APPROVE` with zero validator writes.

## Execution state

`STATIC_COORDINATE_FRAME_AND_OFFLINE_RESOLVER_PASS / PARTIAL_LIVE_SNAPSHOT_UNSEEN`

## Next start

Run an independent prelaunch audit of this collector against the sealed one-activation oracle contract. Only a later fresh, separately authorized run may bind PID/HWND/context/widget state. Do not reuse fixture points.

## Forbidden retries

- Do not use manager `+0x7C/+0x80` as an origin.
- Do not invert endpoints with `trunc(logical/scale)`.
- Do not treat fixture pixels or a JSON `LIVE_READONLY` label as live evidence.
- Do not click, retry, patch, write memory, or change VM/server/protocol/DB state from this handoff.
