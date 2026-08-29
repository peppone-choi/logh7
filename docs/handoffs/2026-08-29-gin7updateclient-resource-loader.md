# Handoff - Gin7UpdateClient executable RESOURCE_LOADER closure

## Goal and bounded status

- Question: what exact original evidence closes `RESOURCE_LOADER` for `RESOURCE:FILE:original-installshield-payload:gin7updateclient.exe`?
- Unit: `RECOVERY:D01:RESOURCE_LOADER:627680C75CFF6DA7`.
- Bounded result: `PASS`, with loader `NOT_APPLICABLE` only in the G7MTClient runtime-asset-loader scope.
- Overall game goal: `INCOMPLETE`.
- Coverage gate: `STRUCTURAL_FATAL`; feature ledger remains `ABSENT`.

## Scope and actual work

- Read the active goal, operator manual/mistakes, previous handoff, reverse-engineering skill, tool index, and static/dynamic workflow.
- Ran three parallel read-only lanes for independent PE triage, updater/network/file/process callflow recovery, and fail-closed adjudication/graph design while retaining the root agent as the only repository writer.
- Completed the mandatory imports gate and recovered exact PE, section, version, InstallShield, configuration, network-capability, update-file, and default G7MT launch evidence.
- Added a reproducible inspector, complete direct-import receipt, and hash-bound static adjudication receipt.
- Added RED tests for the new updater executable kind and matching/conflicting dual-owned process launch claims.
- Added deterministic endpoint-keyed process-launch merging: source-owned updater evidence and target-owned G7MT inbound evidence produce one corroborated semantic edge; signature disagreement fails closed.
- Strengthened the G7MT inbound-launch contract with structured working-directory, override, gate, and runtime-observation fields.
- Regenerated the full foundation in staging and publish modes and ran the aggregate verifier from two fresh roots.

## Changed files

- Importer, graph, and tests:
  - `tools/exhaustive_trace/import_resources.py`
  - `tools/exhaustive_trace/graph.py`
  - `tests/tools/exhaustive_trace/test_resource_importer.py`
  - `tests/tools/exhaustive_trace/test_graph.py`
- Adjudications and static receipts:
  - `evidence/exhaustive-trace/adjudications/resources.json`
  - `evidence/exhaustive-trace/adjudications/gin7updateclient-static-analysis.json`
  - `evidence/exhaustive-trace/adjudications/g7mtclient-static-analysis.json`
- Inspectors and unit evidence:
  - `work/20260829-gin7updateclient-resource-loader/InspectGin7UpdateClient.py`
  - `work/20260829-gin7updateclient-resource-loader/evidence/gin7updateclient-imports.json`
  - `work/20260829-gin7updateclient-resource-loader/evidence/foundation-verification.json`
  - `work/20260829-gin7updateclient-resource-loader/evidence/independent-review.json`
  - `work/20260829-gin7updateclient-resource-loader/scope.md`
  - `work/20260829-gin7updateclient-resource-loader/timeline.md`
  - `work/20260829-g7mtclient-resource-loader/InspectG7MTClient.py`
- Deterministic verifier and generated outputs:
  - `work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1`
  - affected resource inventory, graph, coverage, D01-D16 packages, work packages, and recovery ledger under `evidence/exhaustive-trace/`
- Current documentation:
  - `docs/reverse-engineering/exhaustive-trace/inventory-summary.md`
  - this handoff
- Shared untracked operator note updated but excluded from the scoped commit:
  - `report/manual.md`

## Commands and evidence

- RED: the updater kind was unsupported; matching dual-owned claims could not merge; conflicting claims failed at the wrong boundary.
- Focused: resource importer and graph suites passed 62/62.
- Inspector: `InspectGin7UpdateClient.py` returned `PROVEN_STATIC` with 11 descriptors / 347 imports.
- Staging and publish regeneration both returned PASS with identical graph, coverage, domain, work-package, and recovery hashes.
- Aggregate verifier returned `FOUNDATION_BASELINE_PASS` with 295/295 tests, 32 deterministic artifacts, and two fresh complete regeneration roots.
- Updater static receipt SHA-256: `4F1E31E61AD7A53B820775AFEAED2C4BC634668CA8EBBC125BD649C375E15517`.
- Updater inspector SHA-256: `0417B5094BFA4FB2571E8A3F9373EDA39398630B543AB072272144BAAEDC163A`.
- Author aggregate receipt SHA-256: `04FE7ABB5047051DB3A3A5B9BC527A5FEE7191ECC22578365C18852C230610CE`.

## Confirmed original facts

- Gin7UpdateClient: 1,060,864 bytes, SHA-256 `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`.
- PE identity: PE32 x86 Windows GUI, machine `0x014C`, image base `0x00400000`, entry RVA `0x00009A2E`, four sections.
- Imports: 11 descriptors / 347 imports, including 21 WSOCK32 imports, file mutation, registry, resource, thread, and process APIs.
- `LoadLibraryA` and `GetProcAddress` mean later dynamically resolved APIs are outside the direct static import inventory.
- Version identity: file description `銀英伝VIIアップデートクライアント`, product name `銀河英雄伝説VIIアップデートクライアント`, version `1, 0, 0, 0`.
- Static packing assessment: `NO_KNOWN_PACKER_SIGNATURE_STATIC_ONLY`; no overlay, Authenticode directory, W+X section, or known packer section name was found.
- InstallShield `data1.hdr` contains exact `Gin7UpdateClient.exe` once at relative offset `0x316F6` / ISO offset `0x89EF6`.
- `[UPDATE]` configuration reads server/proxy/path/startup fields. Shipped defaults include `202.8.80.179:47902`, `STARTUP_APPNAME=.\exe\G7MTClient.exe`, and `WORK_DIR=.\exe\`.
- Concrete static surfaces include Winsock connect/send/recv/DNS, update-info processing, temporary/versioned filenames, file create/read/write/delete/move, attributes/time, and staged replacement.
- Default launch chain: wrapper `FUN_00407260`, trigger `0x004068A1`, `CreateProcessA` `0x004072C2`; process/thread handles are closed without waiting.
- Existing BootFirst evidence proves `BootFirst.exe` launches and waits for Gin7UpdateClient, then handles exit code and `.new/.old` replacement.

## Inference and Unknowns

- The whole updater EXE is a Windows-loaded process image that updates files and launches the game, not an asset consumed by a G7MT runtime model/image/audio/map/string/data loader.
- Generic HTTP/FTP library strings do not prove the exercised updater transport. No concrete URL literal was recovered in this unit.
- Exact remote-version comparison semantics, update message schema, endpoint precedence, downloaded payload identity/hash, atomicity/rollback, runtime network success, update application, client launch, and playability remain `UNRESOLVED` or `UNSEEN`.
- Static default launch may be overridden by configuration. Broader updater lifecycle semantics around `Gin7UpdateClient.new` remain unresolved.

## Generated-state delta and safety

- Rows: 15,999 unchanged.
- Graph: 35,686 nodes / 92,056 edges unchanged.
- `Gin7UpdateClient -> LAUNCHES_PROCESS -> G7MTClient` remains exactly one edge, now `CORROBORATED_TYPED_REFERENCE` with two source refs; reverse and `LOADS` edges are 0.
- Evidence gaps: 25,604 -> 25,603.
- Missing-boundary occurrences: 153,596 -> 153,595.
- Closed vertical traces: 0 unchanged; all 15,999 verdicts remain `UNKNOWN`.
- Target row has only `ENUMERATED=true`; every other evidence-state boolean remains false.
- Structural fatal remains exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- VM, debugger, original executable, process memory, physical input, Ghidra execution, server, protocol, database, port, and VM lifecycle actions: 0.
- Original binary writes and automatic retries: 0.

## Independent review

- Verdict: `APPROVE`; blocking findings: 0.
- Independently recomputed the updater hash, PE identity, sections, imports, version, InstallShield filename location, target-client hash, receipt bindings, normalized row, graph cardinality/direction, and aggregate metrics.
- Confirmed the updater static receipt and all seven mandatory tool hashes match current bytes.
- Confirmed the author receipt has 295/295 tests, 32 deterministic artifacts with no run/current mismatch, 104 protected inputs with no mismatch, and unchanged server/contracts/database trees.
- Independently reran the focused resource-importer and graph suites: 62/62 PASS; `git diff --check` passed.
- Reviewer repository writes, original executable runs, VM operations, Ghidra runs, debugger operations, and full-verifier runs: 0.
- Review receipt: `work/20260829-gin7updateclient-resource-loader/evidence/independent-review.json`.
- Review receipt SHA-256: `897AAB5A23A1782BF36AF102227EEF709DD91B05F7A1857EE0285808724068B4`.

## Next start and forbidden retries

- Same-row next unit: `RECOVERY:D01:RESOURCE_OWNER:516E30687184B38B`; the normalized row's next boundary is `RUNTIME_OWNER` and the generated recovery contract names it `RESOURCE_OWNER`.
- Mechanical global next unit: `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`, path `RESOURCE:FILE:original-installshield-payload:update.ini`.
- Do not start either next unit automatically from this handoff; wait for user direction.
- Do not treat imported capability, static default settings, or process-launch proof as observed network success, downloaded content, live update, runtime launch, or playability.
- Do not emit duplicate process edges, weaken canonical signature comparison, reverse the launch edge, convert it to `LOADS`, or promote evidence states.
- Do not issue VM input, attach, memory access, executable patching, Ghidra mutation, server/protocol/database mutation, or automatic retry under this completed offline unit.
