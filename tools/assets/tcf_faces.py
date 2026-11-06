"""얼굴 묶음(.tcf)을 레코드별 TGA 파일로 나눠 업스케일 입력을 만든다. 작성자: Peppone Choi (peppone-choi).

.tcf는 50바이트 머리말 뒤에 비압축 팔레트 TGA가 빈틈없이 이어진다(docs/re/npc-character-catalogue.md).
원작 계열(oem/oam/o)과 제너레이트 계열(gem/gef/gam/gaf) 모두 순서대로 읽으면 파일 끝과 정확히 맞는다.
HED 색인 해석은 npc_catalogue.py가 맡고, 여기서는 순서 번호와 바이트 위치만 기록한다.

출력(E:/logh7/work 아래만): <out>/<묶음>/<묶음>_<NNN>.tga, <out>/index.json.
원본은 읽기만 하고 TGA 바이트는 그대로 복사한다.
"""
import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from npc_catalogue import tga_length  # noqa: E402
from upscale_assets import writable  # noqa: E402

HEADER = 50


def split_tcf(data):
    """(offset, TGA 바이트) 목록. 머리말 뒤가 TGA 연속으로 끝까지 맞지 않으면 오류."""
    if len(data) < HEADER:
        raise ValueError('TCF shorter than its header')
    records, offset = [], HEADER
    while offset < len(data):
        size = tga_length(data, offset)
        records.append((offset, data[offset:offset + size]))
        offset += size
    return records


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--face-dir', required=True, help='원본 data/image/Face (읽기 전용)')
    ap.add_argument('--out', required=True)
    args = ap.parse_args(argv)
    out = writable(args.out)
    index = []
    for tcf in sorted(Path(args.face_dir).glob('*.tcf')):
        group = tcf.stem.lower()
        (out / group).mkdir(parents=True, exist_ok=True)
        for n, (offset, tga) in enumerate(split_tcf(tcf.read_bytes())):
            rel = f'{group}/{group}_{n:03d}.tga'
            (out / rel).write_bytes(tga)
            width, height = struct.unpack_from('<HH', tga, 12)
            index.append(dict(rel=rel, group=group, order=n, offset=offset, size=len(tga), width=width, height=height))
        print(group, sum(1 for i in index if i['group'] == group), flush=True)
    (out / 'index.json').write_text(json.dumps(index, ensure_ascii=False, indent=1), encoding='utf-8')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
