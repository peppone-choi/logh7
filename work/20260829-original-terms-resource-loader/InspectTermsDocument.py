from __future__ import annotations

import argparse
import hashlib
import json
import platform
from pathlib import Path

import pycdlib


EXPECTED = {
    "terms": (8376, "BC7B4D48326A536EAC26F9B4C74395F4C42AC73C461FDE82ECD33B7CA19F4103"),
    "iso": (None, "375838CE1C0798E166D9D127CD598705560DE4EFCFF1FF0AD7D0B19FAB01CC22"),
    "data1": (218843, "A0BE81CB1DB8AE3240837580168FC01862BF979A10D50EE5DE333D7D261A4576"),
    "client": (3956736, "BD19263C10DECC3D58373165A82D42A9267868400D407DA87D5F4F4109AB6E16"),
    "bootfirst": (40960, "23D01278CAABE2AF2C0BC240EF62742B506C1DB9484A2B380E9BD63BCA411096"),
    "updater": (1060864, "EA196E6EAA17BE36715132A7919C5470FF45F614E19D9E7E70CBB2C46BA0429D"),
    "g7start": (434176, "1023C4A045F184BF76CA84AB603E0C03DB989799F02B701BF8DD89B21EA78F93"),
}
SECTOR_SIZE = 2048
DATA1_OFFSET = 0x58800
SETUP_OFFSET = 0x95D1000
SETUP_SIZE = 172535
G7START_OFFSET = 0x954A800
ORIGINAL_NAME = "銀英伝VII利用規約.txt"
TITLE = "銀河英雄伝説Ⅶ利用規約"


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


def checked_file(path: Path, key: str) -> bytes:
    data = path.read_bytes()
    expected_size, expected_sha = EXPECTED[key]
    if expected_size is not None:
        require(len(data) == expected_size, f"{key} byte size differs")
    require(sha256_bytes(data) == expected_sha, f"{key} SHA-256 differs")
    return data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--terms", type=Path, required=True)
    parser.add_argument("--support-license", type=Path, required=True)
    parser.add_argument("--iso", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--bootfirst", type=Path, required=True)
    parser.add_argument("--updater", type=Path, required=True)
    parser.add_argument("--resources-raw", type=Path, required=True)
    parser.add_argument("--functions-raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.project_root.resolve()
    terms_path = args.terms.resolve()
    support_path = args.support_license.resolve()
    iso_path = args.iso.resolve()
    inspector_path = Path(__file__).resolve()
    output_path = args.output.resolve()

    terms = checked_file(terms_path, "terms")
    support = checked_file(support_path, "terms")
    require(terms == support, "InstallShield support license is not byte-identical")
    require(not terms.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")), "terms BOM differs")
    text = terms.decode("cp932", errors="strict")
    require(text.encode("cp932") == terms, "CP932 round trip differs")
    require(text.split("\r\n", 1)[0] == TITLE, "terms title differs")
    require(len(text) == 4371, "terms character count differs")
    require(terms.count(b"\r\n") == 130, "terms CRLF count differs")
    require(terms.replace(b"\r\n", b"").count(b"\r") == 0, "terms has lone CR")
    require(terms.replace(b"\r\n", b"").count(b"\n") == 0, "terms has lone LF")
    require(b"\0" not in terms, "terms contains NUL")
    try:
        terms.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        utf8_status = "STRICT_DECODE_REJECTED"
    else:
        raise ValueError("terms unexpectedly decodes as UTF-8")

    iso = checked_file(iso_path, "iso")
    cd = pycdlib.PyCdlib()
    cd.open(str(iso_path))
    try:
        setup_record = cd.get_record(joliet_path="/setup.inx;1")
        data1_record = cd.get_record(joliet_path="/data1.hdr;1")
        g7start_record = cd.get_record(joliet_path="/G7Start.exe;1")
        extents = {
            "setup.inx": [setup_record.extent_location() * SECTOR_SIZE, setup_record.data_length],
            "data1.hdr": [data1_record.extent_location() * SECTOR_SIZE, data1_record.data_length],
            "G7Start.exe": [g7start_record.extent_location() * SECTOR_SIZE, g7start_record.data_length],
        }
    finally:
        cd.close()
    require(extents["setup.inx"] == [SETUP_OFFSET, SETUP_SIZE], "setup.inx ISO extent differs")
    require(extents["data1.hdr"] == [DATA1_OFFSET, EXPECTED["data1"][0]], "data1.hdr ISO extent differs")
    require(extents["G7Start.exe"] == [G7START_OFFSET, EXPECTED["g7start"][0]], "G7Start ISO extent differs")

    data1 = iso[DATA1_OFFSET : DATA1_OFFSET + EXPECTED["data1"][0]]
    setup = iso[SETUP_OFFSET : SETUP_OFFSET + SETUP_SIZE]
    g7start = iso[G7START_OFFSET : G7START_OFFSET + EXPECTED["g7start"][0]]
    require(sha256_bytes(data1) == EXPECTED["data1"][1], "data1.hdr SHA-256 differs")
    require(sha256_bytes(g7start) == EXPECTED["g7start"][1], "G7Start SHA-256 differs")
    original_name_bytes = ORIGINAL_NAME.encode("cp932")
    require(data1[0x31737 : 0x31737 + len(original_name_bytes)] == original_name_bytes,
            "InstallShield original filename anchor differs")
    require(setup[0x5C16 : 0x5C16 + len(b"license.txt")] == b"license.txt",
            "setup.inx license literal anchor differs")
    require(setup[0x1D5BE : 0x1D5BE + len(b"SdLicense2")] == b"SdLicense2",
            "setup.inx SdLicense2 anchor differs")

    needles = {
        "originalNameCp932": original_name_bytes,
        "originalNameUtf16Le": ORIGINAL_NAME.encode("utf-16le"),
        "titleCp932": TITLE.encode("cp932"),
        "titleUtf16Le": TITLE.encode("utf-16le"),
        "licenseAscii": b"license.txt",
        "sdLicense2Ascii": b"SdLicense2",
        "entireTerms": terms,
    }
    surfaces = [
        ("G7MTClient.exe", args.client.resolve(), "client"),
        ("BootFirst.exe", args.bootfirst.resolve(), "bootfirst"),
        ("Gin7UpdateClient.exe", args.updater.resolve(), "updater"),
    ]
    absence = []
    for name, path, key in surfaces:
        data = checked_file(path, key)
        counts = {label: data.count(needle) for label, needle in needles.items()}
        require(not any(counts.values()), f"{name} contains target-specific terms reference")
        absence.append({"name": name, "path": relative(path, root), "sha256": EXPECTED[key][1],
                        "byteSize": len(data), "hitCounts": counts})
    g7_counts = {label: g7start.count(needle) for label, needle in needles.items()}
    require(not any(g7_counts.values()), "G7Start contains target-specific terms reference")
    absence.append({"name": "G7Start.exe", "path": "original-cd-iso:/G7Start.exe",
                    "sha256": EXPECTED["g7start"][1], "byteSize": len(g7start),
                    "hitCounts": g7_counts})

    for label, path in (("resourcesRaw", args.resources_raw.resolve()),
                        ("functionsRaw", args.functions_raw.resolve())):
        data = path.read_bytes()
        counts = {
            "originalNameUtf8": data.count(ORIGINAL_NAME.encode("utf-8")),
            "titleUtf8": data.count(TITLE.encode("utf-8")),
            "licenseAscii": data.count(b"license.txt"),
            "sdLicense2Ascii": data.count(b"SdLicense2"),
        }
        require(not any(counts.values()), f"{label} contains target-specific terms reference")
        absence.append({"name": label, "path": relative(path, root), "sha256": sha256_bytes(data),
                        "byteSize": len(data), "hitCounts": counts})

    analysis = {
        "format": "CP932_TEXT",
        "role": "ORIGINAL_SERVICE_TERMS",
        "encoding": "CP932",
        "title": TITLE,
        "characterCount": len(text),
        "lineEnding": "CRLF",
    }
    duplicate = {
        "status": "PROVEN",
        "path": relative(support_path, root),
        "contentSha256": EXPECTED["terms"][1],
        "byteSize": len(support),
        "relation": "BYTE_IDENTICAL_INSTALLSHIELD_SUPPORT_COPY",
        "evidence": [
            "installshield-extract:_support_language_independent_os_independent_files/license.txt",
            f"sha256:{EXPECTED['terms'][1]}",
        ],
    }
    tool_paths = {
        "inspectorScript": inspector_path,
        "sourceTerms": terms_path,
        "supportLicense": support_path,
        "originalCdIso": iso_path,
        "client": args.client.resolve(),
        "bootfirst": args.bootfirst.resolve(),
        "updater": args.updater.resolve(),
        "resourcesRaw": args.resources_raw.resolve(),
        "functionsRaw": args.functions_raw.resolve(),
    }
    receipt = {
        "schemaVersion": 1,
        "status": "PROVEN_STATIC",
        "source": {
            "relativePosixPath": "doc/___p_`vii___p_k__.txt",
            "sha256": EXPECTED["terms"][1],
            "byteSize": len(terms),
        },
        "analysis": analysis,
        "duplicateSource": duplicate,
        "installationEvidence": {
            "status": "PROVEN_STATIC_SURFACES",
            "originalName": ORIGINAL_NAME,
            "originalNameEncoding": "CP932",
            "originalNameBytesHex": original_name_bytes.hex().upper(),
            "data1HdrIsoOffset": "0x00058800",
            "filenameData1RelativeOffset": "0x00031737",
            "filenameIsoOffset": "0x00089F37",
            "setupInxIsoOffset": "0x095D1000",
            "licenseLiteralRelativeOffset": "0x00005C16",
            "sdLicense2SymbolRelativeOffset": "0x0001D5BE",
            "callflowStatus": "UNRESOLVED",
        },
        "staticReferenceAbsence": {
            "status": "PROVEN_ON_HASH_BOUND_BYTE_SURFACES",
            "inputs": absence,
            "needleHex": {label: value.hex().upper() for label, value in sorted(needles.items())},
        },
        "encodingChecks": {
            "bom": "ABSENT", "cp932RoundTrip": "PASS", "utf8": utf8_status,
            "crlfCount": 130, "loneCrCount": 0, "loneLfCount": 0, "nulCount": 0,
        },
        "originalCd": {"sha256": EXPECTED["iso"][1], "extents": extents},
        "staticTools": {
            label: {"path": relative(path, root), "sha256": sha256_file(path)}
            for label, path in sorted(tool_paths.items())
        },
        "toolEnvironment": {
            "python": platform.python_version(),
            "pycdlib": getattr(pycdlib, "__version__", "1.20.0"),
        },
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8", newline="\n",
    )
    print(json.dumps({"output": str(output_path), "status": "PROVEN_STATIC",
                      "sourceSha256": EXPECTED["terms"][1]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
