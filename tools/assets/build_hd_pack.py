"""업스케일 결과로 클라이언트 HD 텍스처 묶음(hd\\)을 만든다.

작성자: Peppone Choi (peppone-choi).

호환 DLL(client-compat/hd_textures.h)은 설치 폴더의 hd\\<data 기준 상대 경로>.png 를 찾아 쓴다.
원본 크기의 정확히 N배(2~8)인 파일만 쓰고, 클라이언트가 불러온 뒤 픽셀을 고치는 파일은 제외한다.
제외 규칙은 client-compat/hd_texture_data.h 의 문자열을 그대로 읽는다(규칙을 두 곳에 따로 두지 않는다).

- UI·스폿·모델 Hi: upscale_assets.py 의 <set>/x<배율>/<rel>.png 를 그대로 복사한다.
- 모델 Mid·Lo: Hi 를 2배·4배 줄인 판이라, 같은 이름의 Hi 4배 결과를 (원본 Mid·Lo 크기 × 배율)로 Lanczos 축소한다.
  Hi 에 없는 이름만 별도 업스케일 결과(midlo)를 쓴다.

출력(E:/logh7/work 아래만): <out>/hd/..., <out>/hd-manifest.json. 원본은 읽기만 한다.
"""
import argparse
import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
from upscale_assets import load_rgba, writable  # noqa: E402

HEADER = Path(__file__).resolve().parents[2] / 'client-compat' / 'hd_texture_data.h'
SETS = ('ui', 'spot', 'hi')
MODEL_DIRS = ('Mid', 'Lo')


def read_rules(header=HEADER):
    """hd_texture_data.h 의 kSkipMarkers·kSkipNames 문자열 목록."""
    text = Path(header).read_text(encoding='utf-8')
    rules = {}
    for key in ('kSkipMarkers', 'kSkipNames'):
        m = re.search(key + r'\[\]=\{([^}]*)\}', text)
        if not m:
            raise ValueError(f'{key} not found in {header}')
        rules[key] = re.findall(r'"([^"]*)"', m.group(1))
    return rules


def skipped(rel, rules):
    """DLL 의 logh_hd::skipped 와 같은 판정(파일 이름만, 대소문자 무시)."""
    name = re.split(r'[\\/]', rel)[-1].lower()
    return any(m.lower() in name for m in rules['kSkipMarkers']) or name in (n.lower() for n in rules['kSkipNames'])


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def target(out, rel):
    p = out / 'hd' / Path(*re.split(r'[\\/]', rel))
    return p.with_name(p.name + '.png')


def build(src, upscaled, out, scale, rules):
    src, upscaled, out = Path(src), Path(upscaled), Path(out)
    entries, rejected = [], []

    def add(rel, kind, image_path=None, image=None):
        orig = load_rgba(src / rel).size
        dst = target(out, rel)
        dst.parent.mkdir(parents=True, exist_ok=True)
        if image is None:
            with Image.open(image_path) as im:
                size = im.size
            if size != (orig[0] * scale, orig[1] * scale):
                rejected.append(dict(rel=rel, reason=f'size {size} != {orig}x{scale}'))
                return
            shutil.copyfile(image_path, dst)
        else:
            image.save(dst)
            size = image.size
        entries.append(dict(rel=rel, kind=kind, scale=scale, size=list(size), source_size=list(orig),
                            source_sha256=sha256(src / rel), bytes=dst.stat().st_size))

    for s in SETS:
        manifest = json.loads((upscaled / s / 'manifest.json').read_text(encoding='utf-8'))
        for item in manifest['items']:
            rel = item['rel']
            if skipped(rel, rules):
                rejected.append(dict(rel=rel, reason='skip rule'))
                continue
            path = upscaled / s / f'x{scale}' / (rel + '.png')
            if not path.exists():
                rejected.append(dict(rel=rel, reason='missing upscale'))
                continue
            add(rel, s, image_path=path)

    hi_dir = src / 'model' / 'images' / 'Hi'
    hi_names = {p.name.lower(): p.name for p in hi_dir.iterdir()} if hi_dir.exists() else {}
    for d in MODEL_DIRS:
        folder = src / 'model' / 'images' / d
        if not folder.exists():
            continue
        for p in sorted(folder.iterdir()):
            rel = f'model/images/{d}/{p.name}'
            if skipped(rel, rules):
                rejected.append(dict(rel=rel, reason='skip rule'))
                continue
            hi_name = hi_names.get(p.name.lower())
            hi4 = upscaled / 'hi' / 'x4' / 'model' / 'images' / 'Hi' / ((hi_name or '') + '.png')
            if hi_name and hi4.exists():
                w, h = load_rgba(p).size
                with Image.open(hi4) as im:
                    add(rel, d.lower(), image=im.convert('RGBA').resize((w * scale, h * scale), Image.LANCZOS))
                continue
            own = upscaled / 'midlo' / f'x{scale}' / (rel + '.png')
            if own.exists():
                add(rel, d.lower(), image_path=own)
            else:
                rejected.append(dict(rel=rel, reason='missing upscale'))
    return entries, rejected


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--src', required=True, help='원본 data 루트(읽기 전용)')
    ap.add_argument('--upscaled', required=True, help='upscale_assets 결과 루트(ui, spot, hi, midlo)')
    ap.add_argument('--out', required=True)
    ap.add_argument('--scale', type=int, default=2, choices=(2, 4))
    args = ap.parse_args(argv)
    out = writable(args.out)
    if (out / 'hd').exists():
        shutil.rmtree(out / 'hd')
    entries, rejected = build(args.src, args.upscaled, out, args.scale, read_rules())
    kinds = {}
    for e in entries:
        k = kinds.setdefault(e['kind'], dict(files=0, bytes=0, megapixels=0.0))
        k['files'] += 1
        k['bytes'] += e['bytes']
        k['megapixels'] += e['size'][0] * e['size'][1] / 1e6
    for k in kinds.values():
        k['megapixels'] = round(k['megapixels'], 1)
        k['argb_mib'] = round(k['megapixels'] * 4e6 / 2 ** 20)  # A8R8G8B8 로 모두 올렸을 때
    summary = dict(scale=args.scale, files=len(entries), rejected=len(rejected), kinds=kinds)
    (out / 'hd-manifest.json').write_text(json.dumps(dict(summary=summary, entries=entries, rejected=rejected),
                                                     ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
