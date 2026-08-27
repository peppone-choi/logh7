# Original Game Exhaustive Trace Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. This project is single-writer; do not dispatch subagents unless the user explicitly authorizes delegation. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible closed-world trace system that enumerates the original game's protocol, UI, entities, resources, client functions, authority/persistence paths, joins them into one evidence graph, adjudicates unrecoverable content, and emits complete reverse-engineering plus implementation work packages for all sixteen game domains.

**Architecture:** A Python standard-library package owns normalized schemas, graph construction, coverage validation, and domain packaging. Read-only Ghidra Java exporters and source-specific importers produce the six inventories; generated artifacts are validated independently before they can influence implementation requirements. Domain work starts only from verified orphan/coverage outputs, so the campaign cannot silently omit dormant or manual-only behavior.

**Tech Stack:** Python 3 standard library, `unittest`, PowerShell 7, Ghidra 12.1.2 headless Java scripts, JSON/JSONL, SHA-256, Git.

**Spec:** `docs/superpowers/specs/2026-08-27-original-game-exhaustive-trace-design.md`

## Global Constraints

- Original-client target SHA-256: `BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16`.
- `constmsg.dat` SHA-256: `5B3FAFBA7DD7230CDEB5F2FF9ACF9BBBE20FD95ADE25C425BC0D11AE645C383C`.
- Use `C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat` and `work/ghidra-input-consumption/Unit10Input`.
- Use only Python standard-library modules for the trace foundation.
- Never promote `E:\logh7-revival` implementation behavior as original evidence; import it only as `LEGACY_CANDIDATE` when explicitly requested by an importer.
- Preserve `SHIPPED_REACHABLE`, `SHIPPED_DORMANT`, `MANUAL_ONLY`, and `UNKNOWN` separately.
- Preserve `ENUMERATED`, `STATIC_MAPPED`, `CODEC_PROVEN`, `RUNTIME_OBSERVED`, `PLAYER_VISIBLE`, `AUTHORITY_PROVEN`, `PERSISTENCE_PROVEN`, `BOTH_FACTIONS`, and `INDEPENDENTLY_REVIEWED` as independent booleans.
- Preserve `PASS`, `PARTIAL`, `UNSEEN`, `BLOCKED`, and `UNKNOWN` verdicts.
- No original executable patch, debuggee data write, automatic click/retry, VM lifecycle change, server/protocol/database mutation, or raw-capture commit.
- Each completed bounded unit writes `docs/handoffs/` and stops for user direction.

## File structure

```text
tools/exhaustive_trace/
  __init__.py             public model and validation exports
  model.py                enums and immutable row/node/edge types
  io.py                   deterministic JSON/JSONL and SHA-256 helpers
  inventories.py          six inventory loaders and cross-inventory keys
  graph.py                typed graph construction
  coverage.py             orphan and vertical-trace gates
  recovery.py             recovery-vs-authoring adjudication
  domains.py              sixteen-domain routing and work-package output
  work_packages.py        implementation-closure package generation
  cli.py                  build, audit, and package commands
  import_protocol.py      protocol export normalization
  import_ui.py            UI export normalization
  import_entities.py      entity/record normalization
  import_resources.py     resource/loader normalization
  import_functions.py     client-function normalization
  import_authority.py     authority/persistence normalization
tests/tools/exhaustive_trace/
  test_model.py
  test_importers.py
  test_graph.py
  test_coverage.py
  test_recovery.py
  test_domains.py
  test_work_packages.py
tools/ghidra/
  ExportExhaustiveProtocol.java
  ExportExhaustiveUi.java
  ExportExhaustiveRecords.java
  ExportExhaustiveResources.java
  ExportExhaustiveFunctions.java
docs/reverse-engineering/exhaustive-trace/
  domains.json
  source-manifest.json
  inventory-summary.md
evidence/exhaustive-trace/
  raw/*.json
  inventories/*.jsonl
  graph.jsonl
  coverage.json
  domains/*.json
```

---

### Task 1: Define normalized trace contracts and sixteen domains

**Files:**
- Create: `tools/exhaustive_trace/__init__.py`
- Create: `tools/exhaustive_trace/model.py`
- Create: `tools/exhaustive_trace/io.py`
- Create: `docs/reverse-engineering/exhaustive-trace/domains.json`
- Create: `tests/tools/exhaustive_trace/test_model.py`

**Interfaces:**
- Consumes: approved exhaustive-trace spec.
- Produces: `InventoryRow`, `TraceNode`, `TraceEdge`, deterministic serialization, and domain IDs `D01` through `D16`.

- [x] **Step 1: Write the failing model tests**

```python
import unittest
from tools.exhaustive_trace.model import (
    EvidenceState, InventoryKind, InventoryRow, Reachability, Verdict,
)

class ModelTests(unittest.TestCase):
    def test_states_are_independent(self):
        row = InventoryRow(
            key="PROTOCOL:0x031D",
            inventory=InventoryKind.PROTOCOL,
            name="ResponseStaticInformationBase",
            provenance="ORIGINAL_OBSERVED",
            reachability=Reachability.SHIPPED_REACHABLE,
        )
        self.assertFalse(row.states[EvidenceState.RUNTIME_OBSERVED])
        self.assertFalse(row.states[EvidenceState.PLAYER_VISIBLE])

    def test_unknown_provenance_is_rejected(self):
        with self.assertRaises(ValueError):
            InventoryRow(
                key="ENTITY:PLANET:1",
                inventory=InventoryKind.ENTITY,
                name="planet",
                provenance="trusted",
                reachability=Reachability.UNKNOWN,
            )

if __name__ == "__main__":
    unittest.main()
```

- [x] **Step 2: Run the tests and confirm failure**

Run:

```powershell
python -m unittest tests.tools.exhaustive_trace.test_model -v
```

Expected: import failure because `tools.exhaustive_trace.model` does not exist.

- [x] **Step 3: Implement the immutable model**

Define string enums for six inventories, four reachability values, nine evidence states, and five verdicts. `InventoryRow.__post_init__` must reject unknown provenance and initialize every evidence state to `False` without deriving one state from another.

```python
ALLOWED_PROVENANCE = frozenset({
    "ORIGINAL_OBSERVED", "ORIGINAL_MANUAL", "INFERRED",
    "NEW_DESIGN", "AUTHORED_PLACEHOLDER", "UNKNOWN", "LEGACY_CANDIDATE",
})
```

Also define `RecoveryDisposition` with the eight values from spec section 7.1 and an `ImplementationTarget` enum containing `CONTRACT`, `SERVER`, `LEGACY_GATEWAY`, `NEW_CLIENT`, `DATABASE`, `CONTENT_ADMIN`, `QA`, and `INDEPENDENT_REVIEW`.

- [x] **Step 4: Define all domains in `domains.json`**

Use IDs and slugs:

```json
[
  {"id":"D01","slug":"launcher-update-config-data-root"},
  {"id":"D02","slug":"account-auth-lobby-session-character"},
  {"id":"D03","slug":"faction-calendar-rank-office-authority"},
  {"id":"D04","slug":"world-topology-systems-planets-fortresses-grids"},
  {"id":"D05","slug":"fleets-units-ships-troops-fighters-arms"},
  {"id":"D06","slug":"strategy-navigation-warp-search-encounter-fog"},
  {"id":"D07","slug":"bases-institutions-spots-rooms-facilities"},
  {"id":"D08","slug":"commands-orders-suggestions-mail-messenger"},
  {"id":"D09","slug":"grid-spot-unicast-tactical-communication"},
  {"id":"D10","slug":"economy-production-construction-repair-supply-cargo"},
  {"id":"D11","slug":"tactical-entry-field-deployment-combat-retreat"},
  {"id":"D12","slug":"politics-personnel-diplomacy-governance"},
  {"id":"D13","slug":"growth-rewards-ranking-victory-session-end"},
  {"id":"D14","slug":"offline-ai-timeout-disconnect-reconnect-replay"},
  {"id":"D15","slug":"sound-cursor-localization-hud-information"},
  {"id":"D16","slug":"administration-moderation-publication-backup-operations"}
]
```

- [x] **Step 5: Run tests**

Expected: all model tests pass.

- [x] **Step 6: Commit**

```powershell
git add tools/exhaustive_trace tests/tools/exhaustive_trace/test_model.py docs/reverse-engineering/exhaustive-trace/domains.json
git commit -m "feat: define exhaustive trace contracts"
```

### Task 2: Freeze sources and enforce the PE import-table gate

**Files:**
- Create: `docs/reverse-engineering/exhaustive-trace/source-manifest.json`
- Create: `tools/exhaustive_trace/source_manifest.py`
- Create: `tools/exhaustive_trace/build_pe_imports.py`
- Create: `tests/tools/exhaustive_trace/test_source_manifest.py`
- Create: `evidence/exhaustive-trace/raw/pe-imports.json`

**Interfaces:**
- Consumes: fixed client/message hashes, Ghidra project, original resource roots, official/manual inputs.
- Produces: `SourceManifest.load(path)`, verified hashes, tool paths, PE architecture/import classification, and provenance authority per source.

- [x] **Step 1: Write failing hash and import-gate tests**

Test that a changed client hash is rejected and that the manifest cannot validate until `pe-imports.json` contains PE type, x86 architecture, imported DLL groups, and quality.

```python
def test_import_gate_requires_quality(self):
    payload = {"format":"PE32","architecture":"x86","imports":[]}
    with self.assertRaises(ValueError):
        validate_import_gate(payload)
```

- [x] **Step 2: Run tests and confirm failure**

Expected: import failure for `source_manifest`.

- [x] **Step 3: Implement manifest validation**

Accept import quality only from `readable`, `packed`, `parse_failed`, or `dynamic_only`. Record Direct3D 8, DirectInput 8, DirectSound, Winsock, filesystem, registry, timing, and process/thread import groups without inferring gameplay semantics.

- [x] **Step 4: Generate `pe-imports.json` from the hash-bound Ghidra program**

Use a read-only Ghidra exporter or existing hash-bound import evidence. The JSON must include the exporter/source hash and cannot cite only an analyst report.

- [x] **Step 5: Run tests and manifest verification**

```powershell
python -m unittest tests.tools.exhaustive_trace.test_source_manifest -v
python -m tools.exhaustive_trace.source_manifest docs/reverse-engineering/exhaustive-trace/source-manifest.json
```

Expected: both exit `0`.

- [x] **Step 6: Commit**

```powershell
git add docs/reverse-engineering/exhaustive-trace/source-manifest.json tools/exhaustive_trace/source_manifest.py tools/exhaustive_trace/build_pe_imports.py tests/tools/exhaustive_trace/test_source_manifest.py evidence/exhaustive-trace/raw/pe-imports.json
git commit -m "docs: freeze exhaustive trace sources"
```

### Task 3: Export and normalize the complete protocol inventory

**Files:**
- Create: `tools/ghidra/ExportExhaustiveProtocol.java`
- Create: `tools/exhaustive_trace/import_protocol.py`
- Create: `tests/tools/exhaustive_trace/test_importers.py`
- Create: `evidence/exhaustive-trace/raw/protocol-ghidra.json`
- Create: `evidence/exhaustive-trace/raw/protocol-evidence-manifest.json`
- Create: `evidence/exhaustive-trace/inventories/protocol.jsonl`
- Create: `evidence/exhaustive-trace/inventories/protocol-reconciliation.json`
- Modify: `tools/exhaustive_trace/source_manifest.py`
- Modify: `tests/tools/exhaustive_trace/test_source_manifest.py`
- Modify: `docs/reverse-engineering/exhaustive-trace/source-manifest.json`

**Interfaces:**
- Consumes: dispatcher, serializer/parser strings, message size tables, protocol-name tables.
- Produces: one `InventoryRow` for every message code plus typed `SERIALIZES`, `PARSES`, and `DISPATCHES` facts.

- [x] **Step 1: Write protocol importer tests**

Require unique normalized keys, explicit direction, body-size status, request/response/notify sibling disposition, and at least one evidence reference.

```python
def test_protocol_row_needs_direction(self):
    with self.assertRaises(ValueError):
        normalize_protocol_row({"code":"0x031D","name":"ResponseStaticInformationBase"})
```

- [x] **Step 2: Run importer tests and confirm failure**

Expected: missing importer module or normalization function.

- [x] **Step 3: Implement the read-only Ghidra exporter**

Export address, function, opcode/code family, referenced class/name string, body-size evidence, direction evidence, caller/callee addresses, and destination global/cache. Do not assign a semantic field name from address proximity.

- [x] **Step 4: Run Ghidra headless**

```powershell
& 'C:\Users\user\AppData\Local\Programs\Ghidra\ghidra_12.1.2_PUBLIC\support\analyzeHeadless.bat' `
  'E:\logh7-greenfield\work\ghidra-input-consumption' Unit10Input `
  -process G7MTClient.exe `
  -scriptPath 'E:\logh7-greenfield\tools\ghidra' `
  -postScript ExportExhaustiveProtocol.java `
  'E:\logh7-greenfield\evidence\exhaustive-trace\raw\protocol-ghidra.json' `
  -readOnly
```

Expected: exporter success marker and output-file existence.

- [x] **Step 5: Normalize and test**

```powershell
python -m tools.exhaustive_trace.import_protocol --input evidence/exhaustive-trace/raw/protocol-ghidra.json --output evidence/exhaustive-trace/inventories/protocol.jsonl --reconciliation evidence/exhaustive-trace/inventories/protocol-reconciliation.json --evidence-manifest evidence/exhaustive-trace/raw/protocol-evidence-manifest.json --source-manifest docs/reverse-engineering/exhaustive-trace/source-manifest.json
python -m unittest tests.tools.exhaustive_trace.test_importers -v
```

Expected: every row has a disposition; unknown layouts remain `UNKNOWN` rather than absent.

- [x] **Step 6: Commit**

```powershell
git add tools/ghidra/ExportExhaustiveProtocol.java tools/exhaustive_trace/import_protocol.py tools/exhaustive_trace/source_manifest.py tests/tools/exhaustive_trace/test_importers.py tests/tools/exhaustive_trace/test_source_manifest.py docs/reverse-engineering/exhaustive-trace/source-manifest.json evidence/exhaustive-trace/raw/protocol-ghidra.json evidence/exhaustive-trace/raw/protocol-evidence-manifest.json evidence/exhaustive-trace/inventories/protocol.jsonl evidence/exhaustive-trace/inventories/protocol-reconciliation.json
git commit -m "docs: enumerate original protocol surface"
```

### Task 4: Export and normalize the UI/input inventory

**Files:**
- Create: `tools/ghidra/ExportExhaustiveUi.java`
- Create: `tools/exhaustive_trace/import_ui.py`
- Create: `evidence/exhaustive-trace/raw/ui-ghidra.json`
- Create: `evidence/exhaustive-trace/inventories/ui.jsonl`
- Modify: `tests/tools/exhaustive_trace/test_importers.py`

**Interfaces:**
- Consumes: root-mode builders, manager constructors, descriptor/message tables, event predicates, enable/visibility writers.
- Produces: UI rows keyed as `UI:MODE:{mode}:MANAGER:{manager}:CATEGORY:{category}:INDEX:{index}`.

- [x] **Step 1: Add failing UI tests**

Require builder ownership, label evidence, event/handler disposition, enable state owner, child manager relation, and reachability classification. A row with a label but no handler must be `SHIPPED_DORMANT` or `UNKNOWN`.

- [x] **Step 2: Run tests and confirm failure**

Expected: UI importer missing.

- [x] **Step 3: Implement exporter and importer**

Export all root modes and manager/widget construction sites, including disabled rows. Normalize runtime addresses only as evidence, never stable IDs.

- [x] **Step 4: Generate and verify UI inventory**

Expected: every constructed widget has a handler/dormant/unknown disposition; visible labels are bound to consuming branches.

- [x] **Step 5: Commit**

```powershell
git add tools/ghidra/ExportExhaustiveUi.java tools/exhaustive_trace/import_ui.py tests/tools/exhaustive_trace/test_importers.py evidence/exhaustive-trace/raw/ui-ghidra.json evidence/exhaustive-trace/inventories/ui.jsonl
git commit -m "docs: enumerate original UI and input surface"
```

### Task 5: Export and normalize entity and record inventories

**Files:**
- Create: `tools/ghidra/ExportExhaustiveRecords.java`
- Create: `tools/exhaustive_trace/import_entities.py`
- Create: `evidence/exhaustive-trace/raw/records-ghidra.json`
- Create: `evidence/exhaustive-trace/inventories/entities.jsonl`
- Modify: `tests/tools/exhaustive_trace/test_importers.py`

**Interfaces:**
- Consumes: parser/cache strides, caps, ID comparisons, parent/owner/location readers, manual/source catalogs.
- Produces: entity types, record fields, ID namespaces, lifecycle operations, and typed identity/parent edges.

- [x] **Step 1: Add failing entity tests**

Reject state-bearing entities without ID-namespace disposition, parent disposition, location disposition, creation/update/terminal disposition, or provenance.

- [x] **Step 2: Run tests and confirm failure**

Expected: entity importer missing.

- [x] **Step 3: Export record access patterns**

For every parser and registry export stride, cap, read/write offsets, equality comparisons, downstream consumers, and labels. Preserve unresolved scalars as `fieldNN`.

- [x] **Step 4: Merge original/manual catalogs by evidence edges**

Use source hashes and name/ordinal facts as `NAME_MATCH` or `CATALOG_PARENT`, never `IDENTIFIES`, unless the client compares the value.

- [x] **Step 5: Generate and verify inventory**

Expected: systems/planets/fortresses/special bodies/spots retain the current cardinality and membership distinctions; every other entity type has an explicit first missing boundary.

- [x] **Step 6: Commit**

```powershell
git add tools/ghidra/ExportExhaustiveRecords.java tools/exhaustive_trace/import_entities.py tests/tools/exhaustive_trace/test_importers.py evidence/exhaustive-trace/raw/records-ghidra.json evidence/exhaustive-trace/inventories/entities.jsonl
git commit -m "docs: enumerate original entities and records"
```

### Task 6: Export resources and loader ownership

**Files:**
- Create: `tools/ghidra/ExportExhaustiveResources.java`
- Create: `tools/exhaustive_trace/import_resources.py`
- Create: `evidence/exhaustive-trace/raw/resources-ghidra.json`
- Create: `evidence/exhaustive-trace/inventories/resources.jsonl`
- Modify: `tests/tools/exhaustive_trace/test_importers.py`

**Interfaces:**
- Consumes: hash-bound resource tree, path formatters, loader callsites, draw/audio consumers.
- Produces: resource rows with source hash, loader, runtime key, owner, submission, and visible/audible evidence status.

- [x] **Step 1: Add failing resource tests**

Ensure a file with no loader is `ENUMERATED` only; a loader with no owner is an orphan; a loader/owner with no draw/play receipt is not integrated.

- [x] **Step 2: Run tests and confirm failure**

Expected: resource importer missing.

- [x] **Step 3: Export path and loader references**

Cover images, models, portraits, spot backgrounds, fonts, messages, sounds, maps, cursors, and configuration. Record dynamic formatters and enumerated literal paths.

- [x] **Step 4: Generate and verify inventory**

Expected: all original files have a disposition; unused resources remain visible as orphans/dormant candidates.

- [x] **Step 5: Commit**

```powershell
git add tools/ghidra/ExportExhaustiveResources.java tools/exhaustive_trace/import_resources.py tests/tools/exhaustive_trace/test_importers.py evidence/exhaustive-trace/raw/resources-ghidra.json evidence/exhaustive-trace/inventories/resources.jsonl
git commit -m "docs: enumerate original resources and loaders"
```

### Task 7: Export gameplay/state-bearing client functions

**Files:**
- Create: `tools/ghidra/ExportExhaustiveFunctions.java`
- Create: `tools/exhaustive_trace/import_functions.py`
- Create: `evidence/exhaustive-trace/raw/functions-ghidra.json`
- Create: `evidence/exhaustive-trace/inventories/functions.jsonl`
- Modify: `tests/tools/exhaustive_trace/test_importers.py`

**Interfaces:**
- Consumes: imports, RTTI, vtables, strings, dispatchers, UI and record exporters.
- Produces: function rows grouped as plumbing/library or individually tracked gameplay/state-bearing functions.

- [x] **Step 1: Add failing function tests**

Require individually tracked functions to carry address, proposed name, caller/callee disposition, input/output disposition, side-effect list, evidence, and confidence. Grouped functions require a deterministic grouping rule and member addresses.

- [x] **Step 2: Run tests and confirm failure**

Expected: function importer missing.

- [x] **Step 3: Export and classify candidates mechanically**

Mark functions as gameplay/state-bearing when referenced by a protocol, UI, entity field, resource loader, persistent state, or named game-state string. Do not classify from size alone.

- [x] **Step 4: Generate and verify inventory**

Expected: every function belongs to an explicit group or individual row; no address silently disappears.

- [x] **Step 5: Commit**

```powershell
git add tools/ghidra/ExportExhaustiveFunctions.java tools/exhaustive_trace/import_functions.py tests/tools/exhaustive_trace/test_importers.py evidence/exhaustive-trace/raw/functions-ghidra.json evidence/exhaustive-trace/inventories/functions.jsonl
git commit -m "docs: enumerate original client functions"
```

### Task 8: Inventory authority and persistence paths

**Files:**
- Create: `tools/exhaustive_trace/import_authority.py`
- Create: `evidence/exhaustive-trace/raw/authority-source.json`
- Create: `evidence/exhaustive-trace/inventories/authority.jsonl`
- Modify: `tests/tools/exhaustive_trace/test_importers.py`

**Interfaces:**
- Consumes: greenfield server/contract/database source plus compatibility behavior as separately labeled candidate input.
- Produces: command handler, validation, mutation, event, fanout, persistence, replay, reconnect, and admin-operation rows.

- [ ] **Step 1: Add failing authority tests**

Require every accepted command path to identify validation, mutation/event disposition, response/notify disposition, persistence/replay disposition, and idempotency disposition. A stub or empty response remains an orphan.

- [ ] **Step 2: Run tests and confirm failure**

Expected: authority importer missing.

- [ ] **Step 3: Implement source scanner**

Scan `apps/server`, `contracts`, and `db` using deterministic file and syntax patterns. Hash every source file. Import revival behavior only when `--legacy-candidate-root E:\logh7-revival` is explicitly passed; label every such row `LEGACY_CANDIDATE`.

- [ ] **Step 4: Generate inventory without legacy candidate input**

```powershell
python -m tools.exhaustive_trace.import_authority --server apps/server --contracts contracts --db db --output evidence/exhaustive-trace/inventories/authority.jsonl
```

Expected: current missing handlers and persistence paths appear as unresolved rows rather than being filled from revival.

- [ ] **Step 5: Commit**

```powershell
git add tools/exhaustive_trace/import_authority.py tests/tools/exhaustive_trace/test_importers.py evidence/exhaustive-trace/raw/authority-source.json evidence/exhaustive-trace/inventories/authority.jsonl
git commit -m "docs: enumerate authority and persistence paths"
```

### Task 9: Join inventories into the typed evidence graph

**Files:**
- Create: `tools/exhaustive_trace/inventories.py`
- Create: `tools/exhaustive_trace/graph.py`
- Create: `tests/tools/exhaustive_trace/test_graph.py`
- Create: `evidence/exhaustive-trace/graph.jsonl`

**Interfaces:**
- Consumes: all six validated inventory JSONL files.
- Produces: `build_graph(rows) -> TraceGraph`, typed edges, and deterministic `graph.jsonl`.

- [ ] **Step 1: Write failing graph tests**

```python
def test_name_match_does_not_become_identity(self):
    graph = build_graph(self.rows_with_same_name)
    self.assertIn("NAME_MATCH", graph.edge_types())
    self.assertNotIn("IDENTIFIES", graph.edge_types())

def test_unknown_edge_type_is_rejected(self):
    with self.assertRaises(ValueError):
        TraceEdge("a", "CONNECTED_SOMEHOW", "b", ("E-1",))
```

- [ ] **Step 2: Run tests and confirm failure**

Expected: graph module missing.

- [ ] **Step 3: Implement typed joins**

Allow only the edge types from the spec. Each edge requires at least one evidence reference and a provenance/confidence disposition.

- [ ] **Step 4: Build twice and compare hashes**

```powershell
python -m tools.exhaustive_trace.cli build-graph --inventories evidence/exhaustive-trace/inventories --output evidence/exhaustive-trace/graph.jsonl
Get-FileHash evidence/exhaustive-trace/graph.jsonl -Algorithm SHA256
python -m tools.exhaustive_trace.cli build-graph --inventories evidence/exhaustive-trace/inventories --output evidence/exhaustive-trace/graph.jsonl
Get-FileHash evidence/exhaustive-trace/graph.jsonl -Algorithm SHA256
```

Expected: identical hashes.

- [ ] **Step 5: Commit**

```powershell
git add tools/exhaustive_trace/inventories.py tools/exhaustive_trace/graph.py tests/tools/exhaustive_trace/test_graph.py evidence/exhaustive-trace/graph.jsonl
git commit -m "feat: build exhaustive trace evidence graph"
```

### Task 10: Enforce orphan and vertical-trace coverage gates

**Files:**
- Create: `tools/exhaustive_trace/coverage.py`
- Create: `tests/tools/exhaustive_trace/test_coverage.py`
- Create: `evidence/exhaustive-trace/coverage.json`

**Interfaces:**
- Consumes: typed graph.
- Produces: `audit_graph(graph) -> CoverageReport`, per-row first missing boundary, and fatal structural errors.

- [ ] **Step 1: Write failing coverage tests**

Test protocol rows without direction, UI controls without handler/dormant status, entities without ID/parent disposition, writers without sources, assets without loaders, mutations without event/persistence disposition, and manual features absent from reachability ledger.

Also reject any rule, field, content row, or entity population without one `RecoveryDisposition`, and reject gameplay-feature rows without a disposition for every `ImplementationTarget`.

- [ ] **Step 2: Run tests and confirm failure**

Expected: coverage module missing.

- [ ] **Step 3: Implement fail-closed audits**

Structural omissions exit nonzero. Evidence gaps are emitted with `PARTIAL`, `UNSEEN`, `BLOCKED`, or `UNKNOWN` and an exact `firstMissingBoundary`; they do not make generation fail unless the row lacks a disposition.

- [ ] **Step 4: Generate coverage report**

```powershell
python -m tools.exhaustive_trace.cli audit --graph evidence/exhaustive-trace/graph.jsonl --output evidence/exhaustive-trace/coverage.json
```

Expected: exit `0` only when every row has a disposition; unresolved gameplay remains listed.

- [ ] **Step 5: Commit**

```powershell
git add tools/exhaustive_trace/coverage.py tests/tools/exhaustive_trace/test_coverage.py evidence/exhaustive-trace/coverage.json
git commit -m "test: enforce exhaustive trace coverage gates"
```

### Task 11: Route every row into the sixteen domain work packages

**Files:**
- Create: `tools/exhaustive_trace/domains.py`
- Create: `tests/tools/exhaustive_trace/test_domains.py`
- Create: `evidence/exhaustive-trace/domains/D01.json` through `evidence/exhaustive-trace/domains/D16.json`

**Interfaces:**
- Consumes: domains configuration, graph, coverage report.
- Produces: one deterministic work package per domain and a cross-domain dependency list.

- [ ] **Step 1: Write failing routing tests**

Require every row to have at least one primary domain, allow explicit secondary domains, and reject circular hard dependencies. Assert `D04` contains topology rows and references `docs/superpowers/plans/2026-08-27-original-world-topology-full-trace.md`.

- [ ] **Step 2: Run tests and confirm failure**

Expected: domains module missing.

- [ ] **Step 3: Implement deterministic routing**

Route by typed entity/message/UI/resource relationships, not filenames alone. Ambiguous rows go to a named `crossDomainUnresolved` list with candidate domains and evidence.

- [ ] **Step 4: Generate all packages and verify coverage**

```powershell
python -m tools.exhaustive_trace.cli package-domains --graph evidence/exhaustive-trace/graph.jsonl --coverage evidence/exhaustive-trace/coverage.json --domains docs/reverse-engineering/exhaustive-trace/domains.json --output evidence/exhaustive-trace/domains
python -m unittest tests.tools.exhaustive_trace.test_domains -v
```

Expected: 16 files; zero unassigned rows; ambiguous rows explicitly preserved.

- [ ] **Step 5: Commit**

```powershell
git add tools/exhaustive_trace/domains.py tests/tools/exhaustive_trace/test_domains.py evidence/exhaustive-trace/domains
git commit -m "docs: package exhaustive trace domains"
```

### Task 12: Generate domain implementation and live-validation packages

**Files:**
- Create: `tools/exhaustive_trace/work_packages.py`
- Create: `tests/tools/exhaustive_trace/test_work_packages.py`
- Create: `evidence/exhaustive-trace/domain-plan-inputs.json`

**Interfaces:**
- Consumes: 16 domain packages.
- Produces: ordered domain units containing exact inventory keys, missing boundaries, static exporter requirements, offline replay inputs, faction/role matrix, live-slice requirements, and complete implementation closure.

- [ ] **Step 1: Write failing package tests**

Require every unit to have one question, input evidence, expected output, verifier command, mutation scope, live-input count, independent-review requirement, forbidden retry, recovery disposition, and implementation-target matrix. Reject a unit combining unrelated first-missing boundaries.

Use `FEATURE:MOVE_GRID` as the concrete fixture and require these unit kinds in order:

```python
expected = [
    "reverse_contract", "versioned_contract", "authority_server",
    "legacy_gateway", "new_client", "database_replay",
    "content_admin", "qa_independent_review",
]
self.assertEqual(expected, [unit.kind for unit in package.units])
```

- [ ] **Step 2: Run tests and confirm failure**

Expected: work-package module missing.

- [ ] **Step 3: Implement unit splitting**

Split on domain, feature/entity vertical path, and first missing boundary. A live slice may contain one semantic player action at most; bootstrap-only slices use zero actions. For every gameplay feature, emit separate but linked units for contract, authoritative server, legacy gateway, new client, database/replay, content/admin, QA, and independent review. Emit `NOT_APPLICABLE` only with a non-empty reason.

The generated JSON for `FEATURE:MOVE_GRID` must have this shape:

```json
{
  "featureKey": "FEATURE:MOVE_GRID",
  "domain": "D06",
  "recoveryDisposition": "RECOVERABLE_STATIC",
  "units": [
    {"kind":"reverse_contract","mutatesRuntime":false},
    {"kind":"versioned_contract","targets":["CONTRACT"]},
    {"kind":"authority_server","targets":["SERVER"]},
    {"kind":"legacy_gateway","targets":["LEGACY_GATEWAY"]},
    {"kind":"new_client","targets":["NEW_CLIENT"]},
    {"kind":"database_replay","targets":["DATABASE"]},
    {"kind":"content_admin","targets":["CONTENT_ADMIN"]},
    {"kind":"qa_independent_review","targets":["QA","INDEPENDENT_REVIEW"]}
  ]
}
```

- [ ] **Step 4: Generate and verify plan inputs**

Expected: every unresolved graph row appears in at least one recovery unit; every gameplay feature appears in all applicable implementation units; no unit silently requests server/protocol/DB mutation.

- [ ] **Step 5: Commit**

```powershell
git add tools/exhaustive_trace/work_packages.py tests/tools/exhaustive_trace/test_work_packages.py evidence/exhaustive-trace/domain-plan-inputs.json
git commit -m "docs: generate exhaustive trace work packages"
```

### Task 13: Build the recovery and authoring ledger

**Files:**
- Create: `tools/exhaustive_trace/recovery.py`
- Create: `tests/tools/exhaustive_trace/test_recovery.py`
- Create: `evidence/exhaustive-trace/recovery.json`
- Create: `docs/new-design/2026-08-27-original-character-roster-recovery-boundary.md`

**Interfaces:**
- Consumes: graph, coverage report, source manifest, and domain packages.
- Produces: one adjudicated recovery row for every unresolved value/rule/population and explicit authoring packages.

- [ ] **Step 1: Write failing recovery tests**

Require exactly one recovery disposition, evidence and falsifier for recoverable claims, research history for source conflicts/lost data, and editable schema/approval owner for authored data. Reject any `AUTHORING_REQUIRED` row presented as original evidence.

```python
def test_authored_value_cannot_be_original(self):
    row = RecoveryRow(
        key="ENTITY:CHARACTER_ROSTER",
        disposition="AUTHORING_REQUIRED",
        output_provenance="ORIGINAL_OBSERVED",
    )
    with self.assertRaises(ValueError):
        row.validate()
```

- [ ] **Step 2: Run tests and confirm failure**

Expected: recovery module missing.

- [ ] **Step 3: Implement recovery adjudication**

Classify static-extractable, live-required, conflicting, server-lost, originally unimplemented, authoring-required, and rights-review rows. Preserve research order as general web, Japanese web, user adjudication, then authored replacement.

- [ ] **Step 4: Create the character-roster boundary**

Record the current local candidate facts without promotion: 99 mixed-source named rows, 97 with candidate statistics, 12 official name-to-face-number facts, 2 pixel-confirmed official portrait mappings, and an unresolved majority of O-group portrait slots. Define separate outputs `originalConfirmedCharacters`, `canonCandidateCharacters`, and `authoredPlayableCharacters`.

- [ ] **Step 5: Generate and verify the ledger**

```powershell
python -m tools.exhaustive_trace.recovery --graph evidence/exhaustive-trace/graph.jsonl --coverage evidence/exhaustive-trace/coverage.json --sources docs/reverse-engineering/exhaustive-trace/source-manifest.json --output evidence/exhaustive-trace/recovery.json
python -m unittest tests.tools.exhaustive_trace.test_recovery -v
```

Expected: every unresolved value/rule/population has exactly one disposition and implementation/authoring owner.

- [ ] **Step 6: Commit**

```powershell
git add tools/exhaustive_trace/recovery.py tests/tools/exhaustive_trace/test_recovery.py evidence/exhaustive-trace/recovery.json docs/new-design/2026-08-27-original-character-roster-recovery-boundary.md
git commit -m "docs: adjudicate recovery and authoring gaps"
```

### Task 14: Publish the baseline report and handoff

**Files:**
- Create: `docs/reverse-engineering/exhaustive-trace/inventory-summary.md`
- Create: `work/20260827-original-game-exhaustive-trace-foundation/report/foundation-report.md`
- Create: `work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1`
- Create: `docs/handoffs/2026-08-27-original-game-exhaustive-trace-foundation.md`

**Interfaces:**
- Consumes: all foundation tests, recovery/authoring ledger, implementation packages, and generated artifacts.
- Produces: hash-bound baseline, exact gap counts, first domain start, and forbidden retry list.

- [ ] **Step 1: Write the aggregate verifier**

Run every Python test, regenerate inventories/graph/coverage/domain packages into a temporary directory, compare deterministic hashes, require six inventories and sixteen domains, and reject unassigned rows.

- [ ] **Step 2: Run the aggregate verifier**

```powershell
pwsh -NoProfile -File work/20260827-original-game-exhaustive-trace-foundation/evidence/verify-foundation.ps1
```

Expected: exit `0`; unresolved gameplay rows remain visible in the report and do not masquerade as completed traces.

- [ ] **Step 3: Write the report and handoff**

Report inventory counts, graph node/edge counts, structural-orphan count, evidence-gap count, recovery-disposition counts, authoring-required counts, each domain's reverse-engineering and implementation unit counts, live-slice count, and the exact first unit selected by dependency order. Record independent review as `UNSEEN` unless separately performed.

- [ ] **Step 4: Commit**

```powershell
git add docs/reverse-engineering/exhaustive-trace/inventory-summary.md work/20260827-original-game-exhaustive-trace-foundation docs/handoffs/2026-08-27-original-game-exhaustive-trace-foundation.md
git commit -m "docs: publish exhaustive trace foundation baseline"
```

- [ ] **Step 5: Stop and report**

Do not automatically start the first generated domain unit. Report the foundation status and handoff path, then wait for user direction as required by the project goal.

## Self-review

- Spec coverage: Tasks 3–8 implement all six inventories; Tasks 9–10 implement the graph and orphan gates; Tasks 11–12 cover all sixteen domains, faction/role/live requirements, and complete implementation work generation; Task 13 adjudicates unrecoverable/authored data including the original-character roster; Task 14 covers reproducibility and handoff.
- Scope decomposition: this plan builds the common trace foundation only. Each generated domain package becomes a separate domain plan. Existing topology plan is bound to `D04`.
- Placeholder scan: no implementation step depends on an unnamed function or unspecified output; static addresses not already proven are outputs of hash-bound exporters rather than guessed constants.
- Type consistency: `InventoryRow`, `TraceNode`, `TraceEdge`, `TraceGraph`, `CoverageReport`, domain IDs, evidence states, reachability, and verdict values retain the same names throughout.
