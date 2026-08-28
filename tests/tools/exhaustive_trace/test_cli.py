from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.cli import main
from tools.exhaustive_trace.coverage import load_coverage_json
from tools.exhaustive_trace.domains import load_domain_config, load_domain_packages
from tools.exhaustive_trace.graph import build_graph, graph_jsonl, load_graph_jsonl
from tools.exhaustive_trace.inventories import INVENTORY_SPECS, load_inventory_bundle
from tools.exhaustive_trace.io import canonical_json

from tests.tools.exhaustive_trace.test_graph import fixture_rows


class CliAuditTests(unittest.TestCase):
    def test_task10_artifacts_have_explicit_lf_attributes(self) -> None:
        project = Path(__file__).resolve().parents[3]
        lines = set((project / ".gitattributes").read_text(encoding="utf-8").splitlines())
        required = {
            "tools/exhaustive_trace/import_protocol.py text eol=lf",
            "tests/tools/exhaustive_trace/test_importers.py text eol=lf",
            "evidence/exhaustive-trace/inventories/protocol.jsonl text eol=lf",
            "tools/exhaustive_trace/coverage.py text eol=lf",
            "tests/tools/exhaustive_trace/test_coverage.py text eol=lf",
            "tests/tools/exhaustive_trace/test_cli.py text eol=lf",
            "evidence/exhaustive-trace/coverage.json text eol=lf",
        }
        self.assertEqual(set(), required - lines)

    def test_task11_python_artifacts_have_explicit_lf_attributes(self) -> None:
        project = Path(__file__).resolve().parents[3]
        lines = set((project / ".gitattributes").read_text(encoding="utf-8").splitlines())
        required = {
            "tools/exhaustive_trace/domains.py text eol=lf",
            "tests/tools/exhaustive_trace/test_domains.py text eol=lf",
            "tests/tools/exhaustive_trace/test_model.py text eol=lf",
        }
        self.assertEqual(set(), required - lines)

    def test_task12_artifacts_have_explicit_lf_attributes(self) -> None:
        project = Path(__file__).resolve().parents[3]
        lines = set((project / ".gitattributes").read_text(encoding="utf-8").splitlines())
        required = {
            "tools/exhaustive_trace/work_packages.py text eol=lf",
            "tests/tools/exhaustive_trace/test_work_packages.py text eol=lf",
            "evidence/exhaustive-trace/domain-plan-inputs.json text eol=lf",
        }
        self.assertEqual(set(), required - lines)

    def test_task13_artifacts_have_explicit_lf_attributes(self) -> None:
        project = Path(__file__).resolve().parents[3]
        lines = set((project / ".gitattributes").read_text(encoding="utf-8").splitlines())
        required = {
            "tools/exhaustive_trace/recovery.py text eol=lf",
            "tests/tools/exhaustive_trace/test_recovery.py text eol=lf",
            "evidence/exhaustive-trace/recovery.json text eol=lf",
        }
        self.assertEqual(set(), required - lines)

    def test_audit_atomically_publishes_honest_failing_report(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventories = root / "inventories"
            inventories.mkdir()
            source_manifest = (
                Path(__file__).resolve().parents[3]
                / "docs"
                / "reverse-engineering"
                / "exhaustive-trace"
                / "source-manifest.json"
            )
            rows_by_file = fixture_rows()
            for logical_name, spec in INVENTORY_SPECS.items():
                rows = rows_by_file[logical_name]
                (inventories / spec.filename).write_text(
                    "".join(canonical_json(row) for row in rows), encoding="utf-8", newline="\n"
                )
                (inventories / spec.reconciliation_filename).write_text(
                    canonical_json(
                        {
                            "schemaVersion": 1,
                            "candidateCount": len(rows),
                            "normalizedCount": len(rows),
                            "unresolvedCount": 0,
                            "excludedCount": 0,
                            "unaccountedCount": 0,
                        }
                    ),
                    encoding="utf-8",
                    newline="\n",
                )
            bundle = load_inventory_bundle(inventories, source_manifest=source_manifest)
            graph_path = root / "graph.jsonl"
            graph_path.write_text(
                graph_jsonl(build_graph(bundle.rows), bundle), encoding="utf-8", newline="\n"
            )
            output = root / "coverage.json"

            exit_code = main(
                [
                    "audit",
                    "--inventories", str(inventories),
                    "--source-manifest", str(source_manifest),
                    "--graph", str(graph_path),
                    "--output", str(output),
                ]
            )

            self.assertEqual(1, exit_code)
            self.assertTrue(output.is_file())
            graph = load_graph_jsonl(graph_path, bundle=bundle)
            report = load_coverage_json(output, graph=graph, bundle=bundle)
            self.assertIn(
                "FEATURE_REACHABILITY_LEDGER_ABSENT",
                {fatal.rule_id for fatal in report.fatals},
            )

    def test_package_domains_publishes_exact_verified_set_despite_bound_coverage_fatal(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inventories = root / "inventories"
            inventories.mkdir()
            project = Path(__file__).resolve().parents[3]
            source_manifest = project / "docs/reverse-engineering/exhaustive-trace/source-manifest.json"
            rows_by_file = fixture_rows()
            for logical_name, spec in INVENTORY_SPECS.items():
                rows = rows_by_file[logical_name]
                (inventories / spec.filename).write_text(
                    "".join(canonical_json(row) for row in rows), encoding="utf-8", newline="\n"
                )
                (inventories / spec.reconciliation_filename).write_text(
                    canonical_json(
                        {
                            "schemaVersion": 1,
                            "candidateCount": len(rows),
                            "normalizedCount": len(rows),
                            "unresolvedCount": 0,
                            "excludedCount": 0,
                            "unaccountedCount": 0,
                        }
                    ), encoding="utf-8", newline="\n",
                )
            bundle = load_inventory_bundle(inventories, source_manifest=source_manifest)
            graph_path = root / "graph.jsonl"
            graph = build_graph(bundle.rows)
            graph_path.write_text(graph_jsonl(graph, bundle), encoding="utf-8", newline="\n")
            coverage_path = root / "coverage.json"
            from tools.exhaustive_trace.coverage import audit_graph, coverage_json
            report = audit_graph(graph, bundle=bundle)
            coverage_path.write_text(
                coverage_json(report, graph=graph, bundle=bundle), encoding="utf-8", newline="\n"
            )
            config_path = project / "docs/reverse-engineering/exhaustive-trace/domains.json"
            output = root / "domains"

            exit_code = main(
                [
                    "package-domains",
                    "--inventories", str(inventories),
                    "--source-manifest", str(source_manifest),
                    "--graph", str(graph_path),
                    "--coverage", str(coverage_path),
                    "--domains", str(config_path),
                    "--output", str(output),
                ]
            )

            self.assertEqual(0, exit_code)
            self.assertEqual({f"D{number:02d}.json" for number in range(1, 17)}, {p.name for p in output.iterdir()})
            loaded_graph = load_graph_jsonl(graph_path, bundle=bundle)
            loaded_coverage = load_coverage_json(coverage_path, graph=loaded_graph, bundle=bundle)
            config = load_domain_config(config_path, project_root=project)
            package_set = load_domain_packages(
                output, graph=loaded_graph, coverage=loaded_coverage, config=config
            )
            self.assertEqual(6, package_set.conservation["sourceRowCount"])
            self.assertEqual("STRUCTURAL_FATAL", package_set.coverage_gate_status)


if __name__ == "__main__":
    unittest.main()
