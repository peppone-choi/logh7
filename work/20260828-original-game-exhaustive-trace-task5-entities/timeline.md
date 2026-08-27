# Timeline

## 2026-08-28 | Task 5 start and RED

- re-read the active goal, foundation plan, project manual, and mistakes journal
- retained Codex as single writer and split contract, Ghidra-locus, and protocol/catalog-source discovery into three read-only lanes
- observed RED as the missing `tools.exhaustive_trace.import_entities` module, then added fail-closed identity, relation, lifecycle, layout, catalog-boundary, implementation-target, conservation, determinism, and evidence-binding tests

## 2026-08-28 | static entity and record export

- reused only the clean semantic-DB-hash-bound `ProtocolTrace` project and ran every final headless export with `-readOnly`
- rescanned and cross-checked all 410 Task 3 stream contracts, producing 166 record-family rows and 410 field candidates across 230 unique family/field pairs
- retained 1,015 parser references, 347 registry candidates, 167 stride/cap candidates, 107 comparison candidates, 186 relationship-name candidates, 379 lifecycle call candidates, 329 wire projections, 627 cache consumers, 2 renderer anchors, 545 protocol labels, and 37 catalog/goal claims
- closed the controlled universe at 71 entity types: 29 manual types and 42 protocol-derived, legacy-candidate, or explicit goal-placeholder types

## 2026-08-28 | source boundaries and corrections

- checked every proposed Japanese manual term against whitespace-normalized, hash-bound OCR; removed unsupported `ACCOUNT` and `SPECIAL_CELESTIAL_BODY` manual classifications
- retained account only through protocol-derived record evidence and special celestial bodies only as `LEGACY_CANDIDATE`
- kept playable-faction count 2 as `ORIGINAL_MANUAL`; kept 80 systems, 281 planets, 6 fortresses, and 6 special bodies as `LEGACY_CANDIDATE`; kept 85/300/6 extended values as `NEW_DESIGN`; kept spot count `UNKNOWN` because 44 background assets are not 44 spot entities
- fixed the serialized contract after tests caught omitted required null slots and floating-point catalog counts
- after independent contract review, removed every `MANUAL_ONLY` reachability claim, linked manual rows to shipped record families where present, added every missing controlled type as an explicit `AUTHORED_PLACEHOLDER`, and rejected dangling relation/projection references and non-slot-specific proven relation verbs
- bound every manual term to the original PDF SHA, a concrete PDF page, and the page-indexed DjVu XML SHA; OCR text is retained only as a search count
- bound the playable-faction cardinality to both exact members: `銀河帝国` on PDF page 5 and `自由惑星同盟` on page 62; exporter and importer reject a manual count that differs from its page-bound member list

## 2026-08-28 | normalization and reproduction

- normalized 237 rows: 71 `ENTITY_TYPE` rows and 166 `RECORD_TYPE` rows
- all 237 reachability values remain `UNKNOWN`; no row is promoted to runtime, player-visible, authority, persistence, reconnect, or implementation-complete evidence
- reconciled all 4,388 raw candidates: 684 candidate IDs are represented by normalized rows, 3,704 remain explicit unresolved candidates, zero are excluded, and zero are unaccounted
- two final read-only exports were byte-identical at SHA-256 `5C4136A1C789EA594A9C1F8BF7439AFBEF37DB689CFEBBF0B0E021C254F209C0`; the externally checked Ghidra semantic DB remained `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`
- two importer reproductions matched inventory SHA-256 `36F9408212D1235005D83C4D4CD0C3CCF3AE657728F5274A5097FEC55E2D6410` and reconciliation SHA-256 `A97131D798CB442569A98D09763A8ED0F926D2546091E3BAE118881449699590`
- 24 focused tests and 93 aggregate exhaustive-trace tests passed; source gate verified 21 paths
- independent final reviews: contract, Ghidra-locus, and protocol/source lanes all `APPROVE`; reviewer writes 0
- no runtime, VM, binary, Ghidra database, server, protocol, or database mutation occurred
