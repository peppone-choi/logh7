from __future__ import annotations

import unittest
import json
import tempfile
from pathlib import Path

from tools.exhaustive_trace.model import EvidenceState, InventoryKind, Reachability
from tools.exhaustive_trace.import_protocol import (
    BodySizeStatus,
    BodyMeasurementKind,
    OwnershipStatus,
    ProtocolCodeSpace,
    ProtocolDirection,
    ProtocolRelationType,
    SiblingDisposition,
    build_protocol_inventory,
    build_protocol_reconciliation,
    load_protocol_evidence_manifest,
    normalize_protocol_inventory,
    normalize_protocol_row,
    protocol_row_to_dict,
)


FIXTURE_SHA = "A" * 64
REPOSITORY_SHA = "B" * 64
IMPLEMENTATION_TARGETS = {
    "CONTRACT",
    "SERVER",
    "LEGACY_GATEWAY",
    "NEW_CLIENT",
    "DATABASE",
    "CONTENT_ADMIN",
    "QA",
    "INDEPENDENT_REVIEW",
}


def complete_export(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "source": {
            "program": "g7mtclient.exe",
            "executableSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
            "language": "x86:LE:32:default",
            "compiler": "windows",
            "imageBase": "00400000",
        },
        "exporter": {
            "class": "ExportExhaustiveProtocol",
            "sha256": FIXTURE_SHA,
            "ghidraRepositorySha256": REPOSITORY_SHA,
        },
        "surfaceSha256": "C" * 64,
        "successMarker": "EXPORT_EXHAUSTIVE_PROTOCOL_OK",
        "functions": {
            "parser": "FUN_004B8B00",
            "dispatcher": "FUN_004BA2B0",
            "outbound": "FUN_004B78A0",
        },
        "parserCases": [],
        "parserConditionCodes": [],
        "dispatcherCases": [],
        "dispatcherConditionCodes": [],
        "outboundCases": [],
        "message32Framework": {
            "status": "REGISTERED_AT_SHIPPED_STARTUP_PATH",
            "containerName": "mpsCTMsg32ParseSystem",
            "messageTypeWidthBits": 16,
            "sendFunction": "0x00403C60",
            "parseFunction": "0x00403E30",
        },
        "message32HandlerFamilies": [],
        "message32HandlerCodes": [],
        "protocolStrings": [],
        "streamContracts": [],
        "functionGraphs": {},
        "functionInstructions": {},
    }
    payload.update(overrides)
    return payload


def complete_row(**overrides: object) -> dict[str, object]:
    row: dict[str, object] = {
        "code": "0x031D",
        "codeSpace": "MESSAGE16",
        "name": "ResponseStaticInformationBase",
        "semanticNameStatus": "DIRECT",
        "direction": {
            "status": "SERVER_TO_CLIENT",
            "evidence": ["raw:parserCases:0"],
        },
        "bodySize": {
            "status": "UNKNOWN",
            "measurementKind": "UNKNOWN",
            "bytes": None,
            "evidence": ["raw:parserCases:0"],
        },
        "siblings": {
            "request": {
                "disposition": "OBSERVED",
                "codes": ["0x031C"],
                "evidence": ["raw:outboundCases:0"],
            },
            "response": {
                "disposition": "OBSERVED",
                "codes": ["0x031D"],
                "evidence": ["raw:dispatcherCases:0"],
            },
            "notify": {
                "disposition": "ABSENT_IN_EXPORTED_SURFACE",
                "codes": [],
                "evidence": ["raw:surface:sha256:fixture"],
            },
        },
        "relations": [
            {
                "type": "PARSES",
                "function": "FUN_004B8B00",
                "evidence": ["raw:parserCases:0"],
            },
            {
                "type": "DISPATCHES",
                "function": "FUN_004BA2B0",
                "evidence": ["raw:dispatcherCases:0"],
            },
        ],
        "ownership": {
            "parser": {
                "status": "PROVEN",
                "functions": ["FUN_004B8B00"],
                "evidence": ["raw:parserCases:0"],
            },
            "serializer": {
                "status": "UNKNOWN",
                "functions": [],
                "evidence": ["raw:surface:sha256:fixture"],
            },
            "dispatcher": {
                "status": "PROVEN",
                "functions": ["FUN_004BA2B0"],
                "evidence": ["raw:dispatcherCases:0"],
            },
        },
        "evidence": ["raw:parserCases:0", "raw:dispatcherCases:0"],
        "provenance": "ORIGINAL_OBSERVED",
        "reachability": "UNKNOWN",
        "recoveryDisposition": "RECOVERABLE_STATIC",
    }
    row.update(overrides)
    return row


class ProtocolRowContractTests(unittest.TestCase):
    def test_normalized_protocol_row_requires_every_implementation_target(self) -> None:
        normalized = protocol_row_to_dict(normalize_protocol_row(complete_row()))

        dispositions = normalized["implementationDisposition"]
        self.assertEqual(set(dispositions), IMPLEMENTATION_TARGETS)
        for target in IMPLEMENTATION_TARGETS:
            with self.subTest(target=target):
                self.assertEqual(dispositions[target]["status"], "REQUIRED")
                self.assertTrue(dispositions[target]["reason"])
                self.assertEqual(
                    dispositions[target]["evidence"],
                    [f"goal:implementation-layer:{target}"],
                )

    def test_protocol_row_needs_direction(self) -> None:
        raw = complete_row()
        del raw["direction"]
        with self.assertRaisesRegex(ValueError, "direction"):
            normalize_protocol_row(raw)

    def test_protocol_row_needs_body_size_status(self) -> None:
        raw = complete_row()
        raw["bodySize"] = {
            "measurementKind": "WIRE_BODY",
            "bytes": 4,
            "evidence": ["raw:parserCases:0"],
        }
        with self.assertRaisesRegex(ValueError, "bodySize.status"):
            normalize_protocol_row(raw)

    def test_protocol_row_needs_all_sibling_dispositions(self) -> None:
        raw = complete_row()
        del raw["siblings"]["notify"]
        with self.assertRaisesRegex(ValueError, "siblings"):
            normalize_protocol_row(raw)

    def test_protocol_row_needs_evidence(self) -> None:
        with self.assertRaisesRegex(ValueError, "evidence"):
            normalize_protocol_row(complete_row(evidence=[]))

    def test_protocol_row_relations_are_typed(self) -> None:
        raw = complete_row()
        raw["relations"] = [
            {
                "type": "HANDLES",
                "function": "FUN_004BA2B0",
                "evidence": ["raw:dispatcherCases:0"],
            }
        ]
        with self.assertRaisesRegex(ValueError, "relation type"):
            normalize_protocol_row(raw)

    def test_unknown_semantics_are_retained_explicitly(self) -> None:
        raw = complete_row(
            code="0x0C05",
            name=None,
            semanticNameStatus="UNKNOWN",
            direction={
                "status": "SERVER_TO_CLIENT",
                "evidence": ["raw:parserCases:1"],
            },
            bodySize={
                "status": "UNKNOWN",
                "measurementKind": "UNKNOWN",
                "bytes": None,
                "evidence": ["raw:parserCases:1"],
            },
            reachability="UNKNOWN",
        )
        row = normalize_protocol_row(raw)
        self.assertEqual(row.row.name, "UnknownProtocol_0x0C05")
        self.assertEqual(row.row.reachability, Reachability.UNKNOWN)
        self.assertEqual(row.body_size.status, BodySizeStatus.UNKNOWN)

    def test_normalized_row_contains_core_inventory_contract(self) -> None:
        row = normalize_protocol_row(complete_row())
        self.assertEqual(row.row.key, "PROTOCOL:MESSAGE16:0x031D")
        self.assertEqual(row.code_space, ProtocolCodeSpace.MESSAGE16)
        self.assertEqual(row.row.inventory, InventoryKind.PROTOCOL)
        self.assertEqual(row.direction.status, ProtocolDirection.SERVER_TO_CLIENT)
        self.assertEqual(row.relations[0].type, ProtocolRelationType.PARSES)
        self.assertEqual(
            row.siblings["request"].disposition, SiblingDisposition.OBSERVED
        )
        self.assertTrue(row.row.states[EvidenceState.ENUMERATED])
        self.assertTrue(
            all(
                row.row.states[state] is False
                for state in EvidenceState
                if state is not EvidenceState.ENUMERATED
            )
        )
        self.assertEqual(row.body_size.measurement_kind, BodyMeasurementKind.UNKNOWN)
        self.assertEqual(row.ownership["parser"].status, OwnershipStatus.PROVEN)
        serialized = protocol_row_to_dict(row)
        self.assertEqual(serialized["inventory"], "PROTOCOL")
        self.assertEqual(serialized["recoveryDisposition"], "RECOVERABLE_STATIC")
        self.assertEqual(serialized["key"], "PROTOCOL:MESSAGE16:0x031D")

    def test_duplicate_protocol_codes_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "duplicate protocol code"):
            normalize_protocol_inventory([complete_row(), complete_row()])

    def test_same_numeric_code_in_distinct_code_spaces_is_preserved(self) -> None:
        rows = normalize_protocol_inventory(
            [complete_row(), complete_row(codeSpace="MESSAGE32")]
        )
        self.assertEqual(
            {row.row.key for row in rows},
            {"PROTOCOL:MESSAGE16:0x031D", "PROTOCOL:MESSAGE32:0x031D"},
        )


class ProtocolExportAggregationTests(unittest.TestCase):
    def test_union_keeps_parser_dispatcher_and_outbound_only_codes(self) -> None:
        raw_export = complete_export(
            parserCases=[
                {
                    "codes": ["0x031D"],
                    "allocationSize": {"status": "FIXED", "bytes": 0x520C},
                    "helperCalls": ["FUN_004142E0"],
                },
                {
                    "codes": ["0x0C05"],
                    "allocationSize": {"status": "UNKNOWN", "bytes": None},
                    "helperCalls": [],
                },
            ],
            dispatcherCases=[
                {
                    "codes": ["0x031D"],
                    "messageName": "ResponseStaticInformationBase",
                    "destinationExpressions": ["local_18 + 0x3f5ae8"],
                    "helperCalls": [],
                }
            ],
            outboundCases=[
                {
                    "localKind": "0x001A",
                    "requestCode": "0x031C",
                    "expectedResponseCode": "0x031D",
                }
            ],
        )
        rows = build_protocol_inventory(raw_export)
        by_code = {row.code: row for row in rows}
        self.assertEqual(set(by_code), {"0x031C", "0x031D", "0x0C05"})
        self.assertEqual(
            by_code["0x031C"].direction.status, ProtocolDirection.CLIENT_TO_SERVER
        )
        self.assertEqual(
            by_code["0x031D"].direction.status, ProtocolDirection.SERVER_TO_CLIENT
        )
        self.assertEqual(by_code["0x0C05"].row.name, "UnknownProtocol_0x0C05")
        self.assertEqual(
            by_code["0x031C"].siblings["response"].codes, ("0x031D",)
        )
        self.assertEqual(
            {relation.type for relation in by_code["0x031D"].relations},
            {ProtocolRelationType.PARSES, ProtocolRelationType.DISPATCHES},
        )
        self.assertEqual(by_code["0x031D"].body_size.status, BodySizeStatus.UNKNOWN)
        self.assertIn(
            ("PARSER_ALLOCATION_SIZE", "21004"),
            {(fact.kind, fact.value) for fact in by_code["0x031D"].facts},
        )

    def test_name_prefix_does_not_infer_notify_sibling(self) -> None:
        raw_export = complete_export(
            parserCases=[
                {
                    "codes": ["0x0423"],
                    "allocationSize": {"status": "UNKNOWN", "bytes": None},
                    "helperCalls": [],
                }
            ],
            dispatcherCases=[
                {
                    "codes": ["0x0423"],
                    "messageName": "NotifyMovedShip",
                    "destinationExpressions": [],
                    "helperCalls": [],
                }
            ],
            outboundCases=[],
        )
        row = build_protocol_inventory(raw_export)[0]
        self.assertEqual(
            row.siblings["notify"].disposition, SiblingDisposition.UNKNOWN
        )

    def test_condition_codes_are_conserved_as_typed_rows(self) -> None:
        raw_export = complete_export(
            parserConditionCodes=[
                {"code": "0x0C05", "condition": "param_1 == 0xc05", "status": "DIRECT_EQUALITY"}
            ],
            dispatcherConditionCodes=[
                {"code": "0x0D01", "condition": "local_3c == 0xd01", "status": "DIRECT_EQUALITY"}
            ],
        )
        rows = {row.code: row for row in build_protocol_inventory(raw_export)}
        self.assertEqual(set(rows), {"0x0C05", "0x0D01"})
        self.assertEqual(rows["0x0C05"].relations[0].type, ProtocolRelationType.PARSES)
        self.assertEqual(rows["0x0D01"].relations[0].type, ProtocolRelationType.DISPATCHES)

    def test_hash_bound_export_metadata_is_fail_closed(self) -> None:
        raw_export = complete_export()
        raw_export["exporter"]["sha256"] = "D" * 64
        with self.assertRaisesRegex(ValueError, "exporter hash"):
            build_protocol_inventory(raw_export, expected_exporter_sha256=FIXTURE_SHA)

    def test_protocol_evidence_manifest_rejects_raw_tampering(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw_path = root / "raw.json"
            exporter_path = root / "Exporter.java"
            raw_path.write_text("original", encoding="utf-8")
            exporter_path.write_text("exporter", encoding="utf-8")
            from tools.exhaustive_trace.io import sha256_file

            manifest_path = root / "manifest.json"
            manifest_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "clientSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
                        "ghidraRepositorySha256": REPOSITORY_SHA,
                        "raw": {"path": str(raw_path), "sha256": sha256_file(raw_path)},
                        "exporter": {"path": str(exporter_path), "sha256": sha256_file(exporter_path)},
                    }
                ),
                encoding="utf-8",
            )
            raw_path.write_text("tampered", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "raw hash mismatch"):
                load_protocol_evidence_manifest(manifest_path)

    def test_reconciliation_conserves_every_raw_candidate(self) -> None:
        raw_export = complete_export(
            parserConditionCodes=[
                {"code": "0x031D", "condition": "param_1 == 0x31d", "status": "DIRECT_EQUALITY"}
            ],
            protocolStrings=[
                {"address": "00500000", "value": "ResponseStaticInformationBase", "status": "NAME_CANDIDATE", "xrefs": []},
                {"address": "00500020", "value": "RequestOrphan", "status": "NAME_CANDIDATE", "xrefs": []},
            ],
            streamContracts=[
                {"address": "00500100", "message": "ResponseStaticInformationBase", "method": "Input", "field": "m_items", "maxCountOrBytes": 16, "measurementKind": "ARRAY_CAP", "xrefs": []}
            ],
        )
        rows = build_protocol_inventory(raw_export)
        reconciliation = build_protocol_reconciliation(raw_export, rows)
        self.assertEqual(reconciliation["candidateCount"], 4)
        self.assertEqual(reconciliation["accountedCount"], 4)
        self.assertEqual(reconciliation["unaccountedCount"], 0)
        self.assertEqual(
            {candidate["status"] for candidate in reconciliation["candidates"]},
            {"NORMALIZED", "UNRESOLVED"},
        )

    def test_stream_name_match_does_not_prove_serializer_identity(self) -> None:
        raw_export = complete_export(
            dispatcherCases=[
                {
                    "codes": ["0x031D"],
                    "messageName": "ResponseStaticInformationBase",
                    "destinationExpressions": [],
                    "helperCalls": [],
                }
            ],
            streamContracts=[
                {
                    "address": "00500100",
                    "message": "ResponseStaticInformationBase",
                    "method": "output_to_stream",
                    "field": "m_items",
                    "maxCountOrBytes": 16,
                    "maxExpression": "16",
                    "limitStatus": "FIXED_CAP",
                    "measurementKind": "ARRAY_CAP",
                    "xrefs": [
                        {
                            "functionStatus": "DEFINED_FUNCTION",
                            "function": "FUN_00510000",
                        }
                    ],
                }
            ],
        )
        row = build_protocol_inventory(raw_export)[0]
        self.assertNotIn(ProtocolRelationType.SERIALIZES, {relation.type for relation in row.relations})
        self.assertEqual(row.ownership["serializer"].status, OwnershipStatus.UNKNOWN)
        self.assertEqual(row.body_size.status, BodySizeStatus.UNKNOWN)
        self.assertIn("STREAM_ARRAY_CAP", {fact.kind for fact in row.facts})

    def test_conflicting_direct_names_fail_closed(self) -> None:
        raw_export = complete_export(
            parserCases=[
                {
                    "codes": ["0x031D"],
                    "allocationSize": {"status": "UNKNOWN", "bytes": None},
                    "helperCalls": [],
                    "messageName": "ParserName",
                }
            ],
            dispatcherCases=[
                {
                    "codes": ["0x031D"],
                    "messageName": "DispatcherName",
                    "destinationExpressions": [],
                    "helperCalls": [],
                }
            ],
        )
        row = build_protocol_inventory(raw_export)[0]
        self.assertEqual(row.semantic_name_status, "CANDIDATE")
        self.assertEqual(row.recovery_disposition.value, "SOURCE_CONFLICT")
        self.assertIn("SEMANTIC_NAME_CONFLICT", {fact.kind for fact in row.facts})

    def test_unknown_export_collection_is_rejected(self) -> None:
        raw_export = complete_export(message32Cases=[])
        with self.assertRaisesRegex(ValueError, "top-level fields"):
            build_protocol_inventory(raw_export)

    def test_message32_registry_codes_are_independent_rows(self) -> None:
        raw_export = complete_export(
            message32HandlerFamilies=[
                {
                    "registrationCallsite": "0x004AD300",
                    "registryCallsite": "0x004AD30E",
                    "baseCode": "0x0700",
                    "count": 4,
                    "factory": "0x0043E590",
                    "constructor": "0x0043E600",
                    "vtable": "0x0066CC90",
                    "clientToServerLookup": "0x0043EC10",
                    "serverToClientLookup": "0x0043EC70",
                }
            ],
            message32HandlerCodes=[
                {
                    "code": "0x0700",
                    "familyIndex": 0,
                    "offset": 0,
                    "direction": "BIDIRECTIONAL",
                    "clientToServerRegistered": True,
                    "serverToClientRegistered": True,
                    "clientToServerSlot": 1,
                    "serverToClientSlot": 5,
                    "clientToServerAssignment": "0043e700",
                    "serverToClientAssignment": "0043e710",
                    "factory": "0x0043E590",
                    "constructor": "0x0043E600",
                    "vtable": "0x0066CC90",
                },
                {
                    "code": "0x0702",
                    "familyIndex": 0,
                    "offset": 2,
                    "direction": "SERVER_TO_CLIENT",
                    "clientToServerRegistered": False,
                    "serverToClientRegistered": True,
                    "serverToClientSlot": 7,
                    "serverToClientAssignment": "0043e720",
                    "factory": "0x0043E590",
                    "constructor": "0x0043E600",
                    "vtable": "0x0066CC90",
                },
            ],
        )
        rows = build_protocol_inventory(raw_export)
        self.assertEqual(
            {row.row.key for row in rows},
            {"PROTOCOL:MESSAGE32:0x0700", "PROTOCOL:MESSAGE32:0x0702"},
        )
        by_key = {row.row.key: row for row in rows}
        self.assertEqual(
            by_key["PROTOCOL:MESSAGE32:0x0700"].direction.status,
            ProtocolDirection.BIDIRECTIONAL,
        )

    def test_message32_missing_direction_assignment_is_rejected(self) -> None:
        raw_export = complete_export(
            message32HandlerFamilies=[
                {
                    "registrationCallsite": "0x004AD300", "registryCallsite": "0x004AD30E",
                    "baseCode": "0x0700", "count": 1, "factory": "0x0043E590",
                    "constructor": "0x0043E600", "vtable": "0x0066CC90",
                    "clientToServerLookup": "0x0043EC10", "serverToClientLookup": "0x0043EC70",
                }
            ],
            message32HandlerCodes=[
                {
                    "code": "0x0700", "familyIndex": 0, "offset": 0,
                    "direction": "CLIENT_TO_SERVER", "clientToServerRegistered": True,
                    "serverToClientRegistered": False, "clientToServerSlot": 1,
                    "factory": "0x0043E590", "constructor": "0x0043E600", "vtable": "0x0066CC90",
                }
            ],
        )
        with self.assertRaisesRegex(ValueError, "assignment evidence"):
            build_protocol_inventory(raw_export)

    def test_message32_invalid_registration_chain_is_rejected(self) -> None:
        raw_export = complete_export(
            message32HandlerFamilies=[
                {
                    "registrationCallsite": "0x004AD300", "registryCallsite": "0x004AD30E",
                    "baseCode": "0x0700", "count": 1, "factory": "0x0043E590",
                    "constructor": "0x0043E600", "vtable": "0x0066CC90",
                    "clientToServerLookup": "0x0043EC10", "serverToClientLookup": "0x0043EC70",
                }
            ],
            message32HandlerCodes=[
                {
                    "code": "0x0700", "familyIndex": 0, "offset": 0,
                    "direction": "CLIENT_TO_SERVER", "clientToServerRegistered": True,
                    "serverToClientRegistered": False, "clientToServerSlot": 1,
                    "clientToServerAssignment": "0043e700", "factory": "0xDEADBEEF",
                    "constructor": "0x0043E600", "vtable": "0x0066CC90",
                }
            ],
        )
        with self.assertRaisesRegex(ValueError, "registration chain"):
            build_protocol_inventory(raw_export)

    def test_message32_missing_startup_registration_evidence_is_rejected(self) -> None:
        raw_export = complete_export(
            message32HandlerFamilies=[
                {
                    "baseCode": "0x0700", "count": 1, "factory": "0x0043E590",
                    "constructor": "0x0043E600", "vtable": "0x0066CC90",
                    "clientToServerLookup": "0x0043EC10", "serverToClientLookup": "0x0043EC70",
                }
            ],
            message32HandlerCodes=[],
        )
        with self.assertRaisesRegex(ValueError, "startup registration evidence"):
            build_protocol_inventory(raw_export)

    def test_jsonl_serialization_is_deterministic(self) -> None:
        raw_export = complete_export(
            parserConditionCodes=[
                {"code": "0x031D", "condition": "param_1 == 0x31d", "status": "DIRECT_EQUALITY"}
            ]
        )
        first = [protocol_row_to_dict(row) for row in build_protocol_inventory(raw_export)]
        second = [protocol_row_to_dict(row) for row in build_protocol_inventory(raw_export)]
        self.assertEqual(first, second)


if __name__ == "__main__":
    unittest.main()
