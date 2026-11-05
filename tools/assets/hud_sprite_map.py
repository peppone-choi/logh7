"""캡처 화면의 HUD 부품을 원본 이미지 아틀라스와 대조해 '어느 파일의 어느 조각인지' 출처 지도를 만든다.

작성자: Peppone Choi (peppone-choi).

두 방식을 함께 쓴다.
1. 큰 아틀라스(조각 하나가 96×96 초과): SIFT 특징점 대조 → RANSAC으로 조각별 위치·배율을 추정하고,
   그 위치에서 완전 불투명(알파 250 이상) 픽셀만 다시 비교해 확인한다. 반투명 가장자리·위에 겹친 글자 때문에
   1:1 템플릿 대조가 실패하는 하단 패널 같은 부품용이다.
2. 작은 조각: 완전 불투명 픽셀 마스크로 템플릿 대조(배율 1:1).

출력(E:/logh7/work 아래만): <out>/<캡처>.json, <out>/<캡처>-map.png(출처별 색 상자), <out>/summary.json.
원본은 읽기만 하고 게임은 실행하지 않는다.
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from upscale_assets import load_rgba, writable  # noqa: E402

DEFAULT_DIRS = ['senryaku_panel', 'strategy', 'rader', 'icon', 'window', 'chat', 'soukan', 'shokumu_card', 'Field',
                'gamemenu', 'icon_normal', 'icon_nfocus', 'icon_kj', 'trush']
LARGE = 96 * 96
SOLID = 250
TILE = 32


def components(rgba, min_area=64):
    """알파>0 연결 영역의 사각형 목록. 알파가 없으면 이미지 전체 한 조각."""
    a = np.asarray(rgba)
    alpha = a[..., 3]
    if alpha.min() == 255:
        return [(0, 0, a.shape[1], a.shape[0])]
    n, _, stats, _ = cv2.connectedComponentsWithStats((alpha > 0).astype(np.uint8), connectivity=8)
    return [tuple(int(v) for v in stats[i][:4]) for i in range(1, n)
            if stats[i][4] >= min_area and stats[i][2] >= 6 and stats[i][3] >= 6]


def solid_error(capture_rgb, sprite_rgba, x, y):
    """조각을 화면 (x,y)에 1:1로 놓았을 때 완전 불투명 픽셀의 채널 평균 제곱 오차와 비교 픽셀 수."""
    h, w = sprite_rgba.shape[:2]
    if x < 0 or y < 0 or x + w > capture_rgb.shape[1] or y + h > capture_rgb.shape[0]:
        return None, 0
    mask = sprite_rgba[..., 3] >= SOLID
    n = int(mask.sum())
    if n == 0:
        return None, 0
    diff = capture_rgb[y:y + h, x:x + w].astype(np.float32) - sprite_rgba[..., :3].astype(np.float32)
    return float((diff ** 2).sum(-1)[mask].mean() / 3), n


def template_hits(capture_rgb, sprite_rgba, thresh, limit=8):
    mask = (sprite_rgba[..., 3] >= SOLID).astype(np.float32)
    cnt = float(mask.sum())
    h, w = mask.shape
    if cnt < 48 or h > capture_rgb.shape[0] or w > capture_rgb.shape[1]:
        return []
    m3 = cv2.merge([mask, mask, mask])
    res = cv2.matchTemplate(capture_rgb.astype(np.float32), sprite_rgba[..., :3].astype(np.float32),
                            cv2.TM_SQDIFF, mask=m3) / (cnt * 3)
    res = np.nan_to_num(res, nan=1e9, posinf=1e9)
    hits = []
    while len(hits) < limit:
        mn, _, loc, _ = cv2.minMaxLoc(res)
        if mn > thresh:
            break
        hits.append((loc[0], loc[1], float(mn)))
        res[max(0, loc[1] - h // 2):loc[1] + h // 2 + 1, max(0, loc[0] - w // 2):loc[0] + w // 2 + 1] = 1e9
    return hits


def sift_hits(capture_rgb, atlas_rgba, rects, thresh, sift, cap_feat):
    """아틀라스 전체를 SIFT로 대조해 조각별 화면 위치를 추정한다(배율 0.97~1.03만 1:1로 인정)."""
    a = atlas_rgba.astype(np.float32)
    comp = (a[..., :3] * (a[..., 3:] / 255)).astype(np.uint8)
    k1, d1 = sift.detectAndCompute(cv2.cvtColor(comp, cv2.COLOR_RGB2GRAY), (atlas_rgba[..., 3] >= SOLID).astype(np.uint8))
    k2, d2 = cap_feat
    if d1 is None or d2 is None or len(k1) < 6:
        return []
    good = [m for m, n in cv2.BFMatcher().knnMatch(d1, d2, k=2) if m.distance < 0.75 * n.distance]
    pairs = [(k1[g.queryIdx].pt, k2[g.trainIdx].pt) for g in good]
    out = []
    for _ in range(24):
        if len(pairs) < 6:
            break
        A = np.float32([p[0] for p in pairs])
        B = np.float32([p[1] for p in pairs])
        M, inl = cv2.estimateAffinePartial2D(A, B, ransacReprojThreshold=2.0, maxIters=4000)
        if M is None or inl is None or inl.sum() < 6:
            break
        inl = inl.ravel().astype(bool)
        scale = float(np.hypot(M[0, 0], M[1, 0]))
        if 0.97 <= scale <= 1.03:
            dx, dy = int(round(M[0, 2])), int(round(M[1, 2]))
            pts = A[inl]
            for (x, y, w, h) in rects:  # 인라이어가 든 조각을 그 위치에 놓고 32px 타일마다 불투명 픽셀로 확인
                inside = ((pts[:, 0] >= x) & (pts[:, 0] < x + w) & (pts[:, 1] >= y) & (pts[:, 1] < y + h)).sum()
                if inside < 3:
                    continue
                for ty in range(y, y + h, TILE):
                    for tx in range(x, x + w, TILE):
                        tw, th = min(TILE, x + w - tx), min(TILE, y + h - ty)
                        tile = atlas_rgba[ty:ty + th, tx:tx + tw]
                        if (tile[..., 3] >= SOLID).sum() < tw * th * 0.25:
                            continue
                        err, n = solid_error(capture_rgb, tile, tx + dx, ty + dy)
                        if err is not None and err <= thresh:
                            out.append(dict(atlas_rect=[tx, ty, tw, th], screen=[tx + dx, ty + dy, tw, th],
                                            err=round(err, 1), checked_pixels=n, inliers=int(inside), ok=True,
                                            part=[x, y, w, h]))
        pairs = [p for p, f in zip(pairs, inl) if not f]
    return out


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--image-root', required=True, help='원본 data/image (읽기 전용)')
    ap.add_argument('--extra', nargs='*', default=[])
    ap.add_argument('--captures', nargs='+', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--dirs', nargs='*', default=DEFAULT_DIRS)
    ap.add_argument('--thresh', type=float, default=60.0)
    args = ap.parse_args(argv)
    out = writable(args.out)
    out.mkdir(parents=True, exist_ok=True)
    root = Path(args.image_root)
    files = [p for d in args.dirs for p in sorted((root / d).glob('*')) if p.suffix.lower() in ('.tga', '.png')]
    files += [Path(p) for p in args.extra]
    atlases = []
    for f in files:
        try:
            im = load_rgba(f)
        except Exception as exc:
            print('skip', f, exc)
            continue
        rel = str(f.relative_to(root)).replace('\\', '/') if f.is_relative_to(root) else f.name
        atlases.append((rel, np.asarray(im), components(im)))
    sift = cv2.SIFT_create(nfeatures=8000)
    font = ImageFont.truetype(r'C:/Windows/Fonts/malgun.ttf', 11)
    summary = {}
    for cap in map(Path, args.captures):
        img = Image.open(cap).convert('RGB')
        rgb = np.asarray(img)
        cap_feat = sift.detectAndCompute(cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY), None)
        found = []
        for rel, arr, rects in atlases:
            big = [r for r in rects if r[2] * r[3] > LARGE]
            if big:
                for hit in sift_hits(rgb, arr, big, args.thresh, sift, cap_feat):
                    if hit['ok']:
                        found.append(dict(file=rel, method='sift', **hit))
            for (x, y, w, h) in rects:
                if w * h > LARGE:
                    continue
                for sx, sy, err in template_hits(rgb, arr[y:y + h, x:x + w], args.thresh):
                    found.append(dict(file=rel, method='template', atlas_rect=[x, y, w, h], screen=[sx, sy, w, h],
                                      err=round(err, 1)))
        found.sort(key=lambda f: (-(f['screen'][2] * f['screen'][3]), f['err'] or 0))
        cover = np.zeros(rgb.shape[:2], bool)
        kept = []
        for f in found:
            x, y, w, h = f['screen']
            region = cover[max(0, y):y + h, max(0, x):x + w]
            if region.size and region.mean() > 0.85:
                continue
            cover[max(0, y):y + h, max(0, x):x + w] = True
            kept.append(f)
        (out / f'{cap.stem}.json').write_text(json.dumps(kept, ensure_ascii=False, indent=1), encoding='utf-8')
        ov = img.convert('RGBA')
        layer = Image.new('RGBA', ov.size)
        d = ImageDraw.Draw(layer)
        names = sorted({f['file'] for f in kept})
        colors = {n: tuple(int(c) for c in np.random.default_rng(abs(hash(n)) % 10007).integers(90, 256, 3)) for n in names}
        for f in kept:
            x, y, w, h = f['screen']
            d.rectangle([x, y, x + w - 1, y + h - 1], outline=colors[f['file']] + (255,), width=2 if f['method'] == 'sift' else 1)
        ov.alpha_composite(layer)
        sheet = Image.new('RGBA', (ov.width + 320, ov.height), (10, 14, 24, 255))
        sheet.paste(ov, (0, 0))
        ld = ImageDraw.Draw(sheet)
        for i, n in enumerate(names):
            ld.rectangle([ov.width + 6, 6 + i * 15, ov.width + 16, 16 + i * 15], fill=colors[n] + (255,))
            ld.text((ov.width + 22, 4 + i * 15), n, fill=(220, 230, 250), font=font)
        sheet.convert('RGB').save(out / f'{cap.stem}-map.png')
        use = {}
        for f in kept:
            use[f['file']] = use.get(f['file'], 0) + 1
        summary[cap.stem] = dict(matched=len(kept), covered_ratio=round(float(cover.mean()), 3),
                                 files=dict(sorted(use.items(), key=lambda kv: -kv[1])))
        print(cap.stem, len(kept), summary[cap.stem]['covered_ratio'], flush=True)
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding='utf-8')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
