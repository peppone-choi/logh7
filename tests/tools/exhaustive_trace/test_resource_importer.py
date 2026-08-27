from __future__ import annotations

import copy
import json
import unittest

from tools.exhaustive_trace.import_resources import (
    TreeManifestEntry,
    build_resource_inventory,
    build_resource_reconciliation,
    normalize_resource_inventory,
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


def tree_entry(path: str, marker: str = "A", size: int = 7) -> TreeManifestEntry:
    return TreeManifestEntry(
        relative_path=path,
        content_sha256=marker * 64,
        byte_size=size,
    )


def complete_raw(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schemaVersion": 1,
        "source": {
            "program": "g7mtclient.exe",
            "executableSha256": "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16",
            "language": "x86:LE:32:default",
            "compiler": "windows",
            "imageBase": "00400000",
            "sourceManifestSha256": "1" * 64,
            "treeManifestSha256": "2" * 64,
            "peImportsSha256": "3" * 64,
        },
        "exporter": {
            "class": "ExportExhaustiveResources",
            "sha256": "4" * 64,
            "ghidraRepositorySha256": "5" * 64,
        },
        "surfaceSha256": "6" * 64,
        "successMarker": "EXPORT_EXHAUSTIVE_RESOURCES_OK",
        "audit": {
            "scope": "COMPILED_RESOURCE_ANCHORS",
            "filePresenceIsIntegration": False,
            "stringPresenceIsLoaderProof": False,
            "staticSubmissionIsPlayerVisible": False,
            "limitations": ["runtime resource receipts remain unobserved"],
        },
        "conservation": {"treeFiles": 3},
        "literalPathCandidates": [],
        "pathFormatterCandidates": [],
        "loaderCandidates": [],
        "decodeTransformCandidates": [],
        "runtimeKeyCandidates": [],
        "cacheRegistryCandidates": [],
        "ownerCandidates": [],
        "renderSubmissionCandidates": [],
        "audioSubmissionCandidates": [],
        "uiSubmissionCandidates": [],
        "presentationReceiptCandidates": [],
        "externalDependencyCandidates": [],
        "manualResourceCandidates": [],
    }
    payload.update(overrides)
    return payload


def literal(candidate_id: str, path: str) -> dict[str, object]:
    return {
        "candidateId": candidate_id,
        "address": "00700000",
        "value": path,
        "matchedPaths": [path],
        "status": "EXACT_PATH_MATCH",
        "evidence": ["ghidra:string:00700000"],
    }


def loader(path: str, path_candidate_id: str, status: str = "PROVEN") -> dict[str, object]:
    return {
        "candidateId": "LOADER:00500000:" + path,
        "resourcePath": path,
        "pathCandidateIds": [path_candidate_id],
        "status": status,
        "functions": ["FUN_00500000"],
        "api": "KERNEL32.DLL::CreateFileA",
        "acceptedFormats": [],
        "evidence": ["ghidra:loader:00500000"],
    }


def owner(path: str) -> dict[str, object]:
    return {
        "candidateId": "OWNER:" + path,
        "resourcePath": path,
        "status": "PROVEN",
        "ownerKind": "CLIENT_OBJECT",
        "ownerKeys": ["CLIENT_OBJECT:CURSOR"],
        "functions": ["FUN_00501000"],
        "joinKind": "DATAFLOW",
        "evidence": ["ghidra:owner:00501000"],
    }


def render_submission(path: str, status: str = "RUNTIME_OBSERVED") -> dict[str, object]:
    return {
        "candidateId": "RENDER:" + path,
        "resourcePath": path,
        "status": status,
        "function": "FUN_00502000",
        "sink": "D3D8_DRAW",
        "runtimeReceiptRefs": ["RUN:ONE"] if status == "RUNTIME_OBSERVED" else [],
        "evidence": ["receipt:render"],
    }


def receipt(path: str, source_hash: str = "A" * 64) -> dict[str, object]:
    return {
        "candidateId": "RECEIPT:" + path,
        "resourcePath": path,
        "status": "PLAYER_VISIBLE",
        "runId": "RUN:ONE",
        "sourceSha256": source_hash,
        "runtimeKey": "CURSOR:DEFAULT",
        "ownerKey": "CLIENT_OBJECT:CURSOR",
        "submissionCandidateId": "RENDER:" + path,
        "evidence": ["receipt:owned-hwnd"],
    }


def runtime_key(path: str) -> dict[str, object]:
    return {
        "candidateId": "RUNTIME_KEY:" + path,
        "resourcePath": path,
        "status": "PROVEN",
        "namespace": "CURSOR",
        "value": "CURSOR:DEFAULT",
        "derivationFunction": "FUN_00500000",
        "evidence": ["ghidra:key"],
    }


class ResourceImporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.entries = [
            tree_entry("data/image/window/cursor_parts.tga"),
            tree_entry("data/model/strategy/galaxy.mdx", "B"),
            tree_entry("data/sound/se/cursor.wav", "C"),
        ]

    def test_manifest_file_without_loader_is_enumerated_only(self) -> None:
        rows = build_resource_inventory(complete_raw(), self.entries, root_id="original")
        self.assertEqual(len(rows), 3)
        for row in rows:
            self.assertEqual(row.usage_disposition.value, "ENUMERATED_ONLY")
            self.assertEqual(row.row.reachability.value, "UNKNOWN")
            self.assertTrue(row.row.states[next(iter(row.row.states))])
            self.assertEqual(sum(row.row.states.values()), 1)
            self.assertEqual(row.first_missing_boundary, "LOADER_JOIN")

    def test_tree_paths_are_safe_and_casefold_unique(self) -> None:
        for path in ("../escape.tga", "/absolute.tga", "data\\bad.tga", "./dot.tga"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "resource path"):
                build_resource_inventory(complete_raw(), [tree_entry(path)], root_id="original")

        with self.assertRaisesRegex(ValueError, "case-insensitive"):
            build_resource_inventory(
                complete_raw(),
                [tree_entry("data/A.tga"), tree_entry("data/a.tga", "B")],
                root_id="original",
            )

    def test_duplicate_bytes_do_not_collapse_distinct_paths(self) -> None:
        rows = build_resource_inventory(
            complete_raw(conservation={"treeFiles": 2}),
            [tree_entry("data/a.tga"), tree_entry("data/b.tga")],
            root_id="original",
        )
        self.assertEqual(len(rows), 2)
        self.assertEqual(len({row.row.key for row in rows}), 2)

    def test_literal_match_is_not_loader_or_integration_proof(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(literalPathCandidates=[literal("PATH:00700000", path)])
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.path_resolution.status.value, "CANDIDATE")
        self.assertEqual(row.loader.status.value, "UNKNOWN")
        self.assertEqual(row.usage_disposition.value, "ENUMERATED_ONLY")

    def test_proven_loader_without_owner_is_orphan(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
        )
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.loader.status.value, "PROVEN")
        self.assertEqual(row.usage_disposition.value, "ORPHAN")
        self.assertEqual(row.first_missing_boundary, "RUNTIME_OWNER")

    def test_loader_and_owner_without_receipt_is_only_dormant_candidate(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
            ownerCandidates=[owner(path)],
        )
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.usage_disposition.value, "DORMANT_CANDIDATE")
        self.assertEqual(row.row.reachability.value, "UNKNOWN")
        self.assertEqual(row.first_missing_boundary, "RUNTIME_SUBMISSION_RECEIPT")

    def test_integration_requires_matching_runtime_submission_and_receipt(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
            runtimeKeyCandidates=[runtime_key(path)],
            ownerCandidates=[owner(path)],
            renderSubmissionCandidates=[render_submission(path)],
            presentationReceiptCandidates=[receipt(path)],
        )
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.usage_disposition.value, "INTEGRATED")
        self.assertEqual(row.row.reachability.value, "SHIPPED_REACHABLE")
        self.assertTrue(row.row.states[next(state for state in row.row.states if state.value == "PLAYER_VISIBLE")])

    def test_receipt_with_wrong_source_hash_is_rejected(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
            runtimeKeyCandidates=[runtime_key(path)],
            ownerCandidates=[owner(path)],
            renderSubmissionCandidates=[render_submission(path)],
            presentationReceiptCandidates=[receipt(path, "F" * 64)],
        )
        with self.assertRaisesRegex(ValueError, "receipt source"):
            build_resource_inventory(raw, self.entries, root_id="original")

    def test_loader_path_candidate_must_resolve(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(loaderCandidates=[loader(path, "PATH:MISSING")])
        with self.assertRaisesRegex(ValueError, "path candidate"):
            build_resource_inventory(raw, self.entries, root_id="original")

    def test_runtime_pointer_cannot_be_stable_owner_identity(self) -> None:
        path = self.entries[0].relative_path
        bad_owner = owner(path)
        bad_owner["ownerKeys"] = ["0x00C514E4"]
        raw = complete_raw(ownerCandidates=[bad_owner])
        with self.assertRaisesRegex(ValueError, "runtime pointer"):
            build_resource_inventory(raw, self.entries, root_id="original")

    def test_formatter_candidate_is_conserved_without_guess_expansion(self) -> None:
        raw = complete_raw(
            pathFormatterCandidates=[
                {
                    "candidateId": "FORMATTER:00710000",
                    "address": "00710000",
                    "template": "data/model/planets/p%03d_low.mdx",
                    "function": "FUN_004D3BD0",
                    "argumentDomain": "UNKNOWN",
                    "matchedPaths": [],
                    "status": "UNRESOLVED",
                    "firstMissingBoundary": "ARGUMENT_DOMAIN",
                    "evidence": ["ghidra:string:00710000"],
                }
            ]
        )
        rows = build_resource_inventory(raw, self.entries, root_id="original")
        reconciliation = build_resource_reconciliation(raw, rows, self.entries)
        record = next(item for item in reconciliation["records"] if item["candidateId"] == "FORMATTER:00710000")
        self.assertEqual(record["status"], "UNRESOLVED")
        self.assertEqual(len(rows), 3)

    def test_unknown_sections_cannot_claim_loader_values(self) -> None:
        path = self.entries[0].relative_path
        item = loader(path, "PATH:00700000", status="UNKNOWN")
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[item],
        )
        with self.assertRaisesRegex(ValueError, "unknown loader"):
            build_resource_inventory(raw, self.entries, root_id="original")

    def test_candidate_receipt_cannot_integrate(self) -> None:
        path = self.entries[0].relative_path
        candidate_receipt = receipt(path)
        candidate_receipt["status"] = "CANDIDATE"
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
            runtimeKeyCandidates=[runtime_key(path)],
            ownerCandidates=[owner(path)],
            renderSubmissionCandidates=[render_submission(path)],
            presentationReceiptCandidates=[candidate_receipt],
        )
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.usage_disposition.value, "DORMANT_CANDIDATE")
        self.assertFalse(row.row.states[next(state for state in row.row.states if state.value == "PLAYER_VISIBLE")])

    def test_receipt_without_loader_cannot_set_runtime_or_player_states(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            runtimeKeyCandidates=[runtime_key(path)],
            ownerCandidates=[owner(path)],
            renderSubmissionCandidates=[render_submission(path)],
            presentationReceiptCandidates=[receipt(path)],
        )
        row = build_resource_inventory(raw, self.entries, root_id="original")[0]
        self.assertEqual(row.usage_disposition.value, "ENUMERATED_ONLY")
        self.assertEqual(sum(row.row.states.values()), 1)

    def test_proven_runtime_key_requires_complete_derivation(self) -> None:
        path = self.entries[0].relative_path
        for missing in ("namespace", "value", "derivationFunction"):
            item = runtime_key(path)
            item.pop(missing)
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "proven runtime key"):
                build_resource_inventory(
                    complete_raw(runtimeKeyCandidates=[item]), self.entries, root_id="original"
                )

    def test_proven_owner_requires_kind_key_function_and_join(self) -> None:
        path = self.entries[0].relative_path
        for missing in ("ownerKind", "ownerKeys", "functions", "joinKind"):
            item = owner(path)
            item.pop(missing)
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "proven owner"):
                build_resource_inventory(
                    complete_raw(ownerCandidates=[item]), self.entries, root_id="original"
                )

    def test_external_font_dependency_is_a_separate_conservative_row(self) -> None:
        dependency = {
            "candidateId": "EXTERNAL_DEPENDENCY:FONT:GDI32.DLL::CreateFontA",
            "status": "CANDIDATE",
            "dependencyKind": "OS_FONT_API",
            "name": "GDI32.DLL::CreateFontA",
            "category": "FONT",
            "evidence": ["pe-imports:GDI32.DLL::CreateFontA"],
        }
        rows = build_resource_inventory(
            complete_raw(externalDependencyCandidates=[dependency]),
            self.entries,
            root_id="original",
        )
        external = next(row for row in rows if row.row_kind.value == "EXTERNAL_DEPENDENCY")
        self.assertEqual(external.category.values["value"], "FONT")
        self.assertEqual(external.distribution_disposition, "OS_PROVIDED_EXTERNAL_DEPENDENCY")
        self.assertEqual(external.usage_disposition.value, "ENUMERATED_ONLY")
        self.assertEqual(external.row.reachability.value, "UNKNOWN")

    def test_all_rows_have_exact_implementation_targets_and_local_only_distribution(self) -> None:
        row = build_resource_inventory(complete_raw(), self.entries, root_id="original")[0]
        self.assertEqual(set(row.implementation_disposition), IMPLEMENTATION_TARGETS)
        self.assertTrue(all(section.status.value == "REQUIRED" for section in row.implementation_disposition.values()))
        self.assertEqual(row.distribution_disposition, "USER_OWNED_LOCAL_ONLY")

    def test_spot_background_file_count_is_not_a_spot_entity_count(self) -> None:
        entries = [
            tree_entry(f"data/image/spot/bg{index:02d}.jpg", marker=f"{index % 10}")
            for index in range(44)
        ]
        rows = build_resource_inventory(
            complete_raw(conservation={"treeFiles": len(entries)}),
            entries,
            root_id="original",
        )
        self.assertEqual(len(rows), 44)
        self.assertTrue(all(row.category.values["value"] == "SPOT_BACKGROUND" for row in rows))
        self.assertTrue(all(row.category.status.value == "CANDIDATE" for row in rows))
        self.assertFalse(any(hasattr(row, "spot_count") for row in rows))

    def test_unknown_collection_and_duplicate_candidate_ids_are_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "top-level"):
            build_resource_inventory(complete_raw(ghostCandidates=[]), self.entries, root_id="original")

        path = self.entries[0].relative_path
        duplicate = literal("DUPLICATE", path)
        raw = complete_raw(
            literalPathCandidates=[duplicate],
            pathFormatterCandidates=[copy.deepcopy(duplicate)],
        )
        with self.assertRaisesRegex(ValueError, "duplicate.*candidate"):
            build_resource_inventory(raw, self.entries, root_id="original")

    def test_reconciliation_accounts_for_tree_and_raw_candidates(self) -> None:
        path = self.entries[0].relative_path
        raw = complete_raw(
            literalPathCandidates=[literal("PATH:00700000", path)],
            pathFormatterCandidates=[
                {
                    "candidateId": "FORMATTER:UNRESOLVED",
                    "status": "UNRESOLVED",
                    "firstMissingBoundary": "ARGUMENT_DOMAIN",
                }
            ],
        )
        rows = build_resource_inventory(raw, self.entries, root_id="original")
        reconciliation = build_resource_reconciliation(raw, rows, self.entries)
        self.assertEqual(reconciliation["candidateCount"], 5)
        self.assertEqual(reconciliation["normalizedCount"], 4)
        self.assertEqual(reconciliation["unresolvedCount"], 1)
        self.assertEqual(reconciliation["unaccountedCount"], 0)

    def test_normalized_inventory_is_deterministic(self) -> None:
        a = normalize_resource_inventory(
            build_resource_inventory(complete_raw(), self.entries, root_id="original")
        )
        b = normalize_resource_inventory(
            build_resource_inventory(complete_raw(), list(reversed(self.entries)), root_id="original")
        )
        self.assertEqual(
            [json.dumps(item, sort_keys=True) for item in a],
            [json.dumps(item, sort_keys=True) for item in b],
        )


if __name__ == "__main__":
    unittest.main()
