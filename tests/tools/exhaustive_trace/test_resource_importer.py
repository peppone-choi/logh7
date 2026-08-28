from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.import_resources import (
    TreeManifestEntry,
    build_resource_inventory,
    build_resource_reconciliation,
    load_resource_adjudications,
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

    def test_hash_bound_internet_shortcut_adjudication_closes_loader_as_not_applicable(self) -> None:
        path = "official-site.url"
        entry = TreeManifestEntry(
            relative_path=path,
            content_sha256="4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8",
            byte_size=50,
        )
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "WINDOWS_INTERNET_SHORTCUT",
                "contentSha256": "4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8",
                "contentBytesHex": "5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A",
                "byteSize": 50,
                "originalName": "銀河英雄伝説VII公式サイト.url",
                "originalNameEncoding": "CP932",
                "originalNameBytesHex": "8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C",
                "targetUrl": "http://www.gineiden.com/",
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Windows shell Internet Shortcut; not a game runtime resource",
                    "evidence": ["unit:internet-shortcut-content"],
                },
                "evidence": ["unit:internet-shortcut-content", "original-iso:data1.hdr"],
            }
        }

        row = build_resource_inventory(
            complete_raw(conservation={"treeFiles": 1}),
            [entry],
            root_id="original",
            adjudications=adjudication,
        )[0]

        self.assertEqual(row.loader.status.value, "NOT_APPLICABLE")
        self.assertEqual(row.loader.values["reason"], adjudication[path]["loader"]["reason"])
        self.assertEqual(row.first_missing_boundary, "RUNTIME_OWNER")
        self.assertEqual(row.format.status.value, "PROVEN")
        self.assertEqual(row.format.values["detectedFormat"], "WINDOWS_INTERNET_SHORTCUT")
        self.assertEqual(row.source["originalName"], "銀河英雄伝説VII公式サイト.url")
        self.assertEqual(row.source["targetUrl"], "http://www.gineiden.com/")

    def test_adjudication_file_is_bound_to_source_and_tree_manifests(self) -> None:
        payload = {
            "schemaVersion": 1,
            "source": {
                "rootId": "original",
                "sourceManifestSha256": "1" * 64,
                "treeManifestSha256": "2" * 64,
            },
            "adjudications": [
                {
                    "relativePosixPath": "official-site.url",
                    "schemaVersion": 1,
                    "kind": "WINDOWS_INTERNET_SHORTCUT",
                    "contentSha256": "D" * 64,
                    "contentBytesHex": "5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A",
                    "byteSize": 50,
                    "originalName": "銀河英雄伝説VII公式サイト.url",
                    "originalNameEncoding": "CP932",
                    "originalNameBytesHex": "8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C",
                    "targetUrl": "http://www.gineiden.com/",
                    "loader": {
                        "status": "NOT_APPLICABLE",
                        "reason": "Windows shell Internet Shortcut; not a game runtime resource",
                        "evidence": ["unit:internet-shortcut-content"],
                    },
                    "evidence": ["unit:internet-shortcut-content", "original-iso:data1.hdr"],
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "resource-adjudications.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            loaded = load_resource_adjudications(
                path,
                expected_root_id="original",
                expected_source_manifest_sha256="1" * 64,
                expected_tree_manifest_sha256="2" * 64,
            )

        self.assertEqual(set(loaded), {"official-site.url"})
        self.assertEqual(loaded["official-site.url"]["kind"], "WINDOWS_INTERNET_SHORTCUT")

    def test_not_applicable_adjudication_cannot_override_a_loader_candidate(self) -> None:
        path = "official-site.url"
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "WINDOWS_INTERNET_SHORTCUT",
                "contentSha256": "D" * 64,
                "contentBytesHex": "5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A",
                "byteSize": 50,
                "originalName": "銀河英雄伝説VII公式サイト.url",
                "originalNameEncoding": "CP932",
                "originalNameBytesHex": "8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C",
                "targetUrl": "http://www.gineiden.com/",
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Windows shell Internet Shortcut; not a game runtime resource",
                    "evidence": ["unit:internet-shortcut-content"],
                },
                "evidence": ["unit:internet-shortcut-content"],
            }
        }
        raw = complete_raw(
            conservation={"treeFiles": 1},
            literalPathCandidates=[literal("PATH:00700000", path)],
            loaderCandidates=[loader(path, "PATH:00700000")],
        )

        with self.assertRaisesRegex(ValueError, "conflicts with loader candidates"):
            build_resource_inventory(
                raw,
                [tree_entry(path, marker="D", size=50)],
                root_id="original",
                adjudications=adjudication,
            )

    def test_internet_shortcut_adjudication_rejects_url_that_differs_from_content(self) -> None:
        path = "official-site.url"
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "WINDOWS_INTERNET_SHORTCUT",
                "contentSha256": "4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8",
                "contentBytesHex": "5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A",
                "byteSize": 50,
                "originalName": "銀河英雄伝説VII公式サイト.url",
                "originalNameEncoding": "CP932",
                "originalNameBytesHex": "8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C",
                "targetUrl": "https://wrong.example/",
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Windows shell Internet Shortcut; not a game runtime resource",
                    "evidence": ["unit:internet-shortcut-content"],
                },
                "evidence": ["unit:internet-shortcut-content"],
            }
        }

        with self.assertRaisesRegex(ValueError, "targetUrl differs from shortcut content"):
            build_resource_inventory(
                complete_raw(conservation={"treeFiles": 1}),
                [
                    TreeManifestEntry(
                        relative_path=path,
                        content_sha256="4A480EB7B1D7E2B5B70081E8032A5CEC244340D18E08D10F694A6185042EA1A8",
                        byte_size=50,
                    )
                ],
                root_id="original",
                adjudications=adjudication,
            )

    def test_resource_adjudication_rejects_unknown_fields(self) -> None:
        path = "official-site.url"
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "WINDOWS_INTERNET_SHORTCUT",
                "contentSha256": "D" * 64,
                "contentBytesHex": "5B496E7465726E657453686F72746375745D0D0A55524C3D687474703A2F2F7777772E67696E656964656E2E636F6D2F0D0A",
                "byteSize": 50,
                "originalName": "銀河英雄伝説VII公式サイト.url",
                "originalNameEncoding": "CP932",
                "originalNameBytesHex": "8BE289CD89709759936090E05649498CF68EAE8354834383672E75726C",
                "targetUrl": "http://www.gineiden.com/",
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Windows shell Internet Shortcut; not a game runtime resource",
                    "evidence": ["unit:internet-shortcut-content"],
                },
                "evidence": ["unit:internet-shortcut-content"],
                "unsupportedClaim": "must not be ignored",
            }
        }

        with self.assertRaisesRegex(ValueError, "unknown resource adjudication fields"):
            build_resource_inventory(
                complete_raw(conservation={"treeFiles": 1}),
                [tree_entry(path, marker="D", size=50)],
                root_id="original",
                adjudications=adjudication,
            )

    def test_hash_bound_pe_bootstrap_adjudication_preserves_process_launch_not_asset_load(self) -> None:
        source_path = "bootfirst.exe"
        target_path = "gin7updateclient.exe"
        source_sha = "A" * 64
        target_sha = "B" * 64
        adjudication = {
            source_path: {
                "schemaVersion": 1,
                "kind": "PE_EXECUTABLE_BOOTSTRAP",
                "contentSha256": source_sha,
                "byteSize": 40960,
                "analysis": {
                    "status": "PROVEN",
                    "format": "PE32_X86_GUI_EXECUTABLE",
                    "machine": "0x014C",
                    "subsystem": 2,
                    "entryPointRva": "0x00001150",
                    "role": "UPDATE_CLIENT_BOOTSTRAP",
                    "receiptPath": "evidence/exhaustive-trace/adjudications/fixture.json",
                    "receiptSha256": "C" * 64,
                    "evidence": ["ghidra:bootfirst-flow"],
                },
                "processLaunch": {
                    "status": "PROVEN",
                    "api": "KERNEL32.dll::CreateProcessA",
                    "function": "FUN_00401000",
                    "callsite": "0x004010B1",
                    "targetRelativePosixPath": target_path,
                    "targetSha256": target_sha,
                    "executableStringVa": "0x004060A4",
                    "waitCallsite": "0x004010BE",
                    "exitCodeCallsite": "0x004010D2",
                    "evidence": ["ghidra:bootfirst-flow:00401000"],
                },
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "OS-loaded updater bootstrap process; not a G7MTClient asset format",
                    "evidence": ["ghidra:bootfirst-flow:00401000"],
                },
                "evidence": ["tree-manifest:bootfirst.exe", "ghidra:bootfirst-flow"],
            }
        }

        rows = build_resource_inventory(
            complete_raw(conservation={"treeFiles": 2}),
            [
                TreeManifestEntry(source_path, source_sha, 40960),
                TreeManifestEntry(target_path, target_sha, 1060864),
            ],
            root_id="original",
            adjudications=adjudication,
        )
        row = next(item for item in rows if item.row.name == source_path)
        normalized = normalize_resource_inventory([row])[0]

        self.assertEqual(row.loader.status.value, "NOT_APPLICABLE")
        self.assertEqual(row.format.status.value, "PROVEN")
        self.assertEqual(row.format.values["detectedFormat"], "PE32_X86_GUI_EXECUTABLE")
        self.assertEqual(normalized["source"]["staticRole"], "UPDATE_CLIENT_BOOTSTRAP")
        self.assertEqual(
            normalized["source"]["processLaunch"]["targetRowKey"],
            "RESOURCE:FILE:original:gin7updateclient.exe",
        )
        self.assertEqual(row.first_missing_boundary, "RUNTIME_OWNER")
        self.assertEqual(sum(row.row.states.values()), 1)

    def test_pe_bootstrap_adjudication_file_rejects_static_receipt_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = root / "bootfirst-static.json"
            receipt.write_text("{}\n", encoding="utf-8")
            payload = {
                "schemaVersion": 1,
                "source": {
                    "rootId": "original",
                    "sourceManifestSha256": "1" * 64,
                    "treeManifestSha256": "2" * 64,
                },
                "adjudications": [
                    {
                        "relativePosixPath": "bootfirst.exe",
                        "schemaVersion": 1,
                        "kind": "PE_EXECUTABLE_BOOTSTRAP",
                        "analysis": {
                            "receiptPath": str(receipt),
                            "receiptSha256": "F" * 64,
                        },
                    }
                ],
            }
            path = root / "resources.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "analysis receipt hash mismatch"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

    def test_pe_bootstrap_receipt_rejects_referenced_evidence_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            exporter = root / "Export.java"
            exporter.write_text("final bytes\n", encoding="utf-8")
            receipt = root / "bootfirst-static.json"
            receipt_payload = {
                "schemaVersion": 1,
                "status": "PROVEN_STATIC",
                "source": {"sha256": "A" * 64, "byteSize": 40960},
                "staticTools": {
                    "ghidraExporter": {
                        "path": str(exporter),
                        "sha256": "F" * 64,
                    }
                },
            }
            receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
            receipt_sha = __import__("hashlib").sha256(receipt.read_bytes()).hexdigest().upper()
            payload = {
                "schemaVersion": 1,
                "source": {
                    "rootId": "original",
                    "sourceManifestSha256": "1" * 64,
                    "treeManifestSha256": "2" * 64,
                },
                "adjudications": [
                    {
                        "relativePosixPath": "bootfirst.exe",
                        "schemaVersion": 1,
                        "kind": "PE_EXECUTABLE_BOOTSTRAP",
                        "contentSha256": "A" * 64,
                        "byteSize": 40960,
                        "analysis": {
                            "receiptPath": str(receipt),
                            "receiptSha256": receipt_sha,
                        },
                    }
                ],
            }
            path = root / "resources.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "referenced evidence hash mismatch"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

    def test_hash_bound_pdf_operation_manual_closes_loader_without_state_promotion(self) -> None:
        path = "doc/manual.pdf"
        source_sha = "A" * 64
        analysis = {
            "status": "PROVEN",
            "format": "PDF_1_4",
            "role": "ORIGINAL_OPERATION_MANUAL",
            "headerHex": "255044462D312E340D25E2E3CFD30D0A",
            "pdfVersion": "1.4",
            "pageCount": 69,
            "encrypted": True,
            "emptyPasswordAccess": True,
            "title": "銀河英雄伝説Ⅶ　操作説明書",
            "author": "BOTHTEC",
            "creator": "Word 用 Acrobat PDFMaker 5.0",
            "producer": "Acrobat Distiller 5.0.5 (Windows)",
            "creationDate": "D:20040411114123+09'00'",
            "modificationDate": "D:20040411125421+09'00'",
            "receiptPath": "evidence/exhaustive-trace/adjudications/fixture.json",
            "receiptSha256": "C" * 64,
            "evidence": ["pdf-analysis:fixture"],
        }
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "PDF_OPERATION_MANUAL",
                "contentSha256": source_sha,
                "byteSize": 5374309,
                "analysis": analysis,
                "externalDocumentOpen": {
                    "status": "PROVEN",
                    "openerKey": "ORIGINAL_CD_ARTIFACT:G7START.EXE",
                    "openerName": "G7Start.exe",
                    "openerSha256": "B" * 64,
                    "openerByteSize": 434176,
                    "api": "SHELL32.dll::ShellExecuteA",
                    "commandId": 1001,
                    "handler": "FUN_00403860",
                    "callsite": "0x004038E6",
                    "verb": "open",
                    "targetOriginalName": "銀英伝７マニュアル.pdf",
                    "targetSha256": source_sha,
                    "evidence": ["g7start:ShellExecuteA:0x004038E6"],
                },
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Standalone original operation manual, not a G7MTClient asset format",
                    "evidence": ["pdf-analysis:fixture"],
                },
                "evidence": ["tree-manifest:doc/manual.pdf", "pdf-analysis:fixture"],
            }
        }

        row = build_resource_inventory(
            complete_raw(conservation={"treeFiles": 1}),
            [TreeManifestEntry(path, source_sha, 5374309)],
            root_id="original",
            adjudications=adjudication,
        )[0]
        normalized = normalize_resource_inventory([row])[0]

        self.assertEqual(row.loader.status.value, "NOT_APPLICABLE")
        self.assertEqual(row.format.status.value, "PROVEN")
        self.assertEqual(row.format.values["detectedFormat"], "PDF_1_4")
        self.assertEqual(row.format.values["detector"], "HASH_BOUND_PDF_ANALYSIS")
        self.assertEqual(normalized["source"]["documentRole"], "ORIGINAL_OPERATION_MANUAL")
        self.assertEqual(normalized["source"]["originalName"], "銀英伝７マニュアル.pdf")
        self.assertEqual(normalized["source"]["pdfAnalysis"]["pageCount"], 69)
        self.assertEqual(
            normalized["source"]["externalDocumentOpen"]["openerKey"],
            "ORIGINAL_CD_ARTIFACT:G7START.EXE",
        )
        self.assertEqual(row.first_missing_boundary, "RUNTIME_OWNER")
        self.assertEqual(sum(row.row.states.values()), 1)

    def test_pdf_operation_manual_receipt_rejects_source_or_analysis_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extractor = root / "inspect.py"
            extractor.write_text("print('inspect')\n", encoding="utf-8")
            extractor_sha = __import__("hashlib").sha256(extractor.read_bytes()).hexdigest().upper()
            receipt = root / "manual-static.json"
            receipt_analysis = {
                "format": "PDF_1_4",
                "role": "ORIGINAL_OPERATION_MANUAL",
                "headerHex": "255044462D312E340D25E2E3CFD30D0A",
                "pdfVersion": "1.4",
                "pageCount": 69,
                "encrypted": True,
                "emptyPasswordAccess": True,
                "title": "銀河英雄伝説Ⅶ　操作説明書",
                "author": "BOTHTEC",
                "creator": "Word 用 Acrobat PDFMaker 5.0",
                "producer": "Acrobat Distiller 5.0.5 (Windows)",
                "creationDate": "D:20040411114123+09'00'",
                "modificationDate": "D:20040411125421+09'00'",
            }
            receipt.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "status": "PROVEN_STATIC",
                        "source": {"sha256": "B" * 64, "byteSize": 5374309},
                        "analysis": receipt_analysis,
                        "staticTools": {
                            "extractor": {"path": str(extractor), "sha256": extractor_sha}
                        },
                    }
                ),
                encoding="utf-8",
            )
            receipt_sha = __import__("hashlib").sha256(receipt.read_bytes()).hexdigest().upper()
            payload_analysis = {
                "status": "PROVEN",
                **receipt_analysis,
                "receiptPath": str(receipt),
                "receiptSha256": receipt_sha,
                "evidence": ["pdf-analysis:fixture"],
            }
            payload = {
                "schemaVersion": 1,
                "source": {
                    "rootId": "original",
                    "sourceManifestSha256": "1" * 64,
                    "treeManifestSha256": "2" * 64,
                },
                "adjudications": [
                    {
                        "relativePosixPath": "doc/manual.pdf",
                        "schemaVersion": 1,
                        "kind": "PDF_OPERATION_MANUAL",
                        "contentSha256": "A" * 64,
                        "byteSize": 5374309,
                        "analysis": payload_analysis,
                        "loader": {
                            "status": "NOT_APPLICABLE",
                            "reason": "Standalone operation manual",
                            "evidence": ["pdf-analysis:fixture"],
                        },
                        "evidence": ["pdf-analysis:fixture"],
                    }
                ],
            }
            path = root / "resources.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "PDF operation manual receipt source differs"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

            payload["adjudications"][0]["contentSha256"] = "B" * 64
            payload["adjudications"][0]["analysis"]["title"] = "Different title"
            path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "PDF operation manual receipt analysis differs"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

            external = {
                "status": "PROVEN",
                "openerKey": "ORIGINAL_CD_ARTIFACT:G7START.EXE",
                "openerName": "G7Start.exe",
                "openerSha256": "C" * 64,
                "openerByteSize": 434176,
                "api": "SHELL32.dll::ShellExecuteA",
                "commandId": 1001,
                "handler": "FUN_00403860",
                "callsite": "0x004038E6",
                "verb": "open",
                "targetOriginalName": "銀英伝７マニュアル.pdf",
                "targetSha256": "B" * 64,
                "evidence": ["g7start:ShellExecuteA:0x004038E6"],
            }
            receipt_payload = json.loads(receipt.read_text(encoding="utf-8"))
            receipt_payload["source"]["sha256"] = "B" * 64
            receipt_payload["analysis"]["title"] = "銀河英雄伝説Ⅶ　操作説明書"
            receipt_payload["externalDocumentOpen"] = external
            receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
            payload["adjudications"][0]["analysis"]["title"] = "銀河英雄伝説Ⅶ　操作説明書"
            payload["adjudications"][0]["analysis"]["receiptSha256"] = (
                __import__("hashlib").sha256(receipt.read_bytes()).hexdigest().upper()
            )
            payload["adjudications"][0]["externalDocumentOpen"] = copy.deepcopy(external)
            payload["adjudications"][0]["externalDocumentOpen"]["callsite"] = "0x004038E7"
            path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(
                ValueError, "PDF operation manual receipt external document open differs"
            ):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

    def test_pdf_operation_manual_receipt_rejects_header_or_referenced_evidence_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            extractor = root / "inspect.py"
            extractor.write_text("print('inspect')\n", encoding="utf-8")
            receipt = root / "manual-static.json"
            analysis = {
                "format": "PDF_1_4",
                "role": "ORIGINAL_OPERATION_MANUAL",
                "headerHex": "255044462D312E350D25E2E3CFD30D0A",
                "pdfVersion": "1.4",
                "pageCount": 69,
                "encrypted": True,
                "emptyPasswordAccess": True,
                "title": "銀河英雄伝説Ⅶ　操作説明書",
                "author": "BOTHTEC",
                "creator": "Word 用 Acrobat PDFMaker 5.0",
                "producer": "Acrobat Distiller 5.0.5 (Windows)",
                "creationDate": "D:20040411114123+09'00'",
                "modificationDate": "D:20040411125421+09'00'",
            }
            receipt.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "status": "PROVEN_STATIC",
                        "source": {"sha256": "A" * 64, "byteSize": 5374309},
                        "analysis": analysis,
                        "staticTools": {
                            "extractor": {"path": str(extractor), "sha256": "F" * 64}
                        },
                    }
                ),
                encoding="utf-8",
            )
            receipt_sha = __import__("hashlib").sha256(receipt.read_bytes()).hexdigest().upper()
            payload = {
                "schemaVersion": 1,
                "source": {
                    "rootId": "original",
                    "sourceManifestSha256": "1" * 64,
                    "treeManifestSha256": "2" * 64,
                },
                "adjudications": [
                    {
                        "relativePosixPath": "doc/manual.pdf",
                        "schemaVersion": 1,
                        "kind": "PDF_OPERATION_MANUAL",
                        "contentSha256": "A" * 64,
                        "byteSize": 5374309,
                        "analysis": {
                            "status": "PROVEN",
                            **analysis,
                            "receiptPath": str(receipt),
                            "receiptSha256": receipt_sha,
                            "evidence": ["pdf-analysis:fixture"],
                        },
                        "loader": {
                            "status": "NOT_APPLICABLE",
                            "reason": "Standalone operation manual",
                            "evidence": ["pdf-analysis:fixture"],
                        },
                        "evidence": ["pdf-analysis:fixture"],
                    }
                ],
            }
            path = root / "resources.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "PDF operation manual header differs"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

            analysis["headerHex"] = "255044462D312E340D25E2E3CFD30D0A"
            payload["adjudications"][0]["analysis"]["headerHex"] = analysis["headerHex"]
            receipt_payload = json.loads(receipt.read_text(encoding="utf-8"))
            receipt_payload["analysis"]["headerHex"] = analysis["headerHex"]
            receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
            payload["adjudications"][0]["analysis"]["receiptSha256"] = (
                hashlib.sha256(receipt.read_bytes()).hexdigest().upper()
            )
            path.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "PDF operation manual referenced evidence hash mismatch"):
                load_resource_adjudications(
                    path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

    def test_hash_bound_cp932_terms_document_closes_loader_without_state_promotion(self) -> None:
        path = "doc/terms.txt"
        source_sha = "A" * 64
        analysis = {
            "status": "PROVEN",
            "format": "CP932_TEXT",
            "role": "ORIGINAL_SERVICE_TERMS",
            "encoding": "CP932",
            "title": "銀河英雄伝説Ⅶ利用規約",
            "characterCount": 4371,
            "lineEnding": "CRLF",
            "receiptPath": "evidence/exhaustive-trace/adjudications/fixture.json",
            "receiptSha256": "B" * 64,
            "evidence": ["terms-analysis:fixture"],
        }
        duplicate = {
            "status": "PROVEN",
            "path": "evidence/installshield-extract/support/license.txt",
            "contentSha256": source_sha,
            "byteSize": 8376,
            "relation": "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY",
            "evidence": ["installshield-support:license.txt"],
        }
        adjudication = {
            path: {
                "schemaVersion": 1,
                "kind": "CP932_TERMS_DOCUMENT",
                "contentSha256": source_sha,
                "byteSize": 8376,
                "analysis": analysis,
                "duplicateSource": duplicate,
                "loader": {
                    "status": "NOT_APPLICABLE",
                    "reason": "Original installer legal document, not a G7MTClient runtime asset",
                    "evidence": ["terms-analysis:fixture"],
                },
                "evidence": ["tree-manifest:doc/terms.txt", "terms-analysis:fixture"],
            }
        }

        row = build_resource_inventory(
            complete_raw(conservation={"treeFiles": 1}),
            [TreeManifestEntry(path, source_sha, 8376)],
            root_id="original",
            adjudications=adjudication,
        )[0]
        normalized = normalize_resource_inventory([row])[0]

        self.assertEqual(row.loader.status.value, "NOT_APPLICABLE")
        self.assertEqual(row.format.status.value, "PROVEN")
        self.assertEqual(row.format.values["detectedFormat"], "CP932_TEXT")
        self.assertEqual(row.format.values["detector"], "HASH_BOUND_TEXT_ANALYSIS")
        self.assertEqual(normalized["source"]["documentRole"], "ORIGINAL_SERVICE_TERMS")
        self.assertEqual(normalized["source"]["textAnalysis"]["title"], "銀河英雄伝説Ⅶ利用規約")
        self.assertEqual(normalized["source"]["duplicateSource"], duplicate)
        self.assertEqual(row.first_missing_boundary, "RUNTIME_OWNER")
        self.assertEqual(sum(row.row.states.values()), 1)

    def test_cp932_terms_receipt_rejects_source_analysis_or_duplicate_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            inspector = root / "inspect.py"
            inspector.write_text("print('inspect')\n", encoding="utf-8")
            duplicate_file = root / "license.txt"
            duplicate_file.write_bytes(b"terms")
            source_sha = hashlib.sha256(duplicate_file.read_bytes()).hexdigest().upper()
            inspector_sha = hashlib.sha256(inspector.read_bytes()).hexdigest().upper()
            analysis = {
                "format": "CP932_TEXT",
                "role": "ORIGINAL_SERVICE_TERMS",
                "encoding": "CP932",
                "title": "銀河英雄伝説Ⅶ利用規約",
                "characterCount": 4371,
                "lineEnding": "CRLF",
            }
            duplicate = {
                "status": "PROVEN",
                "path": str(duplicate_file),
                "contentSha256": source_sha,
                "byteSize": 5,
                "relation": "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY",
                "evidence": ["installshield-support:license.txt"],
            }
            receipt = root / "terms-static.json"
            receipt_payload = {
                "schemaVersion": 1,
                "status": "PROVEN_STATIC",
                "source": {"sha256": source_sha, "byteSize": 5},
                "analysis": analysis,
                "duplicateSource": duplicate,
                "staticTools": {
                    "inspector": {"path": str(inspector), "sha256": inspector_sha},
                    "duplicate": {"path": str(duplicate_file), "sha256": source_sha},
                },
            }
            receipt.write_text(json.dumps(receipt_payload), encoding="utf-8")
            receipt_sha = hashlib.sha256(receipt.read_bytes()).hexdigest().upper()
            payload = {
                "schemaVersion": 1,
                "source": {
                    "rootId": "original",
                    "sourceManifestSha256": "1" * 64,
                    "treeManifestSha256": "2" * 64,
                },
                "adjudications": [{
                    "relativePosixPath": "doc/terms.txt",
                    "schemaVersion": 1,
                    "kind": "CP932_TERMS_DOCUMENT",
                    "contentSha256": source_sha,
                    "byteSize": 5,
                    "analysis": {
                        "status": "PROVEN",
                        **analysis,
                        "receiptPath": str(receipt),
                        "receiptSha256": receipt_sha,
                        "evidence": ["terms-analysis:fixture"],
                    },
                    "duplicateSource": duplicate,
                    "loader": {
                        "status": "NOT_APPLICABLE",
                        "reason": "Original installer legal document",
                        "evidence": ["terms-analysis:fixture"],
                    },
                    "evidence": ["terms-analysis:fixture"],
                }],
            }
            adjudications_path = root / "resources.json"
            adjudications_path.write_text(json.dumps(payload), encoding="utf-8")

            loaded = load_resource_adjudications(
                adjudications_path,
                expected_root_id="original",
                expected_source_manifest_sha256="1" * 64,
                expected_tree_manifest_sha256="2" * 64,
            )
            self.assertEqual(loaded["doc/terms.txt"]["kind"], "CP932_TERMS_DOCUMENT")

            for field, value, message in (
                ("title", "Different title", "CP932 terms receipt analysis differs"),
                ("encoding", "UTF-8", "CP932 terms receipt analysis differs"),
                ("characterCount", 4370, "CP932 terms receipt analysis differs"),
            ):
                broken = copy.deepcopy(payload)
                broken["adjudications"][0]["analysis"][field] = value
                adjudications_path.write_text(json.dumps(broken), encoding="utf-8")
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, message):
                    load_resource_adjudications(
                        adjudications_path,
                        expected_root_id="original",
                        expected_source_manifest_sha256="1" * 64,
                        expected_tree_manifest_sha256="2" * 64,
                    )

            broken = copy.deepcopy(payload)
            broken["adjudications"][0]["duplicateSource"]["byteSize"] = 6
            adjudications_path.write_text(json.dumps(broken), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "CP932 terms receipt duplicate source differs"):
                load_resource_adjudications(
                    adjudications_path,
                    expected_root_id="original",
                    expected_source_manifest_sha256="1" * 64,
                    expected_tree_manifest_sha256="2" * 64,
                )

    def test_cp932_terms_adjudication_schema_is_closed(self) -> None:
        path = "doc/terms.txt"
        base = {
            "schemaVersion": 1,
            "kind": "CP932_TERMS_DOCUMENT",
            "contentSha256": "A" * 64,
            "byteSize": 7,
            "analysis": {
                "status": "PROVEN", "format": "CP932_TEXT",
                "role": "ORIGINAL_SERVICE_TERMS", "encoding": "CP932",
                "title": "銀河英雄伝説Ⅶ利用規約", "characterCount": 4371,
                "lineEnding": "CRLF", "receiptPath": "fixture.json",
                "receiptSha256": "B" * 64, "evidence": ["fixture"],
            },
            "duplicateSource": {
                "status": "PROVEN", "path": "license.txt",
                "contentSha256": "A" * 64, "byteSize": 7,
                "relation": "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY",
                "evidence": ["fixture"],
            },
            "loader": {"status": "NOT_APPLICABLE", "reason": "legal document", "evidence": ["fixture"]},
            "evidence": ["fixture"],
        }
        for section, field in (("top", "invented"), ("analysis", "bom"), ("duplicateSource", "alias")):
            broken = copy.deepcopy(base)
            if section == "top":
                broken[field] = True
                expected = "unknown resource adjudication fields"
            else:
                broken[section][field] = True
                expected = "fields differ"
            with self.subTest(section=section), self.assertRaisesRegex(ValueError, expected):
                build_resource_inventory(
                    complete_raw(conservation={"treeFiles": 1}),
                    [TreeManifestEntry(path, "A" * 64, 7)],
                    root_id="original",
                    adjudications={path: broken},
                )

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
