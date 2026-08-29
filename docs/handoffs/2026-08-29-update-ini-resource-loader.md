# Handoff - original update.ini RESOURCE_LOADER closure

## Goal and bounded status

- Question: what exact original evidence closes `RESOURCE_LOADER` for `RESOURCE:FILE:original-installshield-payload:update.ini`?
- Unit: `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`.
- Bounded result: `PASS`, loader `PROVEN` by hash-bound external updater configuration access.
- Overall game goal: `INCOMPLETE`.
- Coverage gate: `STRUCTURAL_FATAL`; feature ledger remains `ABSENT`.

## Scope and actual work

- Performed offline byte, InstallShield-provenance, PE-resource, import, disassembly, and callflow analysis only.
- Reproduced the exact 124-byte original INI template and kept it separate from the divergent installed-state hash.
- Recovered the updater module-directory path derivation, RT_STRING ID 3, profile read flow, and VERSION/LAST_ERROR write flow.
- Added a closed `EXTERNAL_PE_INI_CONFIGURATION` adjudication, reproducible inspector/receipt, fail-closed importer and coverage handling, and typed graph edges.
- Regenerated and published every affected inventory, graph, coverage, domain, work-package, and recovery artifact.
- Ran the aggregate verifier from two fresh roots.

## Confirmed original facts

- Original extracted file: 124 bytes, SHA-256 `EBB093A34852454DD8D15CA14E95804D9200416B8724CD4F445770B07C17EF7C`.
- Exact encoding/grammar: strict ASCII, no BOM/NUL, seven CRLF records with terminal CRLF, one `[UPDATE]` section, and six ordered keys.
- Exact shipped values: VERSION `131`, BASE_DIR `.\`, empty TEMP_DIR/STARTUP_APPNAME/WORK_DIR, LAST_ERROR `0x00000003`.
- Consumer: `Gin7UpdateClient.exe`, SHA-256 `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`.
- Path: updater module directory plus RT_STRING ID 3 `%supdate.ini`, formatted at `0x00404BA9` into app `+0xD8`.
- Reads: `GetPrivateProfileIntA` `0x00404E13`; `GetPrivateProfileStringA` helper API call `0x00404FF7`.
- Writes: `WritePrivateProfileStringA` `0x0040508F` for VERSION and `0x00406F24` for LAST_ERROR.
- `%sSERVER.INI` is a separate endpoint-list path derived from the module directory; it is not this resource.

## Inference and Unknowns

- The installed-tree row has the same path and size but SHA-256 `F89660546D6D0C7D4A00EFDCAA73E5120916C730E07E6CCFE7D8FF111FD71A88` and changing LastWriteTime during the historical observation window.
- Because installed bytes and a pre-updater installed hash were not preserved, exact changed fields and unique mutation causality remain `UNKNOWN`/`INFERRED`.
- Actual runtime reads/writes, filesystem success, endpoint use, network success, update completion, client launch, and playability remain `UNSEEN`.

## Normalized and graph result

- Loader: `PROVEN`, kind `EXTERNAL_PE_CONFIG_ACCESS`; no `loader.functions`.
- Usage: `ORPHAN`; first missing boundary `RUNTIME_OWNER`.
- Evidence states: only `ENUMERATED=true`; `STATIC_MAPPED` and every later state remain false.
- Graph: exactly one updater-to-INI `READS` and one updater-to-INI `WRITES` semantic edge; zero updater-to-INI `LOADS` edges.
- Rows 15,999; nodes 35,686; edges 92,058; evidence gaps 25,602; missing-boundary occurrences 153,594.
- All 15,999 verdicts remain `UNKNOWN`; closed vertical traces remain 0.
- Structural fatal remains exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.

## Verification evidence

- Inspector: `work/20260829-update-ini-resource-loader/InspectUpdateIni.py`, SHA-256 `D454BA10F3FDA5D5F9380F5FA0294B85A260537AE741AE35752A520B1A5B29C9`, checkout-stable `text eol=lf`.
- Static receipt: `evidence/exhaustive-trace/adjudications/update-ini-static-analysis.json`, SHA-256 `A7B7BA3691DD1F1EA0DDD4DC03952217C3A3701FDB9037148C2798373612A965`, LF/no-BOM.
- Focused importer/graph tests: 65/65 PASS; coverage/importer/graph tests: 84/84 PASS.
- Aggregate author receipt: `work/20260827-original-game-exhaustive-trace-foundation/evidence/foundation-verification.json`, SHA-256 `EF3858AF14BDBDC13B02C58B3C52D84A5C366F58BD278EE9E005578442012AFE`.
- Aggregate receipt: 299/299 tests, 32 deterministic artifacts, 109 protected inputs, two fresh byte-identical roots, unchanged server/contracts/database trees.
- `git diff --check`: PASS.

## Independent review

- Final verdict: `APPROVE`; blocking findings: 0.
- The reviewer independently recomputed the source/updater/receipt/inspector hashes, strict ASCII/CRLF contract, InstallShield filename offset, RT_STRING location, profile API IATs, call bytes, and Capstone dataflow.
- Confirmed normalized `PROVEN / ORPHAN / RUNTIME_OWNER / UNKNOWN` with only `ENUMERATED=true`.
- Confirmed updater-to-INI `READS=1`, `WRITES=1`, reverse=0, `LOADS=0` and preserved source-reference unions.
- Confirmed the final importer and `.gitattributes` hashes are protected by the fresh aggregate receipt, 299/299 tests pass, all 32 checked/run-A/run-B artifacts match, 109 protected inputs match current bytes, source trees are unchanged, and `git diff --check` passes without warnings.
- Review receipt: `work/20260829-update-ini-resource-loader/evidence/independent-review.json`.
- Review receipt SHA-256: `8499C85794CA4FAE7761D8938B6A456356C10F173F814FC3B933424685F5C9D0`.

## Safety

- VM, debugger, executable execution, process memory, physical input, server, protocol, database, port, and VM lifecycle actions: 0.
- Original binary/file writes and automatic retries: 0.
- The failed explicit verifier receipt-path preflight consumed no test, regeneration, VM, or runtime action.

## Next start and forbidden retries

- Same-row next boundary: `RESOURCE_OWNER` for `update.ini`; the exact stable owner/object lifecycle is unresolved.
- Mechanical global next unit: `RECOVERY:D01:RESOURCE_OWNER:9D646F35949A6258`, official-site Internet Shortcut row.
- Do not start either automatically; wait for user direction.
- Do not substitute the installed F896... hash for the original EBB... template, infer changed INI values without bytes, call static access runtime success, promote evidence states, fabricate G7 loader functions, or emit a `LOADS` edge.
- Do not issue VM input, attach, process-memory access, executable patching/execution, server/protocol/database mutation, or automatic retry from this handoff.
