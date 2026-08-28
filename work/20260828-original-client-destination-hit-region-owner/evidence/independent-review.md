# Independent read-only review

- reviewer task: `/root/hit_region_review`
- final disposition: `APPROVE`
- files modified by reviewer: none
- verifier: exit 0
- collector tests: 10 cases / 46 mechanically counted assertions
- resolver tests: 7 cases / 34 mechanically counted assertions
- static markers: 12
- forbidden capability hits: 0
- live operations: 0
- game inputs: 0
- permit issued: false

The first review returned `REVISE` because identity matrices could not detect multiplication-order mutations and an input JSON could self-promote from fixture to `LIVE_READONLY`. The single writer replaced the fixture with noncommuting matrices and made every self-claimed live snapshot return `UNBOUND / LIVE_SNAPSHOT_INDEPENDENT_BINDING_REQUIRED`.

The final review independently reran the verifier and confirmed:

- view/projection/world/viewport addresses and W*V*P/D3D viewport conventions against the raw export;
- strict viewport bounds, z=0.1/100 unprojection, Y=0 plane intersection, `ftol` grid formulas, cell centers, and filter epsilon;
- `FUN_004D6310` cell lookup, type, active-record, current-grid, and distance behavior;
- fresh identity gates, 144-read double capture, torn-surface rejection, and read/query-only native imports;
- all ledger artifact hashes and fixture/runtime fail-closed documentation.
