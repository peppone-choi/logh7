#!/usr/bin/env python3
"""MODE2/2352 원시 CD 이미지(.bin)를 2048바이트 섹터 ISO로 변환한다.

사용법:
    python tools/bin2iso.py E:\\logh7-original\\archive\\Logh7.bin E:\\logh7-original\\extracted\\Logh7.iso

섹터 구조(MODE2 XA): sync 12 + header 4 + subheader 8 + user data 2048(Form1) / 2324(Form2).
ISO9660 파일시스템은 Form1 데이터만 사용하므로 오프셋 24에서 2048바이트를 잘라낸다.
Form2 섹터가 있으면 개수만 보고한다(섹터 정렬 유지를 위해 동일하게 2048바이트를 쓴다).
"""
import hashlib
import sys

RAW = 2352
SYNC = b"\x00" + b"\xff" * 10 + b"\x00"


def main(src: str, dst: str) -> int:
    form1 = form2 = bad_sync = 0
    sha256 = hashlib.sha256()
    with open(src, "rb") as fi, open(dst, "wb") as fo:
        while True:
            sec = fi.read(RAW)
            if not sec:
                break
            if len(sec) != RAW:
                print(f"경고: 마지막 섹터 길이 {len(sec)}", file=sys.stderr)
                break
            if sec[:12] != SYNC:
                bad_sync += 1
            mode = sec[15]
            if mode != 2:
                print(f"경고: mode={mode} 섹터 발견", file=sys.stderr)
            submode = sec[18]
            if submode & 0x20:
                form2 += 1
            else:
                form1 += 1
            data = sec[24:24 + 2048]
            fo.write(data)
            sha256.update(data)
    print(f"sectors form1={form1} form2={form2} bad_sync={bad_sync}")
    print(f"iso_sha256={sha256.hexdigest()}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
