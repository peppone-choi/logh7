# D01 Internet Shortcut resource-loader adjudication

## Outcome

- Unit: `RECOVERY:D01:RESOURCE_LOADER:1DFEC1FA0ADCE4B0`
- Bounded status: `PASS`
- Overall game goal: `INCOMPLETE / STRUCTURAL_FATAL`
- Closed boundary: `RESOURCE_LOADER`
- New disposition: `NOT_APPLICABLE` in original-client game-runtime scope
- Runtime/player evidence promoted: none
- VM, original EXE, server, protocol, database, process memory, and binary writes: none

The source file is proven to be the 50-byte Windows Internet Shortcut originally named `銀河英雄伝説VII公式サイト.url`. It targets `http://www.gineiden.com/`; it is installer/user-navigation content, not a G7MTClient runtime asset format. No client loader was fabricated.

## Exact source identity

- frozen row key: ``RESOURCE:FILE:original-installshield-payload:_____p_y_`__vii_____t_c_g.url``
- frozen relative path: ``_____p_y_`__vii_____t_c_g.url``
- tree-manifest path: ``LOGH7/_____p_y_`__vii_____t_c_g.url``
- byte size: `50`
- SHA-256: `4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8`
- exact bytes: `5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A`
- exact text: `[InternetShortcut]\r\nURL=http://www.gineiden.com/\r\n`
- recovered original basename: `銀河英雄伝説VII公式サイト.url`
- basename encoding: CP932
- basename bytes: `8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C`
- original CD ISO SHA-256: `375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22`

The sanitized filename remains the canonical inventory identity. Renaming the extracted file would invalidate the 2,192-file source manifest and downstream bindings, so the Japanese name is preserved as evidence metadata instead.

## Static-reference result

Read-only searches found zero exact filename, URL, domain, or `.url` joins in the hash-bound resource candidates, normalized functions, direct G7MTClient/BootFirst/Gin7UpdateClient byte surfaces, and relevant PE imports. The resource graph had one inventory node and no related edge. This proves absence from the inspected exact-reference surfaces, not the universal impossibility of computed or dynamically resolved access.

Bound inputs:

- `resources-ghidra.json`: `CA05628995627EA0F21B400367CDFD5745A765987775579E2DF8D2195C593AC7`
- `functions-ghidra.json`: `11B52C0D538773B24BEAC68F946EFD663BA96E5931BFBA5BD715600E269807E5`
- `pe-imports.json`: `E0C5BADAE9C5062B2E9F767AB88BA22E557ED99004B8B165BEA7B196DF9A3FBE`

## Implementation

- Added a source/tree-manifest-bound adjudication input at `evidence/exhaustive-trace/adjudications/resources.json`.
- The resource importer validates root, source-manifest hash, tree-manifest hash, exact path, content hash, size, kind, loader reason, and loader evidence.
- A valid adjudication preserves the original path key, records the recovered original name and URL, proves `WINDOWS_INTERNET_SHORTCUT` by content signature, and emits loader `NOT_APPLICABLE`.
- Coverage accepts a resource-loader N/A only with a nonempty reason and evidence.
- All evidence states except the pre-existing `ENUMERATED=true` remain false. `reachability=UNKNOWN`, `usageDisposition=ENUMERATED_ONLY`, and `STATIC_MAPPED=false` are preserved.

## TDD and verification

RED failures were observed for:

1. missing adjudication API,
2. loader N/A still reported as a gap,
3. loader N/A without evidence not reported as fatal.

GREEN results:

- focused adjudication tests: 5/5 PASS
- complete exhaustive-trace suite: 272/272 PASS
- deterministic full-chain verifier: PASS
- independent fresh post-seal verifier: `APPROVE`
- two independent temporary chains matched one another and all 32 checked deterministic artifacts
- source rows: 15,999
- graph: 35,685 nodes, 92,053 edges
- coverage fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`
- coverage evidence gaps: 25,608
- old unit occurrence: 0
- target row next boundary: `RESOURCE_OWNER`
- target row next unit: `RECOVERY:D01:RESOURCE_OWNER:9D646F35949A6258`
- next global dependency-ordered unit: `RECOVERY:D01:RESOURCE_LOADER:C7063B6F6EE54AC7` for `bootfirst.exe`

Sealed aggregate receipt:

- path: `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`
- SHA-256: `8A8A4C439F1D3EF088F519DCD019C81D325C27F82F76F7D2C062ED7FC9BF509C`

Independent receipt:

- path: `C:\Users\user\AppData\Local\Temp\logh7-unit-d01-final-7167f82d1f2f4740b181d39bcfc3c1a6.json`
- SHA-256: `29C82A532B2809DD121583A77453950BC88032401598E1CC4C7DF77504650E4F`
- verdict: `APPROVE`

## Failed approaches and corrections

- A first coverage edit applied the evidence requirement to every historical N/A section and raised fatal count from 1 to 1,588. It was rejected, reduced to the target resource-loader contract, and the fatal count returned to 1.
- A first explicit receipt path targeted a repository work directory. The verifier correctly stopped during preflight because independent receipts must be fresh direct children of the system temp root. No tests or chain generation ran in that attempt; the approved default atomic receipt path was then used.
- The first post-implementation independent execution overlapped a newly added conflict test and correctly rejected the stale seal. The source was then frozen, the adjudication was strengthened to validate exact bytes, URL, CP932 name, and unknown fields, and a fresh 272-test seal replaced all intermediate receipts.

## Remaining unknowns

- The target row's runtime owner/applicability remains `UNKNOWN`; this unit did not close `RESOURCE_OWNER`.
- Implementation-target applicability remains unresolved.
- No original-client runtime action, player-visible behavior, gameplay feature, both-faction parity, authority, persistence, Gate-A, or Gate-B was proved.
- The project-wide feature reachability ledger is still absent.

## Commands

Core reproduction commands:

```powershell
python -B -m unittest discover -s tests/tools/exhaustive_trace -p 'test_*.py'
python -B -m tools.exhaustive_trace.import_resources --input evidence/exhaustive-trace/raw/resources-ghidra.json --output evidence/exhaustive-trace/inventories/resources.jsonl --reconciliation evidence/exhaustive-trace/inventories/resources-reconciliation.json --evidence-manifest evidence/exhaustive-trace/raw/resources-evidence-manifest.json --source-manifest docs/reverse-engineering/exhaustive-trace/source-manifest.json --adjudications evidence/exhaustive-trace/adjudications/resources.json
pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1
```
