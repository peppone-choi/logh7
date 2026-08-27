from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.exhaustive_trace.io import sha256_file
from tools.exhaustive_trace.source_manifest import (
    CLIENT_SHA256,
    SourceManifest,
    classify_import_groups,
    sha256_tree,
    ghidra_program_database_sha256,
    validate_import_gate,
)


GROUP_NAMES = (
    "direct3d8", "directinput8", "directsound", "winsock", "filesystem",
    "registry", "timing", "process_thread",
)


class GhidraProgramDatabaseHashTests(unittest.TestCase):
    def test_volatile_repository_indexes_do_not_change_program_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "idata" / "00" / "~00000000.db" / "db.1.gbf"
            database.parent.mkdir(parents=True)
            database.write_bytes(b"semantic-program")
            index = root / "idata" / "~index.dat"
            index.write_bytes(b"first")
            first = ghidra_program_database_sha256(root)
            index.write_bytes(b"changed-on-read")
            self.assertEqual(ghidra_program_database_sha256(root), first)

    def test_multiple_program_databases_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "idata" / "00" / "~00000000.db" / "db.1.gbf"
            database.parent.mkdir(parents=True)
            database.write_bytes(b"one")
            (database.parent / "db.2.gbf").write_bytes(b"two")
            with self.assertRaisesRegex(ValueError, "exactly one"):
                ghidra_program_database_sha256(root)


def import_payload(client: Path, bound_files: dict[str, Path]) -> dict[str, object]:
    imports = [
        {"dll": "D3D8.DLL", "name": "Direct3DCreate8"},
        {"dll": "DINPUT8.DLL", "name": "DirectInput8Create"},
        {"dll": "DSOUND.DLL", "ordinal": 11, "resolvedName": "DirectSoundCreate8"},
        {"dll": "WS2_32.DLL", "name": "socket"},
        {"dll": "KERNEL32.DLL", "name": "CreateFileA"},
        {"dll": "ADVAPI32.DLL", "name": "RegOpenKeyA"},
        {"dll": "WINMM.DLL", "name": "timeGetTime"},
        {"dll": "KERNEL32.DLL", "name": "CreateThread"},
    ]
    headers = {
        "PROGRAM": "client.bin",
        "LANGUAGE": "x86:LE:32:default",
        "COMPILER": "windows",
        "IMAGE_BASE": "00400000",
        "EXECUTABLE_SHA256": sha256_file(client).lower(),
    }
    return {
        "schemaVersion": 1,
        "format": "PE32",
        "architecture": "x86",
        "quality": "readable",
        "source": {
            "path": str(client),
            "size": client.stat().st_size,
            "executableSha256": sha256_file(client),
            "machine": "0x014C",
            "optionalHeaderMagic": "0x010B",
            "imageBase": "0x00400000",
        },
        "generator": {
            "builder": {
                "path": str(bound_files["builder"]),
                "sha256": sha256_file(bound_files["builder"]),
            },
            "rawPeParser": {
                "path": str(bound_files["parser"]),
                "sha256": sha256_file(bound_files["parser"]),
            },
            "ghidraCrossCheck": {
                "exporterPath": str(bound_files["exporter"]),
                "exporterSha256": sha256_file(bound_files["exporter"]),
                "outputPath": str(bound_files["ghidra_output"]),
                "outputSha256": sha256_file(bound_files["ghidra_output"]),
                "headers": headers,
            },
        },
        "descriptorCount": 1,
        "importCount": len(imports),
        "imports": imports,
        "groups": classify_import_groups(imports),
        "audit": {
            "runtimeDynamicResolutionNotCovered": True,
            "limitation": "Fixture covers only static imports.",
        },
    }


def build_fixture(root: Path) -> tuple[Path, dict[str, str], Path]:
    client = root / "client.bin"
    client.write_bytes(b"client")
    message = root / "constmsg.dat"
    message.write_bytes(b"messages")

    resource_root = root / "resources"
    resource_root.mkdir()
    resource_file = resource_root / "asset.bin"
    resource_file.write_bytes(b"asset")
    tree_manifest = root / "MANIFEST.sha256"
    tree_manifest.write_text(
        f"{sha256_file(resource_file).lower()} *LOGH7/asset.bin\n", encoding="utf-8"
    )

    source_paths = {
        "original-cd-iso": root / "original.iso",
        "cd-manual-v1": root / "manual-v1.pdf",
        "official-web-manual-2004-10-07": root / "manual-web.pdf",
        "ghidra-import-exporter": root / "Export.java",
        "ghidra-import-export": root / "ghidra-output.txt",
        "pe-import-generator": root / "builder.py",
    }
    for identifier, path in source_paths.items():
        path.write_bytes(identifier.encode("ascii"))

    ghidra_output = source_paths["ghidra-import-export"]
    ghidra_output.write_text(
        "PROGRAM=client.bin\n"
        "LANGUAGE=x86:LE:32:default\n"
        "COMPILER=windows\n"
        "IMAGE_BASE=00400000\n"
        f"EXECUTABLE_SHA256={sha256_file(client).lower()}\n"
        "===== IMPORT SUMMARY =====\n",
        encoding="utf-8",
    )
    parser = root / "parser.py"
    parser.write_bytes(b"parser")
    tool = root / "tool.exe"
    tool.write_bytes(b"tool")

    project_dir = root / "ghidra"
    project_dir.mkdir()
    (project_dir / "Fixture.gpr").write_bytes(b"")
    repository = project_dir / "Fixture.rep"
    repository.mkdir()
    (repository / "db.bin").write_bytes(b"database")

    evidence = root / "pe-imports.json"
    bound_files = {
        "builder": source_paths["pe-import-generator"],
        "parser": parser,
        "exporter": source_paths["ghidra-import-exporter"],
        "ghidra_output": ghidra_output,
    }
    evidence.write_text(json.dumps(import_payload(client, bound_files)), encoding="utf-8")

    sources = [
        {
            "id": identifier,
            "path": str(path),
            "sha256": sha256_file(path),
            "authority": "ORIGINAL_MANUAL" if "manual" in identifier else "INFERRED",
        }
        for identifier, path in source_paths.items()
    ]
    manifest = root / "source-manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "client": {
                    "path": client.name,
                    "sha256": sha256_file(client),
                    "authority": "ORIGINAL_OBSERVED",
                },
                "messageData": {
                    "path": message.name,
                    "sha256": sha256_file(message),
                    "authority": "ORIGINAL_OBSERVED",
                },
                "peImports": {"path": evidence.name, "sha256": sha256_file(evidence)},
                "sources": sources,
                "resourceRoots": [
                    {
                        "id": "resources",
                        "path": str(resource_root),
                        "authority": "ORIGINAL_OBSERVED",
                        "pathPrefix": "LOGH7/",
                        "treeManifest": {
                            "path": str(tree_manifest),
                            "sha256": sha256_file(tree_manifest),
                        },
                    }
                ],
                "manualPolicy": {"editionsRemainDistinct": True},
                "tools": {
                    "pefile": {"path": str(parser), "sha256": sha256_file(parser)},
                    "fixture": {"path": str(tool), "sha256": sha256_file(tool)},
                },
                "ghidra": {
                    "projectDir": project_dir.name,
                    "projectName": "Fixture",
                    "programName": "client.bin",
                    "programExecutableSha256": sha256_file(client),
                    "repositorySha256": sha256_tree(repository),
                    "language": "x86:LE:32:default",
                    "compiler": "windows",
                    "imageBase": "0x00400000",
                },
            }
        ),
        encoding="utf-8",
    )
    frozen = {
        identifier: sha256_file(source_paths[identifier])
        for identifier in (
            "original-cd-iso", "cd-manual-v1", "official-web-manual-2004-10-07"
        )
    }
    return manifest, frozen, resource_file


class ImportGateTests(unittest.TestCase):
    def test_import_gate_requires_quality(self) -> None:
        with self.assertRaisesRegex(ValueError, "quality"):
            validate_import_gate(
                {"schemaVersion": 1, "format": "PE32", "architecture": "x86"}
            )

    def test_import_gate_requires_every_named_group(self) -> None:
        payload = {
            "schemaVersion": 1,
            "format": "PE32",
            "architecture": "x86",
            "quality": "readable",
            "imports": [{"dll": "A.DLL", "name": "A"}],
            "importCount": 1,
            "descriptorCount": 1,
            "groups": {"direct3d8": ["A.DLL::A"]},
        }
        with self.assertRaisesRegex(ValueError, "groups"):
            validate_import_gate(payload)

    def test_readable_import_gate_rejects_empty_inventory(self) -> None:
        payload = {
            "schemaVersion": 1,
            "format": "PE32",
            "architecture": "x86",
            "quality": "readable",
            "imports": [],
            "importCount": 0,
            "descriptorCount": 1,
            "groups": {name: [] for name in GROUP_NAMES},
        }
        with self.assertRaisesRegex(ValueError, "must contain imports"):
            validate_import_gate(payload)

    def test_import_gate_rejects_unapproved_quality(self) -> None:
        with self.assertRaisesRegex(ValueError, "quality"):
            validate_import_gate(
                {
                    "schemaVersion": 1,
                    "format": "PE32",
                    "architecture": "x86",
                    "quality": "guessed",
                }
            )

    def test_import_gate_requires_generator_provenance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            client = root / "client.bin"
            client.write_bytes(b"client")
            files = {}
            for name in ("builder", "parser", "exporter", "ghidra_output"):
                files[name] = root / name
                files[name].write_bytes(name.encode("ascii"))
            payload = import_payload(client, files)
            del payload["generator"]
            with self.assertRaisesRegex(ValueError, "generator"):
                validate_import_gate(payload)

    def test_import_gate_rejects_wrong_group_classification(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            client = root / "client.bin"
            client.write_bytes(b"client")
            files = {}
            for name in ("builder", "parser", "exporter", "ghidra_output"):
                files[name] = root / name
                files[name].write_bytes(name.encode("ascii"))
            payload = import_payload(client, files)
            payload["groups"]["direct3d8"] = ["WS2_32.DLL::socket"]
            with self.assertRaisesRegex(ValueError, "classification rules"):
                validate_import_gate(payload)


class SourceManifestTests(unittest.TestCase):
    def test_changed_client_hash_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            client = root / "G7MTClient.exe"
            client.write_bytes(b"changed client")
            manifest = root / "source-manifest.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "client": {
                            "path": client.name,
                            "sha256": CLIENT_SHA256,
                            "authority": "ORIGINAL_OBSERVED",
                        },
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "client hash mismatch"):
                SourceManifest.load(manifest)

    def _load_fixture(self, root: Path, manifest: Path, frozen: dict[str, str]) -> SourceManifest:
        return SourceManifest._load(
            manifest,
            expected_client_sha256=sha256_file(root / "client.bin"),
            expected_message_data_sha256=sha256_file(root / "constmsg.dat"),
            expected_source_sha256=frozen,
        )

    def test_message_data_is_required(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, _ = build_fixture(root)
            payload = json.loads(manifest.read_text(encoding="utf-8"))
            del payload["messageData"]
            manifest.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "messageData"):
                self._load_fixture(root, manifest, frozen)

    def test_resource_tree_mutation_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, resource_file = build_fixture(root)
            resource_file.write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "resource tree mismatch"):
                self._load_fixture(root, manifest, frozen)

    def test_unlisted_resource_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, resource_file = build_fixture(root)
            (resource_file.parent / "unlisted.bin").write_bytes(b"unlisted")
            with self.assertRaisesRegex(ValueError, "file set mismatch"):
                self._load_fixture(root, manifest, frozen)

    def test_missing_ghidra_repository_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, _ = build_fixture(root)
            repository = root / "ghidra" / "Fixture.rep"
            (repository / "db.bin").unlink()
            repository.rmdir()
            with self.assertRaisesRegex(ValueError, "repository is missing"):
                self._load_fixture(root, manifest, frozen)

    def test_raw_parser_must_match_manifest_tool(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, _ = build_fixture(root)
            payload = json.loads(manifest.read_text(encoding="utf-8"))
            wrong_parser = root / "wrong-parser.py"
            wrong_parser.write_bytes(b"wrong")
            payload["tools"]["pefile"] = {
                "path": str(wrong_parser),
                "sha256": sha256_file(wrong_parser),
            }
            manifest.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "differs from tools.pefile"):
                self._load_fixture(root, manifest, frozen)

    def test_valid_manifest_verifies_all_bound_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            manifest, frozen, _ = build_fixture(root)
            loaded = self._load_fixture(root, manifest, frozen)
            self.assertEqual(loaded.client_sha256, sha256_file(root / "client.bin"))
            self.assertEqual(loaded.import_quality, "readable")


if __name__ == "__main__":
    unittest.main()
