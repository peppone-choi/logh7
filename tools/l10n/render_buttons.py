"""원본 버튼 배경을 보존한 한글 글꼴 렌더 시안·복구 패키지. 작성: Peppone Choi.

원본 제작 글꼴은 미확인이다. Windows 맑은 고딕으로 만든 검토 후보이며,
실제 게임의 상태/UV/가독성 확인은 실행 담당이 수행한다.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from PIL import Image, ImageDraw, ImageFont

import image_patch

STATES = ('normal', 'mover', 'down', 'kj', 'kj_d')
LABELS = {'kojin': '기함', 'senjutsu': '사령관', 'shokumu': '함정', 'yousai': '요새'}
ROOT = Path(__file__).resolve().parents[2]


def unpack(data):
    info = image_patch.tga_info(data)
    width, height = info['width'], info['height']
    pos = 18+len(bytes.fromhex(info['id_hex']))
    palette_bytes = info['palette_size']*(info['palette_depth']//8)
    palette = data[pos:pos+palette_bytes]
    pos += palette_bytes
    depth = info['pixel_depth']//8
    source = data[pos:pos+width*height*depth]
    colors = []
    for i in range(width*height):
        if info['image_type'] == 1:
            p = (source[i]-info['palette_first'])*(info['palette_depth']//8)
            value = palette[p:p+info['palette_depth']//8]
        else:
            value = source[i*depth:(i+1)*depth]
        colors.append((value[2],value[1],value[0],value[3] if len(value)==4 else 255))
    image = Image.new('RGBA',(width,height)); image.putdata(colors)
    if not info['descriptor'] & 0x20:
        image = image.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
    if info['descriptor'] & 0x10:
        image = image.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    return info, image, pos


def repack(data, image, edit_box):
    info, original, pos = unpack(data)
    if image.size != original.size:
        raise ValueError('canvas changed')
    result = bytearray(data)
    palette_start = 18+len(bytes.fromhex(info['id_hex']))
    palette = []
    for i in range(info['palette_size']):
        p = palette_start+i*(info['palette_depth']//8)
        value = data[p:p+info['palette_depth']//8]
        palette.append((value[2],value[1],value[0],value[3] if len(value)==4 else 255))
    cache = {}
    for y in range(image.height):
        for x in range(image.width):
            if not (edit_box[0] <= x < edit_box[2] and edit_box[1] <= y < edit_box[3]):
                if image.getpixel((x,y)) != original.getpixel((x,y)):
                    raise ValueError('background outside edit box changed')
                continue
            pixel = image.getpixel((x,y))
            if pixel == original.getpixel((x,y)):
                continue
            sx = image.width-1-x if info['descriptor'] & 0x10 else x
            sy = y if info['descriptor'] & 0x20 else image.height-1-y
            index = sy*image.width+sx
            if info['image_type'] == 1:
                if pixel not in cache:
                    cache[pixel] = min(range(len(palette)),key=lambda i:sum((a-b)**2 for a,b in zip(pixel,palette[i])))
                result[pos+index] = info['palette_first']+cache[pixel]
            else:
                p = pos+index*(info['pixel_depth']//8)
                result[p:p+info['pixel_depth']//8] = bytes((pixel[2],pixel[1],pixel[0],pixel[3]))[:info['pixel_depth']//8]
    image_patch.compatible(data,bytes(result))
    return bytes(result)


def background(images, box):
    # The four labels share a state background. Take the darkest sample at
    # each pixel, then interpolate only the residual pale text pixels. Edges,
    # bevel, canvas and unedited source bytes are not reconstructed.
    result = images[0].copy()
    points = [(x,y) for y in range(box[1],box[3]) for x in range(box[0],box[2])]
    for point in points:
        result.putpixel(point,min((im.getpixel(point) for im in images),key=lambda p:p[0]+p[1]))
    mask = {p for p in points if (lambda c:c[0] > 48 and c[0] > c[1]*0.53)(result.getpixel(p))}
    for _ in range(100):
        previous = result.copy()
        for x,y in mask:
            neighbors=[previous.getpixel(p) for p in ((x-1,y),(x+1,y),(x,y-1),(x,y+1))]
            result.putpixel((x,y),tuple(round(sum(c[i] for c in neighbors)/4) for i in range(4)))
    return result


def render(source, output, font_path):
    output = image_patch.writable(output)
    if not output.is_relative_to((ROOT/'work').resolve()) or output.exists():
        raise ValueError('output must be a new directory in this worktree/work')
    font_path = Path(font_path).resolve()
    font = ImageFont.truetype(str(font_path),14)
    box = (9,3,59,20)
    output.mkdir(parents=True)
    selection, report = [], []
    sheet = Image.new('RGBA',(67*len(STATES),22*len(LABELS)),(0,0,0,255))
    for column,state in enumerate(STATES):
        originals = []
        for name in LABELS:
            path = Path(source)/f'icon_{state}/{name}.tga'
            data = path.read_bytes(); info, image, _ = unpack(data)
            if image.size != (67,22):
                raise ValueError('unexpected button canvas')
            originals.append((path,data,info,image))
        blank = background([row[3] for row in originals],box)
        for row,((name,text),(path,data,info,image)) in enumerate(zip(LABELS.items(),originals)):
            result = image.copy(); result.paste(blank.crop(box),box)
            # Match the source label's 12-pixel visual height and common baseline
            # within its 67x22 canvas. Font identity remains explicitly unknown.
            layer = Image.new('RGBA',result.size)
            draw = ImageDraw.Draw(layer)
            bounds = draw.textbbox((0,0),text,font=font)
            x = round((67-(bounds[2]-bounds[0]))/2-bounds[0])
            y = 5-bounds[1]
            pale = (182,211,247,248) if state in ('normal','kj') else (215,234,255,255)
            draw.text((x,y),text,font=font,fill=pale)
            result = Image.alpha_composite(result,layer)
            replacement = repack(data,result,box)
            relative=f'icon_{state}/{name}.tga'
            for kind,content in [('backup',data),('replacement',replacement)]:
                dest=output/kind/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(content)
            original_hash = image_patch.sha(data)
            if image_patch.sha(path.read_bytes()) != original_hash:
                raise ValueError('original changed')
            selection.append(dict(asset=relative,original_sha256=original_hash,replacement_sha256=image_patch.sha(replacement)))
            actual = unpack(replacement)[1]
            actual.save(output/f'{state}-{name}.png')
            sheet.paste(actual,(column*67,row*22))
            report.append(dict(asset=relative,label=text,edit_box=box,baseline=y+bounds[3],
                               font=str(font_path),font_pixels=14,original_font_confirmed=False,
                               metadata=info,display_validated=False))
    (output/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'render.json').write_text(json.dumps(dict(author='Peppone Choi',font_sha256=image_patch.sha(font_path.read_bytes()),
        assets=report,display_validated=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    sheet.resize((sheet.width*3,sheet.height*3),Image.Resampling.NEAREST).save(output/'preview.png')
    image_patch.prepare(Path(source),output/'replacement',output/'selection.json',output/'package')
    image_patch.verify(output/'package')
    return output


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--font',type=Path,default=Path('C:/Windows/Fonts/malgun.ttf'))
    args=parser.parse_args()
    print(render(args.source,args.output,args.font))
