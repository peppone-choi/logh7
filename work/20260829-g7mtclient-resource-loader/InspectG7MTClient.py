from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
from pathlib import Path

import capstone
import pefile
import pycdlib


EXPECTED = {
    "client": (3956736, "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"),
    "updater": (1060864, "EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D"),
    "bootfirst": (40960, "23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096"),
    "iso": (199462912, "375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22"),
    "data1": (218843, "A0BE81CB1DB8AE3240837580168FC01862BF979A10D50EE5DE333D7D261A4576"),
    "g7start": (434176, "1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93"),
    "peImports": (None, "E0C5BADAE9C5062B2E9F767AB88BA22E557ED99004B8B165BEA7B196DF9A3FBE"),
}
DATA1_OFFSET = 0x58800
G7START_OFFSET = 0x954A800


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


def relative(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path.resolve())


def checked(path: Path, key: str) -> bytes:
    data = path.read_bytes()
    expected_size, expected_sha = EXPECTED[key]
    if expected_size is not None:
        require(len(data) == expected_size, f"{key} byte size differs")
    require(sha256_bytes(data) == expected_sha, f"{key} SHA-256 differs")
    return data


def entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = [0] * 256
    for value in data:
        counts[value] += 1
    length = len(data)
    return -sum((count / length) * math.log2(count / length) for count in counts if count)


def version_strings(pe: pefile.PE) -> dict[str, str]:
    result: dict[str, str] = {}
    for file_info_group in getattr(pe, "FileInfo", []) or []:
        for file_info in file_info_group:
            if getattr(file_info, "Key", b"") != b"StringFileInfo":
                continue
            for table in file_info.StringTable:
                for key, value in table.entries.items():
                    result[key.decode("utf-8", "replace")] = value.decode("utf-8", "replace")
    return result


def bytes_at_va(pe: pefile.PE, data: bytes, va: int, size: int) -> bytes:
    rva = va - pe.OPTIONAL_HEADER.ImageBase
    offset = pe.get_offset_from_rva(rva)
    return data[offset : offset + size]


def c_string_at_va(pe: pefile.PE, data: bytes, va: int) -> str:
    rva = va - pe.OPTIONAL_HEADER.ImageBase
    offset = pe.get_offset_from_rva(rva)
    end = data.index(0, offset)
    return data[offset:end].decode("ascii", "strict")


def import_map(pe: pefile.PE) -> dict[str, int]:
    result: dict[str, int] = {}
    for descriptor in pe.DIRECTORY_ENTRY_IMPORT:
        dll = descriptor.dll.decode("ascii").upper()
        for entry in descriptor.imports:
            name = entry.name.decode("ascii") if entry.name else f"ordinal_{entry.ordinal}"
            result[f"{dll}::{name}"] = entry.address
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--updater", type=Path, required=True)
    parser.add_argument("--bootfirst", type=Path, required=True)
    parser.add_argument("--iso", type=Path, required=True)
    parser.add_argument("--pe-imports", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.project_root.resolve()
    client_path = args.client.resolve()
    updater_path = args.updater.resolve()
    iso_path = args.iso.resolve()
    pe_imports_path = args.pe_imports.resolve()
    output_path = args.output.resolve()
    inspector_path = Path(__file__).resolve()

    client_data = checked(client_path, "client")
    updater_data = checked(updater_path, "updater")
    checked(args.bootfirst.resolve(), "bootfirst")
    iso_data = checked(iso_path, "iso")
    pe_imports_data = checked(pe_imports_path, "peImports")

    client = pefile.PE(data=client_data, fast_load=False)
    require(client.FILE_HEADER.Machine == 0x014C, "client machine differs")
    require(client.OPTIONAL_HEADER.Magic == 0x010B, "client optional-header magic differs")
    require(client.OPTIONAL_HEADER.Subsystem == 2, "client subsystem differs")
    require(client.OPTIONAL_HEADER.ImageBase == 0x00400000, "client image base differs")
    require(client.OPTIONAL_HEADER.AddressOfEntryPoint == 0x00201FBC, "client entry point differs")
    require(len(client.sections) == 5, "client section count differs")
    require(not client.get_overlay(), "client overlay differs")
    require(not client.get_warnings(), "client pefile warnings differ")

    sections = []
    for section in client.sections:
        name = section.Name.rstrip(b"\0").decode("ascii", "strict")
        raw = section.get_data()
        sections.append({
            "name": name,
            "virtualAddress": f"0x{section.VirtualAddress:08X}",
            "virtualSize": section.Misc_VirtualSize,
            "rawOffset": f"0x{section.PointerToRawData:08X}",
            "rawSize": section.SizeOfRawData,
            "characteristics": f"0x{section.Characteristics:08X}",
            "entropy": round(entropy(raw), 6),
            "sha256": sha256_bytes(raw),
        })
    require([item["name"] for item in sections] == [".text", ".rdata", ".data", ".data1", ".rsrc"],
            "client section names differ")
    require(not any(int(item["characteristics"], 16) & 0x20000000 and
                    int(item["characteristics"], 16) & 0x80000000 for item in sections),
            "client has writable executable section")

    imports = import_map(client)
    require(len(client.DIRECTORY_ENTRY_IMPORT) == 19, "client import descriptor count differs")
    require(len(imports) == 452, "client import count differs")
    require(imports.get("KERNEL32.DLL::LoadLibraryA") is not None, "LoadLibraryA import missing")
    require(imports.get("KERNEL32.DLL::GetProcAddress") is not None, "GetProcAddress import missing")
    for name in (
        "KERNEL32.DLL::CreateProcessA", "KERNEL32.DLL::CreateProcessW",
        "KERNEL32.DLL::WinExec", "SHELL32.DLL::ShellExecuteA", "SHELL32.DLL::ShellExecuteW",
    ):
        require(name not in imports, f"unexpected client outbound process import: {name}")

    pe_imports = json.loads(pe_imports_data.decode("utf-8"))
    require(pe_imports["source"]["executableSha256"] == EXPECTED["client"][1],
            "pe-imports source differs")
    require(pe_imports["descriptorCount"] == 19 and pe_imports["importCount"] == 452,
            "pe-imports counts differ")
    require(pe_imports["quality"] == "readable", "pe-imports quality differs")
    require(pe_imports["audit"]["runtimeDynamicResolutionNotCovered"] is True,
            "pe-imports dynamic-resolution limitation differs")

    versions = version_strings(client)
    require(versions.get("OriginalFilename") == "G7MTClient.EXE", "OriginalFilename differs")
    require(versions.get("InternalName") == "G7MTClient", "InternalName differs")
    require(versions.get("FileVersion") == "1, 0, 0, 1", "FileVersion differs")

    ep_offset = client.get_offset_from_rva(client.OPTIONAL_HEADER.AddressOfEntryPoint)
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    ep_instructions = [
        {"address": f"0x{item.address:08X}", "bytes": item.bytes.hex().upper(),
         "mnemonic": item.mnemonic, "operands": item.op_str}
        for item in list(decoder.disasm(client_data[ep_offset : ep_offset + 48], 0x00601FBC))[:8]
    ]
    require(ep_instructions[:3] == [
        {"address": "0x00601FBC", "bytes": "55", "mnemonic": "push", "operands": "ebp"},
        {"address": "0x00601FBD", "bytes": "8BEC", "mnemonic": "mov", "operands": "ebp, esp"},
        {"address": "0x00601FBF", "bytes": "6AFF", "mnemonic": "push", "operands": "-1"},
    ], "client entry instructions differ")

    updater = pefile.PE(data=updater_data, fast_load=False)
    updater_imports = import_map(updater)
    require(updater_imports.get("KERNEL32.DLL::CreateProcessA") == 0x004402AC,
            "updater CreateProcessA IAT differs")
    require(c_string_at_va(updater, updater_data, 0x0044A514) == ".\\exe\\",
            "updater default working directory differs")
    require(c_string_at_va(updater, updater_data, 0x0044A51C) == ".\\exe\\G7MTClient.exe",
            "updater default command differs")
    require(c_string_at_va(updater, updater_data, 0x0044A550) == "WORK_DIR",
            "updater WORK_DIR key differs")
    require(c_string_at_va(updater, updater_data, 0x0044A55C) == "STARTUP_APPNAME",
            "updater STARTUP_APPNAME key differs")
    anchors = {
        0x00404F57: "681CA54400",
        0x00404F6E: "6814A54400",
        0x00406868: "83B82001000001",
        0x0040686F: "7436",
        0x00406872: "8B7B1C",
        0x0040689A: "8B4B24",
        0x004068A1: "E8BA090000",
        0x004072B0: "52",
        0x004072B1: "6A00",
        0x004072C2: "FF15AC024400",
        0x004072D9: "FFD7",
        0x004072E4: "FFD7",
    }
    for va, expected_hex in anchors.items():
        expected = bytes.fromhex(expected_hex)
        require(bytes_at_va(updater, updater_data, va, len(expected)) == expected,
                f"updater launch anchor differs at 0x{va:08X}")

    cd = pycdlib.PyCdlib()
    cd.open(str(iso_path))
    try:
        data1_record = cd.get_record(joliet_path="/data1.hdr;1")
        g7start_record = cd.get_record(joliet_path="/G7Start.exe;1")
        require(data1_record.extent_location() * 2048 == DATA1_OFFSET,
                "data1.hdr ISO extent differs")
        require(g7start_record.extent_location() * 2048 == G7START_OFFSET,
                "G7Start ISO extent differs")
    finally:
        cd.close()
    data1 = iso_data[DATA1_OFFSET : DATA1_OFFSET + EXPECTED["data1"][0]]
    g7start_data = iso_data[G7START_OFFSET : G7START_OFFSET + EXPECTED["g7start"][0]]
    require(sha256_bytes(data1) == EXPECTED["data1"][1], "data1.hdr SHA-256 differs")
    require(sha256_bytes(g7start_data) == EXPECTED["g7start"][1], "G7Start SHA-256 differs")
    name = b"G7MTClient.exe"
    require(data1.count(name) == 1 and data1.find(name) == 0x31764,
            "InstallShield G7MTClient filename differs")
    g7start = pefile.PE(data=g7start_data, fast_load=False)
    require(c_string_at_va(g7start, g7start_data, 0x0043158C) == "exe\\G7MTClient.exe",
            "G7Start G7MTClient string differs")

    analysis = {
        "format": "PE32_X86_GUI_EXECUTABLE",
        "machine": "0x014C",
        "subsystem": 2,
        "imageBase": "0x00400000",
        "entryPointRva": "0x00201FBC",
        "role": "ORIGINAL_PRIMARY_GAME_CLIENT",
        "sectionCount": 5,
        "importDescriptorCount": 19,
        "importCount": 452,
        "importQuality": "READABLE_STATIC_WITH_DYNAMIC_RESOLUTION_LIMITATION",
        "packingAssessment": "NOT_PACKED_BY_STATIC_INDICATORS",
        "originalFilename": "G7MTClient.EXE",
        "fileVersion": "1, 0, 0, 1",
    }
    process_image = {
        "status": "PROVEN_STATIC_FORMAT",
        "osLoader": "WINDOWS_PE_LOADER",
        "target": "SELF_PROCESS_IMAGE",
        "runtimeObservationStatus": "NOT_CLAIMED",
        "evidence": [
            "pe32-machine:0x014C",
            "pe32-subsystem:WINDOWS_GUI",
            "original-client-runtime-execution:NOT_CLAIMED_BY_STATIC_FORMAT",
        ],
    }
    inbound_launch = {
        "status": "PROVEN_STATIC_DEFAULT",
        "launcherRowKey": "RESOURCE:FILE:original-installshield-payload:gin7updateclient.exe",
        "launcherRelativePosixPath": "gin7updateclient.exe",
        "launcherSha256": EXPECTED["updater"][1],
        "api": "KERNEL32.dll::CreateProcessA",
        "callsite": "0x004072C2",
        "triggerCallsite": "0x004068A1",
        "targetCommand": ".\\exe\\G7MTClient.exe",
        "targetRelativePosixPath": "exe/g7mtclient.exe",
        "targetSha256": EXPECTED["client"][1],
        "g7StartLaunchStatus": "UNRESOLVED",
        "evidence": [
            "updater-default:STARTUP_APPNAME=.\\exe\\G7MTClient.exe@0x0044A51C",
            "updater-default:WORK_DIR=.\\exe\\@0x0044A514",
            "updater-trigger-call:0x004068A1",
            "updater-CreateProcessA:0x004072C2",
            "updater-config-override:POSSIBLE",
            "updater-launch-gate-semantics:UNRESOLVED",
            "runtime-launch-observation:UNSEEN",
        ],
    }
    tool_paths = {
        "inspectorScript": inspector_path,
        "client": client_path,
        "updater": updater_path,
        "bootfirst": args.bootfirst.resolve(),
        "originalCdIso": iso_path,
        "peImports": pe_imports_path,
    }
    receipt = {
        "schemaVersion": 1,
        "status": "PROVEN_STATIC",
        "scope": "G7MTCLIENT_RESOURCE_LOADER_BOUNDARY",
        "source": {
            "relativePosixPath": "exe/g7mtclient.exe",
            "sha256": EXPECTED["client"][1],
            "byteSize": len(client_data),
        },
        "analysis": analysis,
        "processImage": process_image,
        "inboundLaunch": inbound_launch,
        "peTriage": {
            "timestampUtc": "2004-04-10T07:14:00Z",
            "entryPointVa": "0x00601FBC",
            "sizeOfImage": client.OPTIONAL_HEADER.SizeOfImage,
            "sections": sections,
            "imphash": client.get_imphash(),
            "exports": 0,
            "delayImports": 0,
            "tlsDirectory": False,
            "clrDirectory": False,
            "manifest": False,
            "authenticode": False,
            "overlayBytes": 0,
            "entryInstructions": ep_instructions,
            "versionStrings": versions,
            "dynamicResolutionSurface": ["KERNEL32.DLL::LoadLibraryA", "KERNEL32.DLL::GetProcAddress"],
            "outboundProcessApiDirectImportCount": 0,
        },
        "installshieldEvidence": {
            "status": "PROVEN_EXACT_FILENAME",
            "exactName": "G7MTClient.exe",
            "data1HdrSha256": EXPECTED["data1"][1],
            "data1HdrRelativeOffset": "0x00031764",
            "isoAbsoluteOffset": "0x00089F64",
        },
        "launchLimitations": {
            "defaultConfigurationMayBeOverridden": True,
            "updaterGateSemantics": "UNRESOLVED",
            "g7StartExactStringPresent": True,
            "g7StartProcessApiConsumption": "UNRESOLVED",
            "runtimeLaunch": "UNSEEN",
            "playability": "NOT_CLAIMED",
        },
        "staticTools": {
            label: {"path": relative(path, root), "sha256": sha256_file(path)}
            for label, path in sorted(tool_paths.items())
        },
        "toolEnvironment": {
            "python": platform.python_version(),
            "pefile": pefile.__version__,
            "capstone": capstone.__version__,
            "pycdlib": getattr(pycdlib, "__version__", "1.20.0"),
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n",
    )
    print(json.dumps({
        "output": str(output_path), "status": "PROVEN_STATIC",
        "sourceSha256": EXPECTED["client"][1], "importCount": 452,
        "inboundLaunch": "PROVEN_STATIC_DEFAULT",
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
