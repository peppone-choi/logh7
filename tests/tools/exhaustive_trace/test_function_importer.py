from __future__ import annotations

import copy
import json
import unittest

from tools.exhaustive_trace.import_functions import (
    build_function_inventory,
    build_function_reconciliation,
    normalize_function_inventory,
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


def function_candidate(
    address: str,
    *,
    name: str | None = None,
    callers: list[dict[str, object]] | None = None,
    callees: list[dict[str, object]] | None = None,
    reads: list[dict[str, object]] | None = None,
    writes: list[dict[str, object]] | None = None,
    strings: list[dict[str, object]] | None = None,
    indirect_calls: list[dict[str, object]] | None = None,
    side_effects: list[str] | None = None,
    classification_status: str = "UNADJUDICATED_INTERNAL",
    reasons: list[str] | None = None,
) -> dict[str, object]:
    bare = address.removeprefix("0x").upper()
    return {
        "candidateId": "FUNCTION:" + bare,
        "address": bare,
        "ghidraName": name or "FUN_" + bare,
        "namespace": "INTERNAL",
        "body": {
            "minAddress": bare,
            "maxAddress": f"{int(bare, 16) + 15:08X}",
            "instructionCount": 4,
        },
        "signature": {
            "status": "UNKNOWN",
            "callingConvention": "unknown",
            "returnType": "undefined",
            "parameters": [],
            "evidence": ["ghidra:signature:" + bare],
        },
        "callers": callers or [],
        "callees": callees or [],
        "dataReferences": {"reads": reads or [], "writes": writes or []},
        "stringReferences": strings or [],
        "indirectCallsites": indirect_calls or [],
        "sideEffects": side_effects or [],
        "classification": {
            "status": classification_status,
            "reasons": reasons or [],
        },
        "evidence": ["ghidra:function:" + bare],
    }


def group_candidate(
    candidate_id: str,
    kind: str,
    rule: str,
    members: list[dict[str, object]],
) -> dict[str, object]:
    return {
        "candidateId": candidate_id,
        "groupKind": kind,
        "groupingRule": rule,
        "members": members,
        "evidence": ["ghidra:function-group:" + kind],
    }


def complete_raw(**overrides: object) -> dict[str, object]:
    functions = [
        function_candidate(
            "00401000",
            callees=[
                {
                    "targetAddress": "00402000",
                    "callsite": "00401004",
                    "kind": "DIRECT_CALL",
                    "evidence": ["ghidra:call:00401004"],
                }
            ],
            reads=[
                {
                    "targetAddress": "00700000",
                    "targetSymbol": "DAT_00700000",
                    "refType": "DATA",
                    "evidence": ["ghidra:data-read:00401008"],
                }
            ],
            side_effects=["READS_GLOBAL", "CALLS_INTERNAL"],
        ),
        function_candidate(
            "00402000",
            callers=[
                {
                    "sourceAddress": "00401000",
                    "callsite": "00401004",
                    "kind": "DIRECT_CALL",
                    "evidence": ["ghidra:call:00401004"],
                }
            ],
        ),
    ]
    groups = [
        group_candidate(
            "FUNCTION_GROUP:EXTERNAL_IMPORT",
            "EXTERNAL_IMPORT",
            "namespace=EXTERNAL and source=raw-pe-imports",
            [
                {
                    "address": "0066B150",
                    "name": "KERNEL32.DLL::GetProcAddress",
                    "namespace": "EXTERNAL",
                    "evidence": ["pe-imports:0066B150"],
                }
            ],
        ),
        group_candidate(
            "FUNCTION_GROUP:THUNK",
            "THUNK",
            "isThunk=true",
            [
                {
                    "address": "006442EC",
                    "name": "FUN_006442EC",
                    "namespace": "INTERNAL",
                    "thunkTarget": "0066B70C",
                    "evidence": ["ghidra:thunk:006442EC"],
                }
            ],
        ),
    ]
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "source": {
            "program": "g7mtclient.exe",
            "executableSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
            "language": "x86:LE:32:default",
            "compiler": "windows",
            "imageBase": "00400000",
            "sourceManifestSha256": "1" * 64,
            "peImportsSha256": "2" * 64,
            "protocolRawSha256": "3" * 64,
            "uiRawSha256": "4" * 64,
            "recordsRawSha256": "5" * 64,
            "resourcesRawSha256": "6" * 64,
        },
        "exporter": {
            "class": "ExportExhaustiveFunctions",
            "sha256": "7" * 64,
            "ghidraRepositorySha256": "8" * 64,
        },
        "surfaceSha256": "9" * 64,
        "successMarker": "EXPORT_EXHAUSTIVE_FUNCTIONS_OK",
        "audit": {
            "scope": "FUNCTION_SURFACE_UNIVERSE",
            "sizeAloneClassifiesPlumbing": False,
            "upstreamMentionIsSemanticIdentity": False,
            "staticCallgraphIsRuntimeReachability": False,
            "groupedTargetReciprocity": "INDIVIDUAL_ONLY_GROUP_INBOUND_RETAINED_IN_CALLER",
            "limitations": ["indirect targets and runtime reachability remain unresolved"],
        },
        "conservation": {
            "functionSurfaceMembers": 4,
            "ghidraDefinedFunctions": 4,
            "ghidraInternalFunctions": 3,
            "individualFunctions": 2,
            "groupedMembers": 2,
            "externalFunctions": 1,
            "thunkFunctions": 1,
            "ghidraExternalFunctions": 1,
            "rawPeImports": 1,
            "upstreamReferences": 0,
            "unresolvedTargets": 0,
        },
        "functionCandidates": functions,
        "functionGroupCandidates": groups,
        "upstreamReferenceCandidates": [],
        "unresolvedTargetCandidates": [],
    }
    payload.update(overrides)
    if "conservation" not in overrides:
        payload["conservation"] = {
            **payload["conservation"],  # type: ignore[arg-type]
            "upstreamReferences": len(payload["upstreamReferenceCandidates"]),  # type: ignore[arg-type]
            "unresolvedTargets": len(payload["unresolvedTargetCandidates"]),  # type: ignore[arg-type]
        }
    return payload


class FunctionImporterTests(unittest.TestCase):
    def test_every_internal_non_thunk_function_is_an_individual_row(self) -> None:
        rows = build_function_inventory(complete_raw())
        individuals = [row for row in rows if row.row_kind.value == "INDIVIDUAL_FUNCTION"]
        self.assertEqual([row.address for row in individuals], ["00401000", "00402000"])
        self.assertTrue(all(row.row.reachability.value == "UNKNOWN" for row in individuals))
        self.assertTrue(all(sum(row.row.states.values()) == 1 for row in individuals))

    def test_unlinked_internal_function_cannot_be_grouped_as_plumbing(self) -> None:
        raw = complete_raw()
        moved = raw["functionCandidates"].pop()  # type: ignore[index,union-attr]
        raw["functionGroupCandidates"].append(  # type: ignore[union-attr]
            group_candidate(
                "FUNCTION_GROUP:PLUMBING",
                "PLUMBING",
                "instructionCount<5",
                [{"address": moved["address"], "name": moved["ghidraName"], "namespace": "INTERNAL", "evidence": ["size-only"]}],
            )
        )
        raw["conservation"] = {
            "functionSurfaceMembers": 4,
            "ghidraDefinedFunctions": 3,
            "ghidraInternalFunctions": 2,
            "individualFunctions": 1,
            "groupedMembers": 3,
            "externalFunctions": 1,
            "thunkFunctions": 1,
            "ghidraExternalFunctions": 1,
            "rawPeImports": 1,
            "upstreamReferences": 0,
            "unresolvedTargets": 0,
        }
        with self.assertRaisesRegex(ValueError, "internal non-thunk"):
            build_function_inventory(raw)

    def test_external_and_thunk_groups_have_deterministic_rules_and_members(self) -> None:
        rows = build_function_inventory(complete_raw())
        groups = [row for row in rows if row.row_kind.value == "FUNCTION_GROUP"]
        self.assertEqual({row.group_kind.value for row in groups}, {"EXTERNAL_IMPORT", "THUNK"})
        self.assertEqual(sum(len(row.member_addresses) for row in groups), 2)
        self.assertTrue(all(row.grouping_rule for row in groups))
        self.assertTrue(
            all(row.confidence.values["semanticClassification"] == "UNKNOWN" for row in groups)
        )

    def test_group_kind_requires_its_canonical_id_and_rule(self) -> None:
        raw = complete_raw()
        raw["functionGroupCandidates"][0]["groupingRule"] = "instructionCount<5"  # type: ignore[index]
        with self.assertRaisesRegex(ValueError, "canonical"):
            build_function_inventory(raw)

        raw = complete_raw()
        raw["functionGroupCandidates"][0]["candidateId"] = "FUNCTION_GROUP:OTHER"  # type: ignore[index]
        with self.assertRaisesRegex(ValueError, "canonical"):
            build_function_inventory(raw)

    def test_duplicate_or_missing_function_membership_is_rejected(self) -> None:
        raw = complete_raw()
        duplicate = copy.deepcopy(raw["functionGroupCandidates"][0])  # type: ignore[index]
        duplicate["candidateId"] = "FUNCTION_GROUP:DUPLICATE"
        raw["functionGroupCandidates"].append(duplicate)  # type: ignore[union-attr]
        with self.assertRaisesRegex(ValueError, "candidateId|exactly once"):
            build_function_inventory(raw)

        raw = complete_raw()
        raw["conservation"] = {**raw["conservation"], "functionSurfaceMembers": 5}  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "conservation"):
            build_function_inventory(raw)

    def test_individual_row_carries_required_semantic_dispositions(self) -> None:
        row = build_function_inventory(complete_raw())[0]
        self.assertEqual(row.proposed_name.status.value, "TECHNICAL_ID_ONLY")
        self.assertEqual(row.inputs_outputs.status.value, "UNKNOWN")
        self.assertEqual(row.callers.status.value, "DIRECT_ENUMERATED")
        self.assertEqual(row.callees.status.value, "DIRECT_ENUMERATED")
        self.assertEqual(row.global_structure_fields.status.value, "CANDIDATE")
        self.assertEqual(set(row.side_effects), {"READS_GLOBAL", "CALLS_INTERNAL"})
        self.assertEqual(row.confidence.status.value, "MECHANICAL_ENUMERATION")

    def test_signature_parameters_are_structured_and_remain_unknown(self) -> None:
        for bad_parameters in ("bad", [1]):
            raw = complete_raw()
            raw["functionCandidates"][0]["signature"]["parameters"] = bad_parameters  # type: ignore[index]
            with self.subTest(parameters=bad_parameters), self.assertRaisesRegex(
                ValueError, "signature parameters"
            ):
                build_function_inventory(raw)

        raw = complete_raw()
        raw["functionCandidates"][0]["signature"]["status"] = "PROVEN"  # type: ignore[index]
        with self.assertRaisesRegex(ValueError, "signature status"):
            build_function_inventory(raw)

    def test_direct_call_edges_must_be_reciprocal(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][1]["callers"] = []  # type: ignore[index]
        with self.assertRaisesRegex(ValueError, "reciprocal"):
            build_function_inventory(raw)

    def test_caller_source_must_be_known_or_explicitly_unresolved(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][1]["callers"].append(  # type: ignore[index]
            {
                "sourceAddress": "00403000",
                "callsite": "00403004",
                "kind": "DIRECT_CALL",
                "evidence": ["ghidra:call:00403004"],
            }
        )
        with self.assertRaisesRegex(ValueError, "caller source"):
            build_function_inventory(raw)

    def test_dangling_direct_target_requires_an_unresolved_candidate(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][0]["callees"].append(  # type: ignore[index]
            {
                "targetAddress": "00403000",
                "callsite": "0040100C",
                "kind": "DIRECT_CALL",
                "evidence": ["ghidra:call:0040100C"],
            }
        )
        with self.assertRaisesRegex(ValueError, "unresolved target"):
            build_function_inventory(raw)

        raw["unresolvedTargetCandidates"] = [
            {
                "candidateId": "UNRESOLVED_TARGET:00403000",
                "targetAddress": "00403000",
                "callsites": ["0040100C"],
                "status": "UNRESOLVED",
                "firstMissingBoundary": "FUNCTION_DEFINITION",
                "evidence": ["ghidra:call:0040100C"],
            }
        ]
        raw["conservation"] = {**raw["conservation"], "unresolvedTargets": 1}  # type: ignore[arg-type]
        rows = build_function_inventory(raw)
        self.assertEqual(len(rows), 4)

    def test_indirect_calls_are_explicit_side_effects_not_silent_drops(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][0]["indirectCallsites"] = [  # type: ignore[index]
            {
                "callsite": "0040100E",
                "operand": "[EAX+0x18]",
                "status": "UNRESOLVED",
                "evidence": ["ghidra:indirect-call:0040100E"],
            }
        ]
        raw["functionCandidates"][0]["sideEffects"].append("CALLS_INDIRECT")  # type: ignore[index]
        row = build_function_inventory(raw)[0]
        self.assertIn("CALLS_INDIRECT", row.side_effects)
        self.assertEqual(row.callees.values["indirectCallsites"][0]["status"], "UNRESOLVED")

    def test_runtime_pointer_cannot_be_a_stable_structure_field(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][0]["dataReferences"]["writes"] = [  # type: ignore[index]
            {
                "targetAddress": "09ABCDEF",
                "targetSymbol": "0x09ABCDEF",
                "refType": "WRITE",
                "evidence": ["runtime:pointer"],
            }
        ]
        with self.assertRaisesRegex(ValueError, "runtime pointer"):
            build_function_inventory(raw)

    def test_empty_original_string_reference_is_preserved(self) -> None:
        raw = complete_raw()
        raw["functionCandidates"][0]["stringReferences"] = [  # type: ignore[index]
            {
                "stringAddress": "00710000",
                "referenceAddress": "00401009",
                "value": "",
                "evidence": ["ghidra:string-ref:00401009"],
            }
        ]
        row = build_function_inventory(raw)[0]
        self.assertEqual(row.global_structure_fields.values["stringReferences"][0]["value"], "")

    def test_upstream_mentions_are_links_not_semantic_identity(self) -> None:
        raw = complete_raw(
            upstreamReferenceCandidates=[
                {
                    "candidateId": "UPSTREAM_REF:UI:1",
                    "artifact": "ui-ghidra",
                    "artifactSha256": "4" * 64,
                    "jsonPointer": "/rootModes/0/builderFunction",
                    "token": "FUN_00401000",
                    "resolvedFunctionCandidateId": "FUNCTION:00401000",
                    "status": "MENTION",
                    "evidence": ["ui-ghidra:/rootModes/0/builderFunction"],
                }
            ]
        )
        row = build_function_inventory(raw)[0]
        self.assertEqual(row.classification.status.value, "EVIDENCE_LINKED")
        self.assertEqual(row.proposed_name.status.value, "TECHNICAL_ID_ONLY")
        self.assertEqual(row.row.reachability.value, "UNKNOWN")

    def test_unknown_upstream_token_is_conserved_as_unresolved(self) -> None:
        raw = complete_raw(
            upstreamReferenceCandidates=[
                {
                    "candidateId": "UPSTREAM_REF:UI:MISSING",
                    "artifact": "ui-ghidra",
                    "artifactSha256": "4" * 64,
                    "jsonPointer": "/ghost",
                    "token": "FUN_0040DEAD",
                    "resolvedFunctionCandidateId": None,
                    "status": "UNRESOLVED",
                    "firstMissingBoundary": "FUNCTION_DEFINITION",
                    "evidence": ["ui-ghidra:/ghost"],
                }
            ]
        )
        rows = build_function_inventory(raw)
        reconciliation = build_function_reconciliation(raw, rows)
        self.assertEqual(reconciliation["unresolvedCount"], 1)
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_all_rows_have_exact_implementation_targets(self) -> None:
        rows = build_function_inventory(complete_raw())
        for row in rows:
            self.assertEqual(set(row.implementation_disposition), IMPLEMENTATION_TARGETS)
            self.assertTrue(
                all(
                    section.status.value in {"REQUIRED", "NOT_APPLICABLE"}
                    and section.values["reason"] is not None
                    for section in row.implementation_disposition.values()
                )
            )

    def test_reconciliation_conserves_rows_and_raw_candidates(self) -> None:
        raw = complete_raw()
        rows = build_function_inventory(raw)
        reconciliation = build_function_reconciliation(raw, rows)
        self.assertEqual(reconciliation["functionSurfaceMemberCount"], 4)
        self.assertEqual(reconciliation["representedFunctionCount"], 4)
        self.assertEqual(reconciliation["candidateCount"], 6)
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_candidate_ids_are_globally_unique_case_insensitively(self) -> None:
        duplicate = {
            "candidateId": "DANGLING_DIRECT:00401004:0040DEAD",
            "callsites": ["00401004"],
            "targetAddress": "0040DEAD",
            "status": "UNRESOLVED",
            "firstMissingBoundary": "FUNCTION_DEFINITION",
            "evidence": ["ghidra:unresolved-call:00401004"],
        }
        raw = complete_raw(unresolvedTargetCandidates=[duplicate, copy.deepcopy(duplicate)])
        raw["conservation"] = {**raw["conservation"], "unresolvedTargets": 2}  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "candidateId"):
            build_function_inventory(raw)

    def test_reconciliation_direct_api_rejects_duplicate_raw_candidate_ids(self) -> None:
        raw = complete_raw()
        rows = build_function_inventory(raw)
        raw["unresolvedTargetCandidates"] = [
            {
                "candidateId": "FUNCTION_GROUP:THUNK",
                "targetAddress": "0040DEAD",
                "callsites": ["00401004"],
                "status": "UNRESOLVED",
                "firstMissingBoundary": "FUNCTION_DEFINITION",
                "evidence": ["ghidra:unresolved-call:00401004"],
            }
        ]
        raw["conservation"] = {**raw["conservation"], "unresolvedTargets": 1}  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "candidateId"):
            build_function_reconciliation(raw, rows)

    def test_every_conservation_counter_is_verified(self) -> None:
        for field in (
            "ghidraExternalFunctions",
            "rawPeImports",
            "upstreamReferences",
            "unresolvedTargets",
        ):
            raw = complete_raw()
            raw["conservation"] = {**raw["conservation"], field: 999}  # type: ignore[arg-type]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "conservation"):
                build_function_inventory(raw)

    def test_conservation_counters_reject_booleans(self) -> None:
        for field, value in (("externalFunctions", True), ("upstreamReferences", False)):
            raw = complete_raw()
            raw["conservation"] = {**raw["conservation"], field: value}  # type: ignore[arg-type]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "conservation"):
                build_function_inventory(raw)

    def test_normalization_is_deterministic(self) -> None:
        raw_a = complete_raw()
        raw_b = complete_raw(
            functionCandidates=list(reversed(copy.deepcopy(raw_a["functionCandidates"]))),
            functionGroupCandidates=list(reversed(copy.deepcopy(raw_a["functionGroupCandidates"]))),
        )
        a = normalize_function_inventory(build_function_inventory(raw_a))
        b = normalize_function_inventory(build_function_inventory(raw_b))
        self.assertEqual(
            [json.dumps(item, sort_keys=True) for item in a],
            [json.dumps(item, sort_keys=True) for item in b],
        )

    def test_raw_metadata_and_unknown_collections_are_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "top-level"):
            build_function_inventory(complete_raw(ghostFunctions=[]))
        raw = complete_raw()
        raw["source"]["executableSha256"] = "F" * 64  # type: ignore[index]
        with self.assertRaisesRegex(ValueError, "executable"):
            build_function_inventory(raw)


if __name__ == "__main__":
    unittest.main()
