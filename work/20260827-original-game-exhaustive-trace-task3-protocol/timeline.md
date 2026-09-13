# Timeline

## 2026-08-27 | Task 3 start and RED

- re-read the goal, `report/manual.md`, and `report/mistakes.md`; constrained the unit to offline static protocol enumeration
- split read-only discovery across contract, Ghidra-locus, and prior-source comparison lanes while retaining Codex as the only writer
- observed initial RED: `ModuleNotFoundError: tools.exhaustive_trace.import_protocol`
- after strengthening the contract, observed a second RED: missing `BodyMeasurementKind`; later observed the expected serializer-xref regression failure before implementing that path

## 2026-08-27 | exporter and normalization

- exported `FUN_004B8B00` parser cases, `FUN_004BA2B0` dispatcher cases, and `FUN_004B78A0` outbound bindings from the frozen x86 Ghidra program
- grouped consecutive switch aliases and added equality-condition code recovery, including the `else if (param_1 == 0x7002)` parser branch
- recovered 545 protocol-string candidates and all 410 stream limit diagnostics, including three dynamic `%d` caps; a raw-memory ASCII supplement recovered `MailSvAccountAddRequest` without retaining short random-byte false positives
- retained parser allocation sizes and stream array caps as facts; did not promote either to fixed wire-body length
- normalized 245 unique MESSAGE16 rows; only `ENUMERATED` is true by default and all later evidence states remain false

## 2026-08-27 | Message32 closed-world correction

- independent review found that the approved design required both `message16` and `message32`, while the first inventory only covered the selected Message16 path
- traced the shipped startup path at `FUN_004AD120` into `mpsCTMsg32ParseSystem`; despite its class name, the container's message type is uint16
- mechanically recovered 15 registered handler families, 302 exact codes, four true union holes (`0701`, `0703`, `0907`, `0A08`), and direction registration totals: C2S 161, S2C 243, bidirectional 102
- derived each direction from the startup factory chain, constructor top-vtable write, vtable metadata and lookup functions, lookup-array base, constructor pointer assignment, and exact logical slot; the encoded family offsets are validation annotations rather than emitted evidence
- after final review, replaced the remaining seed-driven family loop with a startup-function scan that discovers every factory-result→registry-vslot sequence; factory, constructor, top vtable, base, and count now come from the program, while annotations only fail closed on mismatch or omission
- added independent `PROTOCOL:MESSAGE32:*` rows rather than collapsing equal numeric values into Message16
- removed codec-ownership promotion based only on exact name equality, made direct-name conflicts fail closed as `SOURCE_CONFLICT`, and rejected unknown top-level export collections

## 2026-08-27 | Ghidra contamination recovery

- a read-only validator disclosed that nine exploratory headless runs omitted `-readOnly`; the scripts used no semantic mutation APIs and disabled analysis, but Ghidra persisted DB revisions and changed the `Unit10Input` repository hash
- marked `Unit10Input` contaminated and did not delete, revert, or refreeze it
- imported the same SHA-256-fixed original EXE into an isolated `ProtocolTrace` project, ran fresh autoanalysis once, and froze its single semantic program database at SHA-256 `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`
- changed Ghidra source validation to hash the semantic program database while excluding volatile project index files; regression tests prove index churn does not change the program hash and multiple DB revisions fail closed

## 2026-08-27 | conservation and reproducibility

- reconciled all 1,912 raw candidates: 1,393 normalized and 519 explicitly unresolved; unaccounted count 0
- unresolved candidates remain split as outbound missing-response slots 73, protocol strings 224, and stream contracts 222
- two read-only Ghidra exports from the clean project were byte-identical at SHA-256 `00E568BC71A37907D9F6CB5981511E46F921529D28F0339A44968F68A1F2F30E`
- a second importer run reproduced both normalized artifacts byte-for-byte
- the clean project's semantic program database remained `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF` across final read-only exports

## 2026-08-27 | close

- focused Task 3 suite: 23 passed, 0 failed
- aggregate exhaustive-trace suite: 52 passed, 0 failed
- source-manifest gate passed with 21 verified paths
- independent final reviews: contract `APPROVE`, Ghidra-locus `APPROVE`, prior-source reconciliation `APPROVE`; reviewer writes 0
- no live/runtime/server/protocol/database mutation occurred
