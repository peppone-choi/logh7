from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import pypdf
from pypdf import PdfReader


EXPECTED_PDF_SHA256 = "1C4CF3DB13A172361277264C06ADA6E2499BE0969494C6557EB84BC4CC005399"
EXPECTED_ISO_SHA256 = "375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22"
EXPECTED_G7START_SHA256 = "1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93"
EXPECTED_CLIENT_SHA256 = "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"
EXPECTED_BOOTFIRST_SHA256 = "23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096"
EXPECTED_UPDATER_SHA256 = "EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D"
PDF_HEADER_HEX = "255044462D312E340D25E2E3CFD30D0A"
G7START_ISO_OFFSET = 0x954A800
G7START_SIZE = 434176


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


def outline_count(items: list[object]) -> int:
    return sum(outline_count(item) if isinstance(item, list) else 1 for item in items)


def relative_path(path: Path, project_root: Path) -> str:
    return path.resolve().relative_to(project_root.resolve()).as_posix()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--pdf", type=Path, required=True)
    parser.add_argument("--iso", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--bootfirst", type=Path, required=True)
    parser.add_argument("--updater", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    project_root = args.project_root.resolve()
    pdf_path = args.pdf.resolve()
    iso_path = args.iso.resolve()
    output_path = args.output.resolve()
    inspector_path = Path(__file__).resolve()

    pdf_bytes = pdf_path.read_bytes()
    require(len(pdf_bytes) == 5374309, "PDF byte size differs")
    require(sha256_bytes(pdf_bytes) == EXPECTED_PDF_SHA256, "PDF SHA-256 differs")
    require(pdf_bytes[:16].hex().upper() == PDF_HEADER_HEX, "PDF header differs")

    reader = PdfReader(pdf_path)
    require(reader.is_encrypted, "PDF encryption flag differs")
    require(int(reader.decrypt("")) > 0, "PDF empty-password access differs")
    metadata = dict(reader.metadata or {})
    root = reader.trailer["/Root"]
    require(len(reader.pages) == 69, "PDF page count differs")
    require(reader.pdf_header == "%PDF-1.4", "PDF parser version differs")

    action_counts: dict[str, int] = {}
    annotation_counts: dict[str, int] = {}
    external_uris: list[dict[str, object]] = []
    launch_action_count = 0
    for page_number, page in enumerate(reader.pages, 1):
        for annotation_ref in page.get("/Annots", []) or []:
            annotation = annotation_ref.get_object()
            subtype = str(annotation.get("/Subtype"))
            annotation_counts[subtype] = annotation_counts.get(subtype, 0) + 1
            action = annotation.get("/A")
            if action is None:
                continue
            action_type = str(action.get("/S"))
            action_counts[action_type] = action_counts.get(action_type, 0) + 1
            if action_type in {"/Launch", "/GoToR"}:
                launch_action_count += 1
            if action_type == "/URI":
                external_uris.append({"page": page_number, "uri": str(action.get("/URI"))})

    iso_bytes = iso_path.read_bytes()
    require(sha256_bytes(iso_bytes) == EXPECTED_ISO_SHA256, "original CD ISO SHA-256 differs")
    g7start = iso_bytes[G7START_ISO_OFFSET : G7START_ISO_OFFSET + G7START_SIZE]
    require(len(g7start) == G7START_SIZE, "G7Start ISO extent differs")
    require(sha256_bytes(g7start) == EXPECTED_G7START_SHA256, "G7Start SHA-256 differs")

    original_name = "銀英伝７マニュアル.pdf"
    original_name_bytes = original_name.encode("cp932")
    require(g7start[0x27900 : 0x27900 + len(original_name_bytes)] == original_name_bytes,
            "G7Start manual filename anchor differs")
    require(g7start[0x3893 : 0x3898] == bytes.fromhex("6800794200"),
            "G7Start filename xref differs")
    require(g7start[0x38E0 : 0x38E5] == bytes.fromhex("6884164300"),
            "G7Start ShellExecute verb xref differs")
    require(g7start[0x38E6 : 0x38EC] == bytes.fromhex("FF1508734200"),
            "G7Start ShellExecute callsite differs")
    require(g7start[0x27308 : 0x2730C] == bytes.fromhex("3A060300"),
            "G7Start ShellExecute IAT entry differs")
    require(g7start[0x3063C : 0x30649] == b"ShellExecuteA",
            "G7Start ShellExecute import name differs")
    require(g7start[0x279B8 : 0x279D0] == bytes.fromhex(
        "1101000000000000E9030000E90300000C00000060384000"
    ), "G7Start command message-map record differs")
    require(g7start[0x31684 : 0x31689] == b"open\0", "G7Start open verb differs")

    shell_label = "PDFマニュアル".encode("cp932") + b"\0"
    shell_target = ("<TARGETDIR>\\doc\\" + original_name).encode("cp932") + b"\0"
    require(iso_bytes[0x5A6EA : 0x5A6EA + len(shell_label)] == shell_label,
            "InstallShield PDF manual label differs")
    require(iso_bytes[0x5A6F8 : 0x5A6F8 + len(shell_target)] == shell_target,
            "InstallShield PDF manual target differs")
    require(iso_bytes[0x89F4D : 0x89F4D + len(original_name_bytes)] == original_name_bytes,
            "InstallShield PDF manual filename table differs")

    search_needles = {
        "acrobatAscii": b"Acro",
        "docPathAscii": b"doc\\",
        "originalNameCp932": original_name_bytes,
        "originalNameUtf16Le": original_name.encode("utf-16le"),
        "pdfExtensionLower": b".pdf",
        "pdfExtensionUpper": b".PDF",
        "shellExecuteAscii": b"ShellExecute",
        "winExecAscii": b"WinExec",
    }
    reference_inputs = (
        ("G7MTClient.exe", args.client.resolve(), EXPECTED_CLIENT_SHA256, 3956736),
        ("BootFirst.exe", args.bootfirst.resolve(), EXPECTED_BOOTFIRST_SHA256, 40960),
        ("Gin7UpdateClient.exe", args.updater.resolve(), EXPECTED_UPDATER_SHA256, 1060864),
    )
    reference_absence: list[dict[str, object]] = []
    for name, path, expected_sha, expected_size in reference_inputs:
        content = path.read_bytes()
        require(len(content) == expected_size, f"{name} byte size differs")
        require(sha256_bytes(content) == expected_sha, f"{name} SHA-256 differs")
        hit_counts = {label: content.count(needle) for label, needle in search_needles.items()}
        require(not any(hit_counts.values()), f"{name} contains a PDF/manual opener reference")
        reference_absence.append(
            {
                "byteSize": expected_size,
                "hitCounts": hit_counts,
                "name": name,
                "path": relative_path(path, project_root),
                "sha256": expected_sha,
            }
        )

    analysis = {
        "author": str(metadata.get("/Author")),
        "creationDate": str(metadata.get("/CreationDate")),
        "creator": str(metadata.get("/Creator")),
        "emptyPasswordAccess": True,
        "encrypted": True,
        "format": "PDF_1_4",
        "headerHex": PDF_HEADER_HEX,
        "modificationDate": str(metadata.get("/ModDate")),
        "pageCount": len(reader.pages),
        "pdfVersion": "1.4",
        "producer": str(metadata.get("/Producer")),
        "role": "ORIGINAL_OPERATION_MANUAL",
        "title": str(metadata.get("/Title")),
    }
    require(analysis["title"] == "銀河英雄伝説Ⅶ　操作説明書", "PDF title differs")
    require(analysis["author"] == "BOTHTEC", "PDF author differs")

    external_document_open = {
        "api": "SHELL32.dll::ShellExecuteA",
        "callsite": "0x004038E6",
        "commandId": 1001,
        "evidence": [
            "original-cd:G7Start.exe:message-map-command:1001",
            "original-cd:G7Start.exe:FUN_00403860",
            "original-cd:G7Start.exe:ShellExecuteA:0x004038E6",
            "original-cd:G7Start.exe:manual-name@0x00427900",
        ],
        "handler": "FUN_00403860",
        "openerByteSize": G7START_SIZE,
        "openerKey": "ORIGINAL_CD_ARTIFACT:G7START.EXE",
        "openerName": "G7Start.exe",
        "openerSha256": EXPECTED_G7START_SHA256,
        "status": "PROVEN",
        "targetOriginalName": original_name,
        "targetSha256": EXPECTED_PDF_SHA256,
        "verb": "open",
    }
    receipt = {
        "analysis": analysis,
        "documentStructure": {
            "actionCounts": dict(sorted(action_counts.items())),
            "annotationCounts": dict(sorted(annotation_counts.items())),
            "attachmentCount": len(reader.attachments),
            "externalUris": external_uris,
            "formFieldCount": len(reader.get_fields() or {}),
            "hasCatalogAdditionalActions": "/AA" in root,
            "hasCatalogJavaScript": "/JavaScript" in root,
            "launchActionCount": launch_action_count,
            "openActionType": str((root.get("/OpenAction") or {}).get("/S")),
            "outlineItemCount": outline_count(reader.outline),
        },
        "externalDocumentOpen": external_document_open,
        "installationEvidence": {
            "filenameTableIsoOffset": "0x00089F4D",
            "originalName": original_name,
            "shellLabel": "PDFマニュアル",
            "shellLabelIsoOffset": "0x0005A6EA",
            "shellTarget": "<TARGETDIR>\\doc\\" + original_name,
            "shellTargetIsoOffset": "0x0005A6F8",
            "status": "PROVEN_STATIC",
        },
        "originalCd": {
            "g7StartExtent": "0x0954A800",
            "sha256": EXPECTED_ISO_SHA256,
            "sourceId": "original-cd-iso",
        },
        "schemaVersion": 1,
        "source": {
            "byteSize": len(pdf_bytes),
            "relativePosixPath": "doc/___p_`_v_}_j___a__.pdf",
            "sha256": EXPECTED_PDF_SHA256,
        },
        "staticTools": {
            "inspectorScript": {
                "path": relative_path(inspector_path, project_root),
                "sha256": sha256_file(inspector_path),
            }
        },
        "staticReferenceAbsence": {
            "inputs": reference_absence,
            "needleHex": {
                label: needle.hex().upper() for label, needle in sorted(search_needles.items())
            },
            "status": "PROVEN_ON_HASH_BOUND_BYTE_SURFACES",
        },
        "status": "PROVEN_STATIC",
        "toolEnvironment": {
            "pypdfVersion": pypdf.__version__,
            "pythonImplementation": "CPython",
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(json.dumps({
        "output": str(output_path),
        "sourceSha256": EXPECTED_PDF_SHA256,
        "g7StartSha256": EXPECTED_G7START_SHA256,
        "pageCount": 69,
        "status": "PROVEN_STATIC",
    }, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
