# Original-client movement breakpoint receipt schema

## Result

`OFFLINE_MOVEMENT_RECEIPT_SCHEMA_PASS_REARM_PLAN_MISSING_RUNTIME_UNSEEN`

The prior seven movement anchors were not sufficient to prove the same queue entry completed. Static review added two completion anchors:

- `MVB08` at `0x004BDD91`: inbound low16 versus queued expected-opcode comparison.
- `MVB09` at `0x004BDDE2`: post-store queue-count/dequeue boundary.

The receipt is therefore a 7-primary + 2-completion anchor contract. Runtime addresses are always derived from a fresh module base plus RVA; the empty template contains no PID, HWND, module base, hit, coordinate, input, or capture claim.

## Important instruction-boundary correction

x32dbg stops before the instruction. `MVB03` identifies `MOV EBX,0x0B07`, but the value is proved at `MVB04`. `MVB04` identifies `MOV ESI,0x0B01`, but the value is proved at `MVB05`. The schema rejects claims that treat the pre-hit register as the post-assignment result.

## Correlation boundary

The contract binds same-run identity, movement ordinal, queue slot, local kind `0x3B`, outbound `0x0B01`, expected/inbound `0x0B07`, MVB02/MVB05 payload digest equality, ordered MVB01-MVB09 hits, state before/after digests, and owned-HWND pixels.

No original wire correlation ID has been recovered. It is explicitly `ABSENT_OR_UNPROVEN`; the current evidence basis is `SINGLE_OUTSTANDING_QUEUE_ENTRY`. Full payload codec remains `CODEC_UNPROVEN`.

## Breakpoint mechanism boundary

Nine simultaneous x86 hardware breakpoints are impossible. Software INT3 would alter process code bytes and conflicts with the controlling no-memory-write constraint. This unit does not select or execute either method. The first technical blocker is now `MOVEMENT_HARDWARE_BREAKPOINT_REARM_PLAN_MISSING`.

## Verification

- Movement receipt tests: 39 cases / 53 assertions / 37 mutations.
- Prelaunch v6 tests: 17 cases / 29 assertions / 16 mutations.
- JSON Schema Draft 2020-12: template and synthetic specimen PASS.
- Artifact hashes: 11/11, ledger SHA-256 `7A6E5594B7D2DA7DB331A92BEA350759EF36EB857AF75E379C12DE7DF8209DE3`.
- Independent review: APPROVE after one REVISE cycle.

No VM, debugger, guest, process-memory, input, capture, permit, server, protocol, database, binary, or resource operation was performed.
