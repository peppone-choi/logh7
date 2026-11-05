"""원본 게임 이미지·텍스처를 Real-ESRGAN(ncnn-vulkan)으로 일괄 업스케일한다.

작성자: Peppone Choi (peppone-choi).

- 원본은 읽기만 한다. 출력은 E:/logh7/work 아래로 제한한다(게임 저작물은 커밋 금지).
- TGA는 무압축/RLE, 팔레트(24/32비트 항목)/true-color를 직접 해석해 RGBA로 만든다.
  Pillow는 32비트 팔레트 TGA를 읽지 못한다.
- 색은 모델로, 알파는 Lanczos로 키운다. 원본 알파가 0/255 두 값뿐이면 다시 이진화해
  색키 가장자리를 유지한다. 투명 픽셀의 색은 가까운 불투명 색으로 채워 경계 번짐을 줄인다.
- 출력: <out>/x4/<rel>.png, <out>/x2/<rel>.png(4배 결과를 Lanczos 축소), manifest.json
  (원본 SHA-256·크기·모델·소요 시간). 원래 픽셀 배치를 그대로 확대하므로 아틀라스의
  상대 좌표(정규화 UV)는 유지된다.

예: python -I tools/assets/upscale_assets.py --src <data 루트> --out E:/logh7/work/remaster-20261105/upscale \
        --list sample.txt
"""
import argparse
import hashlib
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

WORK = Path('E:/logh7/work')
LOCAL_WORK = Path(__file__).resolve().parents[2] / 'work'
TOOL = Path('E:/Tools/realesrgan-ncnn-vulkan/realesrgan-ncnn-vulkan.exe')
IMAGE_EXT = {'.tga', '.bmp', '.jpg', '.jpeg', '.png'}


def writable(path):
    path = Path(path).resolve()
    if not (path.is_relative_to(WORK.resolve()) or path.is_relative_to(LOCAL_WORK.resolve())):
        raise ValueError('writes are restricted to E:/logh7/work')
    return path


def decode_tga(data):
    """TGA 1/2/3/9/10/11 형식을 RGBA Pillow 이미지로 바꾼다."""
    if len(data) < 18:
        raise ValueError('truncated TGA header')
    ident, cmap, kind, first, colors, bits, _, _, width, height, depth, desc = \
        struct.unpack_from('<BBBHHBHHHHBB', data)
    if kind not in (1, 2, 3, 9, 10, 11) or not width or not height:
        raise ValueError(f'unsupported TGA type {kind}')
    pos = 18 + ident
    palette = []
    if cmap:
        size = (bits + 7) // 8
        for i in range(colors):
            palette.append(_color(data[pos + i * size:pos + (i + 1) * size], bits))
        pos += colors * size
    step = (depth + 7) // 8
    count = width * height
    if kind >= 9:
        raw = bytearray()
        while len(raw) < count * step:
            head = data[pos]
            pos += 1
            n = (head & 0x7f) + 1
            if head & 0x80:
                raw += data[pos:pos + step] * n
                pos += step
            else:
                raw += data[pos:pos + n * step]
                pos += n * step
        raw = bytes(raw[:count * step])
    else:
        raw = data[pos:pos + count * step]
    if len(raw) != count * step:
        raise ValueError('truncated TGA pixels')
    out = bytearray(count * 4)
    base = kind & 3
    for i in range(count):
        if base == 1:
            index = raw[i * step] if step == 1 else struct.unpack_from('<H', raw, i * step)[0]
            rgba = palette[index - first] if 0 <= index - first < len(palette) else (0, 0, 0, 0)
        elif base == 2:
            rgba = _color(raw[i * step:(i + 1) * step], depth)
        else:
            v = raw[i * step]
            rgba = (v, v, v, 255)
        out[i * 4:i * 4 + 4] = bytes(rgba)
    image = Image.frombytes('RGBA', (width, height), bytes(out))
    if not desc & 0x20:  # 기본은 아래에서 위로 저장
        image = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    if desc & 0x10:
        image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    return image


def _color(b, bits):
    if bits == 32:
        return (b[2], b[1], b[0], b[3])
    if bits == 24:
        return (b[2], b[1], b[0], 255)
    if bits in (15, 16):
        v = b[0] | b[1] << 8
        r, g, bl = (v >> 10) & 31, (v >> 5) & 31, v & 31
        a = 255 if bits == 15 or v & 0x8000 else 0
        return (r * 255 // 31, g * 255 // 31, bl * 255 // 31, a)
    if bits == 8:
        return (b[0], b[0], b[0], 255)
    raise ValueError(f'unsupported TGA color depth {bits}')


def load_rgba(path):
    path = Path(path)
    if path.suffix.lower() == '.tga':
        return decode_tga(path.read_bytes())
    return Image.open(path).convert('RGBA')


def bleed(image):
    """투명 영역의 색을 가까운 불투명 색으로 채운다(모델 입력용)."""
    rgba = np.asarray(image, dtype=np.float32)
    known = rgba[..., 3] > 0
    rgb = rgba[..., :3].copy()
    if known.all() or not known.any():
        return Image.fromarray(rgb.astype(np.uint8), 'RGB')
    # 반지름을 늘려 가며 불투명 이웃의 평균색을 아직 비어 있는 칸에만 채운다.
    for radius in (2, 4, 8, 16, 32, 64):
        weight = known.astype(np.float32)
        color = rgb * weight[..., None]
        for axis in (0, 1):
            weight = _box(weight, radius, axis)
            color = _box(color, radius, axis)
        fill = ~known & (weight > 0)
        rgb[fill] = color[fill] / weight[fill][:, None]
        known = known | fill
        if known.all():
            break
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), 'RGB')


def _box(values, radius, axis):
    pad = [(0, 0)] * values.ndim
    pad[axis] = (radius + 1, radius)
    total = np.cumsum(np.pad(values, pad), axis=axis)
    n = values.shape[axis]
    return np.take(total, range(2 * radius + 1, 2 * radius + 1 + n), axis=axis) - \
        np.take(total, range(0, n), axis=axis)


def restore_detail(up, source_rgb, strength):
    """모델이 지운 원본 질감을 되살린다: Lanczos 확대본의 고주파 성분을 AI 결과에 더한다.

    32px 아이콘처럼 작은 원본에서 모델이 음영을 매끈하게 지워 '뭉개짐'이 생기는 문제를 줄인다.
    """
    lanczos = source_rgb.resize(up.size, Image.Resampling.LANCZOS)
    hi = np.asarray(lanczos, dtype=np.float32) - np.asarray(
        lanczos.filter(ImageFilter.GaussianBlur(1.6)), dtype=np.float32)
    out = np.asarray(up, dtype=np.float32) + strength * hi
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), 'RGB')


def staging_dir(out):
    """출력 폴더를 먼저 만들고 그 안에 임시 작업 폴더를 둔다(새 케이스 폴더에서도 첫 실행이 되도록)."""
    out.mkdir(parents=True, exist_ok=True)
    return Path(tempfile.mkdtemp(prefix='.upscale-', dir=out))


def downscale(image, size, binary):
    """4배 결과를 축소한다. 원본 알파가 0/255뿐이면 축소 뒤에도 다시 이진화해 색키 가장자리를 지킨다."""
    small = image.resize(size, Image.Resampling.LANCZOS)
    if binary and small.mode == 'RGBA':
        small.putalpha(small.getchannel('A').point(lambda v: 255 if v >= 128 else 0))
    return small


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True, help='원본 data 루트(읽기 전용)')
    ap.add_argument('--out', required=True)
    ap.add_argument('--list', help='src 기준 상대 경로 목록 파일(줄마다 `상대경로[\\t모델]`)')
    ap.add_argument('--model', default='realesrgan-x4plus-anime')
    ap.add_argument('--tile', default='0')
    ap.add_argument('--detail', type=float, default=0.0,
                    help='원본 질감 복원 강도(0=끔). 작은 아이콘은 realesrgan-x4plus와 0.9 권장')
    args = ap.parse_args(argv)
    src, out = Path(args.src), writable(args.out)
    if args.list:
        items = []
        for line in Path(args.list).read_text(encoding='utf-8').splitlines():
            if line.strip() and not line.startswith('#'):
                rel, _, model = line.partition('\t')
                items.append((rel.strip(), model.strip() or args.model))
    else:
        items = [(str(p.relative_to(src)).replace('\\', '/'), args.model)
                 for p in sorted(src.rglob('*')) if p.suffix.lower() in IMAGE_EXT]
    manifest, groups = [], {}
    stage = staging_dir(out)
    try:
        for i, (rel, model) in enumerate(items):
            data = (src / rel).read_bytes()
            image = load_rgba(src / rel)
            key = f'{i:05d}.png'
            (stage / model / 'in').mkdir(parents=True, exist_ok=True)
            bleed(image).save(stage / model / 'in' / key)
            alpha = image.getchannel('A')
            lo, hi = alpha.getextrema()
            binary = bool(np.isin(np.asarray(alpha), (0, 255)).all())
            groups.setdefault(model, []).append((key, rel, image.size, alpha if lo < 255 else None, binary))
            manifest.append(dict(rel=rel, model=model, sha256=hashlib.sha256(data).hexdigest(),
                                 size=list(image.size), alpha=[lo, hi], binary_alpha=binary))
        timing = {}
        for model, entries in groups.items():
            (stage / model / 'out').mkdir(exist_ok=True)
            start = time.perf_counter()
            subprocess.run([str(TOOL), '-i', str(stage / model / 'in'), '-o', str(stage / model / 'out'),
                            '-n', model, '-s', '4', '-t', args.tile, '-f', 'png'],
                           check=True, cwd=TOOL.parent, capture_output=True)
            timing[model] = round(time.perf_counter() - start, 2)
            for key, rel, size, alpha, binary in entries:
                up = Image.open(stage / model / 'out' / key).convert('RGB')
                big = (size[0] * 4, size[1] * 4)
                if up.size != big:
                    up = up.resize(big, Image.Resampling.LANCZOS)
                if args.detail:
                    up = restore_detail(up, Image.open(stage / model / 'in' / key).convert('RGB'), args.detail)
                if alpha is not None:
                    a = alpha.resize(big, Image.Resampling.LANCZOS)
                    if binary:
                        a = a.point(lambda v: 255 if v >= 128 else 0)
                    up.putalpha(a)
                else:
                    up.putalpha(255)
                for scale, image in (('x4', up), ('x2', downscale(up, (size[0] * 2, size[1] * 2), binary))):
                    target = out / scale / (rel + '.png')
                    target.parent.mkdir(parents=True, exist_ok=True)
                    image.save(target, optimize=False)
        pixels = sum(m['size'][0] * m['size'][1] for m in manifest)
        (out / 'manifest.json').write_text(json.dumps(dict(
            tool=str(TOOL), items=manifest, model_seconds=timing, source_pixels=pixels),
            ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(dict(files=len(manifest), source_megapixels=round(pixels / 1e6, 2),
                              model_seconds=timing), ensure_ascii=False))
    finally:
        shutil.rmtree(stage, ignore_errors=True)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
