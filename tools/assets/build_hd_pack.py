"""업스케일 결과로 클라이언트 HD 텍스처 묶음(hd\\)을 만든다.

작성자: Peppone Choi (peppone-choi).

호환 DLL(client-compat/hd_textures.h)은 설치 폴더의 hd\\ 에서 아래 이름의 PNG 를 찾아 쓴다.
원본 크기의 정확히 N배(2~8)인 파일만 쓰고, 클라이언트가 불러온 뒤 픽셀을 고치는 파일은 제외한다.
제외 규칙은 client-compat/hd_texture_data.h 의 문자열을 그대로 읽는다(규칙을 두 곳에 따로 두지 않는다).

- 파일 이름은 hd\\<rel>.<원본 FNV-1a 32>.png. 같은 경로라도 원본이 다르면(업데이트판) 다른 파일이다.
- 층(--layer 원본 업스케일)을 여러 개 줄 수 있다: 기본 설치판 → 2004-05-14 업데이트판.
- UI·스폿·모델 Hi·기타(extra): upscale_assets.py 의 <set>/x<배율>/<rel>.png 를 그대로 복사한다.
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
SETS = ('ui', 'spot', 'hi', 'extra')
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


_DIGESTS = {}


def fnv1a32(data):
    """DLL 의 logh_hd::fnv1a32 와 같은 값(원본 파일 바이트)."""
    h = 2166136261
    for b in data:
        h = ((h ^ b) * 16777619) & 0xFFFFFFFF
    return h


def file_digest(path):
    key = Path(path).resolve()
    if key not in _DIGESTS:
        _DIGESTS[key] = fnv1a32(key.read_bytes())
    return _DIGESTS[key]


def target(out, rel, digest):
    """hd/<rel>.<fnv1a32 8자리>.png (DLL 의 logh_hd::hdFileName)."""
    p = out / 'hd' / Path(*re.split(r'[\\/]', rel))
    return p.with_name(f'{p.name}.{digest:08x}.png')


def build(layers, out, scale, rules):
    """layers: [(data 루트, 업스케일 루트)]. 앞 층부터 처리하고, 같은 원본(해시)이 이미 있으면 건너뛴다.

    파일 이름에 원본 해시가 들어가므로 기본 설치판과 2004-05-14 업데이트판이 한 묶음에 함께 들어간다.
    """
    out = Path(out)
    entries, rejected, seen = [], [], set()

    def add(src, rel, kind, image_path=None, image=None):
        data = (src / rel).read_bytes()
        digest = file_digest(src / rel)
        dst = target(out, rel, digest)
        if dst in seen:
            return
        orig = load_rgba(src / rel).size
        if image is None:
            with Image.open(image_path) as im:
                size = im.size
            if size != (orig[0] * scale, orig[1] * scale):
                rejected.append(dict(rel=rel, src=str(src), reason=f'size {size} != {orig}x{scale}'))
                return
        dst.parent.mkdir(parents=True, exist_ok=True)
        if image is None:
            shutil.copyfile(image_path, dst)
        else:
            image.save(dst)
            size = image.size
        seen.add(dst)
        entries.append(dict(rel=rel, kind=kind, scale=scale, size=list(size), source_size=list(orig),
                            source=str(src), fnv1a32=f'{digest:08x}', source_sha256=hashlib.sha256(data).hexdigest(),
                            file=dst.relative_to(out / 'hd').as_posix(), bytes=dst.stat().st_size))

    for src, upscaled in layers:
        src, upscaled = Path(src), Path(upscaled)
        for s in SETS:
            mf = upscaled / s / 'manifest.json'
            if not mf.exists():
                continue
            for item in json.loads(mf.read_text(encoding='utf-8'))['items']:
                rel = item['rel']
                if skipped(rel, rules):
                    rejected.append(dict(rel=rel, src=str(src), reason='skip rule'))
                    continue
                path = upscaled / s / f'x{scale}' / (rel + '.png')
                if not path.exists():
                    rejected.append(dict(rel=rel, src=str(src), reason='missing upscale'))
                    continue
                add(src, rel, s, image_path=path)

        hi_dir = src / 'model' / 'images' / 'Hi'
        hi_names = {p.name.lower(): p.name for p in hi_dir.iterdir()} if hi_dir.exists() else {}
        for d in MODEL_DIRS:
            folder = src / 'model' / 'images' / d
            if not folder.exists():
                continue
            for p in sorted(folder.iterdir()):
                rel = f'model/images/{d}/{p.name}'
                if skipped(rel, rules):
                    rejected.append(dict(rel=rel, src=str(src), reason='skip rule'))
                    continue
                if target(out, rel, file_digest(p)) in seen:
                    continue
                hi_name = hi_names.get(p.name.lower())
                hi4 = upscaled / 'hi' / 'x4' / 'model' / 'images' / 'Hi' / ((hi_name or '') + '.png')
                if hi_name and hi4.exists():
                    w, h = load_rgba(p).size
                    with Image.open(hi4) as im:
                        add(src, rel, d.lower(), image=im.convert('RGBA').resize((w * scale, h * scale), Image.LANCZOS))
                    continue
                own = upscaled / 'midlo' / f'x{scale}' / (rel + '.png')
                if own.exists():
                    add(src, rel, d.lower(), image_path=own)
                else:
                    rejected.append(dict(rel=rel, src=str(src), reason='missing upscale'))
    return entries, rejected


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--layer', nargs=2, action='append', required=True, metavar=('SRC', 'UPSCALED'),
                    help='원본 data 루트(읽기 전용)와 그 업스케일 결과 루트(ui, spot, hi, extra, midlo). 여러 번 줄 수 있다')
    ap.add_argument('--out', required=True)
    ap.add_argument('--scale', type=int, default=2, choices=(2, 4))
    args = ap.parse_args(argv)
    out = writable(args.out)
    if (out / 'hd').exists():
        shutil.rmtree(out / 'hd')
    entries, rejected = build(args.layer, out, args.scale, read_rules())
    kinds = {}
    for e in entries:
        k = kinds.setdefault(e['kind'], dict(files=0, bytes=0, megapixels=0.0))
        k['files'] += 1
        k['bytes'] += e['bytes']
        k['megapixels'] += e['size'][0] * e['size'][1] / 1e6
    for k in kinds.values():
        k['megapixels'] = round(k['megapixels'], 1)
        k['argb_mib'] = round(k['megapixels'] * 4e6 / 2 ** 20)  # A8R8G8B8 로 모두 올렸을 때
    summary = dict(scale=args.scale, layers=[s for s, _ in args.layer], files=len(entries), rejected=len(rejected), kinds=kinds)
    (out / 'hd-manifest.json').write_text(json.dumps(dict(summary=summary, entries=entries, rejected=rejected),
                                                     ensure_ascii=False, indent=1), encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
