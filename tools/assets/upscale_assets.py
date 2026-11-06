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


FALLBACKS = ('realesrgan-x4plus-anime', 'lanczos')


def block_error(source_rgb, up_rgb, block=16):
    """업스케일 결과를 원본 크기로 되돌려 16px 블록마다 평균 절대 오차를 구하고 그 최댓값을 낸다.

    평균 오차로는 안 보이는 국소 깨짐(줄무늬 띠 등)을 잡기 위한 값이다. 정상 결과는 실측 40 미만이었다.
    """
    a = np.asarray(source_rgb, dtype=np.float32)
    b = np.asarray(up_rgb.resize(source_rgb.size, Image.Resampling.LANCZOS), dtype=np.float32)
    err = np.abs(a - b).mean(-1)
    h, w = err.shape
    if h < block or w < block:
        return float(err.mean())
    hh, ww = h // block * block, w // block * block
    return float(err[:hh, :ww].reshape(hh // block, block, ww // block, block).mean((1, 3)).max())


def seam_ratio(rgb, axis):
    """반대쪽 가장자리끼리의 차이를 이웃 픽셀끼리의 평균 차이로 나눈 값. 1 근처면 이어지는 텍스처다."""
    a = np.asarray(rgb, dtype=np.float32)
    if axis == 'x':
        edge, inner = np.abs(a[:, 0] - a[:, -1]).mean(), np.abs(a[:, 1:] - a[:, :-1]).mean()
    else:
        edge, inner = np.abs(a[0] - a[-1]).mean(), np.abs(a[1:] - a[:-1]).mean()
    return float(edge / max(inner, 1e-3))


def tiling(rgb, limit=2.0):
    """(좌우로 이어짐, 위아래로 이어짐). 원본 가장자리 차이가 내부 이웃 차이의 limit배 이하이면 이어진다고 본다."""
    if rgb.width < 8 or rgb.height < 8:
        return (False, False)
    return (seam_ratio(rgb, 'x') <= limit, seam_ratio(rgb, 'y') <= limit)


def pad_wrap(rgb, pad, wrap_x, wrap_y):
    """이어지는 방향으로 반대편 내용을 pad만큼 붙인다(업스케일 모델이 가장자리를 끊지 않게)."""
    if not pad or not (wrap_x or wrap_y):
        return rgb
    a = np.asarray(rgb)
    a = np.pad(a, ((pad if wrap_y else 0,) * 2, (pad if wrap_x else 0,) * 2, (0, 0)), mode='wrap')
    return Image.fromarray(a, 'RGB')


def crop_pad(up, pad, wrap_x, wrap_y):
    if not pad or not (wrap_x or wrap_y):
        return up
    px, py = (pad if wrap_x else 0), (pad if wrap_y else 0)
    return up.crop((px, py, up.width - px, up.height - py))


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
    ap.add_argument('--max-block', type=float, default=50.0,
                    help='16px 블록 평균 오차 상한. 넘으면 깨진 출력으로 보고 대체 모델→Lanczos로 다시 만든다')
    ap.add_argument('--wrap', choices=('none', 'auto'), default='none',
                    help='auto: 가장자리가 이어지는 불투명 텍스처(행성·타일 노이즈)를 감싸 붙여 업스케일해 이음매를 보존')
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
        skipped = []
        for i, (rel, model) in enumerate(items):
            data = (src / rel).read_bytes()
            try:
                image = load_rgba(src / rel)
            except Exception as exc:  # 해석 못 한 파일은 건너뛰고 기록한다(전체 실행을 멈추지 않음)
                skipped.append(dict(rel=rel, error=str(exc)[:200]))
                continue
            key = f'{i:05d}.png'
            (stage / model / 'in').mkdir(parents=True, exist_ok=True)
            alpha = image.getchannel('A')
            lo, hi = alpha.getextrema()
            rgb = bleed(image)
            wrap = tiling(rgb) if args.wrap == 'auto' and lo == 255 else (False, False)
            pad = min(16, rgb.width // 4, rgb.height // 4) if any(wrap) else 0
            pad_wrap(rgb, pad, *wrap).save(stage / model / 'in' / key)
            binary = bool(np.isin(np.asarray(alpha), (0, 255)).all())
            meta = dict(rel=rel, model=model, sha256=hashlib.sha256(data).hexdigest(),
                        size=list(image.size), alpha=[lo, hi], binary_alpha=binary, wrap=list(wrap))
            manifest.append(meta)
            groups.setdefault(model, []).append((key, rel, image.size, alpha if lo < 255 else None, binary, pad, wrap, meta))
        timing = {}
        for model, entries in groups.items():
            (stage / model / 'out').mkdir(exist_ok=True)
            start = time.perf_counter()
            if model != 'lanczos':  # lanczos: 노이즈·이펙트처럼 모델이 형태를 바꾸면 안 되는 이미지
                subprocess.run([str(TOOL), '-i', str(stage / model / 'in'), '-o', str(stage / model / 'out'),
                                '-n', model, '-s', '4', '-t', args.tile, '-f', 'png'],
                               check=True, cwd=TOOL.parent, capture_output=True)
            timing[model] = round(time.perf_counter() - start, 2)
            for key, rel, size, alpha, binary, pad, wrap, meta in entries:
                staged = Image.open(stage / model / 'in' / key).convert('RGB')
                padded = (staged.width * 4, staged.height * 4)
                if model == 'lanczos':
                    up = staged.resize(padded, Image.Resampling.LANCZOS)
                else:
                    up = Image.open(stage / model / 'out' / key).convert('RGB')
                    if up.size != padded:
                        up = up.resize(padded, Image.Resampling.LANCZOS)
                    # 큰 모델이 일부 GPU(GTX 16xx 확인)에서 국소적으로 깨진 출력을 낸다. 감지하면 대체 경로로 다시 만든다.
                    worst = block_error(staged, up)
                    meta['block_error'] = round(worst, 1)
                    for alt in (FALLBACKS if worst > args.max_block else ()):
                        if alt == model:
                            continue
                        retry = stage / 'retry' / f'{alt}-{key}'
                        retry.parent.mkdir(parents=True, exist_ok=True)
                        if alt == 'lanczos':
                            cand = staged.resize(padded, Image.Resampling.LANCZOS)
                        else:
                            subprocess.run([str(TOOL), '-i', str(stage / model / 'in' / key), '-o', str(retry),
                                            '-n', alt, '-s', '4', '-t', args.tile, '-f', 'png'],
                                           check=True, cwd=TOOL.parent, capture_output=True)
                            cand = Image.open(retry).convert('RGB').resize(padded, Image.Resampling.LANCZOS)
                        err = block_error(staged, cand)
                        meta.setdefault('fallback', []).append(dict(model=alt, block_error=round(err, 1)))
                        if err <= args.max_block:
                            up = cand
                            meta['used'] = alt
                            break
                if args.detail:
                    up = restore_detail(up, staged, args.detail)
                up = crop_pad(up, pad * 4, *wrap)
                big = (size[0] * 4, size[1] * 4)
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
            tool=str(TOOL), items=manifest, skipped=skipped, model_seconds=timing, source_pixels=pixels),
            ensure_ascii=False, indent=1), encoding='utf-8')
        print(json.dumps(dict(files=len(manifest), skipped=len(skipped), source_megapixels=round(pixels / 1e6, 2),
                              model_seconds=timing), ensure_ascii=False))
    finally:
        shutil.rmtree(stage, ignore_errors=True)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
