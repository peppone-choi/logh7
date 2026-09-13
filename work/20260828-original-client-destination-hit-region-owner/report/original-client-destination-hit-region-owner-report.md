# Original client destination hit-region owner

## Result

- static owner and offline resolver: `PASS`
- bounded status: `PARTIAL_LIVE_SNAPSHOT_UNSEEN`
- live operations: 0
- game inputs: 0
- permit issued: false

This unit closes the static client-pixel-to-destination-grid path and implements a read-only snapshot collector plus an offline exact-pixel resolver. It does not supply an original-runtime click coordinate because no fresh live matrices were captured.

## Target

- `G7MTClient.exe`, PE32 x86
- SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- Ghidra project `work/ghidra-input-consumption/Unit10Input`, program `g7mtclient.exe`, opened with `-readOnly -noanalysis`

## Confirmed static path

```text
GetCursorPos -> ScreenToClient
  -> 0x022143DC/E0 client X/Y
  -> FUN_004D6B70
  -> FUN_004B25A0
  -> FUN_004B22D0 strict viewport unproject
  -> FUN_004B24F0 ray / Y=0 plane intersection
  -> FUN_004D3580 world-to-grid quantization
  -> controller +0x24/+0x28 hover grid
  -> FUN_004D6310 target validation
  -> 0x022142DB bit 0x40 left press edge
  -> selected X/Y and linear ID, state 2
```

`0x022142DB/DC` are the synthesized `VK_LBUTTON/VK_RBUTTON` bytes. They are not previous/current samples. Right press edge sets cancellation state 3.

## Projection and grid formulas

- D3D view: `0x009D1368`
- D3D projection: `0x009D13A8`
- D3D world: `0x009D13E8`
- D3D viewport: `0x009D1428`
- mouse strict-interior RECT: `*(0x007C1B4C)+0x2A5FC`
- `gridX = ftol(worldX + 50.0)`
- `gridY = ftol(25.0 - worldZ)`
- cell center: `(gridX - 49.5, 0, 24.5 - gridY)`

The hit region is the set of integer client pixels whose current inverse projection intersects the Y=0 plane inside the requested grid and also passes `FUN_004D6310`. It is dynamic, not a fixed AABB.

## Original validator reproduction

The collector binds:

- target cell type through `DAT_007CCFFC + 0x2C03CC` index and `+0x2C1755` record; only types 1 and 3 pass;
- target render-record `+0x3C` when requested choice is nonzero;
- current cell from `DAT_007CD04C+0x11178`;
- controller filter `+0x20`; target distance must be at most `filter + 0.05`, and choice zero rejects the current grid.

## Implemented contract

`collect-destination-projection-snapshot.ps1`:

- enforces canonical EXE hash, fresh PID/start time, module base, HWND ownership, MainWindowHandle, and client dimensions in live mode;
- uses only query/read process access;
- captures controller, validator, engine RECT, D3D viewport, and three matrices twice;
- requires both semantic capture surfaces to match;
- exposes 144 scalar reads in the fixture contract and performs no writes or input.

`resolve-destination-hit-region.ps1`:

- refuses absent original target validity, ineligible stage, singular matrices, nonzero viewport origin, and off-screen targets;
- refuses a JSON `LIVE_READONLY` self-claim and retains it only as an unverified claim until a separate independent binding unit;
- scans the strict viewport interior with one precomputed inverse matrix;
- emits exact scanline spans and a half-open bounding rectangle;
- proposes a point only if its full 3x3 neighborhood maps to the same grid.

The synthetic 100x100 fixture yields 25 pixels, rectangle `[50,46,55,51)`, and safe point `(52,47)`. These values are `OFFLINE_FIXTURE` evidence only and are forbidden as oracle coordinates.

## Verification

- collector: 10 cases / 46 mechanically counted assertions
- resolver: 7 cases / 34 mechanically counted assertions
- static markers: 12
- forbidden capability hits: 0
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE` after one `REVISE` cycle

## Remaining boundaries

- fresh destination runtime projection snapshot: `UNSEEN`
- original-runtime hit region and safe point: `UNSEEN`
- destination activation: `UNSEEN`
- confirmation activation: `UNBOUND`
- player-visible WARP, wire request/notify, authority, persistence: `UNSEEN`

The first missing destination boundary is `FRESH_DESTINATION_PROJECTION_SNAPSHOT`. Before requesting a new full-sequence permit, the next offline unit must correct the TextDialog confirmation collector because its earlier origin/rectangle model is now statically disproved.
