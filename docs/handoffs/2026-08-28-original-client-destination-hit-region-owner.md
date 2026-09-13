# Handoff: original-client destination hit-region owner

## Goal and scope

Bind the original mode-`0x101` destination grid from client-area mouse pixels through projection, original target validation, hover state, and click promotion; implement a fail-closed read-only snapshot and offline region resolver without consuming a live permit.

## Result

`STATIC_HIT_REGION_OWNER_AND_OFFLINE_RESOLVER_PASS / PARTIAL_LIVE_SNAPSHOT_UNSEEN`

No VM, live oracle, debugger, process-memory write, binary patch, game input, automatic retry, server/protocol/database change, or permit issuance occurred.

## Actual execution

- reused the existing Ghidra project with exact program `g7mtclient.exe`, `-readOnly -noanalysis`;
- exported input writers, button-state synthesis, viewport/matrix refresh, projection/unprojection, grid quantization, validator lookup, and selection-promotion owners;
- implemented and TDD-verified a double-capture read-only projection collector;
- implemented and TDD-verified an exact client-pixel region resolver;
- reproduced the collector→resolver pipeline using retained offline fixtures;
- ran parallel read-only SelectGrid and TextDialog audits.

## Changed files

- `work/20260828-original-client-destination-hit-region-owner/**`
- this handoff

Operational rules and failures were appended to the shared untracked `report/manual.md` and `report/mistakes.md`; they are not part of the unit commit.

## Reproduction

```powershell
pwsh -NoProfile -File work/20260828-original-client-destination-hit-region-owner/evidence/verify-destination-hit-region-owner.ps1
```

Expected: verifier `PASS`, collector 10/46, resolver 7/34, static markers 12, forbidden hits 0, live operations 0, permit false, bounded status `PARTIAL_LIVE_SNAPSHOT_UNSEEN`.

## Confirmed facts

- `FUN_004D6B70` integrates map rendering, client-pixel unprojection, hover calculation, validity, and press-edge promotion.
- `0x022143DC/E0` are `ScreenToClient` mouse coordinates.
- `0x022142DB/DC` are synthesized left/right button state bytes; bit `0x40` is press edge.
- hover grid is controller `+0x24/+0x28`; selected grid/X/Y are `+0x10/+0x18/+0x1C`; state 2 is selected and state 3 cancelled.
- view/projection/world/viewport are `0x009D1368/0x009D13A8/0x009D13E8/0x009D1428`.
- the separate mouse-gate RECT is `*(0x007C1B4C)+0x2A5FC`.
- world/grid formulas and `FUN_004D6310` cell type, active-record, current-grid, and distance/filter rules are implemented in the read-only contract.

## Inferences kept separate

- projecting the cell center and choosing the nearest replay-verified interior pixel is the safest static candidate policy; it remains an offline implementation decision until a fresh runtime snapshot reproduces the original hover grid.
- the 3x3 margin is a conservative pre-activation policy, not an original-game rule.
- a snapshot JSON cannot promote itself by setting `sourceMode=LIVE_READONLY`; the resolver returns `UNBOUND / LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED` until a later independent receipt binds the live artifact.

## Evidence

- static export: `work/20260828-original-client-destination-hit-region-owner/evidence/destination-hit-region-owner.txt`
- ledger: `evidence/destination-hit-region-ledger.json`
- fixture pipeline receipts: `evidence/fixture-projection-snapshot.json`, `evidence/fixture-hit-region.json`
- aggregate receipt: `evidence/final-verification.json`
- independent review: `evidence/independent-review.md`, final `APPROVE`

## Unknowns and live status

- fresh original runtime matrices/viewport/validator state: `UNSEEN`
- actual original client hit region: `UNSEEN`
- destination click and selection transition: `UNSEEN`
- player-visible WARP completion and wire response: `UNSEEN`
- permit available: none; prior live-v3 permit remains `CONSUMED_NO_RETRY`

## Parallel TextDialog audit carry-forward

The read-only audit statically disproved the previous confirm collector's coordinate model:

- `manager+0x7C/+0x80` are width/height operands, not origin;
- actual logical context is `*(manager+0x08)`, local origin `+0x0C/+0x10`, with recursive parent owner `FUN_00507090`;
- widget hit rect uses origin plus widget `+0x20/+0x24`, optional transform `+0x0C/+0x10`, and size `+0x2C/+0x30`;
- logical/client scaling is owned by `FUN_004EA460`, `FUN_004EA510`, and `FUN_004EA570`.

Carry-forward evidence (not incorporated into this unit ledger): `work/20260828-textdialog-coordinate-frame-owner/textdialog-coordinate-frame.txt`, SHA-256 `BC4DE3E8EBD27CFA0556C08D52408EC6C37AD78D636D693CDC64451E92D1EC47`.

## Exact next start

Start one offline unit `TEXTDIALOG_COORDINATE_FRAME_COLLECTOR_CORRECTION`:

1. replace the disproved `manager+0x7C/+0x80` origin model;
2. capture the UI context parent chain, logical origin, animation cache, widget transform/size, client rect, and scale twice;
3. compute confirm/cancel half-open client rectangles by inverse scale and forward replay;
4. obtain independent review;
5. only then prepare a new explicitly scoped permit for a complete WARP destination/confirm sequence.

## Forbidden retries

- never use fixture point `(52,47)` as an oracle coordinate;
- do not use the old confirm collector's raw rectangles;
- do not reuse the consumed live-v3 permit;
- do not click, retry, write memory, patch the executable, alter VM lifecycle, or change server/protocol/database state;
- do not promote offline fixture PASS to runtime, player-visible, authority, persistence, Gate-A, or Gate-B.
