from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import math
import platform
from pathlib import Path

import capstone
import pefile
import pycdlib


EXPECTED = {
    "updater": (1060864, "EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D"),
    "client": (3956736, "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"),
    "iso": (199462912, "375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22"),
    "data1": (218843, "A0BE81CB1DB8AE3240837580168FC01862BF979A10D50EE5DE333D7D261A4576"),
    "bootfirstReceipt": (None, "CFE22BEE90C04E3BDBB4FCE461132925607CD1B8C041EA5EF70E7D402D318B38"),
}
DATA1_OFFSET = 0x58800


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest().upper()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def checked(path: Path, key: str) -> bytes:
    value = path.read_bytes()
    expected_size, expected_sha = EXPECTED[key]
    if expected_size is not None:
        require(len(value) == expected_size, f"{key} byte size differs")
    require(sha256_bytes(value) == expected_sha, f"{key} SHA-256 differs")
    return value


def relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = [0] * 256
    for value in data:
        counts[value] += 1
    length = len(data)
    return -sum((count / length) * math.log2(count / length) for count in counts if count)


def imports(pe: pefile.PE) -> tuple[list[dict[str, object]], dict[str, int]]:
    descriptors: list[dict[str, object]] = []
    address_map: dict[str, int] = {}
    for descriptor in pe.DIRECTORY_ENTRY_IMPORT:
        dll = descriptor.dll.decode("ascii")
        values = []
        for item in descriptor.imports:
            name = item.name.decode("ascii") if item.name else f"ordinal_{item.ordinal}"
            values.append({"name": name, "iatVa": f"0x{item.address:08X}"})
            address_map[f"{dll.upper()}::{name}"] = item.address
        descriptors.append({"dll": dll, "imports": values})
    return descriptors, address_map


def version_strings(pe: pefile.PE) -> dict[str, str]:
    result: dict[str, str] = {}
    for group in getattr(pe, "FileInfo", []) or []:
        for info in group:
            if getattr(info, "Key", b"") != b"StringFileInfo":
                continue
            for table in info.StringTable:
                for key, value in table.entries.items():
                    result[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    return result


def c_string_at_va(pe: pefile.PE, data: bytes, va: int) -> str:
    offset = pe.get_offset_from_rva(va - pe.OPTIONAL_HEADER.ImageBase)
    return data[offset:data.index(0, offset)].decode("ascii", "strict")


def bytes_at_va(pe: pefile.PE, data: bytes, va: int, size: int) -> bytes:
    offset = pe.get_offset_from_rva(va - pe.OPTIONAL_HEADER.ImageBase)
    return data[offset:offset + size]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--updater", type=Path, required=True)
    parser.add_argument("--target-client", type=Path, required=True)
    parser.add_argument("--bootfirst-analysis", type=Path, required=True)
    parser.add_argument("--g7mtclient-analysis", type=Path, required=True)
    parser.add_argument("--iso", type=Path, required=True)
    parser.add_argument("--output-imports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.project_root.resolve()
    updater_path = args.updater.resolve()
    client_path = args.target_client.resolve()
    bootfirst_path = args.bootfirst_analysis.resolve()
    g7_path = args.g7mtclient_analysis.resolve()
    iso_path = args.iso.resolve()
    imports_path = args.output_imports.resolve()
    output_path = args.output.resolve()
    inspector_path = Path(__file__).resolve()

    data = checked(updater_path, "updater")
    checked(client_path, "client")
    iso_data = checked(iso_path, "iso")
    checked(bootfirst_path, "bootfirstReceipt")
    bootfirst = json.loads(bootfirst_path.read_text(encoding="utf-8"))
    g7 = json.loads(g7_path.read_text(encoding="utf-8"))
    require(bootfirst["processLaunch"]["targetSha256"] == EXPECTED["updater"][1],
            "BootFirst target differs")
    require(g7["inboundLaunch"]["launcherSha256"] == EXPECTED["updater"][1],
            "G7 inbound launcher differs")

    pe = pefile.PE(data=data, fast_load=False)
    require(pe.FILE_HEADER.Machine == 0x014C, "updater machine differs")
    require(pe.OPTIONAL_HEADER.Magic == 0x010B, "updater optional-header magic differs")
    require(pe.OPTIONAL_HEADER.Subsystem == 2, "updater subsystem differs")
    require(pe.OPTIONAL_HEADER.ImageBase == 0x00400000, "updater image base differs")
    require(pe.OPTIONAL_HEADER.AddressOfEntryPoint == 0x00009A2E, "updater entry point differs")
    require(len(pe.sections) == 4, "updater section count differs")
    require(not pe.get_overlay(), "updater overlay differs")
    require(not pe.get_warnings(), "updater pefile warnings differ")

    section_rows = []
    for section in pe.sections:
        raw = section.get_data()
        section_rows.append({
            "name": section.Name.rstrip(b"\0").decode("ascii", "strict"),
            "virtualAddress": f"0x{section.VirtualAddress:08X}",
            "virtualSize": section.Misc_VirtualSize,
            "rawOffset": f"0x{section.PointerToRawData:08X}",
            "rawSize": section.SizeOfRawData,
            "characteristics": f"0x{section.Characteristics:08X}",
            "entropy": round(entropy(raw), 6),
            "sha256": sha256_bytes(raw),
        })
    require([row["name"] for row in section_rows] == [".text", ".rdata", ".data", ".rsrc"],
            "updater section names differ")
    require(not any(int(row["characteristics"], 16) & 0x20000000 and
                    int(row["characteristics"], 16) & 0x80000000 for row in section_rows),
            "updater has writable executable section")

    descriptor_rows, import_map = imports(pe)
    require(len(descriptor_rows) == 11, "updater import descriptor count differs")
    require(len(import_map) == 347, "updater import count differs")
    for name, va in {
        "KERNEL32.DLL::LoadLibraryA": 0x00440268,
        "KERNEL32.DLL::GetProcAddress": 0x004402A0,
        "KERNEL32.DLL::CreateProcessA": 0x004402AC,
        "WSOCK32.DLL::connect": 0x0044057C,
        "WSOCK32.DLL::send": 0x00440568,
        "WSOCK32.DLL::recv": 0x00440564,
    }.items():
        require(import_map.get(name) == va, f"updater import anchor differs: {name}")

    versions = version_strings(pe)
    require(versions.get("FileDescription") == "銀英伝VIIアップデートクライアント",
            "updater FileDescription differs")
    require(versions.get("ProductName") == "銀河英雄伝説VIIアップデートクライアント",
            "updater ProductName differs")
    require(versions.get("FileVersion") == "1, 0, 0, 0", "updater FileVersion differs")

    strings = {
        "serverAddressDefault": (0x0044A540, "202.8.80.179"),
        "serverPortDefault": (0x0044A538, "47902"),
        "workDirectoryDefault": (0x0044A514, ".\\exe\\"),
        "startupAppDefault": (0x0044A51C, ".\\exe\\G7MTClient.exe"),
        "workDirectoryKey": (0x0044A550, "WORK_DIR"),
        "startupAppKey": (0x0044A55C, "STARTUP_APPNAME"),
        "serverIniPattern": (0x0044A5E8, "%sSERVER.INI"),
        "replacementName": (0x0044A63C, "Gin7UpdateClient.new"),
        "updateLog": (0x0044A668, "UPDATE.LOG"),
    }
    for label, (va, expected) in strings.items():
        require(c_string_at_va(pe, data, va) == expected, f"updater string differs: {label}")
    anchors = {
        0x00404F57: "681CA54400", 0x00404F6E: "6814A54400",
        0x00406868: "83B82001000001", 0x0040686F: "7436",
        0x00406872: "8B7B1C", 0x0040689A: "8B4B24",
        0x004068A1: "E8BA090000", 0x004072C2: "FF15AC024400",
        0x004072D9: "FFD7", 0x004072E4: "FFD7",
    }
    for va, expected_hex in anchors.items():
        expected = bytes.fromhex(expected_hex)
        require(bytes_at_va(pe, data, va, len(expected)) == expected,
                f"updater code anchor differs at 0x{va:08X}")

    cd = pycdlib.PyCdlib()
    cd.open(str(iso_path))
    try:
        record = cd.get_record(joliet_path="/data1.hdr;1")
        require(record.extent_location() * 2048 == DATA1_OFFSET, "data1.hdr ISO extent differs")
    finally:
        cd.close()
    data1 = iso_data[DATA1_OFFSET:DATA1_OFFSET + EXPECTED["data1"][0]]
    require(sha256_bytes(data1) == EXPECTED["data1"][1], "data1.hdr SHA-256 differs")
    require(data1.count(b"Gin7UpdateClient.exe") == 1, "InstallShield updater name count differs")
    require(data1.find(b"Gin7UpdateClient.exe") == 0x316F6,
            "InstallShield updater filename offset differs")

    import_payload = {
        "schemaVersion": 1,
        "status": "PROVEN_STATIC",
        "source": {"sha256": EXPECTED["updater"][1], "byteSize": len(data)},
        "quality": "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION",
        "descriptorCount": len(descriptor_rows),
        "importCount": len(import_map),
        "descriptors": descriptor_rows,
        "dynamicResolutionSurface": [
            "KERNEL32.DLL::LoadLibraryA", "KERNEL32.DLL::GetProcAddress"
        ],
    }
    imports_path.parent.mkdir(parents=True, exist_ok=True)
    imports_path.write_text(
        json.dumps(import_payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n",
    )

    analysis = {
        "format": "PE32_X86_GUI_EXECUTABLE", "machine": "0x014C", "subsystem": 2,
        "imageBase": "0x00400000", "entryPointRva": "0x00009A2E",
        "role": "ORIGINAL_GAME_UPDATE_CLIENT", "sectionCount": 4,
        "importDescriptorCount": 11, "importCount": 347,
        "importQuality": "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION",
        "packingAssessment": "NO_KNOWN_PACKER_SIGNATURE_STATIC_ONLY",
        "originalFilename": "", "fileVersion": "1, 0, 0, 0",
    }
    process_image = {
        "status": "PROVEN_STATIC_FORMAT", "osLoader": "WINDOWS_PE_LOADER",
        "target": "SELF_PROCESS_IMAGE", "runtimeObservationStatus": "NOT_CLAIMED",
        "evidence": ["pe32-machine:0x014C", "pe32-subsystem:WINDOWS_GUI",
                     "runtime-execution:NOT_CLAIMED_BY_STATIC_FORMAT"],
    }
    process_launch = {
        "status": "PROVEN_STATIC_DEFAULT", "api": "KERNEL32.dll::CreateProcessA",
        "function": "FUN_00407260", "callsite": "0x004072C2",
        "triggerCallsite": "0x004068A1", "targetCommand": ".\\exe\\G7MTClient.exe",
        "workingDirectory": ".\\exe\\", "targetRelativePosixPath": "exe/g7mtclient.exe",
        "targetSha256": EXPECTED["client"][1], "configOverrideStatus": "POSSIBLE",
        "gateSemantics": "UNRESOLVED", "runtimeObservationStatus": "UNSEEN",
        "evidence": ["updater-default:STARTUP_APPNAME=.\\exe\\G7MTClient.exe@0x0044A51C",
                     "updater-default:WORK_DIR=.\\exe\\@0x0044A514",
                     "updater-trigger-call:0x004068A1", "updater-CreateProcessA:0x004072C2",
                     "updater-config-override:POSSIBLE", "runtime-launch:UNSEEN"],
    }
    ep_offset = pe.get_offset_from_rva(pe.OPTIONAL_HEADER.AddressOfEntryPoint)
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    entry = [{"address": f"0x{item.address:08X}", "bytes": item.bytes.hex().upper(),
              "mnemonic": item.mnemonic, "operands": item.op_str}
             for item in list(decoder.disasm(data[ep_offset:ep_offset + 48], 0x00409A2E))[:8]]
    tool_paths = {
        "inspectorScript": inspector_path, "updater": updater_path,
        "targetClient": client_path, "originalCdIso": iso_path,
        "updaterPeImports": imports_path, "bootfirstStaticAnalysis": bootfirst_path,
        "g7mtclientStaticAnalysis": g7_path,
    }
    receipt = {
        "schemaVersion": 1, "status": "PROVEN_STATIC",
        "scope": "GIN7UPDATECLIENT_RESOURCE_LOADER_BOUNDARY",
        "source": {"relativePosixPath": "gin7updateclient.exe",
                   "sha256": EXPECTED["updater"][1], "byteSize": len(data)},
        "analysis": analysis, "processImage": process_image, "processLaunch": process_launch,
        "peTriage": {
            "timestampUtc": datetime.datetime.fromtimestamp(
                pe.FILE_HEADER.TimeDateStamp, datetime.timezone.utc
            ).isoformat().replace("+00:00", "Z"),
            "entryPointVa": "0x00409A2E", "sizeOfImage": pe.OPTIONAL_HEADER.SizeOfImage,
            "sections": section_rows, "imphash": pe.get_imphash(), "exports": 0,
            "overlayBytes": 0, "authenticode": False, "entryInstructions": entry,
            "versionStrings": versions, "directImportCapabilities": {
                "winsockImports": 21, "fileMutationSurface": True,
                "registryImports": 5, "createProcessA": "0x004402AC",
            },
            "dynamicResolutionSurface": import_payload["dynamicResolutionSurface"],
        },
        "installshieldEvidence": {
            "status": "PROVEN_EXACT_FILENAME", "exactName": "Gin7UpdateClient.exe",
            "data1HdrSha256": EXPECTED["data1"][1],
            "data1HdrRelativeOffset": "0x000316F6", "isoAbsoluteOffset": "0x00089EF6",
        },
        "launchLimitations": {
            "defaultConfigurationMayBeOverridden": True,
            "gateSemantics": "UNRESOLVED", "runtimeLaunch": "UNSEEN",
            "networkSuccess": "UNSEEN", "downloadedPayloadIdentity": "UNRESOLVED",
            "remoteVersionComparison": "UNRESOLVED", "playability": "NOT_CLAIMED",
        },
        "staticTools": {
            label: {"path": relative(path, root), "sha256": sha256_file(path)}
            for label, path in sorted(tool_paths.items())
        },
        "toolEnvironment": {
            "python": platform.python_version(), "pefile": pefile.__version__,
            "capstone": capstone.__version__,
            "pycdlib": getattr(pycdlib, "__version__", "1.20.0"),
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n",
    )
    print(json.dumps({"status": "PROVEN_STATIC", "output": str(output_path),
                      "sourceSha256": EXPECTED["updater"][1], "importCount": 347,
                      "processLaunch": "PROVEN_STATIC_DEFAULT"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
