# Timeline

## 2026-08-28 | Task 4 start and RED

- re-read the active goal, plan, project manual, and mistakes journal
- retained Codex as single writer and split contract, Ghidra-locus, and prior-source discovery into three read-only lanes
- observed RED as the missing `tools.exhaustive_trace.import_ui` module, then added behavior-first row, conservation, determinism, reachability, and evidence-binding tests

## 2026-08-28 | whole-program UI anchor export

- reused only the clean, semantic-DB-hash-bound `ProtocolTrace` project and ran every final headless export with `-readOnly`
- mechanically exported three root modes, 48 direct manager constructor calls, 260 manager lookups, 340 direct widget-constructor calls, 11 descriptor-loader calls, 598 label lookups, 255 event-predicate callsites, 22 exact event types, 50 predicate-owning functions, 453 enable-writer calls, 19 visibility-writer calls, four child-manager attachments, 37 calls across the three input-source functions, and two render-anchor calls
- derived a common mode-1/2/3 manager-`0x16` wrapper and both manager-`0x0B` CFG paths (mode 1/selector 0 and mode 2/selector 1), retained eight expanded selector-1 rows and seven manager-`0x16` information-menu rows, and excluded the single expanded loop template rather than double-counting it as a logical widget
- derived all root mode associations from the `DEC`/`JZ` chain and call targets; derived selector stack offsets, values, sentinel, gates, and final override from instruction operands
- bound the seven menu labels to hash-fixed `constmsg.dat` table `0x25`, the cell consumer, event `0x0E`, payload consumer, and the current jump-table targets

## 2026-08-28 | normalization and boundaries

- normalized 422 logical rows: three mode roots, 54 mode-qualified manager roots, 358 mode-qualified widget rows, and seven menu rows
- all 422 reachability values remain `UNKNOWN`; only `ENUMERATED` is true for every row; 357 known state-field candidates are explicitly `CANDIDATE`
- retained four menu rows as `STATIC_RESET_ONLY_IN_INSPECTED_CONSUMER` handler facts without calling them disabled, unreachable, implemented, or player-visible
- reconciled all 2,119 raw candidates: 407 normalized candidate IDs, 1,711 explicit unresolved candidates, one typed exclusion, and zero unaccounted candidates

## 2026-08-28 | reproducibility and close

- two final read-only exports were byte-identical at SHA-256 `A1B96D615294A6285F4F7E6FDC807218CD9729179BBE8D8FD39255E5F9DCD6F0`
- clean Ghidra semantic program DB remained `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`
- two importer runs reproduced inventory SHA-256 `E25764B9150A95F8D39101524C8D7980E3239BD53BAC6A68E3253D395413DE70` and reconciliation SHA-256 `9642B8D2B48D1078941F411DDD23C92DDD05B9493A0FA427D61D7606F93FBC7D`
- independent final reviews: contract, Ghidra-locus, and source/reproduction lanes all `APPROVE`; reviewer writes 0
- no runtime, VM, binary, Ghidra database, server, protocol, or database mutation occurred
