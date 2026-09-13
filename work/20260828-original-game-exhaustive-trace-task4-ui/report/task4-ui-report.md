# Exhaustive trace foundation Task 4 report

## Verdict

`PASS` for the bounded static UI/input anchor inventory and reconciliation. The overall reimplementation goal remains `INCOMPLETE`; original-client playability and actual player-visible behavior remain `UNSEEN`.

## Exported surface

- root modes: 3 exact branches, modes 1/2/3
- manager surface: 48 direct constructor callsites, one common manager-`0x16` wrapper binding, one manager-`0x0B` binding with distinct mode-1/selector-0 and mode-2/selector-1 CFG paths, and 260 lookup callsites
- widget surface: 340 direct constructor callsites plus 8 mechanically expanded mode-2 manager-`0x0B` selector rows; one loop template is explicitly excluded after expansion
- descriptor loaders: 11 unresolved callsites, retained because a generic loader can instantiate multiple data-driven widgets
- labels: 598 lookup callsites; seven manager-`0x16` menu labels are bound through the builder and `constmsg.dat` table `0x25`
- events/handlers: 255 event-predicate callsites, 22 exact event cases (`0x00..0x0F`, `0x12..0x17`), and 50 caller functions
- state relations: 453 enable-writer calls, 19 visibility-writer calls, and 4 child-manager attachment calls
- input/render: 37 callsites across Win32 input snapshot and DirectInput poll functions, including 16 `GetAsyncKeyState` calls; two render-anchor calls

## Normalized rows and conservation

The normalized inventory contains 422 rows: 3 mode roots, 54 mode-qualified manager roots, 358 widgets, and 7 menu rows. The raw surface contains 2,119 unique candidate IDs. Reconciliation accounts for all of them: 407 candidate IDs normalize to rows, 1,711 remain explicit `UNRESOLVED`, one expanded loop template is `EXCLUDED`, and `unaccountedCount` is zero.

All rows retain `reachability=UNKNOWN`. Only `ENUMERATED` is true; static construction does not promote `PLAYER_VISIBLE`, runtime, authority, persistence, faction, or independent-review state. A constructor default hit-test value of zero is retained as a fact and never converted automatically to `SHIPPED_DORMANT`.

## Detailed mode-2 facts

The current export derives the root dispatcher from its three `DEC EAX`/`JZ` branch targets and their unique builder calls. It records manager `0x16` as common to modes 1/2/3 and manager `0x0B` with both CFG predecessor tuples: mode 1 uses selector 0, while mode 2 uses selector 1. For selector 1, the exporter derives the nine stack values (eight indexes plus the `-1` sentinel), eight initial gates, and index-7 final override from instruction operands before comparing the expected annotations fail-closed. The rows are indexes `0x0B,0x07,0x09,0x02,0x00,0x08,0x0A,0x0C`; index `7` has the proven manager-`0x16` attachment and final hit-gate override. No gate-zero row is called visually disabled or dormant.

Manager `0x16` category-4/index-0 has seven bound menu rows. Rows `0/2/4` have distinct downstream branches; rows `1/3/5/6` share the reset-only target in the inspected consumer. This is a handler disposition, not proof that the UI row is unselectable or that the broader game feature was intentionally removed.

## Reproducibility and limits

The exporter verifies the original EXE, its own externally supplied hash, the clean Ghidra semantic DB hash, and the message-data hash before writing. The evidence manifest binds the exporter and raw bytes, while the importer independently verifies the exact executable hash/language/compiler/image base and source manifest. It rejects unknown top-level collections, runtime-address identities, dangling child-manager links, `UNKNOWN` sections that claim joined fields/functions/targets, `SHIPPED_REACHABLE` rows without call-path/event/handler/enablement/visibility proof, and unaccounted candidates. Known but unjoined `widget+0x15` fields are now typed `CANDIDATE`, not `UNKNOWN`.

Unresolved candidates are deliberate next boundaries, not missing data hidden by the importer. In particular, generic descriptor rows, most label-to-widget joins, event-to-widget joins, enable/visibility writers, dynamic manager IDs, and render reachability require further static joins or fresh runtime observation. This unit does not prove actual pixels, commands, original-server behavior, gameplay, Gate-A, or Gate-B.
