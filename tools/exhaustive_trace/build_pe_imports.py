"""Build deterministic raw PE import evidence for the frozen original client."""

from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any, Sequence

import pefile

from .io import canonical_json, sha256_file
from .source_manifest import CLIENT_SHA256, classify_import_groups


ORDINAL_ALIASES = {
    ("COMCTL32.DLL", 17): "InitCommonControls",
    ("DSOUND.DLL", 11): "DirectSoundCreate8",
    ("OLEDLG.DLL", 8): "OleUIBusyA",
    ("OLEPRO32.DLL", 253): "OleCreateFontIndirect",
}
def _ghidra_headers(path: Path) -> dict[str, str]:
    headers: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line == "===== IMPORT SUMMARY =====":
            break
        if "=" in line:
            key, value = line.split("=", 1)
            headers[key] = value
    required = {"PROGRAM", "LANGUAGE", "COMPILER", "IMAGE_BASE", "EXECUTABLE_SHA256"}
    if set(headers) != required:
        raise ValueError(f"Ghidra export headers mismatch: {sorted(headers)}")
    return headers


def build_payload(client: Path, exporter: Path, ghidra_output: Path) -> dict[str, Any]:
    client = client.resolve()
    exporter = exporter.resolve()
    ghidra_output = ghidra_output.resolve()
    client_hash = sha256_file(client)
    if client_hash != CLIENT_SHA256:
        raise ValueError(f"client hash mismatch: expected {CLIENT_SHA256}, got {client_hash}")

    headers = _ghidra_headers(ghidra_output)
    if headers["EXECUTABLE_SHA256"].upper() != client_hash:
        raise ValueError("Ghidra output is bound to a different executable")

    pe = pefile.PE(str(client), fast_load=False)
    pe.parse_data_directories(
        directories=[pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_IMPORT"]]
    )
    imports: list[dict[str, Any]] = []
    for descriptor in pe.DIRECTORY_ENTRY_IMPORT:
        dll = descriptor.dll.decode("ascii").upper()
        for imported in descriptor.imports:
            item: dict[str, Any] = {
                "dll": dll,
                "iatVa": f"0x{imported.address:08X}",
                "hint": imported.hint,
            }
            if imported.name is not None:
                item["name"] = imported.name.decode("ascii")
            else:
                item["ordinal"] = imported.ordinal
                alias = ORDINAL_ALIASES.get((dll, imported.ordinal))
                if alias:
                    item["resolvedName"] = alias
            imports.append(item)
    imports.sort(
        key=lambda item: (item["dll"], str(item.get("name", "")), item.get("ordinal", -1))
    )

    groups = classify_import_groups(imports)
    pefile_path = Path(pefile.__file__).resolve()
    builder_path = Path(__file__).resolve()
    return {
        "schemaVersion": 1,
        "format": "PE32",
        "architecture": "x86",
        "quality": "readable",
        "source": {
            "path": str(client),
            "size": client.stat().st_size,
            "executableSha256": client_hash,
            "machine": f"0x{pe.FILE_HEADER.Machine:04X}",
            "optionalHeaderMagic": f"0x{pe.OPTIONAL_HEADER.Magic:04X}",
            "imageBase": f"0x{pe.OPTIONAL_HEADER.ImageBase:08X}",
        },
        "generator": {
            "builder": {
                "path": str(builder_path),
                "sha256": sha256_file(builder_path),
            },
            "rawPeParser": {
                "name": "pefile",
                "version": pefile.__version__,
                "path": str(pefile_path),
                "sha256": sha256_file(pefile_path),
                "method": "PE.DIRECTORY_ENTRY_IMPORT",
            },
            "ghidraCrossCheck": {
                "exporterPath": str(exporter),
                "exporterSha256": sha256_file(exporter),
                "outputPath": str(ghidra_output),
                "outputSha256": sha256_file(ghidra_output),
                "headers": headers,
            },
        },
        "descriptorCount": len(pe.DIRECTORY_ENTRY_IMPORT),
        "importCount": len(imports),
        "imports": imports,
        "groups": groups,
        "audit": {
            "priorGhidraExternalFunctionCount": 451,
            "rawPeImportCount": len(imports),
            "rawOnlyImport": "KERNEL32.DLL::GetACP",
            "ordinalAliasCount": len(ORDINAL_ALIASES),
            "runtimeDynamicResolutionNotCovered": True,
            "limitation": (
                "Static PE imports do not enumerate APIs resolved later through "
                "LoadLibraryA/GetProcAddress."
            ),
        },
    }


def _main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client", required=True, type=Path)
    parser.add_argument("--exporter", required=True, type=Path)
    parser.add_argument("--ghidra-output", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)
    payload = build_payload(args.client, args.exporter, args.ghidra_output)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(canonical_json(payload), encoding="utf-8", newline="")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
