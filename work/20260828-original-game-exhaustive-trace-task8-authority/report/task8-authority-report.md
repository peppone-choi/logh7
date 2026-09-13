# Exhaustive trace foundation Task 8 report

## Verdict

`PASS` for the bounded authority and persistence obligation inventory. The overall goal remains `INCOMPLETE`. This unit does not prove that the original client is fully playable, that any server behavior exists, or that any command, entity lifecycle, event emission, persistence path, reconnect path, faction path, HUD path, world delivery path, Gate-A, or Gate-B works.

## Closed obligation surface

The normalized inventory contains 1,102 rows: 547 `PROTOCOL_PATH`, 71 state-bearing `ENTITY_PATH`, 62 `EVENT_PATH` record candidates, and 422 `CLIENT_BEHAVIOR_PATH` rows. The raw requirement surface additionally preserves eight lifecycle obligations for each state-bearing entity, producing 568 lifecycle candidates and 1,670 total requirements. With the three current source roots, the raw reconciliation population is 1,673 candidates.

Protocol obligations preserve all 547 keys. The direction-derived ingress population is 288 rows and egress population is 447 rows, with bidirectional rows carrying both obligations. Exact request identity yields 127 `REQUEST_PATH` rows. The remaining 161 ingress rows stay `UNCLASSIFIED_SERVER_INPUT`; 259 egress-only rows are `SERVER_OUTPUT`. A response that merely references a request sibling is not mislabeled as the request.

Each applicable authority row keeps explicit handler/parsing, validation, accepted and rejected decision, mutation, event, response, notify, recipient/visibility fanout, persistence, checkpoint, replay reducer, reconnect projection, idempotency, and admin-mutation dispositions. Entity rows also retain an authority owner and all eight lifecycle slots. Event record shapes remain emission candidates, not emitted-event proof. Every UI row remains `AUTHORITY_COUNTERPART_UNRESOLVED`, never `CLIENT_ONLY_PROVEN`.

Every presently unresolved static field has a typed future closure route: section roles, eight lifecycle roles, `AUTHORITY_OWNER`, `EMISSION_IDENTITY`, and `AUTHORITY_COUNTERPART` or `CLIENT_ONLY_DISPOSITION`. `NOT_APPLICABLE` requires a non-empty reason and exact source-line evidence. Even a complete static marker set advances only to `RUNTIME_AUTHORITY_EVIDENCE`; it never promotes a proof state.

## Current-source result and conservation

`apps/server`, `contracts`, and `db` contain no files. `db` contains only the empty `migrations` and `seeds` directories. The official run therefore found zero source files and zero trace markers. It did not produce an empty inventory: every requirement is an explicit orphan or unresolved counterpart.

First missing boundaries are actionable: 288 `COMMAND_HANDLER`, 259 `NOTIFICATION_FANOUT`, 71 `AUTHORITY_OWNER`, 62 `EMISSION_IDENTITY`, and 422 `AUTHORITY_COUNTERPART_CLASSIFICATION`. Reconciliation normalizes all 1,670 requirement candidates, retains the three empty roots as `UNRESOLVED/NO_SOURCE_FILES`, and reports zero unaccounted candidates.

Every row has reachability `UNKNOWN`; only `ENUMERATED` is true. Static source markers, if later added, can produce only `SOURCE_CANDIDATE`, `STUB`, or `SOURCE_CONFLICT`. They cannot set `STATIC_MAPPED`, `AUTHORITY_PROVEN`, or `PERSISTENCE_PROVEN`. Filename, class, method, and semantic-name similarity are never identity joins. Legacy source is scanned only through the explicit option and remains separately labeled candidate evidence.

## Reproducibility and limits

The official and reproduction outputs are byte-identical: raw file `2B7CAECFEA25225735B0C5C40DD4598BF670C764163D5A266AC1900ECD74D9F1`, inventory file `1C7A14C9C9C578D7677AD3B432BA6082C3F5923EE482312A1BEDB31E9789B4C4`, and reconciliation file `8C9C83B73EA12879D957BE0790A7CA640823438CCB823ACB236F973C424303FD`. The internal canonical raw surface is `29A3386C586B12688F148AB017B4F4FC2AED0ADE7051AE5907449144D7322CA5`; the internal canonical inventory digest is `C857D4A18DEF908839C794F2BFE603BAB124E3BFAB89D8C861448B495EC112CF`.

Focused tests pass 35/35, all exhaustive-trace tests pass 173/173, and the source gate verifies 21 paths. No VM, client, debugger, process-memory, input, binary, Ghidra, server, protocol, database, port, or lifecycle action occurred.

Three final independent read-only reviews - contract/nested schema, population/link/reconciliation, and source snapshot/reproducibility - returned `APPROVE`; reviewer writes were zero.
