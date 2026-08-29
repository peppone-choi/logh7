"""Reproduce the hash-bound original update.ini loader adjudication receipt."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path

import pefile


SOURCE_SHA = "EBB093A34852454DD8D15CA14E95804D9200416B8724CD4F445770B07C17EF7C"
UPDATER_SHA = "EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D"
INSTALLED_SHA = "F89660546D6D0C7D4A00EFDCAA73E5120916C730E07E6CCFE7D8FF111FD71A88"
EXACT_BYTES = (
    b"[UPDATE]\r\nVERSION=131\r\nBASE_DIR        =.\\\r\nTEMP_DIR        =\r\n"
    b"STARTUP_APPNAME =\r\nWORK_DIR        =\r\nLAST_ERROR=0x00000003\r\n"
)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def record(path: Path) -> dict[str, str]:
    return {"path": str(path), "sha256": sha(path)}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def va_bytes(pe: pefile.PE, data: bytes, va: int, size: int) -> bytes:
    rva = va - pe.OPTIONAL_HEADER.ImageBase
    offset = pe.get_offset_from_rva(rva)
    return data[offset : offset + size]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument(
        "--installed-tree-csv", type=Path,
        default=Path(r"E:\logh7-vms\oracle-win11-hd\captures\oracle-original-install-debug-20260824\guest-installed-tree.csv"),
    )
    parser.add_argument("--iso", type=Path, default=Path(r"E:\logh7-vm-media\LOGH7-original-cd.iso"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    source = root / "evidence/installshield-extract/____________s___/____/update.ini"
    updater = root / "evidence/installshield-extract/____________s___/____/gin7updateclient.exe"
    imports_receipt = root / "work/20260829-gin7updateclient-resource-loader/evidence/gin7updateclient-imports.json"
    updater_receipt = root / "evidence/exhaustive-trace/adjudications/gin7updateclient-static-analysis.json"
    tree_manifest = root / "evidence/oracle-installshield-manual-fallback-20260824/MANIFEST.sha256"
    inspector = Path(__file__).resolve()

    source_bytes = source.read_bytes()
    require(source_bytes == EXACT_BYTES, "update.ini exact bytes differ")
    require(sha(source) == SOURCE_SHA and source.stat().st_size == 124, "update.ini identity differs")
    require(source_bytes.decode("ascii").encode("ascii") == source_bytes, "update.ini is not strict ASCII")
    require(source_bytes.count(b"\r\n") == 7 and source_bytes.endswith(b"\r\n"), "update.ini CRLF contract differs")
    require(b"\n" not in source_bytes.replace(b"\r\n", b""), "update.ini has lone LF")
    require(b"\r" not in source_bytes.replace(b"\r\n", b""), "update.ini has lone CR")
    require(sha(updater) == UPDATER_SHA, "updater identity differs")

    updater_data = updater.read_bytes()
    pe = pefile.PE(data=updater_data, fast_load=False)
    imports = {
        entry.name.decode("ascii")
        for descriptor in pe.DIRECTORY_ENTRY_IMPORT
        for entry in descriptor.imports
        if entry.name
    }
    required_imports = {
        "GetModuleFileNameA", "LoadStringA", "GetPrivateProfileIntA",
        "GetPrivateProfileStringA", "WritePrivateProfileStringA",
    }
    require(required_imports <= imports, "updater profile API imports differ")
    anchors = {
        "pathFormatCall": (0x00404BA9, bytes.fromhex("E86EF20200")),
        "configReadCall": (0x00404BB7, bytes.fromhex("E804020000")),
        "profileIntRead": (0x00404E13, bytes.fromhex("FF15C0024400")),
        "profileStringRead": (0x00404FF7, bytes.fromhex("FF15BC024400")),
        "versionWrite": (0x0040508F, bytes.fromhex("FF15A8024400")),
        "lastErrorWrite": (0x00406F24, bytes.fromhex("FF15A8024400")),
        "loadString": (0x00439C90, bytes.fromhex("FF1568034400")),
    }
    for label, (va, expected) in anchors.items():
        require(va_bytes(pe, updater_data, va, len(expected)) == expected, f"{label} anchor differs")

    # RT_STRING block 1, language 1041: find exact UTF-16 payload for string id 3.
    rt_payload = "%supdate.ini".encode("utf-16le")
    require(updater_data.count(rt_payload) == 1, "RT_STRING id 3 payload occurrence differs")
    require(updater_data.find(rt_payload) == 0x101C4A, "RT_STRING id 3 file offset differs")

    manifest_line = f"{SOURCE_SHA.lower()} *LOGH7/update.ini"
    require(manifest_line in tree_manifest.read_text(encoding="utf-8"), "tree manifest binding differs")
    installed_rows = list(csv.DictReader(args.installed_tree_csv.open("r", encoding="utf-8-sig", newline="")))
    installed = next((row for row in installed_rows if row.get("RelativePath", row.get("relativePath", "")).lower() == "update.ini"), None)
    require(installed is not None, "installed update.ini row is missing")
    installed_values = {str(value).upper() for value in installed.values() if value is not None}
    require(INSTALLED_SHA in installed_values, "installed update.ini hash differs")

    ordered_entries = [
        {"key": "VERSION", "value": "131"},
        {"key": "BASE_DIR", "value": ".\\"},
        {"key": "TEMP_DIR", "value": ""},
        {"key": "STARTUP_APPNAME", "value": ""},
        {"key": "WORK_DIR", "value": ""},
        {"key": "LAST_ERROR", "value": "0x00000003"},
    ]
    analysis = {
        "format": "ASCII_INI", "encoding": "ASCII",
        "role": "ORIGINAL_UPDATE_CLIENT_CONFIGURATION", "section": "UPDATE",
        "byteSize": 124, "lineCount": 7, "crlfCount": 7,
        "trailingCrlf": True, "orderedEntries": ordered_entries,
    }
    access_common = {
        "pathArgumentProvenance": "APP_PLUS_0xD8", "sectionArgument": "UPDATE",
        "status": "PROVEN_STATIC",
    }
    external_access = {
        "status": "PROVEN_STATIC",
        "consumerRowKey": "RESOURCE:FILE:original-installshield-payload:gin7updateclient.exe",
        "consumerRelativePosixPath": "gin7updateclient.exe", "consumerSha256": UPDATER_SHA,
        "targetRelativePosixPath": "update.ini", "targetSha256": SOURCE_SHA,
        "pathResolution": {
            "status": "PROVEN_STATIC", "resourceType": "RT_STRING", "resourceId": 3,
            "language": 1041, "template": "%supdate.ini",
            "moduleDirectoryFunctionVa": "0x00404A80", "formatFunctionVa": "0x00433E1C",
            "formatCallsiteVa": "0x00404BA9", "destinationFieldOffset": "0xD8",
            "result": "<module-directory>update.ini",
        },
        "section": "UPDATE",
        "readAccesses": [
            {"accessId": "READ:VERSION:00404E13", "operation": "READ",
             "api": "KERNEL32.dll::GetPrivateProfileIntA", "consumerFunctionVa": "0x00404DC0",
             "callsiteVa": "0x00404E13", **access_common, "keyArgument": "VERSION",
             "keyResolution": "EXACT_LITERAL", "evidence": ["updater:profile-int-read:0x00404E13"]},
            {"accessId": "READ:STRING-KEYS:00404FF7", "operation": "READ",
             "api": "KERNEL32.dll::GetPrivateProfileStringA", "consumerFunctionVa": "0x00404FD0",
             "callsiteVa": "0x00404FF7", **access_common,
             "keyArgument": "SERVER_ADDRESS|SERVER_PORT|PROXY_ADDRESS|PROXY_PORT|BASE_DIR|TEMP_DIR|STARTUP_APPNAME|WORK_DIR",
             "keyResolution": "VARIABLE", "evidence": ["updater:profile-string-read-helper:0x00404FF7"]},
        ],
        "writeAccesses": [
            {"accessId": "WRITE:VERSION:0040508F", "operation": "WRITE",
             "api": "KERNEL32.dll::WritePrivateProfileStringA", "consumerFunctionVa": "0x00405060",
             "callsiteVa": "0x0040508F", **access_common, "keyArgument": "VERSION",
             "keyResolution": "EXACT_LITERAL", "evidence": ["updater:version-write:0x0040508F"]},
            {"accessId": "WRITE:LAST_ERROR:00406F24", "operation": "WRITE",
             "api": "KERNEL32.dll::WritePrivateProfileStringA", "consumerFunctionVa": "0x00406ED0",
             "callsiteVa": "0x00406F24", **access_common, "keyArgument": "LAST_ERROR",
             "keyResolution": "EXACT_LITERAL", "evidence": ["updater:last-error-write:0x00406F24"]},
        ],
        "runtimeObservationStatus": "UNSEEN",
        "evidence": ["updater:module-directory-path-flow:0x00404A80-0x00404BB7", "updater:profile-api-dataflow:PROVEN_STATIC"],
    }
    receipt = {
        "schemaVersion": 1, "status": "PROVEN_STATIC",
        "scope": "UPDATE_INI_RESOURCE_LOADER_BOUNDARY",
        "source": {"path": str(source), "sha256": SOURCE_SHA, "byteSize": 124},
        "analysis": analysis,
        "externalConfigAccess": external_access,
        "installshieldEvidence": {
            "originalCdIsoSha256": sha(args.iso), "data1HdrFilenameOffset": "0x000316EB",
            "treeManifestPath": str(tree_manifest), "treeManifestSha256": sha(tree_manifest),
        },
        "installedMutationObservation": {
            "status": "INSTALLED_POST_SETUP_UPDATER_DIVERGENT", "causality": "INFERRED",
            "installedSha256": INSTALLED_SHA, "installedByteSize": 124,
            "originalBytesPreserved": False,
        },
        "staticTools": {
            "inspectorScript": record(inspector), "updateIni": record(source),
            "updater": record(updater), "updaterPeImports": record(imports_receipt),
            "updaterStaticAnalysis": record(updater_receipt), "originalCdIso": record(args.iso),
            "treeManifest": record(tree_manifest), "installedTreeCsv": record(args.installed_tree_csv),
        },
        "limitations": {
            "actualRuntimeReadWrite": "UNSEEN", "installedBytes": "NOT_PRESERVED",
            "installedDivergenceCause": "INFERRED", "networkSuccess": "UNSEEN",
            "playability": "NOT_CLAIMED",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
    print("UPDATE_INI_RESOURCE_LOADER_PROVEN_STATIC")


if __name__ == "__main__":
    main()
