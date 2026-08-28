# Original client manager67 current-card hit-surface owner

## Result

- static owner and read-only collector: `PASS`
- bounded status: `STATIC_SELECTED_AUTHORITY_CARD_WIDGET_OWNER_PASS / LIVE_TARGET_UNBOUND`
- original runtime: `UNSEEN`
- player-visible result: `UNSEEN`
- live operations: 0
- game inputs: 0
- permit issued: false

This unit closes the missing offline collector for the manager65-bound authority card and manager67's page-selected click surface. It does not identify the card as a captain portrait and does not provide a live coordinate.

## Corrected root split

`U32(moduleBase+0x1E15E2C)` is a UI registry host, not the strategy root. The bound structures are:

- strategy root: `moduleBase+0x89E638`
- manager65 bound authority-card ID: signed `I32(strategyRoot+0x488)`
- manager67 controller: `strategyRoot+0x48C`
- registry: `U32(U32(moduleBase+0x1E15E2C)+0x0C)`
- manager67 context: `U32(registry+0x1A0)`, required to equal `U32(controller+0x00)`

## Current-card reconciliation

The collector requires all of the following:

- strategy mode equals 2;
- manager67 page is 2 or 3, card count is 1 through 16, and pending hit index is -1;
- `U8(currentRecord+0x270)` equals the controller count;
- `controller+0x628` equals `U32(U32(moduleBase+0x3CCFFC)+8)`;
- manager65 bound card ID is in `0..0xFFFF` and matches exactly one displayed card;
- displayed card `i` is `U16(currentRecord+0x26C+(count-i)*8)`;
- the uniquely reconciled card's action record contains command `0x2B`.

The proven semantic is `AUTHORITY_CARD_WITH_WARP_ACTION_NOT_PROVEN_CAPTAIN_PORTRAIT`. A captain identity or portrait association remains unknown.

## Page-selected widget surface

The manager67 constructor owns four 16-entry pointer arrays at controller offsets `+0x08`, `+0x48`, `+0x88`, and `+0xC8`. The click scanner reads the latter two.

- page 2 selects surface C at `controller+0x88+4*i`;
- page 3 selects surface D at `controller+0xC8+4*i`.

The canonical executable page-table records were independently recomputed from file bytes:

- page 2, file `0x26F540..0x26F747`, SHA-256 `263578B327E595418FC9D8BAC385F00D5573AF308D80700C7979D4E5194D5157`;
- page 3, file `0x26F748..0x26F94F`, SHA-256 `A4A62F0CC4AE9F8E112518BA7B0CDE651B5593A2564B73F743D7B9DCA61DBE53`.

The collector resolves the recursive manager context origin, local widget transform, dimensions, and initialization/hit/active/render gates. It captures the full semantic surface twice and blocks torn process or HWND state.

## Offline fixture result

The retained synthetic fixture reconciles bound card ID 200 to display index 1 and command list `[0x2B,0x01]`. Page 2 selects surface C. Its logical rectangle is `[310,225,430,285)`; under the synthetic 1.25/1.5 scale it resolves to client rectangle `[248,150,344,190)` and safe point `(295,169)`.

Those numbers are fixture-only and are forbidden as original-runtime coordinates. A JSON artifact that merely claims `LIVE_READONLY` remains `UNBOUND` and emits no region until separately bound.

## Verification

- collector: 36 cases / 59 assertions
- resolver: 6 cases / 19 assertions
- source hashes: 4
- canonical executable: verified
- page-table byte ranges: 2
- static collector markers: 12
- published fixture artifacts regenerated and byte-compared: 2
- artifact hashes bound by local ledger: 10
- native API surface: six query/read-only APIs
- forbidden capability hits: 0
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE` after one `REVISE` cycle

## Remaining boundaries

- fresh original-runtime manager65/manager67 snapshot: `UNSEEN`
- independently bound original-runtime hit region: `UNSEEN`
- physical activation: `UNSEEN`
- player-visible WARP result: `UNSEEN`
- wire request/notify, authority, and persistence: `UNSEEN`

The next offline unit should integrate this collector and resolver into the first-play prelaunch bundle. The controlling policy blocker `ACTIVATION_BUDGET_STAGE_CONTRACT_MISMATCH` remains outside this unit.
