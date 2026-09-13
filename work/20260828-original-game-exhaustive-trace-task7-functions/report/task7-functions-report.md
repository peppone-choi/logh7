# Exhaustive trace foundation Task 7 report

## Verdict

`PASS` for the bounded static original-client function inventory and reconciliation. The overall reimplementation goal remains `INCOMPLETE`. Original-client playability, actual runtime reachability, player-visible HUD, correct flagship selection, world/celestial delivery, complete commands, two-faction play, persistence, Gate-A, and Gate-B remain `UNSEEN`, `UNKNOWN`, or pending later units.

## Closed function surface

The frozen Ghidra program contains 12,044 defined functions: 11,593 internal functions and 451 externals. The inventory represents all 11,495 internal non-thunk functions as individual rows, groups 98 internal thunks, and replaces the 451 Ghidra externals with the authoritative 452-member raw PE-import surface. This produces 12,045 function-surface members. Raw PE enumeration retains `GetACP`, so that one-entry difference is explicit rather than collapsed. No Ghidra internal address disappears and no internal non-thunk function is grouped as generic plumbing.

Each individual row carries address, original and proposed-name disposition, direct callers and callees, explicit indirect callsites, input/output disposition, global reads/writes/string references, side effects, confidence, evidence, reachability, independent state flags, and all eight implementation dispositions. Individual-to-individual direct call edges are reciprocal. Group-member edges retain the inbound reference in the individual caller because group rows intentionally have no member-level reciprocal edge. Every caller source must be an individual, grouped member, or exact unresolved `(address, callsite)` candidate. Two dangling direct targets remain explicit unresolved candidates at callsites `00604C66` and `00604CBC`; they are not guessed into functions.

The four upstream static surfaces contribute 8,045 structured function-reference candidates. Of these, 7,996 resolve as evidence links and 49 remain unresolved at the exact structured field boundary. `nearestPriorFunction` and `nearestPriorFunctionEntry` are excluded because proximity is heuristic, not semantic identity. An upstream mention changes only `classification.status` to `EVIDENCE_LINKED`; it does not assign a semantic name, reachability, behavior, or ownership.

## Dispositions and conservation

The normalized inventory has 11,497 rows: 11,495 individual functions and two groups. Classification is 461 `EVIDENCE_LINKED`, 11,034 `UNADJUDICATED_INTERNAL`, and two `GROUPED_BY_RULE`. Every row has reachability `UNKNOWN`; only `ENUMERATED` is true. No runtime, authority, codec, persistence, both-faction, independently reviewed, static-mapped, or player-visible state is promoted.

The raw surface has 20,094 unique candidates, including the two structural group-wrapper IDs. Reconciliation normalizes 20,043 and retains 51 as explicit `UNRESOLVED` candidates: 49 structured upstream addresses and two direct call targets. Unaccounted count is zero. Side-effect tags remain mechanical static facts: 7,116 `RETURNS`, 5,532 `CALLS_INTERNAL`, 4,331 `READS_GLOBAL`, 1,663 `CALLS_INDIRECT`, 1,107 `WRITES_GLOBAL`, and 626 `CALLS_EXTERNAL`.

## Reproducibility and limits

Two final `-noanalysis -readOnly` Ghidra exports are byte-identical at `11B52C0D538773B24BEAC68F946EFD663BA96E5931BFBA5BD715600E269807E5`. The exporter is `FEBAABCBA50985CE36D039787428BF5383FBC8765F904CFF70EB2F6499D2C6BB`; the semantic Ghidra database stayed bound to `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`. Three importer outputs reproduce inventory `4EFA62A95AA81CBB7B8D5983A865B217FD77CEE96EE31DA1B37625B0B6BA0DA3` and reconciliation `BC89C232F5CDE0BABC956CBAF4865AD9B580576A2C3BA2FB94B77C62A163F925`.

Focused tests pass 23/23, all exhaustive-trace tests pass 138/138, and the source gate verifies 21 paths. This unit closes enumeration and conservative static linking only. Function semantics, runtime execution, player behavior, and implementation remain later trace and implementation work.

Three final independent read-only reviews - contract, Ghidra-universe, and provenance/input-link - returned `APPROVE`; reviewer writes were zero.
