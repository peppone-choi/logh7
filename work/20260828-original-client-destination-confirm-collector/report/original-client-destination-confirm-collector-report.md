# Original client destination and confirm stage collectors

## Result

`STATIC_STAGE_STATE_COLLECTORS_PARTIAL`

This bounded unit identifies the original-client state owners for the WARP destination-selection stage and the following confirmation dialog, and provides fresh PID/HWND-bound read-only process-memory collectors for both. Offline verification passes. No live oracle operation or activation was performed.

## Target identity

- file: `G7MTClient.exe`
- format: PE32 x86
- SHA-256: `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- analysis source: existing Ghidra project `work/ghidra-input-consumption/Unit10Input`, program `g7mtclient.exe`, opened read-only without reanalysis

## Confirmed static facts

### Destination selection

- `FUN_00581C80` constructs a heap `SelectGrid` flow object.
- the separate global selection-controller/state base used by `FUN_004D5030` and its consumers is `0x009D2A30`.
- mode `0x101` at `+0x04` identifies the destination-selection mode.
- result, selected-grid, requested-choice, X, and Y fields are at `+0x0C`, `+0x10`, `+0x14`, `+0x18`, and `+0x1C`.
- `FUN_004D5030` is the mode setter and `FUN_00570A10` consumes this state.
- the target-grid callback at `0x00573CD0` calls `FUN_004B49D0`, which queues local kind `0x52`.

### Confirmation dialog

- the WARP flow constructs `TextDialog` through `FUN_00572170(4, 0, 3)`.
- `FUN_004FDDE0(this=0x00C9E638, index=3, builder=4)` resolves manager base `0x00CA292C`.
- confirm and cancel widget pointers are manager `+0x24` and `+0x28`; layout kind at `+0x37C` must equal `4`.
- terminal state is manager `+0xDE0`; `FUN_0056F960` writes `3` for confirm and `4` for cancel, while `1` or `2` are waiting states.

## Implemented evidence collectors

- `collect-destination-stage-state.ps1` validates the exact executable hash, fresh PID/HWND ownership, and reads only the bounded destination-state fields.
- `collect-confirm-stage-state.ps1` performs the same identity checks and reads the manager state, widget pointers, and raw widget rectangles.
- native capability surface is restricted to `OpenProcess`, `ReadProcessMemory`, `CloseHandle`, `IsWindow`, `GetWindowThreadProcessId`, and `GetClientRect`.
- memory writes, binary patching, input synthesis, retry loops, VM lifecycle changes, and server/protocol/database changes are absent.

## Verification

- destination collector: 6 fixture cases, 24 mechanically counted assertions, `PASS_OFFLINE`
- confirmation collector: 5 fixture cases, 25 mechanically counted assertions, `PASS_OFFLINE`
- static markers checked: 8
- forbidden capability hits: 0
- live operations: 0
- permit issued: false
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE`

Machine receipt: `evidence/final-verification.json`. Review receipt: `evidence/independent-review.md`.

## Limits and first missing boundary

- destination activation rectangle: `UNBOUND`
- confirmation widget coordinate frame: `UNBOUND`
- runtime observation: `UNSEEN`
- player-visible proof: `UNSEEN`
- WARP command completion: `UNSEEN`
- authority, persistence, and both-faction behavior: `UNSEEN`
- first missing boundary: `DESTINATION_GRID_WORLD_TO_CLIENT_HIT_REGION_OWNER`

The raw destination X/Y state and confirmation widget rectangles are not yet safe activation coordinates. A later unit must statically bind the destination grid world-to-client hit-test owner, then bind the dialog-manager rectangle coordinate frame. A new live permit must not be requested before those offline bindings and their independent review are complete.
