#!/usr/bin/env python3
"""InstallShield 단일 실행 패키지(Setup Player 계열) 오버레이 분리기.

PE 끝 뒤에 `파일명\\0 상대경로\\0 버전\\0 크기(10진 ASCII)\\0 데이터[크기]` 레코드가 반복된다.
실행하지 않고 오버레이만 잘라 파일로 저장한다. 풀린 data1.hdr 등은 tools/isextract.py 로 이어서 푼다.

사용법:
    python tools/is_sfx_extract.py <setup.exe> <출력 디렉터리>
"""
import hashlib
import os
import sys

import pefile


def cstr(buf, pos):
    end = buf.index(b"\x00", pos)
    return buf[pos:end].decode("cp932", "replace"), end + 1


def main(src, out):
    data = open(src, "rb").read()
    pe = pefile.PE(src, fast_load=True)
    pos = max(s.PointerToRawData + s.SizeOfRawData for s in pe.sections)
    os.makedirs(out, exist_ok=True)
    n = 0
    while pos < len(data):
        try:
            name, pos = cstr(data, pos)
            rel, pos = cstr(data, pos)
            ver, pos = cstr(data, pos)
            size_s, pos = cstr(data, pos)
            size = int(size_s)
        except (ValueError, IndexError):
            print(f"레코드 해석 중단 @0x{pos:x}")
            break
        blob = data[pos:pos + size]
        if len(blob) != size:
            print(f"잘린 레코드: {rel} ({len(blob)}/{size})")
            break
        pos += size
        dst = os.path.join(out, *rel.replace("/", "\\").split("\\"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(blob)
        n += 1
        print(f"{rel}\t{ver}\t{size}\t{hashlib.sha256(blob).hexdigest()}")
    print(f"records={n} end=0x{pos:x} file_size=0x{len(data):x}")
    return 0 if pos == len(data) else 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
