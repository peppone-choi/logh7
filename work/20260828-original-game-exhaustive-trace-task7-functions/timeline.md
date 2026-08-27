# Task 7 timeline

- Read the current goal, Task 6 handoff, foundation plan, `report/manual.md`, and `report/mistakes.md` before action.
- Ran contract, Ghidra-universe, and upstream-input discovery in parallel; all agents were read-only.
- Added function importer tests and observed the required initial `ModuleNotFoundError` RED state.
- Implemented the Ghidra exporter and fail-closed importer, then passed 16 focused tests.
- First attempted final headless replay with the wrong case-sensitive project program name; Ghidra opened read-only and stopped before exporter execution. Retried with `/g7mtclient.exe`.
- Second attempt omitted the exporter's required hash/path arguments; the read-only script rejected usage before output. Retried with the complete hash-bound argument set.
- Contract review found that two group-wrapper candidate IDs were absent from reconciliation and that global ID/count/group-rule validation was incomplete; added RED tests, canonical group rules, case-insensitive global ID checks, exact conservation, honest group confidence, and an explicit grouped-edge reciprocity boundary.
- Renamed the ambiguous `definedFunctions` counter: Ghidra has 12,044 functions; substituting 452 raw imports for 451 Ghidra externals creates the 12,045-member inventory surface.
- Exported the corrected contract twice with `-noanalysis -readOnly`; both raw files SHA-256 `11B52C0D538773B24BEAC68F946EFD663BA96E5931BFBA5BD715600E269807E5`.
- The first corrected three-way normalization failed before final write because one CLI assertion retained the old counter name; corrected it and reran all outputs.
- Normalized official, reproduction A, and reproduction B outputs; all inventory hashes are `4EFA62A95AA81CBB7B8D5983A865B217FD77CEE96EE31DA1B37625B0B6BA0DA3`, and all reconciliation hashes are `BC89C232F5CDE0BABC956CBAF4865AD9B580576A2C3BA2FB94B77C62A163F925`.
- A second contract review found caller-only dangling sources and a validation bypass in the direct reconciliation API; added RED tests, required every caller source to be known/grouped or explicitly unresolved, rejected unused unresolved candidates, and made the public reconciliation path rebuild and compare the validated inventory.
- A third contract review found that signature parameters accepted scalar values and conservation counters accepted Python booleans; added RED tests, exact signature/parameter validation with `UNKNOWN`-only confidence, and exact non-negative integer counter types.
- Final focused tests: 23/23; aggregate exhaustive-trace tests: 138/138; source gate: 21 verified paths.
- Final independent contract, Ghidra-universe, and provenance reviews dispatched in parallel; reviewer writes remain zero.
