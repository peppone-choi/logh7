from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.graph import (
    TraceGraph,
    build_graph,
    graph_jsonl,
    load_graph_jsonl,
)
from tools.exhaustive_trace.inventories import INVENTORY_SPECS, load_inventory_bundle
from tools.exhaustive_trace.io import canonical_json
from tools.exhaustive_trace.model import TraceEdge, TraceNode


def states() -> dict[str, bool]:
    return {
        "ENUMERATED": True,
        "STATIC_MAPPED": False,
        "CODEC_PROVEN": False,
        "RUNTIME_OBSERVED": False,
        "PLAYER_VISIBLE": False,
        "AUTHORITY_PROVEN": False,
        "PERSISTENCE_PROVEN": False,
        "BOTH_FACTIONS": False,
        "INDEPENDENTLY_REVIEWED": False,
    }


def mutable(value):
    if isinstance(value, dict) or hasattr(value, "items"):
        return {key: mutable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [mutable(item) for item in value]
    return copy.deepcopy(value)


def envelope(inventory: str, key: str, name: str) -> dict[str, object]:
    return {
        "inventory": inventory,
        "key": key,
        "name": name,
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "recoveryDisposition": "RECOVERABLE_STATIC",
        "states": states(),
        "evidence": [f"fixture:{key}"],
    }


def fixture_rows() -> dict[str, list[dict[str, object]]]:
    protocol = envelope("PROTOCOL", "PROTOCOL:MESSAGE16:0x0200", "shared-name")
    protocol.update(
        relations=[
            {
                "type": "PARSES",
                "function": "FUN_00401010",
                "evidence": ["fixture:protocol-parser"],
            }
        ],
        codeSpace="MESSAGE16",
        siblings={
            "request": {"codes": ["0x0200"], "disposition": "OBSERVED", "evidence": ["fixture:req"]},
            "response": {"codes": [], "disposition": "UNKNOWN", "evidence": ["fixture:none"]},
            "notify": {"codes": [], "disposition": "UNKNOWN", "evidence": ["fixture:none"]},
        },
    )

    ui = envelope(
        "UI",
        "UI:MODE:0x02:MANAGER:0x0B:CATEGORY:TYPE1:INDEX:0000",
        "shared-name",
    )
    ui.update(
        rowKind="WIDGET",
        builder={
            "functions": ["FUN_00401010"],
            "constructor": "FUN_00401010",
            "constructionSites": ["0x00401012"],
            "status": "PROVEN",
            "evidence": ["fixture:builder"],
        },
        handler={"functions": [], "status": "UNKNOWN", "evidence": ["fixture:none"]},
        enablement={
            "writers": ["FUN_00401010"],
            "stateFields": ["widget+0x15"],
            "status": "PROVEN",
            "evidence": ["fixture:enable"],
        },
        visibility={"writers": [], "stateFields": [], "status": "UNKNOWN", "evidence": ["fixture:none"]},
        label={"consumerFunctions": [], "status": "UNKNOWN", "evidence": ["fixture:none"]},
        event={"types": [], "predicates": [], "status": "UNKNOWN", "evidence": ["fixture:none"]},
        childManagers={"targetKeys": [], "status": "UNKNOWN", "evidence": ["fixture:none"]},
    )

    entity = envelope("ENTITY", "ENTITY:TYPE:DEMO", "demo-entity")
    entity.update(
        rowKind="ENTITY_TYPE",
        layout={
            "fields": [
                {
                    "key": "FIELD:DEMO:id",
                    "name": "id",
                    "status": "CANDIDATE",
                    "evidence": ["fixture:field"],
                }
            ]
        },
        relations={},
        wireProjections={},
        clientRepresentation={},
        lifecycle={},
    )

    resource = envelope("RESOURCE", "RESOURCE:TREE:data/demo.tga", "demo-resource")
    resource.update(rowKind="TREE_FILE")

    function = envelope("FUNCTION", "FUNCTION:INTERNAL:00401010", "FUN_00401010")
    function.update(
        rowKind="INDIVIDUAL_FUNCTION",
        address="00401010",
        callees={
            "direct": [
                {
                    "kind": "DIRECT_CALL",
                    "callsite": "00401015",
                    "targetAddress": "00409999",
                    "evidence": ["fixture:call"],
                }
            ],
            "indirectCallsites": [
                {"callsite": "00401020", "kind": "COMPUTED", "evidence": ["fixture:indirect"]}
            ],
        },
        globalStructureFields={
            "reads": [
                {
                    "referenceAddress": "0040101A",
                    "targetAddress": "00700000",
                    "targetSymbol": "DAT_00700000",
                    "evidence": ["fixture:read"],
                }
            ],
            "writes": [],
            "stringReferences": [],
        },
    )

    authority = envelope(
        "AUTHORITY",
        "AUTHORITY:CLIENT_BEHAVIOR:UI:MODE:0x02:MANAGER:0x0B:CATEGORY:TYPE1:INDEX:0000",
        "demo-authority-obligation",
    )
    authority.update(
        rowKind="CLIENT_BEHAVIOR_PATH",
        sourceKey=ui["key"],
        recoveryDisposition="ORIGINAL_SERVER_LOST",
    )
    return {
        "protocol": [protocol],
        "ui": [ui],
        "entities": [entity],
        "resources": [resource],
        "functions": [function],
        "authority": [authority],
    }


class GraphTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.project = Path(__file__).resolve().parents[3]
        self.source_manifest = (
            self.project / "docs" / "reverse-engineering" / "exhaustive-trace" / "source-manifest.json"
        )
        self.rows_by_file = fixture_rows()
        self.write_bundle()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_bundle(self) -> None:
        for logical_name, spec in INVENTORY_SPECS.items():
            rows = self.rows_by_file[logical_name]
            inventory_path = self.root / spec.filename
            inventory_path.write_text(
                "".join(canonical_json(row) for row in rows), encoding="utf-8", newline="\n"
            )
            reconciliation = {
                "schemaVersion": 1,
                "candidateCount": len(rows),
                "normalizedCount": len(rows),
                "unresolvedCount": 0,
                "excludedCount": 0,
                "unaccountedCount": 0,
            }
            (self.root / spec.reconciliation_filename).write_text(
                canonical_json(reconciliation), encoding="utf-8", newline="\n"
            )

    def load(self):
        return load_inventory_bundle(self.root, source_manifest=self.source_manifest)

    def test_bundle_requires_all_six_inventories_and_reconciliations(self) -> None:
        (self.root / "ui.jsonl").unlink()
        with self.assertRaisesRegex(ValueError, "missing|artifact"):
            self.load()

    def test_bundle_rejects_noncanonical_jsonl_and_unaccounted_reconciliation(self) -> None:
        path = self.root / "protocol.jsonl"
        path.write_text(path.read_text(encoding="utf-8").replace("\n", "\r\n"), encoding="utf-8", newline="")
        with self.assertRaisesRegex(ValueError, "canonical|newline"):
            self.load()

        self.write_bundle()
        recon = self.root / "protocol-reconciliation.json"
        value = json.loads(recon.read_text(encoding="utf-8"))
        value["unaccountedCount"] = 1
        recon.write_text(canonical_json(value), encoding="utf-8", newline="\n")
        with self.assertRaisesRegex(ValueError, "unaccounted"):
            self.load()

    def test_bundle_rejects_inventory_mismatch_and_global_casefold_key_collision(self) -> None:
        self.rows_by_file["ui"][0]["inventory"] = "PROTOCOL"
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "inventory"):
            self.load()

        self.rows_by_file = fixture_rows()
        duplicate = copy.deepcopy(self.rows_by_file["protocol"][0])
        duplicate["key"] = self.rows_by_file["ui"][0]["key"].lower()
        self.rows_by_file["protocol"] = [duplicate]
        self.write_bundle()
        with self.assertRaisesRegex(ValueError, "duplicate|collision"):
            self.load()

    def test_bundle_hash_binds_inventory_reconciliation_and_source_manifest(self) -> None:
        bundle = self.load()
        self.assertEqual(set(bundle.sources), set(INVENTORY_SPECS))
        self.assertRegex(bundle.bundle_sha256, r"^[0-9A-F]{64}$")
        self.assertEqual(6, bundle.row_count)
        self.assertRegex(bundle.source_manifest_sha256, r"^[0-9A-F]{64}$")

    def test_unknown_edge_type_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "relation"):
            TraceEdge("a", "CONNECTED_SOMEHOW", "b", ("E-1",))

    def test_graph_preserves_every_inventory_row_exactly_once(self) -> None:
        bundle = self.load()
        graph = build_graph(bundle.rows)
        inventory_nodes = [node for node in graph.nodes if node.kind == "INVENTORY_ROW"]
        self.assertEqual(6, len(inventory_nodes))
        self.assertEqual({row["key"] for row in bundle.rows}, {node.key for node in inventory_nodes})
        self.assertEqual(0, graph.conservation["unrepresentedSourceRows"])
        self.assertEqual(tuple(bundle.rows), graph.source_rows)

    def test_graph_loader_restores_immutable_source_rows_from_bound_bundle(self) -> None:
        bundle = self.load()
        graph = build_graph(bundle.rows)
        path = self.root / "graph.jsonl"
        path.write_text(graph_jsonl(graph, bundle), encoding="utf-8", newline="\n")

        loaded = load_graph_jsonl(path, bundle=bundle)

        self.assertEqual(tuple(bundle.rows), loaded.source_rows)
        with self.assertRaises(TypeError):
            loaded.source_rows[0]["name"] = "mutated"

    def test_name_match_is_candidate_only_and_never_identity(self) -> None:
        graph = build_graph(self.load().rows)
        matches = [edge for edge in graph.edges if edge.relation == "NAME_MATCH"]
        self.assertEqual(2, len(matches))
        self.assertTrue(all(edge.edge_class == "CANDIDATE" for edge in matches))
        self.assertTrue(all(edge.provenance == "INFERRED" for edge in matches))
        self.assertTrue(all(edge.confidence == "LOW" for edge in matches))
        self.assertFalse(any(edge.relation == "IDENTIFIES" for edge in graph.edges))
        self.assertEqual(1, len([node for node in graph.nodes if node.kind == "NAME_TOKEN"]))

    def test_explicit_function_protocol_ui_and_authority_joins_have_correct_direction(self) -> None:
        graph = build_graph(self.load().rows)
        triples = {(edge.source, edge.relation, edge.target) for edge in graph.edges}
        function_key = "FUNCTION:INTERNAL:00401010"
        protocol_key = "PROTOCOL:MESSAGE16:0x0200"
        ui_key = "UI:MODE:0x02:MANAGER:0x0B:CATEGORY:TYPE1:INDEX:0000"
        authority_key = f"AUTHORITY:CLIENT_BEHAVIOR:{ui_key}"
        self.assertIn((function_key, "PARSES", protocol_key), triples)
        self.assertIn((function_key, "BUILDS", ui_key), triples)
        self.assertIn((function_key, "ENABLES", ui_key), triples)
        self.assertIn((authority_key, "OBLIGATION_FOR", ui_key), triples)

    def test_fields_globals_and_unresolved_calls_become_typed_nodes_not_guesses(self) -> None:
        graph = build_graph(self.load().rows)
        by_kind: dict[str, list[TraceNode]] = {}
        for node in graph.nodes:
            by_kind.setdefault(node.kind, []).append(node)
        self.assertEqual(["FIELD:DEMO:id"], [node.key for node in by_kind["FIELD"]])
        self.assertEqual(["STATE:ADDRESS:00700000"], [node.key for node in by_kind["STATE_LOCATION"]])
        unresolved = {node.key for node in by_kind["UNRESOLVED_REFERENCE"]}
        self.assertIn("UNRESOLVED:FUNCTION_ADDRESS:00409999", unresolved)
        self.assertTrue(any("00401020" in key for key in unresolved))
        self.assertFalse(any(node.key == "FUNCTION:INTERNAL:00409999" for node in graph.nodes))
        self.assertEqual(0, graph.conservation["danglingEdgeCount"])

    def test_entity_rows_with_explicit_null_layout_are_preserved_without_fabricated_fields(self) -> None:
        bundle = self.load()
        rows = [mutable(row) for row in bundle.rows]
        entity = next(row for row in rows if row["inventory"] == "ENTITY")
        entity["layout"] = None

        graph = build_graph(rows)

        self.assertEqual(1, sum(node.key == entity["key"] for node in graph.nodes))
        self.assertFalse(any(node.kind == "FIELD" for node in graph.nodes))

    def test_graph_does_not_promote_inventory_evidence_states(self) -> None:
        bundle = self.load()
        graph = build_graph(bundle.rows)
        for row in bundle.rows:
            node = graph.node(row["key"])
            self.assertEqual(row["states"], dict(node.attributes["states"]))
        with self.assertRaises(TypeError):
            graph.node(bundle.rows[0]["key"]).attributes["states"]["PLAYER_VISIBLE"] = True
        with self.assertRaises(TypeError):
            bundle.rows[0]["states"]["PLAYER_VISIBLE"] = True
        with self.assertRaises(TypeError):
            graph.conservation["nodeCountByKind"]["INVENTORY_ROW"] = 0

    def test_all_reference_bearing_fixture_fields_are_accounted_for(self) -> None:
        bundle = self.load()
        rows = [mutable(row) for row in bundle.rows]
        function = next(row for row in rows if row["inventory"] == "FUNCTION")
        function["globalStructureFields"] = copy.deepcopy(dict(function["globalStructureFields"]))
        function["globalStructureFields"]["stringReferences"] = [
            {
                "referenceAddress": "00401030",
                "stringAddress": "00710000",
                "value": "",
                "evidence": ["fixture:string"],
            }
        ]
        function["classification"] = {
            "upstreamReferences": [
                {"evidence": ["fixture:upstream"], "candidateId": "fixture:upstream:1"}
            ]
        }
        target_function = mutable(function)
        target_function["key"] = "FUNCTION:INTERNAL:00409999"
        target_function["name"] = "FUN_00409999"
        target_function["address"] = "00409999"
        target_function["callees"] = {"direct": [], "indirectCallsites": []}
        target_function["callers"] = {
            "direct": [
                {
                    "callsite": "00401015",
                    "sourceAddress": "00401010",
                    "evidence": ["fixture:caller-mirror"],
                }
            ]
        }
        target_function["globalStructureFields"] = {
            "reads": [], "writes": [], "stringReferences": []
        }
        target_function["classification"] = {"upstreamReferences": []}
        rows.append(target_function)
        ui = next(row for row in rows if row["inventory"] == "UI")
        ui["event"] = copy.deepcopy(dict(ui["event"]))
        ui["event"]["types"] = ["0x0E"]
        ui["event"]["predicates"] = ["FUN_00401010"]
        protocol = next(row for row in rows if row["inventory"] == "PROTOCOL")
        protocol["ownership"] = {
            "parser": {
                "functions": ["FUN_00401010"],
                "evidence": ["fixture:protocol-owner"],
            },
            "dispatcher": {"functions": [], "evidence": ["fixture:none"]},
            "serializer": {"functions": [], "evidence": ["fixture:none"]},
        }
        resource = next(row for row in rows if row["inventory"] == "RESOURCE")
        resource["loader"] = {
            "functions": ["FUN_00401010"],
            "status": "CANDIDATE",
            "evidence": ["fixture:resource-loader"],
        }

        graph = build_graph(rows)
        triples = {(edge.source, edge.relation, edge.target) for edge in graph.edges}

        self.assertIn((function["key"], "MENTIONS", "STRING:ADDRESS:00710000"), triples)
        self.assertIn((function["key"], "LOADS", resource["key"]), triples)
        self.assertTrue(any(node.kind == "EVENT" for node in graph.nodes))
        self.assertIn((ui["key"], "MENTIONS", function["key"]), triples)
        direct_call = next(
            edge
            for edge in graph.edges
            if edge.source == function["key"]
            and edge.relation == "CALLS"
            and edge.target == target_function["key"]
        )
        self.assertTrue(any("/callers/direct/" in ref for ref in direct_call.source_refs))
        parser = next(edge for edge in graph.edges if edge.relation == "PARSES")
        self.assertTrue(any("/ownership/parser/" in ref for ref in parser.source_refs))
        self.assertTrue(
            any(
                "/classification/upstreamReferences/" in ref
                for ref in graph.node(function["key"]).source_refs
            )
        )
        self.assertEqual(
            graph.conservation["joinCandidateCount"],
            graph.conservation["normalizedJoinCandidateCount"]
            + graph.conservation["unresolvedJoinCandidateCount"]
            + graph.conservation["sourceConflictJoinCandidateCount"],
        )
        self.assertEqual(0, graph.conservation["unaccountedJoinCandidates"])

    def test_process_bootstrap_emits_launch_edge_without_asset_load_edge(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        source = next(row for row in rows if row["inventory"] == "RESOURCE")
        source["source"] = {
            "processLaunch": {
                "status": "PROVEN",
                "targetRowKey": "RESOURCE:TREE:gin7updateclient.exe",
                "api": "KERNEL32.dll::CreateProcessA",
                "function": "FUN_00401000",
                "callsite": "0x004010B1",
                "evidence": ["fixture:process-launch"],
            }
        }
        target = envelope(
            "RESOURCE", "RESOURCE:TREE:gin7updateclient.exe", "gin7updateclient.exe"
        )
        target.update(rowKind="TREE_FILE")
        rows.append(target)

        graph = build_graph(rows)
        triples = {(edge.source, edge.relation, edge.target) for edge in graph.edges}

        self.assertIn((source["key"], "LAUNCHES_PROCESS", target["key"]), triples)
        self.assertNotIn((source["key"], "LOADS", target["key"]), triples)

    def test_inbound_launch_emits_launcher_to_target_edge_once(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        target = next(row for row in rows if row["inventory"] == "RESOURCE")
        launcher = envelope(
            "RESOURCE", "RESOURCE:TREE:gin7updateclient.exe", "gin7updateclient.exe"
        )
        launcher.update(rowKind="TREE_FILE")
        target["source"] = {
            "inboundLaunch": {
                "status": "PROVEN_STATIC_DEFAULT",
                "launcherRowKey": launcher["key"],
                "launcherRelativePosixPath": "gin7updateclient.exe",
                "launcherSha256": "B" * 64,
                "api": "KERNEL32.dll::CreateProcessA",
                "callsite": "0x004072C2",
                "triggerCallsite": "0x004068A1",
                "targetCommand": ".\\exe\\G7MTClient.exe",
                "targetRelativePosixPath": "exe/g7mtclient.exe",
                "targetSha256": "A" * 64,
                "g7StartLaunchStatus": "UNRESOLVED",
                "evidence": ["fixture:inbound-launch"],
            }
        }
        rows.append(launcher)

        graph = build_graph(rows)
        triples = [(edge.source, edge.relation, edge.target) for edge in graph.edges]

        self.assertEqual(1, triples.count((launcher["key"], "LAUNCHES_PROCESS", target["key"])))
        self.assertNotIn((target["key"], "LAUNCHES_PROCESS", launcher["key"]), triples)
        self.assertNotIn((launcher["key"], "LOADS", target["key"]), triples)

    def test_matching_source_and_inbound_launch_claims_merge_deterministically(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        target = next(row for row in rows if row["inventory"] == "RESOURCE")
        launcher = envelope(
            "RESOURCE", "RESOURCE:TREE:gin7updateclient.exe", "gin7updateclient.exe"
        )
        launcher.update(rowKind="TREE_FILE")
        signature = {
            "status": "PROVEN_STATIC_DEFAULT", "api": "KERNEL32.dll::CreateProcessA",
            "callsite": "0x004072C2", "triggerCallsite": "0x004068A1",
            "targetCommand": ".\\exe\\G7MTClient.exe", "workingDirectory": ".\\exe\\",
            "targetRelativePosixPath": "exe/g7mtclient.exe", "targetSha256": "A" * 64,
            "configOverrideStatus": "POSSIBLE", "gateSemantics": "UNRESOLVED",
            "runtimeObservationStatus": "UNSEEN",
        }
        launcher["source"] = {
            "processLaunch": {
                **signature, "targetRowKey": target["key"],
                "evidence": ["fixture:source-launch"],
            }
        }
        target["source"] = {
            "inboundLaunch": {
                **signature, "launcherRowKey": launcher["key"],
                "launcherRelativePosixPath": "gin7updateclient.exe",
                "launcherSha256": "B" * 64, "g7StartLaunchStatus": "UNRESOLVED",
                "evidence": ["fixture:inbound-launch"],
            }
        }
        rows.append(launcher)

        graph = build_graph(rows)
        reverse_graph = build_graph(list(reversed(rows)))
        matches = [edge for edge in graph.edges if edge.source == launcher["key"]
                   and edge.relation == "LAUNCHES_PROCESS" and edge.target == target["key"]]

        self.assertEqual(1, len(matches))
        self.assertEqual("CORROBORATED_TYPED_REFERENCE", matches[0].join_basis)
        self.assertEqual(
            {f"inventory:{launcher['key']}/source/processLaunch",
             f"inventory:{target['key']}/source/inboundLaunch"},
            set(matches[0].source_refs),
        )
        self.assertEqual(graph.edges_sha256, reverse_graph.edges_sha256)

    def test_conflicting_dual_process_launch_claims_fail_closed(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        target = next(row for row in rows if row["inventory"] == "RESOURCE")
        launcher = envelope(
            "RESOURCE", "RESOURCE:TREE:gin7updateclient.exe", "gin7updateclient.exe"
        )
        launcher.update(rowKind="TREE_FILE")
        common = {
            "status": "PROVEN_STATIC_DEFAULT", "api": "KERNEL32.dll::CreateProcessA",
            "callsite": "0x004072C2", "triggerCallsite": "0x004068A1",
            "targetCommand": ".\\exe\\G7MTClient.exe", "workingDirectory": ".\\exe\\",
            "targetRelativePosixPath": "exe/g7mtclient.exe", "targetSha256": "A" * 64,
            "configOverrideStatus": "POSSIBLE", "gateSemantics": "UNRESOLVED",
            "runtimeObservationStatus": "UNSEEN",
        }
        launcher["source"] = {"processLaunch": {
            **common, "targetRowKey": target["key"], "evidence": ["fixture:source-launch"],
        }}
        target["source"] = {"inboundLaunch": {
            **common, "callsite": "0x004072C3", "launcherRowKey": launcher["key"],
            "launcherRelativePosixPath": "gin7updateclient.exe", "launcherSha256": "B" * 64,
            "g7StartLaunchStatus": "UNRESOLVED", "evidence": ["fixture:inbound-launch"],
        }}
        rows.append(launcher)

        with self.assertRaisesRegex(ValueError, "conflicting process launch claims"):
            build_graph(rows)

    def test_external_manual_opener_emits_document_edge_without_asset_load_edge(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        manual = next(row for row in rows if row["inventory"] == "RESOURCE")
        manual["source"] = {
            "externalDocumentOpen": {
                "status": "PROVEN",
                "openerKey": "ORIGINAL_CD_ARTIFACT:G7START.EXE",
                "openerName": "G7Start.exe",
                "openerSha256": "1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93",
                "openerByteSize": 434176,
                "api": "SHELL32.dll::ShellExecuteA",
                "commandId": 1001,
                "handler": "FUN_00403860",
                "callsite": "0x004038E6",
                "verb": "open",
                "targetOriginalName": "銀英伝７マニュアル.pdf",
                "targetSha256": "A" * 64,
                "evidence": ["fixture:g7start-document-open"],
            }
        }

        graph = build_graph(rows)
        triples = {(edge.source, edge.relation, edge.target) for edge in graph.edges}

        self.assertIsNotNone(graph.node("ORIGINAL_CD_ARTIFACT:G7START.EXE"))
        self.assertIn(
            ("ORIGINAL_CD_ARTIFACT:G7START.EXE", "OPENS_DOCUMENT", manual["key"]),
            triples,
        )
        self.assertNotIn(
            ("ORIGINAL_CD_ARTIFACT:G7START.EXE", "LOADS", manual["key"]),
            triples,
        )

    def test_external_config_access_emits_read_write_edges_without_function_load(self) -> None:
        rows = [row for group in fixture_rows().values() for row in group]
        config = next(row for row in rows if row["inventory"] == "RESOURCE")
        updater = envelope(
            "RESOURCE", "RESOURCE:FILE:original:gin7updateclient.exe", "gin7updateclient.exe"
        )
        updater.update(rowKind="TREE_FILE")
        config["source"] = {
            "relativePosixPath": "update.ini", "contentSha256": "A" * 64,
            "externalConfigAccess": {
                "status": "PROVEN_STATIC", "consumerRowKey": updater["key"],
                "targetRelativePosixPath": "update.ini", "targetSha256": "A" * 64,
                "readAccesses": [{"accessId": "READ:1", "operation": "READ",
                                  "status": "PROVEN_STATIC", "evidence": ["fixture:read"]}],
                "writeAccesses": [{"accessId": "WRITE:1", "operation": "WRITE",
                                   "status": "PROVEN_STATIC", "evidence": ["fixture:write"]}],
                "evidence": ["fixture:config"],
            },
        }
        rows.append(updater)

        graph = build_graph(rows)
        triples = {(edge.source, edge.relation, edge.target) for edge in graph.edges}
        self.assertIn((updater["key"], "READS", config["key"]), triples)
        self.assertIn((updater["key"], "WRITES", config["key"]), triples)
        self.assertNotIn((updater["key"], "LOADS", config["key"]), triples)
        refs = {
            ref for edge in graph.edges
            if edge.source == updater["key"] and edge.target == config["key"]
            for ref in edge.source_refs
        }
        self.assertIn(
            f"inventory:{config['key']}/source/externalConfigAccess/readAccesses/0", refs
        )
        self.assertIn(
            f"inventory:{config['key']}/source/externalConfigAccess/writeAccesses/0", refs
        )

    def test_trace_graph_rejects_dangling_edges_and_candidate_identity(self) -> None:
        node = TraceNode(
            "A", "INVENTORY_ROW", "a", ("E-A",), provenance="ORIGINAL_OBSERVED",
            confidence="HIGH", disposition="PROVEN", source_refs=("fixture:A",),
        )
        dangling = TraceEdge(
            "A", "CALLS", "B", ("E-CALL",), provenance="ORIGINAL_OBSERVED",
            confidence="HIGH", disposition="PROVEN", edge_class="STRUCTURAL",
            join_basis="DIRECT_ADDRESS_REFERENCE", source_refs=("fixture:call",),
            candidate_id="FIXTURE:CALL:1",
        )
        with self.assertRaisesRegex(ValueError, "dangling"):
            TraceGraph((node,), (dangling,))

        bad_identity = TraceEdge(
            "A", "IDENTIFIES", "A", ("E-NAME",), provenance="INFERRED",
            confidence="LOW", disposition="CANDIDATE", edge_class="CANDIDATE",
            join_basis="NAME_EQUALITY", source_refs=("fixture:name",),
            candidate_id="fixture:name:1",
        )
        with self.assertRaisesRegex(ValueError, "IDENTIFIES|identity"):
            TraceGraph((node,), (bad_identity,))

        wrong_case = TraceEdge(
            "a", "CALLS", "A", ("E-CASE",), provenance="ORIGINAL_OBSERVED",
            confidence="HIGH", disposition="PROVEN", edge_class="STRUCTURAL",
            join_basis="DIRECT_ADDRESS_REFERENCE", source_refs=("fixture:case",),
            candidate_id="fixture:case:1",
        )
        with self.assertRaisesRegex(ValueError, "dangling|endpoint"):
            TraceGraph((node,), (wrong_case,))

        second = TraceEdge(
            "A", "MENTIONS", "A", ("E-SECOND",), provenance="ORIGINAL_OBSERVED",
            confidence="HIGH", disposition="PROVEN", edge_class="STRUCTURAL",
            join_basis="DIRECT_TYPED_REFERENCE", source_refs=("fixture:second",),
            candidate_id="FIXTURE:CALL:1",
        )
        first = TraceEdge(
            "A", "CALLS", "A", ("E-FIRST",), provenance="ORIGINAL_OBSERVED",
            confidence="HIGH", disposition="PROVEN", edge_class="STRUCTURAL",
            join_basis="DIRECT_ADDRESS_REFERENCE", source_refs=("fixture:first",),
            candidate_id="fixture:call:1",
        )
        with self.assertRaisesRegex(ValueError, "candidate"):
            TraceGraph((node,), (first, second))

    def test_ambiguous_function_address_mapping_is_preserved_as_source_conflict(self) -> None:
        bundle = self.load()
        rows = [mutable(row) for row in bundle.rows]
        function = next(row for row in rows if row["inventory"] == "FUNCTION")
        duplicate = copy.deepcopy(function)
        duplicate["key"] = "FUNCTION:INTERNAL:00401011"
        duplicate["name"] = "FUN_00401011"
        rows.append(duplicate)
        graph = build_graph(rows)
        conflicts = [node for node in graph.nodes if node.disposition == "SOURCE_CONFLICT"]
        self.assertTrue(conflicts)
        self.assertTrue(any(edge.disposition == "SOURCE_CONFLICT" for edge in graph.edges))

    def test_graph_is_deterministic_under_input_order_and_round_trips_hashes(self) -> None:
        bundle = self.load()
        graph_a = build_graph(bundle.rows)
        graph_b = build_graph(list(reversed(bundle.rows)))
        text_a = graph_jsonl(graph_a, bundle)
        text_b = graph_jsonl(graph_b, bundle)
        self.assertEqual(text_a, text_b)

        path = self.root / "graph.jsonl"
        path.write_text(text_a, encoding="utf-8", newline="\n")
        loaded = load_graph_jsonl(path, bundle=bundle)
        self.assertEqual(graph_a.nodes_sha256, loaded.nodes_sha256)
        self.assertEqual(graph_a.edges_sha256, loaded.edges_sha256)
        self.assertEqual(graph_a.graph_surface_sha256, loaded.graph_surface_sha256)

    def test_graph_manifest_hash_tampering_is_rejected(self) -> None:
        bundle = self.load()
        text = graph_jsonl(build_graph(bundle.rows), bundle)
        lines = text.splitlines()
        manifest = json.loads(lines[0])
        manifest["graphSurfaceSha256"] = "0" * 64
        lines[0] = canonical_json(manifest).rstrip("\n")
        path = self.root / "tampered-graph.jsonl"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        with self.assertRaisesRegex(ValueError, "surface|hash"):
            load_graph_jsonl(path, bundle=bundle)

        manifest = json.loads(text.splitlines()[0])
        manifest["audit"]["nameMatchIsIdentity"] = True
        audit_tampered = self.root / "tampered-audit-graph.jsonl"
        audit_tampered.write_text(
            canonical_json(manifest) + "\n".join(text.splitlines()[1:]) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        with self.assertRaisesRegex(ValueError, "audit|contract"):
            load_graph_jsonl(audit_tampered, bundle=bundle)

        manifest = json.loads(text.splitlines()[0])
        manifest["bundleSha256"] = "F" * 64
        source_tampered = self.root / "tampered-source-graph.jsonl"
        source_tampered.write_text(
            canonical_json(manifest) + "\n".join(text.splitlines()[1:]) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        with self.assertRaisesRegex(ValueError, "bundle|source|hash"):
            load_graph_jsonl(source_tampered, bundle=bundle)


if __name__ == "__main__":
    unittest.main()
