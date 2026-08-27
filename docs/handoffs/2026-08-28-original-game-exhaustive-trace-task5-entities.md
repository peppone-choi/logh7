# Handoff - exhaustive trace foundation Task 5 entities/records

- status: `PASS` for the bounded static entity/record inventory; overall goal `INCOMPLETE`; original gameplay/runtime and live world population `UNSEEN`
- inventory: 237 rows — the exact controlled universe of 71 entity types plus 166 record types; all reachability `UNKNOWN`
- raw surface: 4,388 candidates; 684 represented by normalized rows, 3,704 explicit `UNRESOLVED`, excluded 0, unaccounted 0
- compiled coverage: 410 stream contracts, 230 unique family/field pairs, 1,015 parser references, 347 registries, 167 stride/cap candidates, 107 comparisons, 186 relationship candidates, 379 lifecycle candidates, 329 wire projections, 627 cache consumers, 2 renderer anchors, and 545 labels
- cardinality boundary: factions 2 are `ORIGINAL_MANUAL` with `銀河帝国` page 5 and `自由惑星同盟` page 62 bound separately; 80 systems/281 planets/6 fortresses/6 special bodies are `LEGACY_CANDIDATE`; 85 systems/300 planets/6 fortresses are `NEW_DESIGN`; spot count is `UNKNOWN`; caps and 44 background assets are not populations
- evidence discipline: account is protocol-derived, not manual; special celestial bodies are legacy-catalog-only; 28 absent/alias-uncertain controlled types are explicit `AUTHORED_PLACEHOLDER`; manual terms bind PDF page anchors and use flat OCR as search-only; all record layouts remain `CANDIDATE`; all stable ID, membership, ownership, location, authority, persistence, reconnect, and live-count gaps remain explicit
- reproducibility: raw SHA-256 `5C4136A1C789EA594A9C1F8BF7439AFBEF37DB689CFEBBF0B0E021C254F209C0`; inventory `36F9408212D1235005D83C4D4CD0C3CCF3AE657728F5274A5097FEC55E2D6410`; reconciliation `A97131D798CB442569A98D09763A8ED0F926D2546091E3BAE118881449699590`; Ghidra DB unchanged at `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`
- validation: 24 focused tests and 93 aggregate tests passed; source gate verified 21 paths
- independent review: contract, Ghidra-locus, and protocol/source reviews all `APPROVE`; reviewer writes 0
- report: `work/20260828-original-game-exhaustive-trace-task5-entities/report/task5-entities-report.md`
- receipt: `work/20260828-original-game-exhaustive-trace-task5-entities/evidence/task5-verification.json`
- runtime state: no VM, client launch, debugger, process memory, input, binary patch, Ghidra DB, server, protocol, database, port, or lifecycle action
- next start: foundation Task 6 — export resources and loader ownership
- forbidden retry: do not promote legacy/new-design catalogs, array caps, asset counts, UI slots, or name matches to original-live population or identity joins; do not call static record anchors proof of world download, playability, authority, persistence, Gate-A, or Gate-B; do not run Ghidra without `-readOnly`
