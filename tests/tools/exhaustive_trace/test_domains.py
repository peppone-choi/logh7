from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

from tools.exhaustive_trace.coverage import CoverageFatal, CoverageReport, CoverageRow
from tools.exhaustive_trace.domains import (
    build_domain_packages,
    domain_package_files,
    load_domain_config,
    load_domain_packages,
)
from tools.exhaustive_trace.io import canonical_json
from tools.exhaustive_trace.model import EvidenceState, ImplementationTarget, TraceEdge, TraceNode


DOMAIN_SLUGS = {
    "D01": "launcher-update-config-data-root",
    "D02": "account-auth-lobby-session-character",
    "D03": "faction-calendar-rank-office-authority",
    "D04": "world-topology-systems-planets-fortresses-grids",
    "D05": "fleets-units-ships-troops-fighters-arms",
    "D06": "strategy-navigation-warp-search-encounter-fog",
    "D07": "bases-institutions-spots-rooms-facilities",
    "D08": "commands-orders-suggestions-mail-messenger",
    "D09": "grid-spot-unicast-tactical-communication",
    "D10": "economy-production-construction-repair-supply-cargo",
    "D11": "tactical-entry-field-deployment-combat-retreat",
    "D12": "politics-personnel-diplomacy-governance",
    "D13": "growth-rewards-ranking-victory-session-end",
    "D14": "offline-ai-timeout-disconnect-reconnect-replay",
    "D15": "sound-cursor-localization-hud-information",
    "D16": "administration-moderation-publication-backup-operations",
}
TOPOLOGY_PLAN = (
    "docs/superpowers/plans/2026-08-27-original-world-topology-full-trace.md"
)


def _sha(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest().upper()


def _implementation() -> dict[str, dict[str, Any]]:
    return {
        target.value: {
            "status": "REQUIRED",
            "reason": f"fixture requires {target.value}",
            "evidence": [f"goal:implementation-layer:{target.value}"],
        }
        for target in ImplementationTarget
    }


def _states() -> dict[str, bool]:
    return {state.value: state is EvidenceState.ENUMERATED for state in EvidenceState}


def _row(key: str, inventory: str, row_kind: str, name: str, **extra: Any) -> dict[str, Any]:
    value: dict[str, Any] = {
        "key": key,
        "inventory": inventory,
        "name": name,
        "rowKind": row_kind,
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "recoveryDisposition": "RECOVERABLE_STATIC",
        "implementationDisposition": _implementation(),
        "states": _states(),
        "evidence": [f"fixture:{key}"],
    }
    value.update(extra)
    return value


@dataclass(frozen=True)
class SyntheticGraph:
    source_rows: tuple[Mapping[str, Any], ...]
    nodes: tuple[TraceNode, ...]
    edges: tuple[TraceEdge, ...]
    bundle_sha256: str = "1" * 64
    source_manifest_sha256: str = "2" * 64
    client_sha256: str = "3" * 64
    message_data_sha256: str = "4" * 64
    nodes_sha256: str = "5" * 64
    edges_sha256: str = "6" * 64
    graph_surface_sha256: str = "7" * 64

    @property
    def conservation(self) -> Mapping[str, int]:
        return {"sourceRowNodes": len(self.source_rows)}


def _fixture_graph(*, reversed_order: bool = False) -> SyntheticGraph:
    topology = _row(
        "ENTITY:TYPE:SYSTEM",
        "ENTITY",
        "ENTITY_TYPE",
        "MisleadingEconomyCache",
        entityType="SYSTEM",
        stateBearing=True,
    )
    authority = _row(
        "AUTHORITY:ENTITY:ENTITY:TYPE:SYSTEM",
        "AUTHORITY",
        "ENTITY_PATH",
        "MisleadingHudAuthority",
        sourceKey=topology["key"],
    )
    no_signal = _row(
        "FUNCTION:INTERNAL:00401000",
        "FUNCTION",
        "INDIVIDUAL_FUNCTION",
        "LooksLikePlanetButIsUnclassified",
        address="00401000",
    )
    no_relation = _row(
        "RESOURCE:FILE:UNCLASSIFIED",
        "RESOURCE",
        "TREE_FILE",
        "OpaqueAssetWithoutTypedOwner",
    )
    rows = [topology, authority, no_signal, no_relation]
    nodes = [
        TraceNode(
            row["key"],
            "INVENTORY_ROW",
            row["name"],
            tuple(row["evidence"]),
            provenance=row["provenance"],
            confidence="HIGH",
            disposition="PROVEN",
            source_refs=(f"inventory:{row['key']}",),
            attributes={
                "inventory": row["inventory"],
                "rowKind": row["rowKind"],
                "reachability": row["reachability"],
                "recoveryDisposition": row["recoveryDisposition"],
                "implementationDisposition": row["implementationDisposition"],
                "states": row["states"],
            },
        )
        for row in rows
    ]
    name_match = TraceEdge(
        no_signal["key"],
        "NAME_MATCH",
        topology["key"],
        ("fixture:name-equality",),
        provenance="INFERRED",
        confidence="LOW",
        disposition="CANDIDATE",
        edge_class="CANDIDATE",
        join_basis="EXACT_NORMALIZED_NAME",
        source_refs=("fixture:name-equality",),
        candidate_id="DOMAIN-TEST:NAME-MATCH:1",
    )
    if reversed_order:
        rows.reverse()
        nodes.reverse()
    return SyntheticGraph(tuple(rows), tuple(nodes), (name_match,))


def _coverage(graph: SyntheticGraph, *, reversed_order: bool = False) -> CoverageReport:
    rows: list[CoverageRow] = []
    for source in graph.source_rows:
        fatal = ()
        if source["inventory"] == "FUNCTION":
            fatal = (
                CoverageFatal(
                    "TEST_FATAL",
                    source["key"],
                    "classification.status",
                    tuple(source["evidence"]),
                    "fixture structural failure must remain visible",
                ),
            )
        rows.append(
            CoverageRow(
                row_key=source["key"],
                inventory=source["inventory"],
                reachability=source["reachability"],
                recovery_disposition=source["recoveryDisposition"],
                implementation_disposition=source["implementationDisposition"],
                states=source["states"],
                verdict="UNKNOWN",
                first_missing_boundary="SEMANTIC_ROLE",
                all_missing_boundaries=("SEMANTIC_ROLE",),
                gaps=(),
                fatals=fatal,
            )
        )
    if reversed_order:
        rows.reverse()
    source_rows = sorted(
        (dict(row) for row in graph.source_rows),
        key=lambda row: (row["key"].casefold(), row["key"]),
    )
    return CoverageReport(
        graph_binding={
            "bundleSha256": graph.bundle_sha256,
            "sourceManifestSha256": graph.source_manifest_sha256,
            "clientSha256": graph.client_sha256,
            "messageDataSha256": graph.message_data_sha256,
            "nodesSha256": graph.nodes_sha256,
            "edgesSha256": graph.edges_sha256,
            "graphSurfaceSha256": graph.graph_surface_sha256,
            "sourceRowCount": len(source_rows),
            "sourceRowsSha256": _sha(source_rows),
        },
        rows=tuple(rows),
        global_fatals=(),
        conservation={
            "sourceRowCount": len(rows),
            "auditedRowCount": len(rows),
            "fatalStructuralCount": 1,
            "evidenceGapCount": 0,
        },
    )


class DomainPackageTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.plan = self.root / TOPOLOGY_PLAN
        self.plan.parent.mkdir(parents=True)
        self.plan.write_text("# topology fixture\n", encoding="utf-8", newline="\n")
        self.config_path = self.root / "domains.json"
        self.graph = _fixture_graph()
        self.coverage = _coverage(self.graph)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _config_payload(
        self, *, dependencies: Mapping[str, list[str]] | None = None
    ) -> dict[str, Any]:
        dependencies = dependencies or {"D04": ["D01"], "D05": ["D04"]}
        return {
            "recordType": "DOMAIN_CONFIGURATION",
            "schemaVersion": 1,
            "domains": [
                {
                    "id": domain_id,
                    "slug": slug,
                    "hardDependencies": dependencies.get(domain_id, []),
                    "planRefs": [TOPOLOGY_PLAN] if domain_id == "D04" else [],
                }
                for domain_id, slug in DOMAIN_SLUGS.items()
            ],
        }

    def _write_config(
        self, *, dependencies: Mapping[str, list[str]] | None = None
    ) -> None:
        self.config_path.write_text(
            canonical_json(self._config_payload(dependencies=dependencies)),
            encoding="utf-8",
            newline="\n",
        )

    @staticmethod
    def _bytes(value: bytes | str) -> bytes:
        return value if isinstance(value, bytes) else value.encode("utf-8")

    def _build(self):
        self._write_config()
        config = load_domain_config(self.config_path, project_root=self.root)
        return config, build_domain_packages(self.graph, self.coverage, config)

    def _publish_fixture(self, directory: Path, files: Mapping[str, bytes | str]) -> None:
        directory.mkdir()
        for name, value in files.items():
            (directory / name).write_bytes(self._bytes(value))

    def test_exact_sixteen_domain_config_and_dependency_first_topology(self) -> None:
        self._write_config()

        config = load_domain_config(self.config_path, project_root=self.root)

        self.assertEqual({item.id for item in config.definitions}, set(DOMAIN_SLUGS))
        self.assertEqual(len(config.definitions), 16)
        order = list(config.topological_order)
        self.assertLess(order.index("D01"), order.index("D04"))
        self.assertLess(order.index("D04"), order.index("D05"))

    def test_unknown_self_and_cyclic_hard_dependencies_are_rejected(self) -> None:
        cases = {
            "unknown": {"D01": ["D99"]},
            "self": {"D01": ["D01"]},
            "two-node cycle": {"D01": ["D02"], "D02": ["D01"]},
            "three-node cycle": {
                "D01": ["D02"],
                "D02": ["D03"],
                "D03": ["D01"],
            },
        }
        for label, dependencies in cases.items():
            with self.subTest(label=label):
                self._write_config(dependencies=dependencies)
                with self.assertRaisesRegex(ValueError, "dependency|cycle|self|unknown"):
                    load_domain_config(self.config_path, project_root=self.root)

    def test_typed_topology_and_authority_source_join_route_to_d04(self) -> None:
        _, package_set = self._build()
        files = domain_package_files(package_set)
        d04 = json.loads(self._bytes(files["D04.json"]).decode("utf-8"))
        assignments = {row["rowKey"]: row for row in d04["primaryRows"]}

        self.assertEqual(assignments["ENTITY:TYPE:SYSTEM"]["routingDisposition"], "PROVEN")
        self.assertEqual(
            assignments["AUTHORITY:ENTITY:ENTITY:TYPE:SYSTEM"]["routingDisposition"],
            "PROVEN",
        )
        self.assertIn(
            "sourceKey",
            " ".join(assignments["AUTHORITY:ENTITY:ENTITY:TYPE:SYSTEM"]["routingEvidence"]),
        )

    def test_every_row_has_one_primary_and_unresolved_name_match_is_not_proof(self) -> None:
        _, package_set = self._build()
        payloads = [
            json.loads(self._bytes(value).decode("utf-8"))
            for value in domain_package_files(package_set).values()
        ]
        primary = [
            (payload["domain"]["id"], row)
            for payload in payloads
            for row in payload["primaryRows"]
        ]
        counts: dict[str, int] = {}
        for _, row in primary:
            counts[row["rowKey"]] = counts.get(row["rowKey"], 0) + 1
        self.assertEqual(counts, {row["key"]: 1 for row in self.graph.source_rows})

        function_owner, function = next(
            (domain, row)
            for domain, row in primary
            if row["rowKey"] == "FUNCTION:INTERNAL:00401000"
        )
        self.assertNotEqual(
            (function_owner, function["routingDisposition"]),
            ("D04", "PROVEN"),
        )
        unresolved = [
            item
            for payload in payloads
            for item in payload["crossDomainUnresolved"]
            if item["rowKey"]
            in {function["rowKey"], "RESOURCE:FILE:UNCLASSIFIED"}
        ]
        self.assertEqual(
            {item["rowKey"] for item in unresolved},
            {function["rowKey"], "RESOURCE:FILE:UNCLASSIFIED"},
        )
        for item in unresolved:
            self.assertTrue(item["candidateDomains"])
            self.assertTrue(item["evidence"])

    def test_unknown_bound_ui_label_is_not_defaulted_to_fleet_domain(self) -> None:
        base = _fixture_graph()
        row = _row(
            "UI:MODE:0x02:MANAGER:0x16:ROW:0099",
            "UI",
            "MENU_ROW",
            "MisleadingFleetDisplayName",
            label={
                "status": "BOUND_CONSUMER",
                "text": "未知情報",
                "consumerFunctions": ["FUN_00401000"],
                "evidence": ["fixture:unknown-label"],
            },
        )
        node = TraceNode(
            row["key"], "INVENTORY_ROW", row["name"], tuple(row["evidence"]),
            provenance=row["provenance"], confidence="HIGH", disposition="PROVEN",
            source_refs=(f"inventory:{row['key']}",), attributes={"inventory": "UI", "rowKind": "MENU_ROW"},
        )
        graph = SyntheticGraph((*base.source_rows, row), (*base.nodes, node), base.edges)
        self._write_config()
        config = load_domain_config(self.config_path, project_root=self.root)
        packages = build_domain_packages(graph, _coverage(graph), config)
        rows = [
            item
            for payload in (
                json.loads(self._bytes(value).decode("utf-8"))
                for value in domain_package_files(packages).values()
            )
            for item in payload["primaryRows"]
            if item["rowKey"] == row["key"]
        ]

        self.assertEqual(1, len(rows))
        self.assertNotEqual("PROVEN", rows[0]["routingDisposition"])

    def test_candidate_edge_class_never_proves_domain_even_with_proven_disposition(self) -> None:
        base = _fixture_graph()
        candidate = TraceEdge(
            "FUNCTION:INTERNAL:00401000",
            "OBLIGATION_FOR",
            "ENTITY:TYPE:SYSTEM",
            ("fixture:candidate-obligation",),
            provenance="INFERRED",
            confidence="LOW",
            disposition="PROVEN",
            edge_class="CANDIDATE",
            join_basis="DIRECT_TYPED_REFERENCE",
            source_refs=("fixture:candidate-obligation",),
            candidate_id="DOMAIN-TEST:CANDIDATE-PROVEN:1",
        )
        graph = SyntheticGraph(base.source_rows, base.nodes, (*base.edges, candidate))
        self._write_config()
        config = load_domain_config(self.config_path, project_root=self.root)
        packages = build_domain_packages(graph, _coverage(graph), config)
        row = next(
            item
            for payload in (
                json.loads(self._bytes(value).decode("utf-8"))
                for value in domain_package_files(packages).values()
            )
            for item in payload["primaryRows"]
            if item["rowKey"] == "FUNCTION:INTERNAL:00401000"
        )

        self.assertNotEqual(("D04", "PROVEN"), (row["primaryDomain"], row["routingDisposition"]))

    def test_secondary_assignments_never_repeat_the_primary_domain(self) -> None:
        _, package_set = self._build()
        for name, value in domain_package_files(package_set).items():
            payload = json.loads(self._bytes(value).decode("utf-8"))
            primary_keys = {row["rowKey"] for row in payload["primaryRows"]}
            self.assertTrue(primary_keys.isdisjoint(payload["secondaryRowKeys"]), name)
            for row in payload["primaryRows"]:
                self.assertNotIn(payload["domain"]["id"], row["secondaryDomains"])

    def test_d04_binds_the_exact_topology_plan(self) -> None:
        _, package_set = self._build()
        d04 = json.loads(
            self._bytes(domain_package_files(package_set)["D04.json"]).decode("utf-8")
        )

        self.assertEqual([item["path"] for item in d04["domain"]["planRefs"]], [TOPOLOGY_PLAN])
        self.assertEqual(
            d04["domain"]["planRefs"][0]["sha256"],
            hashlib.sha256(self.plan.read_bytes()).hexdigest().upper(),
        )

    def test_cross_domain_dependencies_separate_configured_hard_from_graph_soft(self) -> None:
        _, package_set = self._build()
        d04 = json.loads(
            self._bytes(domain_package_files(package_set)["D04.json"]).decode("utf-8")
        )
        dependencies = d04["crossDomainDependencies"]

        self.assertIn(
            {
                "kind": "HARD",
                "sourceDomain": "D04",
                "targetDomain": "D01",
                "relation": "DEPENDS_ON",
                "disposition": "CONFIGURED",
                "sourceKey": None,
                "targetKey": None,
                "candidateId": None,
                "evidence": ["domain-config:D04:hardDependency:D01"],
            },
            dependencies,
        )
        name_match = next(
            item for item in dependencies
            if item["candidateId"] == "DOMAIN-TEST:NAME-MATCH:1"
        )
        self.assertEqual("SOFT", name_match["kind"])
        self.assertEqual("CANDIDATE", name_match["disposition"])
        self.assertEqual("NAME_MATCH", name_match["relation"])

    def test_current_coverage_fatal_is_preserved_without_status_promotion(self) -> None:
        _, package_set = self._build()
        rows = [
            row
            for value in domain_package_files(package_set).values()
            for row in json.loads(self._bytes(value).decode("utf-8"))["primaryRows"]
        ]
        function = next(row for row in rows if row["rowKey"] == "FUNCTION:INTERNAL:00401000")

        self.assertEqual(function["coverageVerdict"], "UNKNOWN")
        self.assertEqual(
            [fatal["ruleId"] for fatal in function["coverageFatals"]],
            ["TEST_FATAL"],
        )

    def test_domain_files_are_exactly_sixteen_and_deterministic(self) -> None:
        config, first = self._build()
        reversed_graph = _fixture_graph(reversed_order=True)
        second = build_domain_packages(
            reversed_graph,
            _coverage(reversed_graph, reversed_order=True),
            config,
        )
        first_files = {
            name: self._bytes(value) for name, value in domain_package_files(first).items()
        }
        second_files = {
            name: self._bytes(value) for name, value in domain_package_files(second).items()
        }

        self.assertEqual(set(first_files), {f"D{number:02d}.json" for number in range(1, 17)})
        self.assertEqual(first_files, second_files)

    def test_loader_rejects_tampered_missing_and_extra_package_files(self) -> None:
        config, package_set = self._build()
        files = domain_package_files(package_set)

        with self.subTest(case="tampered"):
            directory = self.root / "tampered"
            self._publish_fixture(directory, files)
            path = directory / "D04.json"
            payload = json.loads(path.read_text(encoding="utf-8"))
            payload["domain"]["slug"] = "tampered"
            path.write_text(canonical_json(payload), encoding="utf-8", newline="\n")
            with self.assertRaisesRegex(ValueError, "hash|binding|package|reproduc"):
                load_domain_packages(
                    directory, graph=self.graph, coverage=self.coverage, config=config
                )

        with self.subTest(case="missing"):
            directory = self.root / "missing"
            self._publish_fixture(
                directory, {name: value for name, value in files.items() if name != "D16.json"}
            )
            with self.assertRaisesRegex(ValueError, "missing|artifact|file|set"):
                load_domain_packages(
                    directory, graph=self.graph, coverage=self.coverage, config=config
                )

        with self.subTest(case="extra"):
            directory = self.root / "extra"
            self._publish_fixture(directory, files)
            (directory / "unexpected.json").write_text("{}\n", encoding="utf-8", newline="\n")
            with self.assertRaisesRegex(ValueError, "extra|artifact|file|set"):
                load_domain_packages(
                    directory, graph=self.graph, coverage=self.coverage, config=config
                )

    def test_linked_config_plan_and_package_inputs_are_rejected(self) -> None:
        self._write_config()
        linked_config = self.root / "linked-domains.json"
        try:
            linked_config.symlink_to(self.config_path)
        except OSError as error:
            self.skipTest(f"symlink creation unavailable: {error}")
        with self.subTest(case="config"):
            with self.assertRaisesRegex(ValueError, "link|reparse"):
                load_domain_config(linked_config, project_root=self.root)

        real_plan = self.root / "real-plan.md"
        real_plan.write_text("# linked topology fixture\n", encoding="utf-8", newline="\n")
        self.plan.unlink()
        self.plan.symlink_to(real_plan)
        with self.subTest(case="plan"):
            with self.assertRaisesRegex(ValueError, "link|reparse"):
                load_domain_config(self.config_path, project_root=self.root)
        self.plan.unlink()
        self.plan.write_text("# topology fixture\n", encoding="utf-8", newline="\n")

        config, package_set = self._build()
        files = domain_package_files(package_set)
        directory = self.root / "linked-package"
        self._publish_fixture(directory, files)
        real_d04 = self.root / "real-D04.json"
        real_d04.write_bytes((directory / "D04.json").read_bytes())
        (directory / "D04.json").unlink()
        (directory / "D04.json").symlink_to(real_d04)
        with self.subTest(case="package-child"):
            with self.assertRaisesRegex(ValueError, "link|reparse"):
                load_domain_packages(
                    directory, graph=self.graph, coverage=self.coverage, config=config
                )

    def test_current_artifacts_preserve_d04_minimum_and_exact_unresolved_ledger(self) -> None:
        project = Path(__file__).resolve().parents[3]
        package_root = project / "evidence/exhaustive-trace/domains"
        payloads = [
            json.loads((package_root / f"D{number:02d}.json").read_text(encoding="utf-8"))
            for number in range(1, 17)
        ]
        d04 = next(payload for payload in payloads if payload["domain"]["id"] == "D04")
        required = {
            "ENTITY:TYPE:STAR_SYSTEM", "ENTITY:TYPE:PLANET", "ENTITY:TYPE:FORTRESS",
            "ENTITY:TYPE:GRID_CELL", "ENTITY:TYPE:GRID_TYPE", "ENTITY:TYPE:SPECIAL_BODY",
            "ENTITY:TYPE:SPECIAL_CELESTIAL_BODY", "ENTITY:TYPE:ROUTE_EDGE",
            "ENTITY:TYPE:SYSTEM", "ENTITY:RECORD:StaticInformationGrid",
            "ENTITY:RECORD:ResponseStaticInformationGridType",
        }
        primary_d04 = {row["rowKey"] for row in d04["primaryRows"]}
        self.assertEqual(set(), required - primary_d04)

        unresolved_expected = {
            row["rowKey"]
            for payload in payloads
            for row in payload["primaryRows"]
            if row["routingDisposition"] != "PROVEN"
        }
        unresolved_actual = {
            row["rowKey"]
            for payload in payloads
            for row in payload["crossDomainUnresolved"]
        }
        self.assertEqual(unresolved_expected, unresolved_actual)

if __name__ == "__main__":
    unittest.main()
