# Handoff - exhaustive trace foundation Task 4 UI/input

- status: `PASS` for the bounded static UI/input anchor inventory; overall goal `INCOMPLETE`; original gameplay/runtime `UNSEEN`
- inventory: 422 rows — 3 mode roots, 54 manager roots, 358 widgets, and 7 menu rows
- raw surface: 2,119 candidates; 407 normalized candidate IDs, 1,711 explicit `UNRESOLVED`, 1 typed exclusion, unaccounted 0
- root/manager/widget coverage: mechanically derived modes 1/2/3, 48 direct manager constructors, a common mode-1/2/3 manager-`0x16` wrapper, a manager-`0x0B` wrapper with separate mode-1/selector-0 and mode-2/selector-1 CFG paths, 260 manager lookups, and 340 direct widget constructor calls plus 8 expanded selector-1 rows
- label/input coverage: 598 label lookups; 7 current message-table labels bound; 255 event-predicate callsites plus 22 event types; 37 input-source calls including 16 `GetAsyncKeyState` calls
- evidence discipline: all 422 reachability values remain `UNKNOWN`; only `ENUMERATED` is true; 357 known-but-unjoined state fields are `CANDIDATE`, while empty unjoined sections remain `UNKNOWN`; construction/gate/handler/render evidence is not promoted to player-visible or playable
- mode-2 correction: manager-`0x0B` gate-zero rows are not called visually disabled/dormant; manager-`0x16` rows `1/3/5/6` are only reset-only in the inspected downstream consumer
- reproducibility: two raw exports SHA-256 `A1B96D615294A6285F4F7E6FDC807218CD9729179BBE8D8FD39255E5F9DCD6F0`; inventory SHA-256 `E25764B9150A95F8D39101524C8D7980E3239BD53BAC6A68E3253D395413DE70`; reconciliation SHA-256 `9642B8D2B48D1078941F411DDD23C92DDD05B9493A0FA427D61D7606F93FBC7D`
- validation: 17 focused tests and 69 aggregate tests passed; source gate verified 21 paths
- independent review: contract, Ghidra-locus, and source/reproduction reviews all `APPROVE`; reviewer writes 0
- report: `work/20260828-original-game-exhaustive-trace-task4-ui/report/task4-ui-report.md`
- receipt: `work/20260828-original-game-exhaustive-trace-task4-ui/evidence/task4-verification.json`
- runtime state: no VM, client launch, debugger, process memory, input, binary patch, Ghidra DB, server, protocol, database, port, or lifecycle action
- next start: foundation Task 5 — export and normalize entity and record inventories
- forbidden retry: do not use `Unit10Input` as final evidence, omit `-readOnly`, use runtime pointers as stable IDs, infer visibility from construction, infer dormancy from default gate zero, infer label ownership by string proximity, collapse mode-specific manager IDs, discard unresolved descriptor/event/state candidates, or call this static unit proof of playability/authority/persistence
