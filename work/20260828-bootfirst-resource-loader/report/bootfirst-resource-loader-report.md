# D01 BootFirst resource-loader adjudication

## Outcome

- Unit: `RECOVERY:D01:RESOURCE_LOADER:C7063B6F6EE54AC7`
- Bounded status: `PASS`
- Overall game goal: `INCOMPLETE / STRUCTURAL_FATAL`
- Closed boundary: `RESOURCE_LOADER`
- New disposition: `NOT_APPLICABLE` in game-asset-loader scope
- Proven structural relation: `BootFirst.exe LAUNCHES_PROCESS Gin7UpdateClient.exe`
- Runtime/player evidence promoted: none
- VM, original EXE execution, debugger, process memory, physical input, server, protocol, database, and binary writes: none

`BootFirst.exe` is a PE32 x86 update-client bootstrap. It performs `.new`/`.old` self-replacement housekeeping, starts `.\Gin7UpdateClient.exe`, waits for it, reads its exit code, and conditionally relaunches it. It is executable control-flow content, not a game asset consumed through a resource loader. No `G7MTClient.exe` asset loader was fabricated.

## Exact identities

- resource row: `RESOURCE:FILE:original-installshield-payload:bootfirst.exe`
- extracted relative path: `bootfirst.exe`
- byte size: `40,960`
- SHA-256: `23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096`
- format: `PE32 x86 GUI`
- image base: `0x00400000`
- entry RVA: `0x00001150`
- PE timestamp: `2004-02-13T06:19:56Z`
- file version: `1.0.0.0`
- company: `株式会社 マルチターム`
- description: `アップデートクライアント 起動プログラム`

Launched target:

- path: `.\Gin7UpdateClient.exe`
- byte size: `1,060,864`
- SHA-256: `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`

## Static control-flow evidence

- BootFirst function: `0x00401000-0x0040114D`
- target string VA: `0x004060A4`
- `CreateProcessA` IAT: `0x00405014`
- launch callsite: `0x004010B1`
- `WaitForSingleObject` callsite: `0x004010BE`
- `GetExitCodeProcess` callsite: `0x004010D2`
- exit code `1` triggers conditional relaunch, with a maximum of four attempts
- BootFirst has no direct `G7MTClient.exe` reference; the updater owns its separate later launch logic

InstallShield contains a shell object targeting `<TARGETDIR>\BootFirst.exe`, proving the installed entry point path but not proving setup automatically executes it.

Bound evidence:

- static receipt: `evidence/exhaustive-trace/adjudications/bootfirst-static-analysis.json`
- static receipt SHA-256: `CFE22BEE90C04E3BDBB4FCE461132925607CD1B8C041EA5EF70E7D402D318B38`
- Ghidra exporter SHA-256: `DB99864A5AF34DD888092F42E73094BDF8E664C4E68261F68E31E50AEAC54DD5`
- Ghidra output SHA-256: `BF48F72407EEAD223A4017AC991ECAE01C3AB9E4F8F44F5ED00F61315FD92A06`

## Implementation

- Added strict `PE_EXECUTABLE_BOOTSTRAP` adjudication validation to the resource importer.
- The importer verifies the PE/static receipt, exact source hash/size, referenced Ghidra exporter/output hashes, launch target existence and hash, address formats, and callsite evidence.
- Added `LAUNCHES_PROCESS` to the graph relation model and emitted exactly one proven, high-confidence, semantic, direct-typed-reference edge from BootFirst to Gin7UpdateClient.
- No `LOADS` edge is emitted for BootFirst.
- The target preserves `ENUMERATED=true`; `STATIC_MAPPED`, `RUNTIME_OBSERVED`, `PLAYER_VISIBLE`, authority, persistence, and gameplay states remain false.
- The loader gap is closed as scope-qualified `NOT_APPLICABLE`; the next boundary for this row is `RESOURCE_OWNER`.

## TDD and verification

RED failures were observed for:

1. PE bootstrap adjudication normalization and launch preservation,
2. exact `LAUNCHES_PROCESS` graph emission without `LOADS`,
3. static receipt hash mismatch rejection,
4. referenced exporter/output hash mismatch rejection.

GREEN results:

- related tests: 66/66 PASS
- complete exhaustive-trace suite: 276/276 PASS
- deterministic full-chain verifier: PASS
- independent fresh post-seal verifier: `APPROVE`
- checked/run-A/run-B artifacts: 32/32 byte-identical
- protected inputs: 88/88 unchanged
- source rows: 15,999
- graph: 35,685 nodes, 92,054 edges
- coverage fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`
- coverage evidence gaps: 25,607
- closed vertical traces: 0
- confirmed gameplay features: 0
- old unit occurrence: 0
- target row next unit: `RECOVERY:D01:RESOURCE_OWNER:6A88913B2B850300`
- next global dependency-ordered unit: `RECOVERY:D01:RESOURCE_LOADER:E8B07A6802B17EEF`

Sealed aggregate receipt:

- path: `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`
- SHA-256: `6DA901377E32FBC6BF179905437A55BE0B7A675AD5A2B4745DCCE09BBD2D3F81`

Independent receipt:

- path: `C:\Users\user\AppData\Local\Temp\logh7-bootfirst-independent-f0c48880217442fdb549b1ec29f52367.json`
- SHA-256: `2334C9E1511935D817DB9354FF21621D700016E59B76AECE89760FD2F4823FE4`
- verdict: `APPROVE`

## Failed approach and correction

The first Ghidra headless command failed before analysis because its project parent directory did not exist. No analysis artifact was produced. The exact owned directory was created and the command was rerun once successfully. Future headless invocations must create and verify the owned project parent before launch.

## Remaining unknowns

- The BootFirst row's `RESOURCE_OWNER` and implementation-target applicability remain unresolved.
- This unit does not prove BootFirst runtime execution, updater network behavior, updater-to-game launch success, original-client playability, any player-visible function, both factions, authority, persistence, Gate-A, or Gate-B.
- The project-wide feature reachability ledger is absent; all 15,999 source rows retain unknown overall verdicts.
- The complete C++23 client and C# authoritative server are not implemented by this unit.

## Reproduction

```powershell
python -B -m unittest discover -s tests/tools/exhaustive_trace -p 'test_*.py'
pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1
```
