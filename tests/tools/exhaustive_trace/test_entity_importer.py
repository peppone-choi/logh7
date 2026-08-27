from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from tools.exhaustive_trace.import_entities import (
    build_entity_inventory,
    build_entity_reconciliation,
    normalize_entity_inventory,
)


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


def unknown_section() -> dict[str, object]:
    return {"status": "UNKNOWN", "evidence": ["raw:unjoined"]}


def complete_entity_candidate(**overrides: object) -> dict[str, object]:
    candidate: dict[str, object] = {
        "candidateId": "ENTITY_TYPE:SYSTEM",
        "rowKind": "ENTITY_TYPE",
        "entityType": "SYSTEM",
        "name": "star system",
        "stateBearing": True,
        "provenance": "ORIGINAL_MANUAL",
        "reachability": "MANUAL_ONLY",
        "recoveryDisposition": "RECOVERABLE_LIVE",
        "idNamespace": {
            "status": "UNKNOWN",
            "name": None,
            "fields": [],
            "widthBits": None,
            "signedness": "UNKNOWN",
            "uniquenessScope": "UNKNOWN",
            "comparisonFunctions": [],
            "nullSemantics": "UNKNOWN",
            "evidence": ["raw:unjoined"],
        },
        "relations": {
            name: {"status": "UNKNOWN", "edges": [], "evidence": ["raw:unjoined"]}
            for name in ("parent", "owner", "faction", "location", "visibility")
        },
        "lifecycle": {
            name: {"status": "UNKNOWN", "operations": [], "evidence": ["raw:unjoined"]}
            for name in (
                "definition",
                "create",
                "select",
                "query",
                "update",
                "transfer",
                "destroy",
                "terminal",
            )
        },
        "wireProjections": {
            name: {"status": "UNKNOWN", "protocolKeys": [], "fieldKeys": [], "evidence": ["raw:unjoined"]}
            for name in ("static", "dynamic", "notification")
        },
        "clientRepresentation": {
            "cache": {"status": "UNKNOWN", "writers": [], "readers": [], "evidence": ["raw:unjoined"]},
            "renderer": {"status": "UNKNOWN", "consumers": [], "evidence": ["raw:unjoined"]},
        },
        "authority": unknown_section(),
        "persistence": unknown_section(),
        "reconnectReplay": unknown_section(),
        "implementationDisposition": {
            target: {"status": "REQUIRED", "reason": None, "evidence": ["goal:implementation-layer"]}
            for target in IMPLEMENTATION_TARGETS
        },
        "catalogCardinality": [],
        "firstMissingBoundary": "ID_NAMESPACE",
        "evidence": ["manual:entity:SYSTEM"],
    }
    candidate.update(overrides)
    return candidate


def complete_record_candidate(**overrides: object) -> dict[str, object]:
    candidate = complete_entity_candidate(
        candidateId="RECORD_SCHEMA:StaticInformationBase",
        rowKind="RECORD_TYPE",
        entityType="BASE",
        recordType="StaticInformationBase",
        name="StaticInformationBase",
        provenance="ORIGINAL_OBSERVED",
        reachability="UNKNOWN",
        recoveryDisposition="RECOVERABLE_STATIC",
        idNamespace={
            "status": "PROVEN",
            "name": "BASE_ID",
            "fields": ["FIELD:StaticInformationBase:id"],
            "widthBits": 32,
            "signedness": "UNSIGNED",
            "uniquenessScope": "GLOBAL",
            "comparisonFunctions": ["FUN_004C4540"],
            "nullSemantics": "ZERO_RESERVED",
            "evidence": ["ghidra:id-compare:0x004C4540"],
        },
        firstMissingBoundary="PARENT_RELATION",
    )
    candidate["layout"] = {
        "status": "PROVEN",
        "layoutSpace": "CACHE",
        "strideBytes": 60,
        "recordCap": 350,
        "fields": [
            {
                "key": "FIELD:StaticInformationBase:id",
                "ordinal": 0,
                "name": "id",
                "semanticNameStatus": "PROVEN",
                "status": "PROVEN",
                "offsetBytes": 0,
                "widthBits": 32,
                "scalarKind": "INTEGER",
                "signedness": "UNSIGNED",
                "arrayCap": None,
                "aliasGroup": None,
                "reads": ["FUN_004C4540"],
                "writes": ["FUN_004142E0"],
                "comparisons": ["FUN_004C4540"],
                "evidence": ["ghidra:field:id"],
            }
        ],
        "evidence": ["ghidra:record-schema:StaticInformationBase"],
    }
    candidate.update(overrides)
    return candidate


def complete_export(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "source": {
            "program": "g7mtclient.exe",
            "executableSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
            "language": "x86:LE:32:default",
            "compiler": "windows",
            "imageBase": "00400000",
            "manualTextSha256": "A" * 64,
            "manualPdfSha256": "1" * 64,
            "manualPageXmlSha256": "2" * 64,
            "catalogCandidateSha256": "B" * 64,
            "protocolRawSha256": "F" * 64,
        },
        "exporter": {
            "class": "ExportExhaustiveRecords",
            "sha256": "C" * 64,
            "ghidraRepositorySha256": "D" * 64,
        },
        "surfaceSha256": "E" * 64,
        "successMarker": "EXPORT_EXHAUSTIVE_RECORDS_OK",
        "audit": {
            "scope": "COMPILED_RECORD_ANCHORS",
            "capsArePopulationCounts": False,
            "catalogParentIsRuntimeJoin": False,
            "authorityPersistenceCovered": False,
            "limitations": ["original live populations remain unknown"],
        },
        "conservation": {"streamContracts": 410, "recordFamilies": 166, "familyFields": 230},
        "entityTypeCandidates": [],
        "recordSchemaCandidates": [],
        "recordFieldCandidates": [],
        "recordParserCandidates": [],
        "recordRegistryCandidates": [],
        "strideCapCandidates": [],
        "idComparisonCandidates": [],
        "relationshipCandidates": [],
        "lifecycleCandidates": [],
        "wireProjectionCandidates": [],
        "cacheConsumerCandidates": [],
        "rendererConsumerCandidates": [],
        "catalogCandidates": [],
        "manualEntityCandidates": [],
        "labelCandidates": [],
    }
    payload.update(overrides)
    return payload


class EntityImporterTests(unittest.TestCase):
    def test_state_bearing_entity_requires_every_identity_relation_and_lifecycle_slot(self) -> None:
        for field in ("idNamespace", "relations", "lifecycle", "wireProjections"):
            with self.subTest(field=field):
                candidate = complete_entity_candidate()
                del candidate[field]
                with self.assertRaisesRegex(ValueError, field):
                    build_entity_inventory(
                        complete_export(entityTypeCandidates=[candidate])
                    )

    def test_relation_and_lifecycle_exact_sets_are_fail_closed(self) -> None:
        candidate = complete_entity_candidate()
        del candidate["relations"]["location"]
        with self.assertRaisesRegex(ValueError, "relations"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

        candidate = complete_entity_candidate()
        del candidate["lifecycle"]["terminal"]
        with self.assertRaisesRegex(ValueError, "lifecycle"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_unknown_relation_cannot_claim_an_edge(self) -> None:
        candidate = complete_entity_candidate()
        candidate["relations"]["parent"]["edges"] = [
            {
                "relation": "CATALOG_PARENT",
                "targetEntityType": "SYSTEM",
                "sourceField": "parentName",
                "targetNamespace": "NAME",
                "joinFunction": None,
                "evidence": ["catalog:name-match"],
            }
        ]
        with self.assertRaisesRegex(ValueError, "unknown relation"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_proven_relation_requires_typed_join_evidence(self) -> None:
        candidate = complete_entity_candidate()
        candidate["relations"]["location"] = {
            "status": "PROVEN",
            "edges": [],
            "evidence": ["ghidra:location"],
        }
        with self.assertRaisesRegex(ValueError, "proven relation"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_proven_id_namespace_requires_key_consumer_and_null_semantics(self) -> None:
        candidate = complete_record_candidate()
        candidate["idNamespace"]["comparisonFunctions"] = []
        with self.assertRaisesRegex(ValueError, "ID namespace"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

    def test_proven_field_requires_offset_width_signedness_and_evidence(self) -> None:
        for field_name in ("offsetBytes", "widthBits", "signedness", "evidence"):
            with self.subTest(field=field_name):
                candidate = complete_record_candidate()
                field = candidate["layout"]["fields"][0]
                if field_name == "evidence":
                    field[field_name] = []
                else:
                    field[field_name] = None
                with self.assertRaisesRegex(ValueError, "proven field"):
                    build_entity_inventory(
                        complete_export(recordSchemaCandidates=[candidate])
                    )

    def test_unknown_field_cannot_claim_semantics_or_layout(self) -> None:
        candidate = complete_record_candidate()
        field = candidate["layout"]["fields"][0]
        field.update(
            status="UNKNOWN",
            name="parentId",
            semanticNameStatus="PROVEN",
            offsetBytes=6,
            widthBits=16,
        )
        with self.assertRaisesRegex(ValueError, "unknown field"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

    def test_field_must_fit_stride_and_non_alias_fields_cannot_overlap(self) -> None:
        candidate = complete_record_candidate()
        candidate["layout"]["fields"][0]["offsetBytes"] = 59
        with self.assertRaisesRegex(ValueError, "stride"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

        candidate = complete_record_candidate()
        duplicate = copy.deepcopy(candidate["layout"]["fields"][0])
        duplicate["key"] = "FIELD:StaticInformationBase:field01"
        duplicate["name"] = "field01"
        candidate["layout"]["fields"].append(duplicate)
        with self.assertRaisesRegex(ValueError, "overlap"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

    def test_caps_are_not_promoted_to_catalog_cardinality(self) -> None:
        candidate = complete_record_candidate()
        row = build_entity_inventory(
            complete_export(recordSchemaCandidates=[candidate])
        )[0]
        self.assertEqual(row.layout.values["recordCap"], 350)
        self.assertEqual(row.catalog_cardinality, ())

    def test_catalog_sources_and_membership_dispositions_remain_distinct(self) -> None:
        candidate = complete_entity_candidate(
            catalogCardinality=[
                {
                    "sourceId": "legacy-verified-galaxy",
                    "status": "LEGACY_CANDIDATE",
                    "count": 80,
                    "membershipStatus": "SOURCE_CONFLICT",
                    "evidence": ["legacy-ledger:systems:80"],
                },
                {
                    "sourceId": "extended-playability-galaxy",
                    "status": "NEW_DESIGN",
                    "count": 85,
                    "membershipStatus": "PROVISIONAL",
                    "evidence": ["legacy-ledger:systems:85"],
                },
            ]
        )
        row = build_entity_inventory(
            complete_export(entityTypeCandidates=[candidate])
        )[0]
        self.assertEqual([item["count"] for item in row.catalog_cardinality], [80, 85])
        self.assertEqual(
            [item["status"] for item in row.catalog_cardinality],
            ["LEGACY_CANDIDATE", "NEW_DESIGN"],
        )

    def test_original_manual_count_requires_every_page_bound_member(self) -> None:
        candidate = complete_entity_candidate(
            catalogCardinality=[
                {
                    "sourceId": "official-web-manual-2004-10-07",
                    "status": "ORIGINAL_MANUAL",
                    "count": 2,
                    "membershipStatus": "PLAYABLE_FACTIONS_ONLY",
                    "members": [
                        {
                            "term": "銀河帝国",
                            "pdfPage": 5,
                            "pdfSha256": "1" * 64,
                            "pageXmlSha256": "2" * 64,
                        }
                    ],
                    "evidence": ["manual:page-bound-member"],
                }
            ]
        )
        with self.assertRaisesRegex(ValueError, "manual count.*member"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_implementation_disposition_requires_exact_targets_and_rejects_complete(self) -> None:
        candidate = complete_entity_candidate()
        del candidate["implementationDisposition"]["DATABASE"]
        with self.assertRaisesRegex(ValueError, "implementation"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

        candidate = complete_entity_candidate()
        candidate["implementationDisposition"]["SERVER"]["status"] = "COMPLETE"
        with self.assertRaisesRegex(ValueError, "implementation"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_reachable_entity_requires_query_projection_and_representation_callpath(self) -> None:
        candidate = complete_entity_candidate(
            reachability="SHIPPED_REACHABLE",
            reachabilityEvidence=["callpath:manual-only-is-not-enough"],
        )
        with self.assertRaisesRegex(ValueError, "reachable"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_manual_only_conflicts_with_a_shipped_record_for_the_same_type(self) -> None:
        entity = complete_entity_candidate(
            candidateId="MANUAL_ENTITY:SYSTEM",
            provenance="ORIGINAL_MANUAL",
            reachability="MANUAL_ONLY",
        )
        record = complete_record_candidate(
            candidateId="RECORD_SCHEMA:StaticSystem",
            entityType="SYSTEM",
            recordType="StaticSystem",
        )
        with self.assertRaisesRegex(ValueError, "MANUAL_ONLY.*record"):
            build_entity_inventory(
                complete_export(
                    manualEntityCandidates=[entity],
                    recordSchemaCandidates=[record],
                )
            )

    def test_complete_export_requires_the_exact_entity_type_universe(self) -> None:
        with self.assertRaisesRegex(ValueError, "entity type universe"):
            build_entity_inventory(
                complete_export(entityTypeCandidates=[complete_entity_candidate()]),
                require_complete_entity_types=True,
            )

    def test_proven_relation_uses_slot_verb_and_a_layout_field(self) -> None:
        candidate = complete_record_candidate()
        candidate["relations"]["location"] = {
            "status": "PROVEN",
            "edges": [
                {
                    "relation": "NAME_MATCH",
                    "targetEntityType": "SYSTEM",
                    "sourceField": "FIELD:StaticInformationBase:id",
                    "targetNamespace": "SYSTEM_ID",
                    "joinFunction": "FUN_004C4540",
                    "evidence": ["ghidra:join"],
                }
            ],
            "evidence": ["ghidra:join"],
        }
        with self.assertRaisesRegex(ValueError, "relation verb"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

        candidate["relations"]["location"]["edges"][0].update(
            relation="LOCATION_ID_JOIN", sourceField="FIELD:StaticInformationBase:missing"
        )
        with self.assertRaisesRegex(ValueError, "sourceField"):
            build_entity_inventory(complete_export(recordSchemaCandidates=[candidate]))

    def test_projection_keys_must_resolve_to_protocol_and_layout_candidates(self) -> None:
        candidate = complete_record_candidate()
        candidate["wireProjections"]["static"] = {
            "status": "PROVEN",
            "protocolKeys": ["WIRE_PARSER:0X9999"],
            "fieldKeys": ["FIELD:StaticInformationBase:id"],
            "evidence": ["ghidra:projection"],
        }
        wire = [{"candidateId": "WIRE_PARSER:0X0001", "status": "UNJOINED"}]
        with self.assertRaisesRegex(ValueError, "protocol key"):
            build_entity_inventory(
                complete_export(
                    recordSchemaCandidates=[candidate], wireProjectionCandidates=wire
                )
            )

        candidate["wireProjections"]["static"].update(
            protocolKeys=["WIRE_PARSER:0X0001"],
            fieldKeys=["FIELD:StaticInformationBase:missing"],
        )
        with self.assertRaisesRegex(ValueError, "projection field"):
            build_entity_inventory(
                complete_export(
                    recordSchemaCandidates=[candidate], wireProjectionCandidates=wire
                )
            )

    def test_runtime_pointer_cannot_be_a_stable_entity_identity(self) -> None:
        candidate = complete_entity_candidate(entityType="0x095D6EAC")
        with self.assertRaisesRegex(ValueError, "entity type"):
            build_entity_inventory(complete_export(entityTypeCandidates=[candidate]))

    def test_reconciliation_conserves_all_unjoined_candidates(self) -> None:
        raw = complete_export(
            entityTypeCandidates=[complete_entity_candidate()],
            recordParserCandidates=[
                {
                    "candidateId": "RECORD_PARSER:004142E0",
                    "function": "FUN_004142E0",
                    "status": "UNJOINED",
                }
            ],
        )
        rows = build_entity_inventory(raw)
        reconciliation = build_entity_reconciliation(raw, rows)
        self.assertEqual(reconciliation["candidateCount"], 2)
        self.assertEqual(reconciliation["normalizedCount"], 1)
        self.assertEqual(reconciliation["unresolvedCount"], 1)
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_unknown_collection_and_duplicate_candidate_ids_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "top-level"):
            build_entity_inventory(complete_export(ghostRecords=[]))

        duplicate = complete_entity_candidate()
        with self.assertRaisesRegex(ValueError, "duplicate.*candidate"):
            build_entity_inventory(
                complete_export(
                    entityTypeCandidates=[duplicate],
                    manualEntityCandidates=[copy.deepcopy(duplicate)],
                )
            )

    def test_normalized_inventory_is_deterministic(self) -> None:
        first = complete_entity_candidate()
        second = complete_entity_candidate(
            candidateId="ENTITY_TYPE:PLANET",
            entityType="PLANET",
            name="planet",
        )
        a = normalize_entity_inventory(
            build_entity_inventory(
                complete_export(entityTypeCandidates=[first, second])
            )
        )
        b = normalize_entity_inventory(
            build_entity_inventory(
                complete_export(entityTypeCandidates=[second, first])
            )
        )
        self.assertEqual(
            [json.dumps(item, sort_keys=True) for item in a],
            [json.dumps(item, sort_keys=True) for item in b],
        )

    def test_raw_source_metadata_is_frozen(self) -> None:
        cases = {
            "executableSha256": "F" * 64,
            "language": "x86:BE:32:default",
            "compiler": "gcc",
            "imageBase": "00500000",
        }
        for field, value in cases.items():
            with self.subTest(field=field):
                source = dict(complete_export()["source"])
                source[field] = value
                with self.assertRaisesRegex(ValueError, "source"):
                    build_entity_inventory(complete_export(source=source))

    def test_ghidra_exporter_preserves_required_null_slots(self) -> None:
        exporter = Path("tools/ghidra/ExportExhaustiveRecords.java").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(exporter.count(".serializeNulls()"), 2)

    def test_ghidra_exporter_preserves_catalog_counts_as_integers(self) -> None:
        exporter = Path("tools/ghidra/ExportExhaustiveRecords.java").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("new Gson().fromJson(claim", exporter)
        self.assertIn('claim.get("count").getAsLong()', exporter)


if __name__ == "__main__":
    unittest.main()
