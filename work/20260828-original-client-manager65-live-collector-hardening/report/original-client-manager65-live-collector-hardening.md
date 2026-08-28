# Original-client manager65 live collector hardening

## Result

- static owner and hardened read-only collector/resolver: `PASS`
- bounded status: `OFFLINE_MANAGER65_HARDENED_COLLECTOR_RESOLVER_PASS_LIVE_UNSEEN`
- live operations / process-memory reads / inputs: `0 / 0 / 0`
- permit issued: false

This unit replaces, but does not edit, the sealed historical manager65 collector. It does not produce a live coordinate or prove WARP playability.

## Critical correction

The historical collector used `U32[0x02215E2C]` as the strategy root. Static reconciliation shows that address is the UI registry-host global.

- strategy root: `moduleBase+0x89E638`
- manager65 controller: `strategyRoot+0x130`
- registry: `U32(U32(moduleBase+0x1E15E2C)+0x0C)`
- manager65 registry slot: `U32(registry+0x198)`

The slot pointer must equal `U32(controller+0x00)`. The historical collector and receipt are retained only as superseded offline provenance.

## Hardened capture contract

The new collector internally enforces the canonical executable SHA-256 before process lookup and requires module base `0x00400000`. Live mode performs exact pre/post checks for PID, start time, executable hash, module base, HWND validity/ownership, MainWindowHandle, and client dimensions. Process access is restricted to `0x0410` query/read.

Each complete semantic surface is captured twice:

- builder/handler/strategy mode;
- registry-host/registry/manager65 slot;
- manager context and recursive origin;
- page, action count, selected action index, bound card ID;
- authoritative record action-count crosscheck and command IDs;
- every widget's initialization, local-transform, hit, active, render, and dimension gates;
- logical/client scale, engine logical surface, and HWND client rectangle.

The safe action semantic is `CURRENT_AUTHORITY_CARD_ACTION_WIDGET_FOR_COMMAND_0X2B_WARP_NAVIGATION`. It is not evidence of a captain portrait, player-visible WARP, server acceptance, or persistence.

## Offline fixture result

The synthetic fixture binds card ID 7, action index 1, and command `0x2B`. Its logical rectangle `[700,630,820,654)` maps through scale `1.25/1.5` to exact client region `[560,420,656,436)` with safe point `(607,427)`.

These values are fixture-only. A self-authored `LIVE_READONLY` capture is `UNBOUND`, emits no region, and has `automaticActivationPoint=null`. Only exact `SYNTHETIC_FIXTURE` provenance may resolve offline; unknown or missing provenance is rejected.

## Verification

- tests: 44 cases / 91 assertions
- parentless double-capture reads: 142
- parent-chain fixture reads: 156
- upstream source hashes: 4
- published fixture artifacts reproduced: 2
- local artifacts hash-bound: 9
- native API surface: six query/read-only APIs
- forbidden capability hits: 0
- aggregate verifier: `PASS`
- independent read-only review: `APPROVE` after two `REVISE` cycles

## Remaining boundaries

- fresh manager65 original-runtime snapshot: `UNSEEN`
- independently bound manager65 action-0x2B hit region: `UNBOUND`
- physical activation and visible WARP: `UNSEEN`
- wire, authority, persistence, Gate-A, Gate-B: not proven

The next offline unit should integrate this hardened receipt into prelaunch v3, retire `MANAGER65_LIVE_COLLECTOR_NOT_HARDENED`, and introduce the independent manager65 hit-region binding boundary without running the oracle.
