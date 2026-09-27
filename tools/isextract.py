#!/usr/bin/env python3
"""InstallShield 6+ 캐비닛(dataN.hdr / dataN.cab) 추출기.

unshield(https://github.com/twogood/unshield, lib/file.c·libunshield.c)의 IS6+ 경로를
파이썬으로 옮긴 것이다. 헤더의 **모든** 파일 항목을 순회해 추출하고, 항목마다
크기·MD5를 검증해 매니페스트(CSV)에 결과를 남긴다. 추출 누락을 눈으로 확인하기 위함.

사용법:
    python tools/isextract.py <data1.hdr 경로> <출력 디렉터리> [--list]

출력:
    <출력>/<파일그룹>/<디렉터리>/<파일>
    <출력>/_manifest.csv   (index, group, directory, name, flags, sizes, md5, status)
    <출력>/_components.txt (컴포넌트 → 파일그룹 대응)
"""
import csv
import hashlib
import os
import struct
import sys
import zlib

CAB_SIGNATURE = 0x28635349
MAX_FILE_GROUP_COUNT = 71
MAX_COMPONENT_COUNT = 71

FILE_SPLIT = 1
FILE_OBFUSCATED = 2
FILE_COMPRESSED = 4
FILE_INVALID = 8
LINK_PREV = 1

ENCODING = "cp932"  # 일본어판: 이름이 Shift_JIS


def u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def u64(b, o):
    return struct.unpack_from("<Q", b, o)[0]


def cstr(b, o):
    end = b.index(b"\x00", o)
    raw = b[o:end]
    try:
        return raw.decode(ENCODING)
    except UnicodeDecodeError:
        return raw.decode("latin-1")


class Header:
    def __init__(self, path):
        self.path = path
        self.data = open(path, "rb").read()
        d = self.data
        if u32(d, 0) != CAB_SIGNATURE:
            raise ValueError("ISc( 시그니처 아님")
        self.version = u32(d, 4)
        if self.version >> 24 == 1:
            self.major = (self.version >> 12) & 0xF
        elif self.version >> 24 in (2, 4):
            v = self.version & 0xFFFF
            self.major = v // 100 if v else 0
        else:
            self.major = 0
        if self.major < 6:
            raise ValueError(f"IS{self.major}은 이 스크립트 범위 밖(IS6+ 전용)")
        self.cdo = u32(d, 12)  # cab descriptor offset
        p = self.cdo + 0xC
        self.file_table_offset = u32(d, p); p += 8
        self.file_table_size = u32(d, p); p += 4
        self.file_table_size2 = u32(d, p); p += 4
        self.directory_count = u32(d, p); p += 12
        self.file_count = u32(d, p); p += 4
        self.file_table_offset2 = u32(d, p); p += 4
        p += 0xE
        self.file_group_offsets = [u32(d, p + 4 * i) for i in range(MAX_FILE_GROUP_COUNT)]
        p += 4 * MAX_FILE_GROUP_COUNT
        self.component_offsets = [u32(d, p + 4 * i) for i in range(MAX_COMPONENT_COUNT)]
        base = self.cdo + self.file_table_offset
        n = self.directory_count + self.file_count
        self.file_table = [u32(d, base + 4 * i) for i in range(n)]

    def buf(self, off):
        return self.cdo + off

    def string(self, off):
        return cstr(self.data, self.buf(off))

    def directory_name(self, i):
        return cstr(self.data, self.cdo + self.file_table_offset + self.file_table[i])

    def descriptor(self, i):
        d = self.data
        p = self.cdo + self.file_table_offset + self.file_table_offset2 + i * 0x57
        fd = {}
        fd["flags"] = u16(d, p)
        fd["expanded"] = u64(d, p + 2)
        fd["compressed"] = u64(d, p + 10)
        fd["data_offset"] = u64(d, p + 18)
        fd["md5"] = d[p + 26:p + 42]
        fd["name_offset"] = u32(d, p + 58)
        fd["dir_index"] = u16(d, p + 62)
        fd["link_prev"] = u32(d, p + 64 + 12)
        fd["link_next"] = u32(d, p + 64 + 16)
        fd["link_flags"] = d[p + 64 + 20]
        fd["volume"] = u16(d, p + 64 + 21)
        fd["name"] = cstr(d, self.cdo + self.file_table_offset + fd["name_offset"]) if fd["name_offset"] else ""
        return fd

    def _walk(self, offsets):
        for start in offsets:
            nxt = start
            while nxt:
                p = self.buf(nxt)
                desc = u32(self.data, p + 4)
                nxt = u32(self.data, p + 8)
                yield desc

    def file_groups(self):
        out = []
        for desc in self._walk(self.file_group_offsets):
            p = self.buf(desc)
            name = self.string(u32(self.data, p))
            q = p + 4 + 0x12
            first = struct.unpack_from("<i", self.data, q)[0]
            last = struct.unpack_from("<i", self.data, q + 4)[0]
            out.append((name, first, last))
        return out

    def components(self):
        out = []
        for desc in self._walk(self.component_offsets):
            p = self.buf(desc)
            name = self.string(u32(self.data, p))
            q = p + 4 + 0x6B
            cnt = u16(self.data, q)
            tbl = u32(self.data, q + 2)
            groups = []
            if tbl:
                t = self.buf(tbl)
                groups = [self.string(u32(self.data, t + 4 * k)) for k in range(cnt)]
            out.append((name, groups))
        return out


def deobfuscate(buf, seed):
    out = bytearray(len(buf))
    for k, c in enumerate(buf):
        x = c ^ 0xD5
        x = ((x >> 2) | (x << 6)) & 0xFF
        out[k] = (x - ((seed + k) % 0x47)) & 0xFF
    return bytes(out), seed + len(buf)


class VolumeSet:
    def __init__(self, hdr_path):
        self.dir = os.path.dirname(hdr_path)
        base = os.path.basename(hdr_path)
        stem = ""
        for ch in base:
            if ch == "." or ch.isdigit():
                break
            stem += ch
        self.stem = stem

    def open(self, vol):
        path = os.path.join(self.dir, f"{self.stem}{vol}.cab")
        f = open(path, "rb")
        common = f.read(20)
        if u32(common, 0) != CAB_SIGNATURE:
            raise ValueError(f"{path}: 시그니처 불일치")
        vh = f.read(64)
        h = {
            "data_offset": u32(vh, 0),
            "first_file_index": u32(vh, 8),
            "last_file_index": u32(vh, 12),
            "first_file_offset": u32(vh, 16) | (u32(vh, 20) << 32),
            "first_file_size_expanded": u32(vh, 24) | (u32(vh, 28) << 32),
            "first_file_size_compressed": u32(vh, 32) | (u32(vh, 36) << 32),
            "last_file_offset": u32(vh, 40) | (u32(vh, 44) << 32),
            "last_file_size_expanded": u32(vh, 48) | (u32(vh, 52) << 32),
            "last_file_size_compressed": u32(vh, 56) | (u32(vh, 60) << 32),
        }
        return f, h


def read_raw(vs, index, fd):
    """파일의 (압축된) 원시 바이트 전체를 볼륨을 넘나들며 읽는다."""
    compressed = bool(fd["flags"] & FILE_COMPRESSED)
    total = fd["compressed"] if compressed else fd["expanded"]
    chunks = []
    vol = fd["volume"]
    remaining = total
    first = True
    while remaining > 0:
        f, vh = vs.open(vol)
        try:
            if fd["flags"] & FILE_SPLIT:
                if index == vh["last_file_index"] and vh["last_file_offset"] != 0x7FFFFFFF:
                    off = vh["last_file_offset"]
                    avail = vh["last_file_size_compressed"] if compressed else vh["last_file_size_expanded"]
                elif index == vh["first_file_index"]:
                    off = vh["first_file_offset"]
                    avail = vh["first_file_size_compressed"] if compressed else vh["first_file_size_expanded"]
                else:
                    raise ValueError(f"분할 파일 {index}이 볼륨 {vol}에 없음")
            else:
                if not first:
                    raise ValueError("비분할 파일이 볼륨 경계를 넘음")
                off = fd["data_offset"]
                avail = total
            f.seek(off)
            n = min(avail, remaining)
            data = f.read(n)
            if len(data) != n:
                raise ValueError(f"볼륨 {vol}에서 {n}바이트 중 {len(data)}바이트만 읽힘")
            chunks.append(data)
            remaining -= n
        finally:
            f.close()
        vol += 1
        first = False
    raw = b"".join(chunks)
    if fd["flags"] & FILE_OBFUSCATED:
        raw, _ = deobfuscate(raw, 0)
    return raw


def expand(raw, fd):
    if not (fd["flags"] & FILE_COMPRESSED):
        return raw
    out = []
    p = 0
    while p < len(raw):
        n = u16(raw, p)
        p += 2
        if n == 0:
            raise ValueError("청크 길이 0 (구형 포맷 가능성)")
        chunk = raw[p:p + n] + b"\x00"
        p += n
        dec = zlib.decompressobj(-15)
        out.append(dec.decompress(chunk))
    return b"".join(out)


def safe(part):
    bad = '<>:"|?*'
    return "".join("_" if c in bad or ord(c) < 32 else c for c in part)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    hdr_path, out_dir = argv[1], argv[2]
    list_only = "--list" in argv
    h = Header(hdr_path)
    vs = VolumeSet(hdr_path)
    groups = h.file_groups()
    comps = h.components()
    print(f"version=0x{h.version:08x} major={h.major} dirs={h.directory_count} files={h.file_count} "
          f"groups={len(groups)} components={len(comps)}")

    def group_of(i):
        for name, first, last in groups:
            if first <= i <= last:
                return name
        return "_nogroup"

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "_components.txt"), "w", encoding="utf-8") as cf:
        for name, gl in comps:
            cf.write(f"{name}: {', '.join(gl)}\n")
        cf.write("\n# file groups (name, first, last)\n")
        for g in groups:
            cf.write(f"{g[0]}\t{g[1]}\t{g[2]}\n")

    stats = {"ok": 0, "invalid": 0, "failed": 0, "linked": 0, "listed": 0}
    rows = []
    for i in range(h.file_count):
        fd = h.descriptor(i)
        dname = h.directory_name(fd["dir_index"]) if fd["dir_index"] < h.directory_count else "?"
        grp = group_of(i)
        row = {
            "index": i, "group": grp, "directory": dname, "name": fd["name"],
            "flags": f"0x{fd['flags']:04x}", "volume": fd["volume"],
            "expanded": fd["expanded"], "compressed": fd["compressed"],
            "md5_expected": fd["md5"].hex(), "md5_actual": "", "status": "", "error": "",
        }
        if (fd["flags"] & FILE_INVALID) or fd["data_offset"] == 0 or not fd["name_offset"]:
            row["status"] = "invalid"
            stats["invalid"] += 1
            rows.append(row)
            continue
        if list_only:
            row["status"] = "listed"
            stats["listed"] += 1
            rows.append(row)
            continue
        src_index, src_fd = i, fd
        if fd["link_flags"] & LINK_PREV:
            src_index = fd["link_prev"]
            src_fd = h.descriptor(src_index)
            row["status"] = "linked"
        try:
            data = expand(read_raw(vs, src_index, src_fd), src_fd)
            md5 = hashlib.md5(data).hexdigest()
            row["md5_actual"] = md5
            if len(data) != src_fd["expanded"]:
                raise ValueError(f"크기 불일치 {len(data)} != {src_fd['expanded']}")
            if md5 != src_fd["md5"].hex():
                raise ValueError("MD5 불일치")
            parts = [safe(grp)] + [safe(x) for x in dname.replace("/", "\\").split("\\") if x] + [safe(fd["name"])]
            dst = os.path.join(out_dir, *parts)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, "wb") as fo:
                fo.write(data)
            if row["status"] == "linked":
                stats["linked"] += 1
            else:
                row["status"] = "ok"
                stats["ok"] += 1
        except Exception as e:  # 실패 항목은 매니페스트에 남긴다
            row["status"] = "failed"
            row["error"] = str(e)
            stats["failed"] += 1
        rows.append(row)

    with open(os.path.join(out_dir, "_manifest.csv"), "w", newline="", encoding="utf-8-sig") as mf:
        w = csv.DictWriter(mf, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    print("result " + " ".join(f"{k}={v}" for k, v in stats.items()))
    for r in rows:
        if r["status"] == "failed":
            print(f"FAILED #{r['index']} {r['group']}/{r['directory']}/{r['name']}: {r['error']}")
    return 1 if stats["failed"] else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
