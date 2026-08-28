# D01 CD operation-manual resource-loader adjudication

## Outcome

- Unit: `RECOVERY:D01:RESOURCE_LOADER:E8B07A6802B17EEF`
- Bounded status: `PASS`
- Overall game goal: `INCOMPLETE / STRUCTURAL_FATAL`
- Closed boundary: `RESOURCE_LOADER`
- Disposition: `NOT_APPLICABLE` in G7MTClient runtime asset-loader scope
- Proven external relation: `G7Start.exe OPENS_DOCUMENT 銀英伝７マニュアル.pdf`
- Runtime/player evidence promoted: none
- VM, original EXE execution, debugger, process memory, physical input, server, protocol, database, and binary writes: none

The hash-bound file is the original 69-page operation manual. `G7Start.exe` delegates it to the operating system's PDF viewer through `ShellExecuteA`; `G7MTClient.exe` does not parse it as a data, render, audio, or map resource. The external user-navigation path is preserved without inventing a game asset loader.

## Exact PDF identity

- inventory row: `RESOURCE:FILE:original-installshield-payload:doc/___p_`_v_}_j___a__.pdf`
- extracted path: `doc/___p_`_v_}_j___a__.pdf`
- original Joliet/install name: `銀英伝７マニュアル.pdf`
- byte size: `5,374,309`
- SHA-256: `1C4CF3DB13A172361277264C06ADA6E2499BE0969494C6557EB84BC4CC005399`
- header: `%PDF-1.4`
- pages: `69`, A4 portrait
- title: `銀河英雄伝説Ⅶ　操作説明書`
- author: `BOTHTEC`
- creator: `Word 用 Acrobat PDFMaker 5.0`
- producer: `Acrobat Distiller 5.0.5 (Windows)`
- creation: `2004-04-11 11:41:23 +09:00`
- modification: `2004-04-11 12:54:21 +09:00`
- encrypted: yes, empty-password access succeeds; printing/copy permitted

The document contains no form fields, attachments, JavaScript, catalog additional actions, or launch/file actions. It has 72 outline items, 98 link annotations, 97 internal `GoTo` actions, and one URI on page 8 to `http://www.gineiden.com/`.

## Visual inspection

All 69 pages were rendered and reviewed as complete contact sheets. The cover, table of contents, installation/login/logout material, strategy and tactical instructions, both-faction organization charts, and command tables are present and ordered. No blank, missing, or corrupt page was observed.

Poppler substituted unavailable non-embedded Japanese fonts and pypdf warned about incomplete `90ms/90msp-RKSJ-H` extraction support. The render therefore proves readable document structure, not pixel identity to the 2004 authoring host; extracted text is search evidence, not a canonical transcription.

## Installer and external-open evidence

Original CD ISO SHA-256: `375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22`.

InstallShield static anchors:

- shell label `PDFマニュアル` at ISO offset `0x0005A6EA`
- target `<TARGETDIR>\doc\銀英伝７マニュアル.pdf` at `0x0005A6F8`
- filename-table entry at `0x00089F4D`

Original CD `G7Start.exe`:

- ISO extent: `0x0954A800`
- byte size: `434,176`
- SHA-256: `1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93`
- MFC command ID: `1001`
- handler: `FUN_00403860`
- filename xref: `0x00403893 -> 0x00427900`
- API: `SHELL32.dll::ShellExecuteA`
- callsite: `0x004038E6`
- verb: `open`

Exact ASCII, CP932, and UTF-16LE byte searches over the hash-bound `G7MTClient.exe`, `BootFirst.exe`, and `Gin7UpdateClient.exe` found zero occurrences of the original filename, PDF extensions, `doc\`, Acrobat, `ShellExecute`, or `WinExec`. This is absence on the inspected byte surfaces, not proof against every possible computed or future path.

## Implementation

- Added the narrow `PDF_OPERATION_MANUAL` adjudication kind. Arbitrary extension-only PDFs cannot inherit this disposition.
- The importer verifies the analysis receipt hash, exact source hash/size, PDF header/version, 69-page metadata contract, empty-password access, referenced inspector hash, and external document-open record.
- The normalized row records `documentRole=ORIGINAL_OPERATION_MANUAL`, original name, PDF analysis, and the external opener.
- Added graph relation `OPENS_DOCUMENT` and one proven external-artifact node for `G7Start.exe`.
- No `LOADS` relation is emitted for the PDF.
- `ENUMERATED=true` is preserved; every other evidence state remains false.

## TDD and verification

RED failures were observed for:

1. unsupported `PDF_OPERATION_MANUAL` normalization,
2. missing receipt source/analysis/header/reference validation,
3. missing external-document record binding,
4. absent `G7Start.exe` graph node and `OPENS_DOCUMENT` edge,
5. missing original-name preservation.

GREEN results:

- complete exhaustive-trace suite: 280/280 PASS
- deterministic full-chain verifier: PASS
- independent fresh post-seal verifier: `APPROVE`
- checked/run-A/run-B artifacts: 32/32 byte-identical
- protected inputs: 95/95 unchanged
- source rows: 15,999
- graph: 35,686 nodes, 92,055 edges
- coverage fatal: exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`
- evidence gaps: 25,606
- closed vertical traces: 0
- confirmed gameplay features: 0
- old unit occurrence: 0
- target row next unit: `RECOVERY:D01:RESOURCE_OWNER:0F8AD043BA655644`
- next global unit: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`

Sealed aggregate receipt:

- path: `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`
- SHA-256: `187F17E7978F9ACF24BC1F73AE13F1C499977C54074F2E12D74E43F6BCDA6235`

Independent receipt:

- path: `C:\Users\user\AppData\Local\Temp\logh7-pdf-postseal-review-f21545a72dfc49b79cd86ca160658aaa.json`
- SHA-256: `A5986499541DDEF97C1923EDE0F0C23539E76126406F98A49E2D226BAEE6F088`
- verdict: `APPROVE`

## Evidence bindings

- inspector SHA-256: `4F55234F6BDF22A2AA24C99818CD0D064E35E9F878560B37420EA4879C3EC1CC`
- static receipt SHA-256: `16B20FDEB11539BF2B434F55B78629102282750A01432DD485C90426F74A42B2`
- adjudications SHA-256: `023FE64A893BD5F51D025220953D81DEAD013357B6B7E41D4C425E2BEB4DBBA7`
- resource inventory SHA-256: `A1DDAAF70CE5BDED0B8D6E82A1147EE27A6EBF33BD3B5BDFB1C1E980524E2FE7`
- graph SHA-256: `05771703EEB797301C46C412476267CD6A6C7492B960E211EF04392AAFC7092F`
- coverage SHA-256: `2D1EF1E97072E0A9DDBD1E802975955DC10E9BFF696D26A6E0632AA1D51D7AE7`
- work-package SHA-256: `28BB966D276DC342A473D24AB682E3D4E322805E932B2FA5B62FF9A4DEB4951F`
- recovery SHA-256: `F43295C69F2A4A85E006F8D9CB396045DFEEB2B4AB2661996904C86CCD0FAE77`

## Remaining unknowns

- The PDF row's runtime/external owner disposition and implementation-target applicability remain unresolved at `RESOURCE_OWNER`.
- No particular VM installation or user click was observed.
- This unit does not prove original-client playability, any gameplay function, both-faction parity, server authority, persistence, Gate-A, or Gate-B.
- The complete C++23 client and C# authoritative server remain unimplemented by this unit.

## Reproduction

```powershell
$py = 'C:\Users\user\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $py work/20260828-cd-manual-resource-loader/InspectCdManual.py `
  --project-root E:\logh7-greenfield `
  --pdf 'E:\logh7-greenfield\evidence\installshield-extract\____________s___\____\doc\___p_`_v_}_j___a__.pdf' `
  --iso E:\logh7-vm-media\LOGH7-original-cd.iso `
  --client E:\logh7-greenfield\evidence\installshield-extract\____________s___\____\exe\g7mtclient.exe `
  --bootfirst E:\logh7-greenfield\evidence\installshield-extract\____________s___\____\bootfirst.exe `
  --updater E:\logh7-greenfield\evidence\installshield-extract\____________s___\____\gin7updateclient.exe `
  --output evidence/exhaustive-trace/adjudications/cd-manual-v1-static-analysis.json
python -B -m unittest discover -s tests/tools/exhaustive_trace -p 'test_*.py'
pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1
```
