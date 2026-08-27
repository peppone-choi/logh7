# Handoff - exhaustive trace foundation Task 3 protocol

- status: `PASS` for the bounded static-export unit; overall goal `INCOMPLETE`; original gameplay/runtime state `UNSEEN`
- normalized inventory: 547 code-space-qualified rows — Message16 245 and Message32-container 302; 161 direct names and 386 explicit unknown names
- static loci: Message16 parser 201 codes, dispatcher 200 codes, outbound binder 127 cases; Message32 15 registered handler families
- raw tables: 545 protocol strings and 410 stream contracts
- conservation: 1,912 candidates accounted; 1,393 normalized; 519 explicit `UNRESOLVED`; unaccounted 0
- evidence discipline: all 547 body sizes remain `UNKNOWN`; parser allocations, handler-family maximum lengths, and array caps are not wire lengths; Message16 name joins do not prove codec ownership
- Message32 fact: the class/container name does not imply a uint32 opcode; its registered message type is uint16, retained in a separate code space
- reproducibility: clean semantic program DB SHA-256 `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`; two raw exports SHA-256 `00E568BC71A37907D9F6CB5981511E46F921529D28F0339A44968F68A1F2F30E`; importer outputs also reproduced byte-for-byte
- validation: 23 focused tests and 52 aggregate tests passed; frozen source gate verified 21 paths
- independent review: contract, Ghidra-locus, and prior-source reviewers all `APPROVE`; reviewer writes 0
- report: `work/20260827-original-game-exhaustive-trace-task3-protocol/report/task3-protocol-report.md`
- receipt: `work/20260827-original-game-exhaustive-trace-task3-protocol/evidence/task3-verification.json`
- runtime state: no VM, client launch, debugger, process memory, input, binary patch, server, protocol, database, port, or lifecycle action
- next start: foundation Task 4 — export and normalize the complete UI/input inventory
- contamination note: prior `Unit10Input` was metadata-saved by a validator that omitted `-readOnly`; it remains preserved but is excluded from final evidence
- Message32 registration proof: all startup factory-result→registry-vslot sequences, factory, constructor, top vtable, base/count, lookup-array bases, constructor assignments, and logical slots are derived from the analyzed program and fail closed against the family annotations; 15 unique factory and 15 unique registry callsites are retained
- forbidden retry: do not reuse contaminated `Unit10Input`, omit `-readOnly` on export, hash volatile Ghidra indexes as semantic state, collapse Message16/Message32 equal numeric codes, call Message32 a uint32 opcode, treat allocation/cap values as wire lengths, infer identity from names/proximity, silently discard unresolved candidates, or call this static unit proof of playability/authority/persistence
