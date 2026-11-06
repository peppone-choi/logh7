"""제국 훈장 초안 그림을 엠블럼 한 장에서 절차적으로 만든다.

작성자: Peppone Choi (peppone-choi).

원본 클라이언트에는 동맹 훈장 그림(data/image/Medal/m_f001~015, 80×80)만 있고 제국 훈장 그림은 없다.
훈장 이름은 constmsg.dat 8번 묶음에 제국 26개(0~25)·동맹 26개(26~51)가 있다(`evidence:client`).
이 도구는 흰 엠블럼(검은 바탕) 그림을 높이맵으로 바꾸고 금속 조명을 입혀, 동맹 훈장처럼 배경 없는
돋을새김 훈장을 만든다. 모양·금속·리본 색은 설계 추정이다(`evidence:guess`).

출력(E:/logh7/work 아래만): <out>/x1/eNN.png(80), <out>/x2/eNN.png(160), <out>/sheet.png, <out>/medals.json.
엠블럼 그림과 출력은 저장소에 넣지 않는다.
"""
import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage

sys.path.insert(0, str(Path(__file__).resolve().parent))
from upscale_assets import load_rgba, writable  # noqa: E402

S = 640  # 작업 해상도(최종 160 의 4배)
C = S / 2

METALS = {
    'gold': [(0, (52, 30, 8)), (.42, (146, 100, 32)), (.68, (214, 172, 78)), (.88, (250, 228, 148)), (1, (255, 250, 222))],
    'silver': [(0, (34, 36, 42)), (.42, (116, 121, 132)), (.68, (186, 191, 202)), (.88, (232, 236, 244)), (1, (255, 255, 255))],
    'bronze': [(0, (44, 22, 12)), (.42, (126, 72, 46)), (.68, (186, 118, 82)), (.88, (228, 172, 136)), (1, (250, 226, 202))],
    'iron': [(0, (8, 8, 10)), (.5, (30, 31, 35)), (.8, (72, 74, 82)), (1, (160, 163, 172))],
}
ENAMEL = {'black': (18, 18, 22), 'red': (122, 18, 26), 'white': (226, 224, 216), 'blue': (24, 40, 104)}
# 리본 줄무늬(왼쪽→오른쪽 너비 비율, 색). 제국 리본 색은 원작 근거가 없어 흑·백·적으로 둔다(evidence:guess).
RIBBONS = {
    'empire': [(.18, (20, 20, 24)), (.14, (230, 228, 220)), (.36, (150, 22, 30)), (.14, (230, 228, 220)), (.18, (20, 20, 24))],
    'empire-gold': [(.2, (150, 22, 30)), (.12, (212, 170, 70)), (.36, (20, 20, 24)), (.12, (212, 170, 70)), (.2, (150, 22, 30))],
    'empire-black': [(.15, (230, 228, 220)), (.7, (20, 20, 24)), (.15, (230, 228, 220))],
}


# ---- 모양(0~1 마스크, S×S) -------------------------------------------------

def _canvas():
    return Image.new('L', (S, S), 0)


def _mask(img):
    return np.asarray(img, dtype=np.float32) / 255


def polygon(points):
    im = _canvas()
    ImageDraw.Draw(im).polygon([(float(x), float(y)) for x, y in points], fill=255)
    return _mask(im)


def disc(r, cx=C, cy=C):
    im = _canvas()
    ImageDraw.Draw(im).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    return _mask(im)


def ellipse(rx, ry, cx=C, cy=C):
    im = _canvas()
    ImageDraw.Draw(im).ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=255)
    return _mask(im)


def ring(r_out, r_in, cx=C, cy=C):
    return np.clip(disc(r_out, cx, cy) - disc(r_in, cx, cy), 0, 1)


def rotate(points, angle, cx=C, cy=C):
    ca, sa = math.cos(angle), math.sin(angle)
    return [(cx + x * ca - y * sa, cy + x * sa + y * ca) for x, y in points]


def star(n, r_out, r_in, cx=C, cy=C, phase=-math.pi / 2):
    pts = []
    for k in range(n * 2):
        r = r_out if k % 2 == 0 else r_in
        a = phase + k * math.pi / n
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return polygon(pts)


def sunburst(n, r_long, r_short, width, cx=C, cy=C):
    """가는 빛살 n 개(긴 것과 짧은 것이 번갈아)."""
    m = np.zeros((S, S), np.float32)
    for k in range(n):
        r = r_long if k % 2 == 0 else r_short
        arm = [(-width, 0), (width, 0), (0, -r)]
        m = np.maximum(m, polygon(rotate(arm, k * 2 * math.pi / n, cx, cy)))
    return m


def cross(r, w_in, w_out, notch=0.0, cx=C, cy=C, phase=0.0):
    """십자. notch>0 이면 팔 끝을 V 로 판다(몰타 십자)."""
    m = np.zeros((S, S), np.float32)
    for k in range(4):
        arm = [(-w_in, 0), (w_in, 0), (w_out, -r)]
        if notch:
            arm.append((0, -r * (1 - notch)))
        arm.append((-w_out, -r))
        m = np.maximum(m, polygon(rotate(arm, phase + k * math.pi / 2, cx, cy)))
    return m


def leaf(cx, cy, length, width, angle):
    pts = [(length / 2 * math.cos(t), width / 2 * math.sin(t)) for t in np.linspace(0, 2 * math.pi, 24, endpoint=False)]
    return polygon(rotate(pts, angle, cx, cy))


def wreath(r, leaves=11, size=1.0, cx=C, cy=C, span=150):
    """아래 가운데에서 양쪽으로 올라가는 월계수 두 가지(줄기 + 마주난 잎)."""
    m = np.zeros((S, S), np.float32)
    im = _canvas()
    draw = ImageDraw.Draw(im)
    length, width = 0.2 * r * size, 0.075 * r * size
    for side in (-1, 1):
        stem = []
        for i, t in enumerate(np.linspace(0.04, 1, leaves)):
            th = math.radians(t * span)
            x, y = cx + side * r * math.sin(th), cy + r * math.cos(th)
            stem.append((x, y))
            tx, ty = side * math.cos(th), -math.sin(th)  # 위로 올라가는 접선
            nx, ny = side * math.sin(th), math.cos(th)  # 바깥쪽 법선
            for k in (1, -1):
                vx, vy = tx * 0.8 + k * nx * 0.6, ty * 0.8 + k * ny * 0.6
                ang = math.atan2(vy, vx)
                m = np.maximum(m, leaf(x + math.cos(ang) * length * 0.45, y + math.sin(ang) * length * 0.45,
                                       length, width, ang))
        draw.line(stem, fill=255, width=max(3, round(0.022 * r * size)), joint='curve')
    return np.maximum(m, _mask(im))


def oak(cx, cy, size, angle=0.0):
    """참나무 잎 한 장(마디 진 타원 세 쌍 + 끝 + 잎맥). angle=0 이면 위를 향한다."""
    m = np.zeros((S, S), np.float32)

    def at(dx, dy):
        return cx + dx * math.cos(angle) - dy * math.sin(angle), cy + dx * math.sin(angle) + dy * math.cos(angle)

    for t in (0.18, 0.45, 0.7):
        for side in (-1, 1):
            m = np.maximum(m, leaf(*at(side * size * 0.15, -size * t), size * 0.34, size * 0.22, angle + side * 0.8 - math.pi / 2))
    m = np.maximum(m, leaf(*at(0, -size * 0.9), size * 0.3, size * 0.22, angle - math.pi / 2))
    return np.maximum(m, leaf(*at(0, -size * 0.5), size, size * 0.12, angle - math.pi / 2))


def swords(length, cx=C, cy=C, spread=0.62):
    m = np.zeros((S, S), np.float32)
    for side in (-1, 1):
        blade = [(-5, -length / 2), (5, -length / 2), (7, length * 0.28), (0, length * 0.36), (-7, length * 0.28)]
        guard = [(-26, length * 0.28), (26, length * 0.28), (26, length * 0.31), (-26, length * 0.31)]
        grip = [(-6, length * 0.31), (6, length * 0.31), (6, length * 0.46), (-6, length * 0.46)]
        for part in (blade, guard, grip):
            flipped = [(x, -y) for x, y in part]
            m = np.maximum(m, polygon(rotate(flipped, side * spread, cx, cy)))
    return m


def emblem(source, box, cx=C, cy=C, thick=0):
    """흰 엠블럼(검은 바탕) → 마스크. box 는 결과 너비(px). thick>0 이면 선을 그만큼 굵힌다."""
    g = Image.open(source).convert('L')
    a = np.asarray(g)
    ys, xs = np.nonzero(a > 40)
    g = g.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    h = round(box * g.height / g.width)
    g = g.resize((round(box), h), Image.LANCZOS)
    im = _canvas()
    im.paste(g, (round(cx - box / 2), round(cy - h / 2)))
    m = _mask(im)
    if thick:
        m = ndimage.grey_dilation(m, footprint=disc_footprint(thick))
        m = np.clip(ndimage.gaussian_filter(m, thick * 0.35) * 1.4 - 0.2, 0, 1)
    return m


def disc_footprint(r):
    y, x = np.ogrid[-r:r + 1, -r:r + 1]
    return x * x + y * y <= r * r


def bold_emblem(out, src, box, metal, thick=3, cx=C, cy=C):
    """굵힌 엠블럼 + 흑 칠보 테두리 + 원래 선을 판 무늬. 최상위 훈장에서 쌍두독수리가 바탕에 묻히지 않게 한다."""
    out = over(out, shade(emblem(src, box, cx, cy, thick + 8), 'enamel:black', bevel=4, depth=4))
    fat = emblem(src, box, cx, cy, thick)
    layer = shade(fat, metal, bevel=6, depth=16)
    thin = ndimage.gaussian_filter(emblem(src, box, cx, cy), 0.8)
    carve = np.clip(fat - thin, 0, 1)  # 굵히며 메운 원래의 검은 선(깃털·비늘)
    layer[..., :3] *= (1 - 0.6 * carve)[..., None]
    return over(out, layer)


# ---- 조명 -----------------------------------------------------------------

def ramp(stops, v):
    xs = [s for s, _ in stops]
    out = np.empty(v.shape + (3,), np.float32)
    for ch in range(3):
        out[..., ch] = np.interp(v, xs, [c[ch] for _, c in stops])
    return out


_RNG = np.random.default_rng(7)
_GRAIN = ndimage.gaussian_filter(_RNG.standard_normal((S, S)).astype(np.float32), 2.2)


def height(mask, bevel, dome=0.0, grain=0.0):
    inside = mask > 0.5
    d = ndimage.distance_transform_edt(inside)
    h = np.clip(d / bevel, 0, 1)
    h = 1 - (1 - h) ** 2
    if dome:
        h = h + dome * np.clip(d / max(d.max(), 1), 0, 1)
    h = ndimage.gaussian_filter(h.astype(np.float32), 1.0)
    return h + grain * _GRAIN


def shade(mask, look, bevel=10.0, depth=14.0, dome=0.0, grain=0.04, relief=None):
    """look: METALS 키(금속) 또는 'enamel:<색>'. relief 를 주면 그 높이맵(0~1)을 쓴다."""
    h = (height(mask, bevel, dome, grain) if relief is None else relief) * depth
    gy, gx = np.gradient(h)
    n = np.dstack([-gx, -gy, np.ones_like(h)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    light = np.array([-0.55, -0.7, 0.9])
    light /= np.linalg.norm(light)
    half = light + np.array([0, 0, 1.0])
    half /= np.linalg.norm(half)
    diff = np.clip(n @ light, 0, 1)
    spec = np.clip(n @ half, 0, 1)
    if look.startswith('enamel:'):
        base = np.array(ENAMEL[look.split(':')[1]], np.float32)
        rgb = base * (0.7 + 0.45 * diff[..., None]) + 255 * (spec ** 60)[..., None] * 0.55
    else:
        v = np.clip(0.08 + 0.92 * diff ** 1.4, 0, 1)
        rgb = ramp(METALS[look], v) + 255 * (spec ** 30)[..., None] * 0.35
    return np.dstack([np.clip(rgb, 0, 255), np.clip(mask, 0, 1) * 255]).astype(np.float32)


def portrait(source, r, crop=None, cx=C, cy=C):
    """초상 사진 → 원형 돋을새김 높이맵(0~1)과 마스크. crop: (x0, y0, x1, y1) 정사각에 가깝게."""
    g = load_rgba(source).convert('L')  # 팔레트 TGA 도 읽는다
    if crop:
        g = g.crop(tuple(crop))
    side = min(g.size)
    g = g.crop(((g.width - side) // 2, (g.height - side) // 2, (g.width + side) // 2, (g.height + side) // 2))
    g = g.resize((2 * r, 2 * r), Image.LANCZOS)
    a = np.asarray(g, np.float32) / 255
    lo, hi = np.percentile(a, (2, 98))
    a = np.clip((a - lo) / max(hi - lo, 1e-3), 0, 1)
    # 밝기 → 높이: 큰 형태(넓은 흐림)와 윤곽(좁은 흐림)을 섞어 얕은 돋을새김처럼 만든다.
    h = 0.55 * ndimage.gaussian_filter(a, r * 0.06) + 0.45 * ndimage.gaussian_filter(a, 1.2)
    edges = ndimage.gaussian_gradient_magnitude(a, 1.5)
    h = h - 0.6 * np.clip(edges / max(edges.max(), 1e-3), 0, 1)  # 윤곽선은 파인 선으로
    h = (h - h.min()) / max(h.max() - h.min(), 1e-3)
    field = np.zeros((S, S), np.float32)
    x0, y0 = round(cx - r), round(cy - r)
    field[y0:y0 + 2 * r, x0:x0 + 2 * r] = h
    mask = disc(r, cx, cy)
    return field * mask, mask


def ribbon(kind, top, bottom, half_w):
    """세로 줄무늬 천 리본(아래는 V 로 접힘)."""
    rgba = np.zeros((S, S, 4), np.float32)
    x0 = C - half_w
    xs = np.arange(S, dtype=np.float32)
    col = np.zeros((S, 3), np.float32)
    pos = x0
    for frac, color in RIBBONS[kind]:
        w = frac * 2 * half_w
        col[(xs >= pos) & (xs < pos + w)] = color
        pos += w
    m = polygon([(C - half_w, top), (C + half_w, top), (C + half_w, bottom), (C, bottom - half_w * 0.35), (C - half_w, bottom)])
    shading = 0.8 + 0.25 * np.cos((xs - C) / half_w * math.pi / 2)[None, :]  # 가운데가 밝은 천 주름
    weave = 1 + 0.07 * np.sin(np.arange(S)[:, None] * 0.9) + 0.05 * _GRAIN * 8
    rgb = col[None, :, :] * (shading * weave)[..., None]
    rgba[..., :3] = np.clip(rgb, 0, 255)
    rgba[..., 3] = m * 255
    return rgba


def over(dst, src, shadow=True):
    """src(RGBA float) 를 dst 위에 덮는다. shadow 면 오른쪽 아래로 그림자를 먼저 깐다."""
    a = src[..., 3:] / 255
    if shadow:
        sh = ndimage.shift(ndimage.gaussian_filter(a[..., 0], 4), (6, 4), order=1)[..., None] * 0.55
        dst[..., :3] *= 1 - sh
        dst[..., 3:] = np.maximum(dst[..., 3:], sh * 255)
    dst[..., :3] = dst[..., :3] * (1 - a) + src[..., :3] * a
    dst[..., 3:] = dst[..., 3:] * (1 - a) + 255 * a
    return dst


def erode(mask, px):
    return ndimage.distance_transform_edt(mask > 0.5).astype(np.float32).clip(0, px) / px * (mask > 0.5) >= 1


# ---- 훈장 설계 ------------------------------------------------------------

def _medal_on_ribbon(src, metal, wreathed, rib='empire'):
    cy = C + 70
    out = np.zeros((S, S, 4), np.float32)
    out = over(out, ribbon(rib, 0, 220, 118), shadow=False)
    out = over(out, shade(ring(30, 16, C, 200), metal, bevel=6, depth=10))
    r = 190 if not wreathed else 160
    if wreathed:
        out = over(out, shade(wreath(196, 10, 1.1, C, cy, span=165), metal, bevel=8, depth=10))
    out = over(out, shade(disc(r, C, cy), metal, bevel=14, depth=10, dome=0.4))
    out = over(out, shade(ring(r - 14, r - 22, C, cy), metal, bevel=3, depth=10), shadow=False)
    out = over(out, shade(emblem(src, r * 1.55, C, cy + 4), metal, bevel=4, depth=9))
    return out


def design(no, src):
    out = np.zeros((S, S, 4), np.float32)
    if no == 1:  # 大双頭鷲勲章: 은 8각 빛살 별(안쪽 흑 칠보) 위에 큰 금 엠블럼
        out = over(out, shade(sunburst(48, 314, 270, 8), 'silver', bevel=4, depth=10))
        body = star(8, 300, 168, phase=-math.pi / 2 + math.pi / 8)
        out = over(out, shade(body, 'silver', bevel=40, depth=20, dome=0.6))
        out = over(out, shade(erode(body, 30).astype(np.float32), 'enamel:black', bevel=6, depth=5), shadow=False)
        out = bold_emblem(out, src, 500, 'gold')
    elif no == 2:  # 双頭鷲武勲章: 교차검 + 청 칠보 몰타 십자 위에 큰 금 엠블럼(푸르 르 메리트형)
        out = over(out, shade(swords(640, spread=math.pi / 4), 'gold', bevel=5, depth=12))
        body = cross(300, 36, 156, notch=0.28)
        out = over(out, shade(body, 'gold', bevel=8, depth=14))
        out = over(out, shade(erode(body, 14).astype(np.float32), 'enamel:blue', bevel=6, depth=5), shadow=False)
        out = bold_emblem(out, src, 460, 'gold')
    elif no == 3:  # 銀河帝国大十字章: 금 몰타 십자 + 적 칠보 + 흑 원판
        body = cross(290, 44, 140, notch=0.22)
        out = over(out, shade(star(8, 312, 200, phase=-math.pi / 2 + math.pi / 8), 'gold', bevel=30, depth=18, dome=0.4))
        out = over(out, shade(sunburst(32, 300, 262, 9), 'gold', bevel=4, depth=10), shadow=False)
        out = over(out, shade(body, 'gold', bevel=10, depth=14))
        out = over(out, shade(erode(body, 20).astype(np.float32), 'enamel:white', bevel=6, depth=5), shadow=False)
        out = over(out, shade(disc(124), 'gold', bevel=10, depth=12))
        out = over(out, shade(disc(104), 'enamel:black', bevel=5, depth=4), shadow=False)
        out = over(out, shade(emblem(src, 184), 'gold', bevel=4, depth=10))
    elif no in (4, 5):  # 功一級/二級騎士十字章: 철 십자 + 금/은 테두리
        rim = 'silver'
        out = over(out, ribbon('empire', 0, 150, 92), shadow=False)
        body = cross(250, 52, 128, cy=C + 50)
        out = over(out, shade(ring(26, 13, C, 140), rim, bevel=5, depth=10))
        out = over(out, shade(body, rim, bevel=8, depth=14))
        out = over(out, shade(erode(body, 16).astype(np.float32), 'iron', bevel=10, depth=10), shadow=False)
        out = over(out, shade(emblem(src, 200, C, C + 54), rim, bevel=4, depth=9))
        if no == 4:  # 1급: 리본과 십자 사이에 참나무 잎 + 교차검 걸쇠(금)
            out = over(out, shade(swords(250, C, 150, spread=1.05), 'gold', bevel=4, depth=10))
            clasp = np.maximum.reduce([oak(C + dx, 176, 120, ang) for dx, ang in ((-40, -0.75), (0, 0.0), (40, 0.75))])
            out = over(out, shade(clasp, 'gold', bevel=4, depth=10))
    elif no in (6, 7):  # 功一級/二級十字章: 돋을새김 십자
        metal = 'gold' if no == 6 else 'silver'
        cy = C + 60 if no == 7 else C
        if no == 7:  # 2급은 리본에 단다. 1급은 리본 없이 가슴에 다는 십자
            out = over(out, ribbon('empire-black', 0, 150, 92), shadow=False)
            out = over(out, shade(ring(26, 13, C, 140), metal, bevel=5, depth=10))
        body = cross(240 if no == 7 else 290, 70, 120 if no == 7 else 146, cy=cy)
        out = over(out, shade(body, metal, bevel=22, depth=16, dome=0.3))
        out = over(out, shade(emblem(src, 190 if no == 7 else 230, C, cy + 4), metal, bevel=4, depth=9))
    elif 8 <= no <= 12:  # 功一~五級戦功章: 원형 메달(1·2급은 월계수)
        metal, wreathed = {8: ('gold', True), 9: ('silver', True), 10: ('gold', False), 11: ('silver', False),
                           12: ('bronze', False)}[no]
        out = _medal_on_ribbon(src, metal, wreathed, 'empire-gold' if no <= 9 else 'empire')
    elif no == 13:  # 遠征部隊従軍記章: 팔 방패(원정 방패형) + 위 엠블럼 + 별
        pts = [(C - 190, 210), (C + 190, 210), (C + 190, 420)]
        pts += [(C + 190 * math.cos(t), 420 + 190 * math.sin(t)) for t in np.linspace(0, math.pi, 24)]
        shield = polygon(pts)
        out = over(out, shade(shield, 'silver', bevel=14, depth=14, dome=0.2))
        out = over(out, shade(star(4, 96, 22, C, 440), 'gold', bevel=10, depth=12))
        out = over(out, shade(emblem(src, 380, C, 150), 'gold', bevel=4, depth=10))
    elif no == 14:  # 戦闘功績記章: 가로 걸쇠(근접전 걸쇠형) — 막대 + 참나무 잎 + 가운데 원판
        bar = polygon([(24, C - 50), (S - 24, C - 50), (S - 6, C), (S - 24, C + 50), (24, C + 50), (6, C)])
        out = over(out, shade(bar, 'gold', bevel=8, depth=10))
        for side in (-1, 1):
            out = over(out, shade(oak(C + side * 150, C + 60, 150, side * 1.3), 'gold', bevel=4, depth=10))
        out = over(out, shade(disc(150), 'gold', bevel=12, depth=12, dome=0.3))
        out = over(out, shade(disc(130), 'enamel:black', bevel=5, depth=4), shadow=False)
        out = over(out, shade(emblem(src, 230), 'gold', bevel=4, depth=10))
    elif no == 15:  # 戦傷章: 철 타원 + 교차검 + 은 엠블럼
        out = over(out, shade(ellipse(196, 232), 'iron', bevel=16, depth=12, dome=0.3))
        out = over(out, shade(swords(420, C, C + 40, spread=0.8), 'silver', bevel=4, depth=10))
        out = over(out, shade(emblem(src, 290, C, C - 20), 'silver', bevel=4, depth=10))
    elif no == 16:  # 特別戦功メダル(1~10 공용): 은 육각별 + 금 원판
        out = over(out, shade(star(6, 300, 170), 'silver', bevel=30, depth=18, dome=0.4))
        out = over(out, shade(disc(160), 'gold', bevel=14, depth=12, dome=0.3))
        out = over(out, shade(emblem(src, 250), 'gold', bevel=4, depth=10))
    elif no == 17:  # 参謀記章: 금 테 방패 + 흑 칠보 + 은 엠블럼
        shield = polygon([(C - 220, 90), (C + 220, 90), (C + 220, 330), (C, 560), (C - 220, 330)])
        out = over(out, shade(shield, 'gold', bevel=12, depth=14))
        out = over(out, shade(erode(shield, 22).astype(np.float32), 'enamel:black', bevel=5, depth=4), shadow=False)
        out = over(out, shade(emblem(src, 360, C, C - 20), 'silver', bevel=4, depth=10))
    elif no in (20, 21):  # 초상 안: 월계수 + 원판 + 초상 돋을새김
        metal = 'gold' if no == 20 else 'silver'
        pic, crop = PORTRAIT
        out = over(out, shade(wreath(258, 12, 1.05, C, C + 4, span=150), metal, bevel=8, depth=10))
        out = over(out, shade(disc(248), metal, bevel=16, depth=12, dome=0.2))
        out = over(out, shade(ring(232, 218), metal, bevel=4, depth=10), shadow=False)
        field, mask = portrait(pic, 210, crop)
        base = height(mask, 12, 0.0, 0.03)
        out = over(out, shade(mask, metal, depth=16, relief=0.25 * base + 0.75 * field), shadow=False)
        out = over(out, shade(emblem(src, 120, C, C + 186), metal, bevel=3, depth=8))
    elif no in (18, 19):  # 금장·은장 한 쌍: 8각 빛살 별 + 월계수 고리 + 흑 칠보 원판(독일십자형)
        metal = 'gold' if no == 18 else 'silver'
        out = over(out, shade(star(8, 306, 170, phase=-math.pi / 2 + math.pi / 8), 'silver', bevel=40, depth=20, dome=0.6))
        out = over(out, shade(sunburst(64, 290, 250, 6), 'silver', bevel=3, depth=8), shadow=False)
        out = over(out, shade(disc(176), metal, bevel=10, depth=12))
        out = over(out, shade(wreath(150, 9, 0.9, C, C, span=172), metal, bevel=5, depth=10), shadow=False)
        out = over(out, shade(disc(112), 'enamel:black', bevel=6, depth=6))
        out = over(out, shade(emblem(src, 190), metal, bevel=3, depth=9))
    return Image.fromarray(np.clip(out, 0, 255).round().astype(np.uint8), 'RGBA')


# constmsg.dat 8번 묶음 제국 쪽 번호(0~25) → 초안 번호
EMPIRE = [
    (1, [0], '大双頭鷲勲章', '대쌍두독수리훈장'),
    (2, [1], '双頭鷲武勲章', '쌍두독수리무공훈장'),
    (3, [2], '銀河帝国大十字章', '은하제국대십자장'),
    (4, [3], '功一級騎士十字章', '공1급기사십자장'),
    (5, [4], '功二級騎士十字章', '공2급기사십자장'),
    (6, [5], '功一級十字章', '공1급십자장'),
    (7, [6], '功二級十字章', '공2급십자장'),
    (8, [7], '功一級戦功章', '공1급전공장'),
    (9, [8], '功二級戦功章', '공2급전공장'),
    (10, [9], '功三級戦功章', '공3급전공장'),
    (11, [10], '功四級戦功章', '공4급전공장'),
    (12, [11], '功五級戦功章', '공5급전공장'),
    (13, [12], '遠征部隊従軍記章', '원정부대종군기장'),
    (14, [13], '戦闘功績記章', '전투공적기장'),
    (15, [14], '戦傷章', '전상장'),
    (16, list(range(15, 25)), '特別戦功メダル１~１０', '특별전공메달 1~10'),
    (17, [25], '参謀記章', '참모기장'),
]
# 초안 번호 → 그림 안 크기 비율. constmsg 순서를 서열로 보고 아래로 갈수록 작게 한다(evidence:guess).
SCALE = {1: 1.0, 2: .96, 3: .93, 4: .88, 5: .85, 6: .82, 7: .80, 8: .78, 9: .76, 10: .73, 11: .71, 12: .69,
         13: .66, 14: .66, 15: .63, 16: .70, 17: .60, 18: .80, 19: .78}


def fit_rank(im, no):
    f = SCALE.get(no, 1.0)
    if f >= 1:
        return im
    small = im.resize((round(S * f), round(S * f)), Image.LANCZOS)
    out = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    out.alpha_composite(small, ((S - small.width) // 2, (S - small.height) // 2))
    return out


EXTRA = [(18, '예비 금장(이름 미배정, 독일십자형)'), (19, '예비 은장(이름 미배정, 독일십자형)')]
PORTRAITS = [(20, '초상 안 금장'), (21, '초상 안 은장')]
PORTRAIT = (None, None)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--emblem', required=True, help='흰 엠블럼·검은 바탕 그림')
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', type=int, nargs='*')
    ap.add_argument('--portrait', help='초상 안(e20·e21)에 쓸 인물 그림')
    ap.add_argument('--crop', type=int, nargs=4, metavar=('X0', 'Y0', 'X1', 'Y1'), help='초상 그림에서 얼굴·흉상 영역')
    args = ap.parse_args(argv)
    global PORTRAIT
    PORTRAIT = (args.portrait, args.crop)
    out = writable(args.out)
    for d in ('x1', 'x2'):
        (out / d).mkdir(parents=True, exist_ok=True)
    numbers = [n for n, *_ in EMPIRE] + [n for n, _ in EXTRA] + ([n for n, _ in PORTRAITS] if args.portrait else [])
    if args.only:
        numbers = [n for n in numbers if n in args.only]
    tiles = []
    for n in numbers:
        im = fit_rank(design(n, args.emblem), n)
        im.resize((160, 160), Image.LANCZOS).save(out / 'x2' / f'e{n:02d}.png')
        im.resize((80, 80), Image.LANCZOS).save(out / 'x1' / f'e{n:02d}.png')
        tiles.append(im.resize((240, 240), Image.LANCZOS))
        print('e%02d' % n, flush=True)
    sheet = Image.new('RGBA', (240 * 5, 240 * ((len(tiles) + 4) // 5)), (34, 34, 42, 255))
    for i, t in enumerate(tiles):
        sheet.alpha_composite(t, ((i % 5) * 240, (i // 5) * 240))
    sheet.save(out / 'sheet.png')
    meta = dict(source='constmsg.dat group 8 (empire 0..25)',
                medals=[dict(file=f'e{n:02d}.png', constmsg=idx, ja=ja, ko=ko) for n, idx, ja, ko in EMPIRE]
                + [dict(file=f'e{n:02d}.png', note=note) for n, note in EXTRA + (PORTRAITS if args.portrait else [])])
    (out / 'medals.json').write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding='utf-8')


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
