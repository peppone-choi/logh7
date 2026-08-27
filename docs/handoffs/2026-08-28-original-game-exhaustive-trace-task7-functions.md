# Handoff - exhaustive trace foundation Task 7 functions

- status: `PASS` for the bounded static function inventory; overall goal `INCOMPLETE`; original gameplay and runtime reachability `UNSEEN`
- function universe: 12,044 Ghidra-defined = 11,593 internal + 451 external; inventory surface 12,045 = 11,495 individual internal non-thunks + 98 internal thunks + 452 raw PE imports; every address represented exactly once
- inventory: 11,497 rows — 11,495 `INDIVIDUAL_FUNCTION`, two deterministic `FUNCTION_GROUP`; all reachability `UNKNOWN`, only `ENUMERATED` true
- classification: 461 evidence-linked, 11,034 unadjudicated internal, two grouped by rule; upstream mention is not semantic identity
- upstream links: 8,045 structured references = 7,996 resolved mentions + 49 unresolved; heuristic `nearestPriorFunction` fields excluded
- unresolved: two dangling direct call targets plus 49 structured upstream addresses, all explicit; no guessed function creation
- reconciliation: 20,094 candidates = 20,043 normalized + 51 unresolved; unaccounted 0; two group-wrapper IDs included
- PE boundary: raw PE imports 452 vs Ghidra external functions 451; raw-only `GetACP` preserved
- reproducibility: raw `11B52C0D538773B24BEAC68F946EFD663BA96E5931BFBA5BD715600E269807E5`; inventory `4EFA62A95AA81CBB7B8D5983A865B217FD77CEE96EE31DA1B37625B0B6BA0DA3`; reconciliation `BC89C232F5CDE0BABC956CBAF4865AD9B580576A2C3BA2FB94B77C62A163F925`; Ghidra DB unchanged at `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`
- validation: 23 focused tests and 138 aggregate tests passed; source gate verified 21 paths
- independent review: contract, Ghidra-universe, and provenance/input-link final reviews all `APPROVE`; reviewer writes 0
- report: `work/20260828-original-game-exhaustive-trace-task7-functions/report/task7-functions-report.md`
- receipt: `work/20260828-original-game-exhaustive-trace-task7-functions/evidence/task7-verification.json`
- runtime state: no VM, client launch, debugger, process memory, input, binary patch, Ghidra DB, server, protocol, database, port, or lifecycle action
- next start: foundation Task 8 — inventory authority and persistence paths
- forbidden retry: do not infer semantics, ownership, reachability, playability, HUD correctness, flagship identity, world delivery, command completeness, faction support, Gate-A, or Gate-B from an address, size, name, call edge, string, upstream mention, or nearest-prior function; do not run Ghidra without `-readOnly`
