import dataclasses
import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.io import canonical_json, sha256_file
from tools.exhaustive_trace.model import (
    ALLOWED_PROVENANCE,
    EvidenceState,
    ImplementationTarget,
    InventoryKind,
    InventoryRow,
    Reachability,
    RecoveryDisposition,
    TraceEdge,
    TraceNode,
    Verdict,
)


class ModelTests(unittest.TestCase):
    def test_states_are_independent_and_default_false(self):
        row = InventoryRow(
            key="PROTOCOL:0x031D",
            inventory=InventoryKind.PROTOCOL,
            name="ResponseStaticInformationBase",
            provenance="ORIGINAL_OBSERVED",
            reachability=Reachability.SHIPPED_REACHABLE,
        )

        self.assertEqual(set(EvidenceState), set(row.states))
        self.assertTrue(all(value is False for value in row.states.values()))
        self.assertFalse(row.states[EvidenceState.RUNTIME_OBSERVED])
        self.assertFalse(row.states[EvidenceState.PLAYER_VISIBLE])

    def test_unknown_provenance_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "unsupported provenance"):
            InventoryRow(
                key="ENTITY:PLANET:1",
                inventory=InventoryKind.ENTITY,
                name="planet",
                provenance="trusted",
                reachability=Reachability.UNKNOWN,
            )

    def test_row_is_immutable(self):
        row = InventoryRow(
            key="ENTITY:SYSTEM:1",
            inventory=InventoryKind.ENTITY,
            name="system",
            provenance="ORIGINAL_MANUAL",
            reachability=Reachability.UNKNOWN,
        )

        with self.assertRaises(dataclasses.FrozenInstanceError):
            row.name = "changed"

    def test_all_contract_enums_are_complete(self):
        self.assertEqual(
            {
                "PROTOCOL",
                "UI",
                "ENTITY",
                "RESOURCE",
                "FUNCTION",
                "AUTHORITY",
            },
            {item.value for item in InventoryKind},
        )
        self.assertEqual(
            {
                "ENUMERATED",
                "STATIC_MAPPED",
                "CODEC_PROVEN",
                "RUNTIME_OBSERVED",
                "PLAYER_VISIBLE",
                "AUTHORITY_PROVEN",
                "PERSISTENCE_PROVEN",
                "BOTH_FACTIONS",
                "INDEPENDENTLY_REVIEWED",
            },
            {item.value for item in EvidenceState},
        )
        self.assertEqual(
            {"SHIPPED_REACHABLE", "SHIPPED_DORMANT", "MANUAL_ONLY", "UNKNOWN"},
            {item.value for item in Reachability},
        )
        self.assertEqual(
            {"PASS", "PARTIAL", "UNSEEN", "BLOCKED", "UNKNOWN"},
            {item.value for item in Verdict},
        )
        self.assertEqual(
            {
                "RECOVERED_ORIGINAL",
                "RECOVERABLE_STATIC",
                "RECOVERABLE_LIVE",
                "SOURCE_CONFLICT",
                "ORIGINAL_SERVER_LOST",
                "ORIGINAL_UNIMPLEMENTED",
                "AUTHORING_REQUIRED",
                "RIGHTS_REVIEW_REQUIRED",
            },
            {item.value for item in RecoveryDisposition},
        )
        self.assertEqual(
            {
                "CONTRACT",
                "SERVER",
                "LEGACY_GATEWAY",
                "NEW_CLIENT",
                "DATABASE",
                "CONTENT_ADMIN",
                "QA",
                "INDEPENDENT_REVIEW",
            },
            {item.value for item in ImplementationTarget},
        )
        self.assertEqual(
            {
                "ORIGINAL_OBSERVED",
                "ORIGINAL_MANUAL",
                "INFERRED",
                "NEW_DESIGN",
                "AUTHORED_PLACEHOLDER",
                "UNKNOWN",
                "LEGACY_CANDIDATE",
            },
            ALLOWED_PROVENANCE,
        )

    def test_canonical_json_is_deterministic(self):
        left = canonical_json({"z": 1, "a": [3, 2, 1]})
        right = canonical_json({"a": [3, 2, 1], "z": 1})

        self.assertEqual(left, right)
        self.assertEqual('{"a":[3,2,1],"z":1}\n', left)

    def test_canonical_json_rejects_lossy_mapping_keys(self):
        with self.assertRaisesRegex(ValueError, "mapping key"):
            canonical_json({1: "integer", "1": "string"})

    def test_canonical_json_rejects_non_finite_numbers(self):
        for value in (float("nan"), float("inf"), float("-inf")):
            with self.subTest(value=value):
                with self.assertRaisesRegex(ValueError, "JSON compliant"):
                    canonical_json({"value": value})

    def test_contract_objects_serialize_deterministically(self):
        row = InventoryRow(
            key="PROTOCOL:0x031D",
            inventory=InventoryKind.PROTOCOL,
            name="ResponseStaticInformationBase",
            provenance="ORIGINAL_OBSERVED",
            reachability=Reachability.SHIPPED_REACHABLE,
        )
        node = TraceNode("PROTOCOL:0x031D", "PROTOCOL", "static base", ("E-001",))
        edge = TraceEdge("UI:OPEN", "TRIGGERS", node.key, ("E-002",))

        serialized = canonical_json({"row": row, "node": node, "edge": edge})
        decoded = json.loads(serialized)

        self.assertEqual("PROTOCOL", decoded["row"]["inventory"])
        self.assertEqual("SHIPPED_REACHABLE", decoded["row"]["reachability"])
        self.assertEqual(9, len(decoded["row"]["states"]))
        self.assertEqual(["E-001"], decoded["node"]["evidence"])
        self.assertEqual("TRIGGERS", decoded["edge"]["relation"])

    def test_state_keys_and_values_must_be_typed(self):
        base = {
            "key": "PROTOCOL:0x031D",
            "inventory": InventoryKind.PROTOCOL,
            "name": "ResponseStaticInformationBase",
            "provenance": "ORIGINAL_OBSERVED",
            "reachability": Reachability.SHIPPED_REACHABLE,
        }

        with self.assertRaisesRegex(ValueError, "EvidenceState"):
            InventoryRow(**base, states={"RUNTIME_OBSERVED": False})
        with self.assertRaisesRegex(ValueError, "boolean"):
            InventoryRow(**base, states={EvidenceState.RUNTIME_OBSERVED: "false"})

    def test_sha256_file_hashes_exact_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.bin"
            path.write_bytes(b"abc")

            self.assertEqual(
                "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
                sha256_file(path),
            )

    def test_trace_node_and_edge_require_evidence(self):
        node = TraceNode(
            key="ENTITY:SYSTEM:1",
            kind="ENTITY",
            label="system",
            evidence=("E-001",),
        )
        edge = TraceEdge(
            source=node.key,
            relation="LOCATED_IN",
            target="ENTITY:GRID_CELL:2106",
            evidence=("E-002",),
        )

        self.assertEqual("ENTITY:SYSTEM:1", node.key)
        self.assertEqual("LOCATED_IN", edge.relation)
        with self.assertRaisesRegex(ValueError, "evidence"):
            TraceEdge(
                source=node.key,
                relation="LOCATED_IN",
                target="ENTITY:GRID_CELL:2106",
                evidence=(),
            )

    def test_trace_evidence_is_deeply_immutable(self):
        source_evidence = ["E-001"]
        node = TraceNode("ENTITY:SYSTEM:1", "ENTITY", "system", source_evidence)
        edge = TraceEdge(node.key, "LOCATED_IN", "ENTITY:GRID_CELL:2106", source_evidence)
        source_evidence.append("E-999")

        self.assertEqual(("E-001",), node.evidence)
        self.assertEqual(("E-001",), edge.evidence)

    def test_trace_evidence_rejects_scalar_and_non_text_values(self):
        for bad_evidence in ("E-001", (123,), {"E-001", "E-002"}):
            with self.subTest(bad_evidence=bad_evidence):
                with self.assertRaisesRegex(ValueError, "evidence"):
                    TraceNode("ENTITY:SYSTEM:1", "ENTITY", "system", bad_evidence)
                with self.assertRaisesRegex(ValueError, "evidence"):
                    TraceEdge(
                        "ENTITY:SYSTEM:1",
                        "LOCATED_IN",
                        "ENTITY:GRID_CELL:2106",
                        bad_evidence,
                    )

    def test_domains_define_exactly_d01_through_d16(self):
        root = Path(__file__).resolve().parents[3]
        path = root / "docs/reverse-engineering/exhaustive-trace/domains.json"
        domains = json.loads(path.read_text(encoding="utf-8"))

        self.assertEqual([f"D{index:02d}" for index in range(1, 17)], [row["id"] for row in domains])
        self.assertEqual(
            [
                "launcher-update-config-data-root",
                "account-auth-lobby-session-character",
                "faction-calendar-rank-office-authority",
                "world-topology-systems-planets-fortresses-grids",
                "fleets-units-ships-troops-fighters-arms",
                "strategy-navigation-warp-search-encounter-fog",
                "bases-institutions-spots-rooms-facilities",
                "commands-orders-suggestions-mail-messenger",
                "grid-spot-unicast-tactical-communication",
                "economy-production-construction-repair-supply-cargo",
                "tactical-entry-field-deployment-combat-retreat",
                "politics-personnel-diplomacy-governance",
                "growth-rewards-ranking-victory-session-end",
                "offline-ai-timeout-disconnect-reconnect-replay",
                "sound-cursor-localization-hud-information",
                "administration-moderation-publication-backup-operations",
            ],
            [row["slug"] for row in domains],
        )


if __name__ == "__main__":
    unittest.main()
