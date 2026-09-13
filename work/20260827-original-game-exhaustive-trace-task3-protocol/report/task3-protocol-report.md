# Exhaustive trace foundation Task 3 report

## Verdict

`PASS` for the bounded Task 3 static-export surface, with the overall game-reimplementation goal still `INCOMPLETE` and runtime playability `UNSEEN`. The result is reproducible and fail-closed over the three selected protocol loci plus exported name and stream tables; it is not a claim that dynamic-only or original-server-only messages have been recovered.

## Inventory

- parser: 156 switch case groups covering 167 labels plus 34 direct equality-condition codes; 201 unique parsed codes
- dispatcher: 162 switch case groups plus 38 direct equality-condition codes; 200 unique dispatched codes
- outbound binder: 127 local-kind bindings
- static candidate tables: 545 protocol strings and 410 stream contracts
- Message16 inventory: 245 unique rows; C2S 41, S2C 118, bidirectional 86
- Message32 container registry: 15 mechanically enumerated startup registrations and 302 unique rows; C2S 59, S2C 141, bidirectional 102
- combined inventory: 547 rows with code-space-qualified keys; 161 direct semantic names and 386 explicit unknown names
- body-size status: all 547 `UNKNOWN`; allocation sizes, family maximum lengths, and array caps are separate facts rather than wire lengths
- Message16 proven ownership: parser 201, dispatcher 200, serializer 0
- Message32 framework ownership: parser/dispatcher 243 and serializer 161, tied to mechanically enumerated startup factory-result→registry-vslot chains, derived constructor/vtable/base/count/array assignments, exact logical slots, and framework consumers

The zero Message16 serializer-owner count is deliberate. Stream diagnostics expose exact methods and xref addresses, but name equality is not an identity edge and the selected Ghidra xrefs do not prove opcode-to-codec ownership. Message32 ownership is separate: its startup registry gives exact direction arrays and framework send/parse consumers.

## Conservation

The reconciliation ledger accounts for all 1,912 raw candidates. Of these, 1,393 link to normalized rows and 519 remain explicit `UNRESOLVED`; no candidate is dropped. The unresolved set is 73 absent expected-response fields in outbound cases, 224 name strings without an exact direct row-name join, and 222 stream contracts without an exact direct row-name join. Prefixes, address proximity, allocation sizes, and nearest-prior function names are never used to manufacture semantics.

## Reproducibility and boundary

The exporter validates the original executable hash and embeds its own SHA-256 plus the frozen Ghidra semantic program-DB SHA-256. Two serialized read-only Ghidra runs produced identical raw bytes at SHA-256 `00E568BC71A37907D9F6CB5981511E46F921529D28F0339A44968F68A1F2F30E`. A separate committed evidence manifest binds the full raw-artifact SHA-256 and exporter SHA-256; the importer verifies it, the full frozen source manifest, semantic program DB, program identity, language/compiler/image base, success marker, embedded surface-digest syntax, unique startup/registry callsite evidence, and each registered Message32 assignment chain before writing output. Two imports were byte-identical.

An exploratory validator accidentally saved metadata revisions into the previous `Unit10Input` project by omitting `-readOnly`. That project is retained as contaminated evidence and is not used by the final artifacts. The final source is a fresh isolated `ProtocolTrace` analysis of the same fixed EXE; volatile Ghidra index files are excluded from the semantic DB hash, while multiple DB revisions fail validation.

No VM, original-client runtime, debugger, memory, click/input, binary patch, server, wire protocol, database, port, or lifecycle action occurred. `CODEC_PROVEN`, `RUNTIME_OBSERVED`, `PLAYER_VISIBLE`, `AUTHORITY_PROVEN`, `PERSISTENCE_PROVEN`, `BOTH_FACTIONS`, and `INDEPENDENTLY_REVIEWED` are not inferred from this static unit.
