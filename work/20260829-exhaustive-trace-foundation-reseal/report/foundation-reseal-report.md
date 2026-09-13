# Exhaustive trace foundation current-state reseal report

## Outcome

The Ghidra repository multiplicity regression is resolved without deleting,
renaming, or modifying either database revision. The current bounded foundation is
`PASS_REPRODUCIBLE_BASELINE_WITH_ACKNOWLEDGED_FATAL`; the overall game goal remains
`INCOMPLETE`.

Receipt:
`work/20260829-exhaustive-trace-foundation-reseal/evidence/foundation-verification.json`,
SHA-256 `2ECF55C94C0D1FBF1D43BD9B9F021B9F76C6122CA7A72386BEDB715E539E9864`.
It records independent review as `UNSEEN`.

After the author receipt was sealed, three separate read-only review lanes returned
`APPROVE`. The independent full execution receipt is SHA-256
`A629DC9BB0758312B563C523A73660EC4E179CF979646A9FD20CE8B91BCB7FA1`;
the review index is SHA-256
`8312104E52CABF205C42DDB62D74E3F4AA1C7A820E220273EF6DB1517DF483A4`.
Neither artifact edits or promotes the author receipt.

## Correction

The source manifest previously searched the complete Ghidra repository and required
exactly one `db.*.gbf`. The authoritative `db.1.gbf` remained byte-identical to the
manifest hash, but a later diagnostic command omitted `-readOnly` and caused Ghidra to
save `db.2.gbf`. Those files differ in eight header bytes and remain distinct evidence.

The manifest now binds the safe repository-relative path
`idata/00/~00000000.db/db.1.gbf` and SHA-256
`888F81BFEAB5B878723345CE7B049E709A4E3DE438F72005E1F68F9A100000AF`.
The LF-normalized source manifest SHA-256 is
`128798534AEDBA4600EECDEFE5A8892030F36D6697901F36143CDECDBCC6B699`.
The validator rejects absolute paths, traversal, missing files, invalid database names,
and missing explicit paths. Generic unbound hashing still rejects multi-database
repositories. `db.2.gbf` is preserved as non-authoritative contamination evidence.

The provenance-only source-manifest change was propagated through hash-bound resource
and function raw exports, evidence manifests, resource adjudications, inventories,
graph, coverage, domain packages, work packages, and recovery ledger. No semantic row
was promoted.

## Verification

- Full exhaustive-trace suite: 285/285 passed; failures, errors, skips, expected
  failures, and unexpected successes all zero.
- Source-manifest gate: PASS, 22 verified paths.
- Two complete fresh regeneration roots matched one another and all 32 checked
  deterministic artifacts byte-for-byte.
- A separate independent verifier execution repeated 285/285 and all 32 artifact
  comparisons from its own fresh receipt path; author/independent artifact-field
  mismatches were zero.
- Protected inputs and server/contracts/database trees were unchanged during the
  aggregate verifier.
- VM, original EXE, debugger, process memory, physical input, server/protocol/database
  mutation, VM lifecycle mutation, auto-click, and automatic retry actions: zero.

## Current counts

- Inventories: protocol 547, UI 422, entity 237, resource 2,194, function 11,497,
  authority 1,102; total 15,999.
- Graph: 35,686 nodes, 92,055 edges, structural orphans 0, unresolved-reference
  nodes 6,378.
- Coverage: 15,999 `UNKNOWN`, closed vertical traces 0, gaps 25,606,
  missing-boundary occurrences 153,598.
- Domain assignment: 15,999 primary keys, unassigned 0, duplicate primary 0,
  routing unresolved 15,317.
- Recovery: 16,269 subjects, 16,267 actionable, 2 recovered, 60 authoring packages,
  unaccounted 0.
- Confirmed gameplay features 0; candidate gameplay packages 1; candidate units 8;
  actual live-action units 0.
- Structural fatal 1, exactly `FEATURE_REACHABILITY_LEDGER_ABSENT`.

## Current artifact bindings

| Artifact | Bytes | SHA-256 | Internal surface |
|---|---:|---|---|
| `graph.jsonl` | 102,938,427 | `0F1617D1D8C40C854F9A825CED4B69CA1D4FC47CCB316C6075A612D7A46C5F10` | `28C275C99FEBDB9F993729FEE1A2BCA34B8A7797038A6C61AE758A2A9A325918` |
| `coverage.json` | 38,640,987 | `FDF8C398BFD8A93EC88DA4C0033EC31F4277AED182FAD24E6FA662697E6D0F3D` | `D0A93944721FEF320B197ECACBA62AEF734CC013416D491AE482024EBEDAFB31` |
| 16 domain packages | 44,707,077 total | receipt contains every file hash | `75A1751A6CE61862F751D1EC2238ABCEE3606DA4EF9D2D3C8E1639A8BAD80DF7` |
| `domain-plan-inputs.json` | 81,454,241 | `8E85B46355BB36C355543D0564E426F0F6DE626825261312F8424564D1D10304` | `6136822743E5008DC5E60D3137995E22C83CA77F733E8EFF115820E4F498F5D4` |
| `recovery.json` | 32,960,647 | `0ED2824746FFEEC43DA054CA5E81590C6043F13CD7D793FCD1072A15BE62C498` | `769A7096E8177434D5B4111FDB37BBC04291DFC179213C6441657A38B04DDE7D` |

## First dependency-ordered unit

`RECOVERY:D01:RESOURCE_LOADER:F9CBE1F4AEAE7D6B`, path
``RESOURCE:FILE:original-installshield-payload:doc/___p_`vii___p_k__.txt``.
It remains `NOT_STARTED / NOT_AUTHORIZED_BY_BASELINE`; this reseal does not start it.

## Limits

All 15,999 rows remain `UNKNOWN`, no vertical trace is closed, and no gameplay feature
is confirmed. Original-client playability, authority, persistence, both factions,
PostgreSQL replay, clean-room client/server implementation, content completion, and
deployment remain unproved. The old Task 14 review is historical; the current review
approves only reproducibility and provenance safety, not gameplay completion.
