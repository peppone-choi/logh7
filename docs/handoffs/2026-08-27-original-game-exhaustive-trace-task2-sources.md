# Handoff - exhaustive trace foundation Task 2 sources

- status: `PASS`; overall goal `INCOMPLETE`; gameplay/runtime state `UNSEEN`
- frozen inputs: original client, `constmsg.dat`, exact 2,192-file InstallShield resource tree, original CD ISO, CD manual v1, later official web manual, Ghidra project/program, and toolchain
- import gate: x86 PE32, 19 descriptors, 452 unique raw imports, quality `readable`
- required group counts: Direct3D8 1; DirectInput8 1; DirectSound 1; Winsock 18; filesystem 23; registry 12; timing 13; process/thread 35
- reconciliation: prior Ghidra export has 451 external functions; raw PE adds `KERNEL32.DLL::GetACP`; four ordinal imports retain resolved-name aliases
- reproducibility: checked-in generator reproduces evidence SHA-256 `E0C5BADAE9C5062B2E9F767AB88BA22E557ED99004B8B165BEA7B196DF9A3FBE`
- validation: 13 focused tests and 27 aggregate tests passed; manifest CLI passed with 21 verified paths
- independent review: final code and evidence reviews `OK`; reviewer writes 0
- report: `work/20260827-original-game-exhaustive-trace-task2-sources/report/task2-sources-report.md`
- receipt: `work/20260827-original-game-exhaustive-trace-task2-sources/evidence/task2-verification.json`
- runtime state: no VM, client launch, debugger, process memory, live input, server, protocol, database, or port action
- next start: foundation Task 3 — export and normalize the complete protocol inventory
- forbidden retry: do not treat 451 Ghidra externals as the raw 452-import set, merge the two manual editions, trust a tree sidecar without exact file-set validation, infer gameplay semantics from imports, or claim dynamic API completeness from the static IAT
