# Timeline

## 2026-08-27 | Task 2 start and RED

- located the canonical CD-extracted client and `constmsg.dat`, two distinct official manual editions, original CD/InstallShield lineage, `Unit10Input` Ghidra project, and installed tool paths
- confirmed target client SHA-256 `BD19263C...AB6E16` and message SHA-256 `5B3FAFBA...5C383C`
- observed initial RED: `ModuleNotFoundError: tools.exhaustive_trace.source_manifest`

## 2026-08-27 | import reconciliation

- compared the existing hash-bound Ghidra external-function export with the raw PE import directory
- found 451 Ghidra external functions versus 452 raw PE imports; the sole missing raw import was `KERNEL32.DLL::GetACP`, while four ordinal imports were friendly-name aliases
- implemented a checked-in deterministic raw PE generator and preserved the static dynamic-resolution limitation

## 2026-08-27 | fail-closed review corrections

- made `messageData` mandatory and kept the CD and later web manuals as distinct frozen sources
- changed resource validation from sidecar-only to exact 2,192-file path/hash set verification, including rejection of unlisted additions
- bound PE schema/counts/groups/source facts, generator/parser, Ghidra repository/program/headers/exporter/output, and tools by hash
- centralized explicit import classification so the generator and validator cannot drift
- removed hash overrides from the public `SourceManifest.load(path)` contract

## 2026-08-27 | close

- focused Task 2 suite: 13 passed, 0 failed
- aggregate exhaustive-trace suite: 27 passed, 0 failed
- deterministic regeneration reproduced SHA-256 `E0C5BADAE9C5062B2E9F767AB88BA22E557ED99004B8B165BEA7B196DF9A3FBE`
- manifest CLI passed with 21 hash-bound verified paths
- both final independent read-only reviews returned `OK`; reviewer writes 0
- no live/runtime/server/protocol/database mutation occurred
