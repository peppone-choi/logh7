# Handoff - exhaustive trace foundation current-state reseal

## Goal and bounded status

- Goal: restore current end-to-end reproducibility after the Ghidra repository acquired a second program-database revision, then reconcile Task 10-14 status without deleting evidence or promoting gameplay claims.
- Bounded status: `PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`.
- Overall game goal: `INCOMPLETE`.
- Coverage gate: `STRUCTURAL_FATAL`.
- Feature ledger: `ABSENT`.

## Scope and actual work

- Read the current goal, `report/manual.md`, `report/mistakes.md`, the foundation plan, current artifacts, and historical Task 10-14 receipts.
- Reproduced the current full-suite failure caused by two `db.*.gbf` files.
- Proved that authoritative `db.1.gbf` is the hash-bound fresh-import database and `db.2.gbf` was created by a later diagnostic Ghidra run that omitted `-readOnly`.
- Added a safe repository-relative `programDatabasePath` contract and negative tests.
- Preserved both Ghidra database revisions; neither was deleted, renamed, rewritten, opened by Ghidra, or promoted.
- Propagated the source-manifest provenance hash through the exact raw/evidence/adjudication chain and regenerated every deterministic foundation artifact.
- Detected and corrected a mixed-EOL source-manifest candidate before independent review; only the LF-normalized final receipt is retained.
- Ran one staging-only complete build before publication, then ran the aggregate verifier against the published artifacts from two fresh roots.
- Reconciled stale Task 10-14 plan state and current inventory documentation while preserving historical receipts and their review scope.

## Changed files

- Validator and tests:
  - `.gitattributes` (explicit LF rules for the changed validator and test)
  - `tools/exhaustive_trace/source_manifest.py`
  - `tests/tools/exhaustive_trace/test_source_manifest.py`
- Provenance inputs:
  - `docs/reverse-engineering/exhaustive-trace/source-manifest.json`
  - `evidence/exhaustive-trace/raw/functions-ghidra.json`
  - `evidence/exhaustive-trace/raw/resources-ghidra.json`
  - `evidence/exhaustive-trace/raw/functions-evidence-manifest.json`
  - `evidence/exhaustive-trace/raw/resources-evidence-manifest.json`
  - `evidence/exhaustive-trace/adjudications/resources.json`
- Regenerated checked outputs:
  - affected function/resource inventories
  - `graph.jsonl`, `coverage.json`, all D01-D16 packages,
    `domain-plan-inputs.json`, and `recovery.json`
- Current documentation:
  - `docs/reverse-engineering/exhaustive-trace/inventory-summary.md`
  - `docs/superpowers/plans/2026-08-27-original-game-exhaustive-trace-foundation.md`
  - historical report/handoff supersession notices
  - this handoff and `work/20260829-exhaustive-trace-foundation-reseal/`
- Shared untracked operator notes updated but excluded from the scoped commit:
  - `report/manual.md`
  - `report/mistakes.md`

## Commands and evidence

- RED: `python -B -m unittest tests.tools.exhaustive_trace.test_source_manifest.GhidraProgramDatabaseHashTests -v` failed with two expected signature errors before implementation.
- Focused: `python -B -m unittest tests.tools.exhaustive_trace.test_source_manifest -v` passed 20/20.
- Manifest: `python -B -m tools.exhaustive_trace.source_manifest docs/reverse-engineering/exhaustive-trace/source-manifest.json` returned PASS with 22 verified paths.
- Complete suite: aggregate verifier recorded 285/285 with every non-pass bucket zero.
- Staging regeneration: `pwsh -NoProfile -File work/20260829-exhaustive-trace-foundation-reseal/regenerate-foundation.ps1` returned PASS before any checked artifact was published.
- Aggregate: `pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1 -ReceiptPath <fresh-system-temp-child>` returned `FOUNDATION_BASELINE_PASS`.
- Author receipt: `work/20260829-exhaustive-trace-foundation-reseal/evidence/foundation-verification.json`, SHA-256 `2ECF55C94C0D1FBF1D43BD9B9F021B9F76C6122CA7A72386BEDB715E539E9864`.

## Evidence facts

- `db.1.gbf`: 54,591,488 bytes, SHA-256 `888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`; authoritative manifest target.
- LF-normalized source manifest: SHA-256 `128798534AEDBA4600EECDEFE5A8892030F36D6697901F36143CDECDBCC6B699`.
- `db.2.gbf`: 54,591,488 bytes, SHA-256 `1BA96D6327A0AB082D88285C1C48D48ED0586330CA7137875ABE3E7C0E6F3740`; preserved non-authoritative revision.
- The two files differ at exactly eight bytes, offsets `0x08..0x0F`; this does not make their byte identities interchangeable.
- Inventory rows 15,999; graph 35,686 nodes / 92,055 edges; structural orphans 0.
- Coverage gaps 25,606; missing-boundary occurrences 153,598; closed vertical traces 0; all 15,999 row verdicts `UNKNOWN`.
- Exactly one fatal remains: `FEATURE_REACHABILITY_LEDGER_ABSENT`.
- Confirmed gameplay features 0; actual live-action units 0.
- Current first unit: `RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`, path ``RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt``, `NOT_STARTED / NOT_AUTHORIZED_BY_BASELINE`.

## Independent review

- Author receipt truthfully records `independentReview=UNSEEN` and is immutable.
- Separate source-path safety, metrics/document binding, and one-run full-execution lanes all returned `APPROVE` with repository writes 0.
- Independent execution receipt: `work/20260829-exhaustive-trace-foundation-reseal/evidence/foundation-independent-verification.json`, SHA-256 `A629DC9BB0758312B563C523A73660EC4E179CF979646A9FD20CE8B91BCB7FA1`, 76,624 bytes, 285/285, 32 artifacts, protected mismatches 0.
- Review index: `work/20260829-exhaustive-trace-foundation-reseal/evidence/foundation-independent-review.json`, SHA-256 `8312104E52CABF205C42DDB62D74E3F4AA1C7A820E220273EF6DB1517DF483A4`.
- The separate approval does not edit or promote the author receipt.
- Historical Task 14 approval remains scoped to its historical receipt and is not reused.

## Confirmed facts versus limits

Confirmed:

- Exact manifest selection removes repository multiplicity ambiguity without destructive cleanup.
- The current checked foundation is reproducible from two fresh roots under the author verifier.
- Every source row remains assigned to one primary domain and one recovery unit.

Not proved:

- Original-client playability, any complete vertical trace, player-visible gameplay, authority, persistence, both factions, PostgreSQL replay, complete content, clean-room implementation, Gate-A, Gate-B, or final completion.
- `db.2.gbf` semantic equivalence or authority.

## Runtime and safety state

- VM actions: 0.
- Original executable actions: 0.
- Ghidra executions: 0 in this unit.
- Debugger attach and process-memory access: 0.
- Physical input and automatic retry: 0.
- Server, protocol, database, port, and VM lifecycle changes: 0.
- Ghidra repository files deleted, renamed, or rewritten: 0.

## Next start and forbidden retries

- After user direction, the mechanical next recovery unit is the TXT documentation row named above, unless the user explicitly reprioritizes a different bounded unit.
- Do not start it automatically from this handoff.
- Do not delete `db.2.gbf`, select it because it is newer, or weaken generic multi-database rejection.
- Do not reuse the old independent review, overwrite either sealed receipt, erase the feature-ledger fatal, promote candidate MOVE_GRID work, or call the game playable.
- Do not issue VM input, attach, memory access, server/protocol/database mutation, or automatic retry under this offline reseal.
