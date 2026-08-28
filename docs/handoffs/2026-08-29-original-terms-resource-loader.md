# Handoff - original CP932 terms document RESOURCE_LOADER closure

## Goal and bounded status

- Question: what exact original evidence closes `RESOURCE_LOADER` for ``RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt``?
- Unit: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`.
- Bounded result: `PASS`, with loader `NOT_APPLICABLE` only in the G7MTClient runtime-resource-loader scope.
- Overall game goal: `INCOMPLETE`.
- Coverage gate: `STRUCTURAL_FATAL`; feature ledger remains `ABSENT`.

## Scope and actual work

- Read the active goal, operator manual/mistakes, reverse-engineering workflow, current foundation, and prior resource adjudications.
- Ran three parallel read-only evidence/contract lanes while retaining the root Codex agent as the only repository writer.
- Proved the exact payload byte identity, strict encoding, title, line endings, original InstallShield name, byte-identical support copy, and installer string surfaces.
- Searched the hash-bound client, bootstrap, updater, launcher extent, and current Ghidra raw exports for exact target-specific references.
- Added a reproducible static inspector and hash-bound receipt.
- Added RED tests, implemented a closed `CP932_TERMS_DOCUMENT` adjudication, regenerated the full foundation in staging and publish modes, and ran the aggregate verifier from two fresh roots.
- Obtained a separate read-only independent review with verdict `APPROVE`.

## Changed files

- Importer and tests:
  - `tools/exhaustive_trace/import_resources.py`
  - `tests/tools/exhaustive_trace/test_resource_importer.py`
- Adjudication and static receipt:
  - `evidence/exhaustive-trace/adjudications/resources.json`
  - `evidence/exhaustive-trace/adjudications/terms-of-service-static-analysis.json`
- Reproducible inspector and unit evidence:
  - `work/20260829-original-terms-resource-loader/InspectTermsDocument.py`
  - `work/20260829-original-terms-resource-loader/scope.md`
  - `work/20260829-original-terms-resource-loader/timeline.md`
  - `work/20260829-original-terms-resource-loader/evidence/foundation-verification.json`
  - `work/20260829-original-terms-resource-loader/evidence/independent-review.json`
- Foundation verifier and generated outputs:
  - `work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1`
  - affected resource inventory, graph, coverage, D01-D16 packages, work packages, and recovery ledger under `evidence/exhaustive-trace/`
- Current documentation:
  - `docs/reverse-engineering/exhaustive-trace/inventory-summary.md`
  - this handoff
- Shared untracked operator note updated but excluded from the scoped commit:
  - `report/manual.md`

## Commands and evidence

- RED: the three new TXT tests initially failed because `CP932_TERMS_DOCUMENT` was unsupported and receipt drift was not rejected.
- Focused: `python -B -m unittest tests.tools.exhaustive_trace.test_resource_importer -v` passed 36/36.
- Inspector: `python -B work/20260829-original-terms-resource-loader/InspectTermsDocument.py ...` returned `PROVEN_STATIC`.
- Staging and publish: `pwsh -NoProfile -File work/20260829-exhaustive-trace-foundation-reseal/regenerate-foundation.ps1` with and without `-Publish` returned PASS.
- Aggregate: `pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1 -ReceiptPath <fresh-system-temp-receipt>` returned `FOUNDATION_BASELINE_PASS`.
- Static receipt SHA-256: `34A90CCFD3758DFD0403AE9DFE221921AAC85CCE8E9140477FB5CD55931FA168`.
- Author aggregate receipt SHA-256: `CAB09AAB2674B9ADD16A5799FF8803D840DD73DD62DAD7CB38BD116E75413C3A`.

## Confirmed original facts

- Payload and support copy: 8,376 bytes, SHA-256 `BC7B4D48326A536EAC26F9B4C74395F4C42AC73C461FDE82ECD33B7CA19F4103`, byte-identical.
- Encoding: strict CP932 round trip; no BOM or NUL; 4,371 characters; 130 CRLF sequences; no lone CR/LF.
- Title: `銀河英雄伝説Ⅶ利用規約`.
- Original name: `銀英伝VII利用規約.txt`, CP932 bytes `8BE289709360564949979897708B4B96F12E747874`, at `data1.hdr` relative offset `0x31737` / ISO offset `0x89F37`.
- Original ISO SHA-256: `375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22`.
- `setup.inx` contains `license.txt` at relative `0x5C16` and `SdLicense2` at `0x1D5BE`.
- Exact target filename/title/content references are absent from the hash-bound G7MTClient, BootFirst, Gin7UpdateClient, G7Start extent, and current resource/function Ghidra exports.

## Inference and Unknowns

- The byte-identical support copy and installer string surfaces support the classification `ORIGINAL_SERVICE_TERMS` / installer legal document.
- No compiled InstallScript callsite currently binds the `license.txt` operand to `SdLicense2`; installer callflow remains `UNRESOLVED`.
- Static absence is limited to the exact hash-bound byte/export surfaces. It is not a universal claim that no computed or transformed reference could exist.
- Resource owner, runtime observation, player visibility, gameplay reachability, authority, persistence, both factions, rights disposition, and clean-room implementation remain unproved.

## Generated-state delta and safety

- Rows: 15,999 unchanged.
- Graph: 35,686 nodes / 92,055 edges unchanged.
- Evidence gaps: 25,606 -> 25,605.
- Missing-boundary occurrences: 153,598 -> 153,597.
- Closed vertical traces: 0 unchanged; all 15,999 verdicts remain `UNKNOWN`.
- Target row has only `ENUMERATED=true`; every other evidence-state boolean remains false.
- Structural fatal remains exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- VM, debugger, original executable, process memory, physical input, Ghidra execution, server, protocol, database, port, and VM lifecycle actions: 0.
- Original binary writes and automatic retries: 0.

## Independent review

- Verdict: `APPROVE`; blocking findings: 0.
- Independently recomputed TXT/duplicate/ISO/setup offsets and exact-reference absence.
- Confirmed author receipt 288/288 with failures, errors, and skips all zero.
- Confirmed 32 deterministic artifacts satisfy `checked=runA=runB=current`.
- Confirmed 99 protected inputs satisfy `before=after=current`, source trees are unchanged, and repository writes by the reviewer were 0.
- Review receipt: `work/20260829-original-terms-resource-loader/evidence/independent-review.json`.
- Review receipt SHA-256: `F91D8D41E81B208364988DA2CB6CA57250A3CA9530B1E37C89B2BDDA4872093B`.

## Next start and forbidden retries

- Same-row next unit: `RECOVERY:D01:RESOURCE_OWNER:2676D028DBA8EC74`.
- Mechanical global next unit: `RECOVERY:D01:RESOURCE_LOADER:E346F47C94A6E543`, path `RESOURCE:FILE:original-installshield-payload:exe/g7mtclient.exe`.
- Do not start either next unit automatically from this handoff; wait for user direction.
- Do not add a `LOADS` or `OPENS_DOCUMENT` edge, claim installer callflow, promote evidence states, or call the original/new game playable from this static legal-document result.
- Do not issue VM input, attach, memory access, executable patching, server/protocol/database mutation, or automatic retry under this completed offline unit.
