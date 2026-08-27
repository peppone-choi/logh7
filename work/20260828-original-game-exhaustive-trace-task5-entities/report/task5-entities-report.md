# Exhaustive trace foundation Task 5 report

## Verdict

`PASS` for the bounded static entity/record inventory and reconciliation. The overall reimplementation goal remains `INCOMPLETE`; original-client playability, live world population, authoritative server behavior, persistence, and actual player-visible behavior remain `UNSEEN` or `UNKNOWN`.

## Exported surface

The exporter independently rescans the hash-fixed program and cross-checks the Task 3 protocol artifact. It retains 410 stream contracts, 166 record families, 230 unique family/field pairs, 1,015 parser references, 347 registry candidates, 167 stride/cap candidates, 107 ID-comparison candidates, 186 relationship-name candidates, 379 lifecycle-call candidates, 329 wire projections, 627 cache-consumer candidates, two selected-planet renderer anchors, and 545 protocol labels.

These are compiled anchors, not completed structures. All 166 layouts remain `CANDIDATE`: exact field offsets, widths, signedness, stable ID namespaces, typed parent/owner/faction/location/visibility joins, authority, persistence, and reconnect/replay are not asserted without operand-level or live evidence.

## Normalized rows and conservation

The normalized inventory contains 237 rows: 71 entity types and 166 record types. Provenance is 179 `ORIGINAL_OBSERVED`, 29 `ORIGINAL_MANUAL`, one `LEGACY_CANDIDATE`, and 28 `AUTHORED_PLACEHOLDER`. All 237 reachability values are `UNKNOWN`; no static or manual row is called manual-only, shipped-reachable, or playable. The 28 placeholders close the controlled taxonomy while stating that original existence, aliasing, subtype membership, or data ownership is still unconfirmed.

The raw surface contains 4,388 unique candidate IDs. Reconciliation represents 684 through normalized rows and retains 3,704 as explicit unresolved candidates. No candidate is silently dropped: `excludedCount=0` and `unaccountedCount=0`. Every row carries all required ID, relation, lifecycle, projection, cache/renderer, authority, persistence, reconnect, recovery, and eight implementation-target dispositions; unknown sections cannot claim joined semantics. The importer also rejects a manual-only type when a shipped record exists, an incomplete 71-type universe, dangling relation fields, wrong slot-specific proven verbs, dangling protocol keys, and dangling projection fields.

## Cardinality and membership boundary

- The two playable factions are `ORIGINAL_MANUAL`, with `銀河帝国` bound to PDF/page-XML page 5 and `自由惑星同盟` to page 62; this does not prove every runtime faction row, both-faction behavior, or current database population.
- Counts 80 systems, 281 planets, and 6 fortresses are `LEGACY_CANDIDATE`, not original-live facts.
- Six special celestial bodies are `LEGACY_CANDIDATE` from a separately hash-bound model catalog, with system/grid membership still unknown.
- Counts 85 systems, 300 planets, and 6 fortresses are `NEW_DESIGN`, not original facts.
- Spot count remains `UNKNOWN`; the 44 original spot-background assets are explicitly not converted to 44 spot entities.
- Protocol array caps, presentation slots, and catalog parentage are not population counts, runtime identity joins, ownership, faction membership, or location proof.

`ACCOUNT` was removed from manual evidence because `アカウント` is absent from the bound search OCR and survives only through protocol-derived records. `SPECIAL_CELESTIAL_BODY` was likewise removed from manual evidence because `ブラックホール` is absent; it survives only as the legacy candidate above. Every retained manual term is now bound to the original PDF SHA, a concrete 1-based PDF page, and the page-indexed DjVu XML SHA; the flat OCR occurrence count is explicitly search-only. Representative original pages for ranking/unit vocabulary, playable-faction vocabulary, and transport-package vocabulary were rendered and inspected.

## Reproducibility and limits

The exporter mechanically verifies the original EXE and the manual text/PDF/page-XML, catalog-candidate, and Task 3 protocol input hashes before writing. The exporter and clean Ghidra DB hashes are externally supplied provenance values; the evidence manifest and structured verification receipt bind and independently check them rather than claiming the Java script can hash its own source or open project database. Two final `-readOnly` exports are byte-identical at `5C4136A1C789EA594A9C1F8BF7439AFBEF37DB689CFEBBF0B0E021C254F209C0`; the semantic DB remained unchanged at `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`. Two importer reproductions match inventory `36F9408212D1235005D83C4D4CD0C3CCF3AE657728F5274A5097FEC55E2D6410` and reconciliation `A97131D798CB442569A98D09763A8ED0F926D2546091E3BAE118881449699590`.

This unit does not prove full world data download, correct flagship model selection, HUD text, commands, proposals, two-faction gameplay, original character lists, actual entity counts, gameplay, Gate-A, or Gate-B. It creates the fail-closed entity/record surface needed to trace those vertical behaviors in later units.

Three final independent read-only reviews - contract, Ghidra-locus, and protocol/source provenance - returned `APPROVE`; reviewer writes were zero.
