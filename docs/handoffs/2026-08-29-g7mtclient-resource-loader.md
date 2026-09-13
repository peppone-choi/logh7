# Handoff - primary G7MTClient executable RESOURCE_LOADER closure

## Goal and bounded status

- Question: what exact original evidence closes `RESOURCE_LOADER` for `RESOURCE:FILE:original-installshield-payload:exe/g7mtclient.exe`?
- Unit: `RECOVERY:D01:RESOURCE_LOADER:E346F47C94A6E543`.
- Bounded result: `PASS`, with loader `NOT_APPLICABLE` only in the G7MTClient runtime-asset-loader scope.
- Overall game goal: `INCOMPLETE`.
- Coverage gate: `STRUCTURAL_FATAL`; feature ledger remains `ABSENT`.

## Scope and actual work

- Read the active goal, operator manual/mistakes, previous handoff, reverse-engineering skill, tool index, and static/dynamic workflow.
- Ran three parallel read-only lanes for PE triage, original launch-callflow recovery, and fail-closed adjudication design while retaining the root agent as the sole repository writer.
- Completed the mandatory PE imports gate and recovered exact PE, section, version, install-table, and updater launch evidence.
- Added a reproducible inspector and hash-bound static receipt.
- Added RED tests for the new primary-client kind, receipt drift, closed fields, loader-candidate conflict, state preservation, and graph direction.
- Added target-owned inbound-launch normalization and the semantic graph edge `Gin7UpdateClient.exe LAUNCHES_PROCESS G7MTClient.exe`.
- Regenerated the full foundation in staging and publish modes, ran the aggregate verifier from two fresh roots, and obtained an independent read-only `APPROVE`.

## Changed files

- Importer, graph, and tests:
  - `tools/exhaustive_trace/import_resources.py`
  - `tools/exhaustive_trace/graph.py`
  - `tests/tools/exhaustive_trace/test_resource_importer.py`
  - `tests/tools/exhaustive_trace/test_graph.py`
- Adjudication and static receipt:
  - `evidence/exhaustive-trace/adjudications/resources.json`
  - `evidence/exhaustive-trace/adjudications/g7mtclient-static-analysis.json`
- Inspector and unit evidence:
  - `work/20260829-g7mtclient-resource-loader/InspectG7MTClient.py`
  - `work/20260829-g7mtclient-resource-loader/scope.md`
  - `work/20260829-g7mtclient-resource-loader/timeline.md`
  - `work/20260829-g7mtclient-resource-loader/evidence/foundation-verification.json`
  - `work/20260829-g7mtclient-resource-loader/evidence/independent-review.json`
- Deterministic verification and EOL binding:
  - `.gitattributes`
  - `work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1`
- Regenerated outputs:
  - affected resource inventory, graph, coverage, D01-D16 packages, work packages, and recovery ledger under `evidence/exhaustive-trace/`
- Current documentation:
  - `docs/reverse-engineering/exhaustive-trace/inventory-summary.md`
  - this handoff
- Shared untracked operator note updated but excluded from the scoped commit:
  - `report/manual.md`

## Commands and evidence

- RED: four new focused tests failed because the primary-client kind and inbound-launch graph edge were unsupported.
- Focused: `python -B -m unittest tests.tools.exhaustive_trace.test_resource_importer tests.tools.exhaustive_trace.test_graph.GraphTests.test_inbound_launch_emits_launcher_to_target_edge_once` passed 40/40.
- Inspector: `python -B work/20260829-g7mtclient-resource-loader/InspectG7MTClient.py ...` returned `PROVEN_STATIC`.
- Staging and publish: `pwsh -NoProfile -File work/20260829-exhaustive-trace-foundation-reseal/regenerate-foundation.ps1` with and without `-Publish` returned PASS.
- Aggregate: `pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1 -ReceiptPath <fresh-system-temp-receipt>` returned `FOUNDATION_BASELINE_PASS`.
- Static receipt SHA-256: `0E880071B5D92F93DF63F63FE6C6AF4B05996B8A1C535824AC595BA9D9529DD9`.
- Inspector SHA-256: `E26FD5E829A42E5731C1ABD4A1046CDEFBA1288821D5CC30733F1B57F122C691`.
- Author aggregate receipt SHA-256: `D02F1A4461916455BA1ADD2206F2C4DDFAEFACE376BC60AAAB53D670DE9296CE`.

## Confirmed original facts

- G7MTClient: 3,956,736 bytes, SHA-256 `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`.
- PE identity: PE32 x86 Windows GUI, machine `0x014C`, image base `0x00400000`, entry RVA `0x00201FBC`, five sections.
- Imports: 19 descriptors / 452 imports, readable; includes Winsock, Direct3D8, DirectInput8, sound, UI, file, registry, and dynamic API-resolution surfaces.
- `LoadLibraryA` and `GetProcAddress` mean later dynamically resolved APIs are not covered by the static imports table.
- Version identity: `OriginalFilename=G7MTClient.EXE`, `InternalName=G7MTClient`, `FileVersion=1, 0, 0, 1`.
- Static packing assessment: `NOT_PACKED_BY_STATIC_INDICATORS`; this is not an absolute unpacked claim.
- InstallShield `data1.hdr` contains exact `G7MTClient.exe` once at relative offset `0x31764` / ISO offset `0x89F64`.
- Updater defaults: `STARTUP_APPNAME=.\\exe\\G7MTClient.exe` and `WORK_DIR=.\\exe\\`.
- Updater launch chain: trigger callsite `0x004068A1`, `CreateProcessA` callsite `0x004072C2`.

## Inference and Unknowns

- The whole EXE is a Windows-loaded primary process image and consumer of game assets, not an asset consumed by its own runtime resource loaders.
- Updater-to-client launch status is `PROVEN_STATIC_DEFAULT`, not runtime observed: the config may override both values and a guarding global's semantics remain `UNRESOLVED`.
- `G7Start.exe` contains an exact client path string, but its consumption by a process API is unproved; `G7Start -> G7MTClient` remains `UNRESOLVED`.
- Internal G7MT resource-loader functions, embedded PE resources, resource ownership, runtime execution, reachability, player-visible playability, authority, persistence, both factions, and clean-room implementation remain open.

## Generated-state delta and safety

- Rows: 15,999 unchanged.
- Graph: 35,686 nodes unchanged; edges 92,055 -> 92,056.
- Added exactly one semantic edge: `Gin7UpdateClient.exe LAUNCHES_PROCESS G7MTClient.exe`; reverse and `LOADS` edges are 0.
- Evidence gaps: 25,605 -> 25,604.
- Missing-boundary occurrences: 153,597 -> 153,596.
- Closed vertical traces: 0 unchanged; all 15,999 verdicts remain `UNKNOWN`.
- Target row has only `ENUMERATED=true`; every other evidence-state boolean remains false.
- Structural fatal remains exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- VM, debugger, original executable, process memory, physical input, Ghidra execution, server, protocol, database, port, and VM lifecycle actions: 0.
- Original binary writes and automatic retries: 0.

## Independent review

- Verdict: `APPROVE`; blocking findings: 0.
- Independently recomputed the PE/imports/version identity, updater default launch anchors, graph direction/cardinality, row states, generated metrics, and author receipt bindings.
- Confirmed author receipt 292/292 with failures, errors, and skips all zero.
- Confirmed 32 deterministic artifacts satisfy `checked=runA=runB=current`.
- Confirmed 101 protected inputs satisfy `before=after=current`, source trees are unchanged, and repository writes by the reviewer were 0.
- Review receipt: `work/20260829-g7mtclient-resource-loader/evidence/independent-review.json`.
- Review receipt SHA-256: `4C629DD74ADD71881DDD6B0C4D56F217F737BF6578F9BA7C284CDCCCF8440614`.

## Next start and forbidden retries

- Same-row next unit: `RECOVERY:D01:RESOURCE_OWNER:C03D5586366AB472`.
- Mechanical global next unit: `RECOVERY:D01:RESOURCE_LOADER:627680C75CFF6DA7`, path `RESOURCE:FILE:original-installshield-payload:gin7updateclient.exe`.
- Do not start either next unit automatically from this handoff; wait for user direction.
- Do not treat `PROVEN_STATIC_DEFAULT` as an observed launch, claim G7Start launch, reverse the launch edge, convert it to `LOADS`, or call the original/new game playable.
- Do not issue VM input, attach, memory access, executable patching, Ghidra mutation, server/protocol/database mutation, or automatic retry under this completed offline unit.
