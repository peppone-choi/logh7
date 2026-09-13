# Task 9 report - unified typed evidence graph

## Scope and result

Foundation Task 9 joined the six closed inventories into one deterministic, hash-bound graph. This
is a static evidence-graph PASS only. It does not prove original-client playability, runtime
behavior, server authority, persistence, both factions, player visibility, Gate-A, or Gate-B.

No VM, original executable, debugger, process memory, input, Ghidra database, server, protocol,
database, port, or lifecycle state was changed.

## Inputs

| Inventory | Rows | File SHA-256 |
|---|---:|---|
| Protocol | 547 | `556A1C7C21FA01548F996A103214B62B9C707A0377A7E9CA861F771ECE510FC2` |
| UI | 422 | `E25764B9150A95F8D39101524C8D7980E3239BD53BAC6A68E3253D395413DE70` |
| Entity | 237 | `36F9408212D1235005D83C4D4CD0C3CCF3AE657728F5274A5097FEC55E2D6410` |
| Resource | 2,194 | `42DD5300048848DA2D43D80C036B85AA0AED9F4D0EC23740B630A28044FEF405` |
| Function | 11,497 | `4EFA62A95AA81CBB7B8D5983A865B217FD77CEE96EE31DA1B37625B0B6BA0DA3` |
| Authority | 1,102 | `1C7A14C9C9C578D7677AD3B432BA6082C3F5923EE482312A1BEDB31E9789B4C4` |

The exact 12-file inventory/reconciliation set and live source manifest are validated before graph
construction. Bundle SHA-256 is
`30A383BDA36554F65175125222DADE7CA9C8F6623D513B12A1246970431B1B57`.

## Output population

- source inventory row nodes: 15,999; missing 0; extra 0
- derived nodes: 19,686
- total nodes: 35,685
- edges: 92,053
- independently enumerated input join candidates: 132,954
- normalized join candidates: 124,917
- unresolved join candidates: 8,037
- source-conflict join candidates: 0
- join-reference surface SHA-256: `49292B8D5DA77DDDB88AE2AF6EC11D9CC3D5972C42BE3666BFB8F3EC3F967781`
- normalized edges: 84,638
- unresolved edges: 7,390
- source-conflict edges: 25
- unaccounted join candidates: 0
- dangling endpoints: 0
- duplicate node keys, edge IDs, and candidate IDs: 0

Node kinds:

- 15,999 `INVENTORY_ROW`
- 10,058 `STATE_LOCATION`
- 6,378 `UNRESOLVED_REFERENCE`
- 1,642 `STRING_LITERAL`
- 1,377 `NAME_TOKEN`
- 230 `FIELD`
- 1 `EVENT`

The 5,145 function string references, 35,520 reciprocal caller references, 7,996 upstream
classification references, 1,048 protocol ownership references, 167 resource loader-function
references, 365 UI state-field references, 16 UI enablement predicates,
and 14 populated UI event type/predicate references are represented rather than silently omitted.
Exact function name/address index
collisions produce explicit `SOURCE_CONFLICT` nodes and edges. No nearest-function join exists.

`NAME_MATCH` has 3,638 candidate edges. Every one remains `INFERRED`, `LOW`, `CANDIDATE`; none is
an identity edge and no evidence state is promoted.

## Integrity and reproducibility

- graph file bytes: 101,553,653
- graph file SHA-256: `ED1ED9EC7435245AF856F631D742D4D6B4B76B478E7A44493BCB4988B783BE71`
- nodes SHA-256: `95C5B37434D9D630BCBC25620FFDA4E37CDB731D98A0EBBA0F81E5326E348A77`
- edges SHA-256: `BFF4EDD9C1609B784599C4B02029D45F8ED1C13181E798B0616AB7B64B321BB9`
- graph surface SHA-256: `EF0DDFDC5C6F8F1701FD568FD2CC9948E1FF496A3A8287D40D448CD19E08D33D`
- two independent CLI outputs were byte-identical
- clean discovery: 191 tests, all PASS

Full-suite command:

```powershell
python -m unittest discover -s tests/tools/exhaustive_trace -p 'test_*.py'
```

The CLI writes a same-directory temporary file, verifies canonical JSONL, node/edge/conservation
hashes, and the expected inventory bundle before atomic publication. A failed prepublication
verification preserves the prior output.

## Review history

The independent contract review rejected two drafts because reference-bearing fields and mirrored
source references were incomplete, then because resource loader functions, audit sealing, and deep
conservation immutability were still missing. The final graph independently enumerates the input
reference paths before checking their classification. Earlier findings also included a hard-coded
unaccounted zero, mutable nested states, incomplete source-manifest binding, non-exact endpoint
identity, first-seen mapping ambiguity, and missing candidate-ID uniqueness;
and the structural edge vocabulary was not adjudicated in the controlling spec. These findings were
reproduced as RED tests and fixed before regeneration. Final contract, population, and QA reviews all
returned `APPROVE`; reviewer writes were zero. The bounded receipt is
`work/20260828-original-game-exhaustive-trace-task9-graph/repro/task9-verification.json`.

## Remaining boundary

Task 9 does not close vertical feature/entity/content traces. The next bounded unit is Task 10:
compute orphan and vertical-trace coverage gates from this graph without promoting unresolved,
candidate, or source-conflict edges.
