# Handoff: original-client destination and confirm stage collectors

## Goal and bounded scope

Advance the original client's first playable WARP command without consuming a live permit: identify the destination-selection and confirmation-stage state owners, then implement hash-bound, fresh PID/HWND-bound, read-only collectors.

## Result

`STATIC_STAGE_STATE_COLLECTORS_PARTIAL`

Offline collectors and their tests pass. No original-client run, debugger attach, breakpoint, process-memory write, activation, VM lifecycle action, server/protocol/database change, or binary patch occurred.

## Changed files

- `work/20260828-original-client-destination-confirm-collector/ExportDestinationConfirmOwners.java`
- `work/20260828-original-client-destination-confirm-collector/evidence/destination-confirm-owners.txt`
- `work/20260828-original-client-destination-confirm-collector/src/collect-destination-stage-state.ps1`
- `work/20260828-original-client-destination-confirm-collector/src/collect-confirm-stage-state.ps1`
- fixture-driven tests and fixtures under the same unit
- `work/20260828-original-client-destination-confirm-collector/evidence/destination-confirm-stage-ledger.json`
- `work/20260828-original-client-destination-confirm-collector/evidence/verify-destination-confirm-stage-collectors.ps1`
- `work/20260828-original-client-destination-confirm-collector/evidence/final-verification.json`
- unit report and this handoff

Operational lessons were appended to `report/manual.md` and `report/mistakes.md`; those shared untracked files are not part of this unit's commit scope.

## Reproduction

```powershell
& 'work/20260828-original-client-destination-confirm-collector/evidence/verify-destination-confirm-stage-collectors.ps1'
```

Expected bounded result: verifier `PASS`, status `STATIC_STAGE_STATE_COLLECTORS_PARTIAL`, destination 6 cases/24 mechanically counted assertions, confirm 5 cases/25 mechanically counted assertions, forbidden capability hits 0, live operations 0.

## Evidence and confirmed facts

- target SHA-256: `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- destination controller base: `0x009D2A30`; mode `0x101`; selected grid `+0x10`; requested choice `+0x14`; X/Y `+0x18/+0x1C`
- destination callback at `0x00573CD0` reaches the local kind `0x52` queue path through `FUN_004B49D0`
- confirmation manager base: `0x00CA292C`; confirm/cancel widgets `+0x24/+0x28`; layout `+0x37C == 4`; terminal state `+0xDE0`
- confirmation writes state `3`; cancellation writes state `4`
- collectors bind executable identity, process identity, and HWND ownership before read-only capture

Static export, ledger, collector hashes, and safety assertions are bound in `evidence/final-verification.json`.

The separate read-only reviewer reran the verifier without modifying files and returned `APPROVE`; its receipt is `evidence/independent-review.md`.

## Inference kept separate

- these states appear sufficient to decide when a later human activation could be eligible, but no activation coordinate is proven yet.
- local kind `0x52` is part of the destination-selection path; this unit does not claim that it proves the complete network request or server authority behavior.

## Unknowns and unproved claims

- destination grid world-to-client hit-region transform: `MISSING`
- confirmation widget manager-to-client coordinate frame: `MISSING`
- fresh live state values: `UNSEEN`
- player-visible WARP completion: `UNSEEN`
- network request/response in this path: `UNSEEN`
- authority, persistence, all content, both factions, and Gate-A/Gate-B: not proved

## Live state

- prior live-v3 permit: `CONSUMED_NO_RETRY`
- permit issued by this unit: false
- live operations: 0
- activation inputs: 0

## Exact next start

Start one offline unit named `DESTINATION_GRID_WORLD_TO_CLIENT_HIT_REGION_OWNER`:

1. trace the `SelectGrid` draw and mouse-hit consumers from the mode `0x101` state;
2. bind grid identity to an exact client-area activation rectangle and falsifier;
3. bind the confirmation widget raw rectangle to its client coordinate frame;
4. add fixture-driven, read-only preflight tests and obtain independent review;
5. only then prepare a new explicitly scoped live permit for the full WARP destination/confirm sequence.

## Forbidden retries and actions

- do not reuse the consumed live-v3 permit;
- do not click or synthesize input from the raw X/Y or widget rectangle fields;
- do not auto-retry, auto-click, write process memory, patch the executable, change VM lifecycle, or change server/protocol/database state;
- do not promote this offline PASS to runtime, player-visible, authority, persistence, both-faction, Gate-A, or Gate-B evidence.
