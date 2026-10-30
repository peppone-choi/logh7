"""전략/HUD 시안 자산의 일본어를 한국어로 조판한다. 작성: Peppone Choi.

실제 사용 여부·원본 제작 폰트는 미확인이다. 원본/새 캔버스·알파와
편집 상자 밖 픽셀을 보존하고, TGA 원바이트/PNG 메타데이터를 유지한다.
글자 뒤 배경은 지정한 같은 행의 빈 지점으로 근사한다.
"""
import argparse
import io
import json
from pathlib import Path
import struct
import zlib

from PIL import Image, ImageDraw, ImageFont

import image_patch
from render_buttons import ROOT, repack, unpack


SOURCES = {
    'effect/strategy.tga': ('b8e25b3ad2056d786273fff6e749817e5bb9df7309d57f81d9005de652ee9b9e', (1024, 768)),
    'strategy/underpanel.png': ('e8cb9922b2db264abf39e847dfe4317b15ccc3d8ef6db8db0d8a1c78d4ac2bfb', (1600, 200)),
}


def labels(relative):
    """좌표는 원본 캔버스 기준이며 문구/버튼의 상태·얼굴·수치는 보존한다."""
    rows = []
    def add(box, text, sample, size=12, color=(0, 0, 0, 255), center=False):
        rows.append(dict(box=box, text=text, sample_x=sample, size=size, color=color, center=center))
    if relative == 'effect/strategy.tga':
        add((752, 8, 844, 23), '보유 카드', 910, color=(255, 255, 255, 255))
        for y, title in ((41, '제국군 사관학교: 사관학교 교감'),
                         (120, '함대[제1함대]: 함대 부사령관'), (197, '작전 명령')):
            add((770, y, 971, y+15), title, 972)
        for y in (41, 120, 197, 272):
            add((977, y, 1013, y+15), '직무', 1014, color=(204, 179, 75, 255))
        for y in (78, 157, 234):
            add((851, y, 971, y+15), '소유자: 뮐러', 980)
        add((770, 295, 819, 310), '소유자:', 816)
        add((851, 316, 880, 331), '설명', 882)
        commands = ('원거리 이동', '근거리 이동', '퇴역', '회견', '판매', '심의',
                    '참가', '수강', '병과 훈련', '지원', '망명', '자금 투입', '설득', '반란')
        for i, command in enumerate(commands):
            x, y = 770+(i % 3)*80, 442+(i//3)*36
            add((x, y, x+65, y+16), command, x+66, size=11, color=(0, 65, 60, 255), center=True)
        add((752, 627, 844, 643), '제안/명령', 910, color=(255, 255, 255, 255))
        white = (255, 255, 255, 255)
        cyan = (0, 220, 255, 255)
        add((83, 659, 274, 674), '뮐러대령  제1함대 부사령관', 275, color=white)
        add((83, 674, 109, 688), '체력', 108, color=(167, 240, 255, 255))
        add((270, 694, 308, 714), '동행', 306, size=16, color=white)
        add((406, 661, 594, 681), '우주력 796년10월10일10:10', 600, size=14, color=white)
        add((83, 718, 108, 732), '공적', 108, color=cyan)
        add((159, 718, 184, 732), '평가', 184, color=cyan)
        for y, line in ((732, ('통솔', '지휘', '운영', '영향력')), (746, ('공격', '방어', '기동', '정보'))):
            for x, text in zip((83, 134, 184, 234), line):
                add((x, y, x+25 if text != '영향력' else x+37, y+13), text, x+24, color=cyan)
        add((822, 658, 978, 674), '페잔 자치령 정부', 980, color=white)
        for y, text in ((679, '회의실'), (702, '재무국장 집무실'), (725, '무역국장 집무실'), (748, '항로국장 집무실')):
            add((893, y, 991, y+16), text, 990, color=white)
    elif relative == 'strategy/underpanel.png':
        color = (205, 205, 239, 255)
        add((132, 45, 212, 59), '뮐러대령', 214, size=11, color=color)
        add((246, 45, 359, 59), '제1함대 부사령관', 360, size=11, color=color)
        add((132, 63, 157, 77), '체력', 158, color=color)
        for y, line in ((99, ('통솔', '정치', '운영', '정보')), (115, ('지휘', '기동', '공격', '방어'))):
            for x, text in zip((132, 180, 228, 276), line):
                add((x, y, x+24, y+13), text, x+25, size=11, color=color)
        add((132, 130, 168, 143), '영향력', 168, size=11, color=color)
        add((228, 130, 252, 143), '공적', 252, size=11, color=color)
        add((295, 151, 337, 164), '동행 중', 339, size=11, color=color)
        add((38, 164, 233, 178), '우주력 796년 10월 10일 12시 15분', 234, size=11, color=color)
        add((1251, 89, 1353, 102), '발트뮐러 성계', 1354, size=11, color=color)
        add((1259, 115, 1343, 128), '행성 워프', 1344, size=11, color=color)
        add((1439, 27, 1509, 40), '방위사령부', 1510, size=11, color=(20, 20, 60, 255))
        for y, text in ((46, '시설 입구'), (68, '회의실'), (90, '집무실'), (112, '그림멜스하우젠 집무실')):
            add((1404 if y != 46 else 1387, y, 1558, y+13), text, 1560, size=11, color=color)
    else:
        raise ValueError('unknown mockup asset')
    return rows


def png_repack(data, image):
    """원래 ancillary chunk를 바이트 그대로 두고 IDAT 픽셀만 재압축한다."""
    width, height, _, chunks = image_patch.png_pixels(data)
    if image.mode != 'RGBA' or image.size != (width, height):
        raise ValueError('PNG canvas or alpha storage changed')
    raw = image.tobytes()
    scanlines = b''.join(b'\0'+raw[y*width*4:(y+1)*width*4] for y in range(height))
    result = bytearray(image_patch.PNG_SIGNATURE)
    emitted = False
    for kind, content in chunks:
        if kind == b'IDAT':
            if emitted:
                continue
            emitted = True
            content = zlib.compress(scanlines, 9)
        result += struct.pack('>I', len(content))+kind+content+struct.pack('>I', zlib.crc32(kind+content) & 0xffffffff)
    image_patch.compatible(data, bytes(result))
    return bytes(result)


def translate(data, relative, font_path):
    digest, size = SOURCES[relative]
    if image_patch.sha(data) != digest:
        raise ValueError('mockup source hash mismatch')
    original = unpack(data)[1] if relative.endswith('.tga') else Image.open(io.BytesIO(data)).convert('RGBA')
    if original.size != size:
        raise ValueError('mockup canvas changed')
    image = original.copy()
    report = []
    for spec in labels(relative):
        x0, y0, x1, y1 = spec['box']
        # Sample the original, never a previous translated label.
        for y in range(y0, y1):
            background = ((0, 0, 0, 255) if relative.endswith('.png') and y0 not in (27, 164)
                          else original.getpixel((spec['sample_x'], y)))
            image.paste(background, (x0, y, x1, y+1))
        draw = ImageDraw.Draw(image)
        size = spec['size']
        while True:
            font = ImageFont.truetype(str(font_path), size)
            bounds = draw.textbbox((0, 0), spec['text'], font=font)
            if bounds[2]-bounds[0] <= x1-x0 and bounds[3]-bounds[1] <= y1-y0:
                break
            size -= 1
            if size < 8:
                raise ValueError('Korean mockup label exceeds original cell')
        origin = (x0-bounds[0]+((x1-x0-(bounds[2]-bounds[0]))//2 if spec['center'] else 0),
                  y0+(y1-y0-(bounds[3]-bounds[1]))//2-bounds[1])
        draw.text(origin, spec['text'], font=font, fill=spec['color'])
        report.append(dict(**spec, font_pixels=size, ink_box=draw.textbbox(origin, spec['text'], font=font)))
    if original.getchannel('A').tobytes() != image.getchannel('A').tobytes():
        raise ValueError('mockup alpha mask changed')
    replacement = repack(data, image, (0, 0, *image.size)) if relative.endswith('.tga') else png_repack(data, image)
    return replacement, image, report


def render(source, output, font_path):
    source, font_path = Path(source).resolve(), Path(font_path).resolve()
    output = image_patch.writable(output)
    if output.exists() or not output.is_relative_to((ROOT/'work').resolve()):
        raise ValueError('output must be new and inside this worktree/work')
    output.mkdir(parents=True)
    selection, report = [], []
    for relative in SOURCES:
        path = source/relative
        data = path.read_bytes()
        replacement, preview, cells = translate(data, relative, font_path)
        for folder, content in (('backup', data), ('replacement', replacement)):
            target = output/folder/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        preview.save(output/(Path(relative).stem+'-korean.png'))
        selection.append(dict(asset=relative, original_sha256=image_patch.sha(data), replacement_sha256=image_patch.sha(replacement)))
        report.append(dict(asset=relative, labels=cells, original_font_confirmed=False,
                           actual_reference_confirmed=False, display_validated=False))
        if data != path.read_bytes():
            raise ValueError('original changed')
    (output/'selection.json').write_text(json.dumps(selection, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    (output/'render.json').write_text(json.dumps(dict(author='Peppone Choi', font=str(font_path),
        font_sha256=image_patch.sha(font_path.read_bytes()), assets=report), ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    image_patch.prepare(source, output/'replacement', output/'selection.json', output/'package')
    return output


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--font', type=Path, default=Path('C:/Windows/Fonts/malgun.ttf'))
    args = p.parse_args()
    print(render(args.source, args.output, args.font))
