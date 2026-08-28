# Original TextDialog coordinate correction

## Result

`STATIC_COORDINATE_FRAME_AND_OFFLINE_RESOLVER_PASS / PARTIAL_LIVE_SNAPSHOT_UNSEEN`

The former confirmation collector is not safe as an activation-coordinate source. It treated manager `+0x7C/+0x80` as an origin, omitted widget `+0x18`, and did not bind recursive UI-context parents or the client-to-logical scale.

The corrected read-only collector now double-captures the fixed manager, UI-context chain, widget gates and geometry, scale globals, engine client rectangle, and owned HWND client size. It compares manager `+0xDBC/+0xDC0` with the root context's local origin and separately sums the parent chain for the logical hit-test origin.

The offline resolver enumerates client pixels and replays the original `trunc(pixel * float32Scale)` transform. It therefore preserves the exact half-open logical hit rectangle instead of using `trunc(logical / scale)`, which is not an inverse at boundaries.

## Evidence classification

- `ORIGINAL_OBSERVED`: none in this unit; no live run occurred.
- `INFERRED`, static hash-bound: offsets, recursive parent resolution, widget gates, half-open hit test, x87 round-to-zero scaling.
- `NEW_DESIGN`: fail-closed double-capture JSON schema and offline safe-point resolver.
- `UNSEEN`: fresh guest PID/HWND values, live context topology, live logical/client rectangles, actual player-visible activation.

Synthetic fixture results are not runtime coordinates and must never be clicked. A `LIVE_READONLY` self-claim is deliberately returned as `UNBOUND / LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED`.
