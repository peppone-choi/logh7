# Handoff: D01 Internet Shortcut RESOURCE_LOADER

## Completed unit

- `RECOVERY:D01:RESOURCE_LOADER:1DFEC1FA0ADCE4B0`
- status: `PASS` for this bounded static adjudication
- overall status: `INCOMPLETE / STRUCTURAL_FATAL`

The exact original file is `銀河英雄伝説VII公式サイト.url`, a 50-byte Windows Internet Shortcut to `http://www.gineiden.com/`. Its game-runtime `RESOURCE_LOADER` is now explicitly `NOT_APPLICABLE` with hash-bound reason and evidence. No loader, owner, runtime use, player-visible state, or gameplay behavior was invented.

## Evidence and verification

- payload SHA-256: `4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8`
- adjudication SHA-256: `254C21ED3C7D83E2C9FADCEF3F2B6F70C476D43DB65A511ABE351F32617D75B8`
- resource inventory SHA-256: `4CCF362E0C7E49EB7CCF58F312E98741A50DBC964AD7DC8F08C515738D53FC68`
- graph SHA-256: `0354056E4E336454B4F8AC98AAD6FF1F9652B01517EC97BE792108E7FDCBCEED`
- coverage SHA-256: `C38AB74F12CC12F5A3761B7B8CF194D6593C2DE00F09497A4BFA2DAFD658F4B6`
- work-package SHA-256: `77E67DC8340D9F32808C05309FA4641BD45DD389248B4298BFCAE7DFC85DEF72`
- recovery SHA-256: `0DECFFF0DD9F5056EE7A294CBEBF6C8AE8E0767F97896318DE1A53B2004E82F4`
- aggregate verification receipt SHA-256: `8A8A4C439F1D3EF088F519DCD019C81D325C27F82F76F7D2C062ED7FC9BF509C`
- independent receipt SHA-256: `29C82A532B2809DD121583A77453950BC88032401598E1CC4C7DF77504650E4F`
- independent verdict: `APPROVE`
- tests: 272/272 PASS
- deterministic artifacts: 32/32 matched across checked, run-A, and run-B
- global fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`

## State transition

- old unit count: 0
- target row loader: `NOT_APPLICABLE`
- target row next boundary: `RESOURCE_OWNER`
- target row next unit: `RECOVERY:D01:RESOURCE_OWNER:9D646F35949A6258`
- next global dependency-ordered unit: `RECOVERY:D01:RESOURCE_LOADER:C7063B6F6EE54AC7` (`bootfirst.exe`)

The global next unit and this row's next unit differ because deterministic ordering advances to the next file before returning to later boundaries of the same row.

## Next start

Start only one newly authorized bounded unit. The deterministic global next unit is the `bootfirst.exe` loader adjudication. If continuity on the same shortcut row is explicitly preferred, start its `RESOURCE_OWNER` unit instead.

## Forbidden retries and promotions

- Do not rename the sanitized extracted file; preserve the Japanese name as alias evidence.
- Do not manufacture a G7MTClient loader or owner for installer metadata.
- Do not treat exact-reference absence as proof that dynamic access is impossible.
- Do not promote `STATIC_MAPPED`, `RUNTIME_OBSERVED`, `PLAYER_VISIBLE`, gameplay, authority, persistence, both factions, Gate-A, or Gate-B.
- Do not reuse the failed repository-local independent receipt path; use the verifier default or a fresh `.json` direct child of the real system temp root.
- Do not broaden the resource-loader evidence rule to all historical N/A sections without a separately authorized migration unit.
