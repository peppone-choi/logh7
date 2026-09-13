# Exhaustive trace foundation Task 2 report

## Verdict

`PASS` for foundation Task 2. The original client, message data, original resource tree, two official manual editions, Ghidra analysis state, required tools, generator, and PE import evidence are now frozen behind one fail-closed `SourceManifest.load(path)` gate.

## Frozen evidence

- client: 3,956,736 bytes, SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`
- `constmsg.dat`: 114,905 bytes, SHA-256 `5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C`
- PE: x86 PE32, machine `0x014C`, optional-header magic `0x010B`, image base `0x00400000`
- raw import directory: 19 descriptors, 452 unique imports, quality `readable`
- required groups: Direct3D8 1, DirectInput8 1, DirectSound 1, Winsock 18, filesystem 23, registry 12, timing 13, process/thread 35
- Ghidra cross-check: 451 external functions; the raw-only import is `KERNEL32.DLL::GetACP`; four ordinal/name pairs are preserved as aliases
- original resource root: all 2,192 sidecar-listed files are checked, and missing, changed, or unlisted files fail validation

## Reproducibility and review

`build_pe_imports.py` parses the raw import directory, records raw ordinals plus known Ghidra aliases, applies the same explicit classifier used by validation, and hashes itself, `pefile`, the Ghidra exporter, and its output. Fresh generation is byte-identical to the committed evidence.

Initial independent review identified incomplete trust-boundary enforcement. Regression tests and minimal fixes closed message omission, empty imports/groups, metadata/provenance omission, missing Ghidra repository, resource mutation/unlisted additions, wrong group classification, parser drift, manual merging, and public hash-override paths. Both reviewers reported final `OK` with no writes.

## Boundary

This task proves source identity and the static PE import surface only. `LoadLibraryA`/`GetProcAddress` mean runtime-resolved APIs are not enumerated by this artifact. No gameplay feature, protocol behavior, server authority, persistence, player-visible state, or two-faction playability is claimed. Foundation Task 3 remains `NOT_STARTED`.
