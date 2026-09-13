from __future__ import annotations

import unittest
from pathlib import Path

from tools.exhaustive_trace.cli import _write_atomic, main
from tools.exhaustive_trace.graph import load_graph_jsonl

from tests.tools.exhaustive_trace import test_graph as graph_test_support


class GraphCliTests(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = graph_test_support.GraphTests(
            methodName="test_bundle_hash_binds_inventory_reconciliation_and_source_manifest"
        )
        self.fixture.setUp()

    def tearDown(self) -> None:
        self.fixture.tearDown()

    def test_build_graph_command_writes_a_verified_graph_atomically(self) -> None:
        output = self.fixture.root / "output" / "graph.jsonl"
        result = main(
            [
                "build-graph",
                "--inventories",
                str(self.fixture.root),
                "--source-manifest",
                str(self.fixture.source_manifest),
                "--output",
                str(output),
            ]
        )

        self.assertEqual(0, result)
        self.assertTrue(output.is_file())
        loaded = load_graph_jsonl(output, bundle=self.fixture.load())
        self.assertEqual(6, loaded.conservation["sourceRowNodes"])
        self.assertEqual([], list(output.parent.glob(".graph.jsonl.*.tmp")))

    def test_failed_prepublication_verification_preserves_prior_output(self) -> None:
        output = self.fixture.root / "graph.jsonl"
        output.write_bytes(b"prior\n")

        def reject(_path: Path):
            raise ValueError("fixture verification failure")

        with self.assertRaisesRegex(ValueError, "verification"):
            _write_atomic(output, b"replacement\n", verify=reject)

        self.assertEqual(b"prior\n", output.read_bytes())
        self.assertEqual([], list(output.parent.glob(".graph.jsonl.*.tmp")))


if __name__ == "__main__":
    unittest.main()
