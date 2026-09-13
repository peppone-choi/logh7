# Timeline

## 2026-08-29 - unit start

- `decision_delta`: selected mechanical first recovery unit `RECOVERY:D01:RESOURCE_LOADER:627680C75CFF6DA7`.
- Confirmed the target file at 1,060,864 bytes and SHA-256 `EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D`.
- Entered reverse-engineering triage with mandatory imports gate; dynamic analysis remains unnecessary unless static evidence is insufficient.
- `carry_forward_refs`: `scope.md`, `docs/handoffs/2026-08-29-g7mtclient-resource-loader.md`, `report/manual.md`, `report/mistakes.md`.

## 2026-08-29 - triage to static

- `decision_delta`: PE imports are readable, no known packer signature or W+X section was found, and the bounded loader question is answerable statically; dynamic execution is not required for this unit.
- Independent triage confirmed PE32 x86 GUI, 4 sections, 11 import descriptors, 347 imports, version `1, 0, 0, 0`, and exact InstallShield filename offset `data1.hdr+0x316F6`.
- Static updater-role tracing confirmed `[UPDATE]` configuration/defaults, Winsock and file-apply surfaces, the default G7MTClient launch, and the BootFirst inbound chain. Runtime network success, payload identity, remote-version comparison, rollback, and playability remain unresolved or unseen.
- `carry_forward_refs`: `evidence/gin7updateclient-imports.json`, `evidence/exhaustive-trace/adjudications/gin7updateclient-static-analysis.json`.

## 2026-08-29 - static to synthesis

- RED: the new updater kind was unsupported, matching dual-owned process claims produced no valid merge, and conflicting claims did not fail at the intended boundary.
- GREEN: added closed `PE_GAME_UPDATER_EXECUTABLE` handling and endpoint-keyed deterministic process-launch claim merging.
- Focused result: resource-importer and graph suites 62/62 PASS.
- Published regeneration: rows 15,999; nodes 35,686; edges 92,056; gaps 25,603; missing-boundary occurrences 153,595; fatal exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- The updater-to-client edge remains exactly one, now with `CORROBORATED_TYPED_REFERENCE` and both source-owned and target-owned source refs.

## 2026-08-29 - verification

- Aggregate verifier: 295/295 PASS; 32 deterministic artifacts; 104 protected inputs; two fresh roots match checked outputs; source trees unchanged.
- Author receipt SHA-256: `04FE7ABB5047051DB3A3A5B9BC527A5FEE7191ECC22578365C18852C230610CE`.
- Next mechanical unit: `RECOVERY:D01:RESOURCE_LOADER:CF0262816AEED6F7`, path `RESOURCE:FILE:original-installshield-payload:update.ini`.
- `decision_delta`: current unit is ready for independent read-only review; no live action was used.

## 2026-08-29 - independent review

- Independent read-only verdict: `APPROVE`; blocking findings 0.
- Reviewer recomputed PE/import/version/InstallShield facts, receipt/tool hashes, normalized row, single corroborated launch edge, aggregate counts, and author-receipt bindings.
- Focused independent tests: 62/62 PASS; repository writes by reviewer 0.
- Review receipt SHA-256: `897AAB5A23A1782BF36AF102227EEF709DD91B05F7A1857EE0285808724068B4`.
- `decision_delta`: bounded unit complete; write handoff, commit exact scope, report, and wait.
