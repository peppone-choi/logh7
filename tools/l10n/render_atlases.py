"""진영·결과 아틀라스의 UV 칸을 유지한 한국어 후보 생성. 작성: 최병호.

원본 제작 글꼴은 미확인이다. 실행 담당의 실제 화면 확인 전에는 배포 검수
완료로 취급하지 않는다. 원본 팔레트·알파 형식·캔버스와 수정 영역 밖 바이트를 보존한다.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import image_patch
from render_buttons import ROOT, repack, unpack

FACTIONS = ('은하제국 정규군', '은하제국 반란군', '자유행성동맹 정규군', '자유행성동맹 반란군', '우주해적')


def label(image, box, text, font_path, size, color, glow=None):
    image.paste((0, 0, 0, 0), box)
    draw = ImageDraw.Draw(image)
    padding = 4 if glow else 1
    while True:
        font = ImageFont.truetype(str(font_path), size)
        bounds = draw.textbbox((0, 0), text, font=font)
        if bounds[2]-bounds[0] <= box[2]-box[0]-padding*2 and bounds[3]-bounds[1] <= box[3]-box[1]-padding*2:
            break
        size -= 1
        if size < 8:
            raise ValueError('label does not fit its original UV cell')
    x, y = box[0]+padding-bounds[0], box[1]+padding-bounds[1]
    draw.text((x, y), text, font=font, fill=color, stroke_width=3 if glow else 0, stroke_fill=glow)
    return dict(label=text, box=box, font_pixels=size, baseline=y+bounds[3])


def render(source, output, font_path):
    source, font_path = Path(source).resolve(), Path(font_path).resolve()
    output = image_patch.writable(output)
    if not output.is_relative_to((ROOT/'work').resolve()) or output.exists():
        raise ValueError('output must be new and inside this worktree/work')
    output.mkdir(parents=True)
    specs = {}
    factions = [((0, y*34, 225, min(y*34+34, 170)), text, 26, (255,255,255,255), (20,55,250,240))
                for y,text in enumerate(FACTIONS)]
    results = [((0, y*51, 192, y*51+51), text, 38, (255,255,255,255), (20,55,250,240))
               for y,text in enumerate(('한정적 승리','국지적 승리','결정적 승리'))]
    results += [((0,153,90,204),'패배',38,(255,255,255,255),(20,55,250,240)),
                ((91,153,184,204),'제압',38,(255,255,255,255),(20,55,250,240)),
                ((0,204,94,255),'사망',38,(255,255,255,255),(190,20,5,240))]
    for name in ('gamemenu/jinei.tga','window/ending_parts_2.tga'):
        specs[name] = (factions,(0,0,225,170))
    for name in ('gamemenu/kekka.tga','window/ending_parts.tga'):
        specs[name] = (results,(0,0,192,255))
    specs['rader/parts.tga'] = ([(box,text,12,(0,0,0,255),None) for box,text in
        [((0,0,125,12),'최소화'),((0,34,125,46),'닫기'),((0,68,125,80),'아군 위치'),((0,105,125,117),'레이더 점')]], (0,0,125,117))
    selection, report = [], []
    sheet = Image.new('RGBA',(256*len(specs),256),(15,20,32,255))
    for column,(relative,(cells,box)) in enumerate(specs.items()):
        path=source/relative; data=path.read_bytes(); info,original,_=unpack(data)
        image=original.copy()
        labels=[label(image,*cell[:2],font_path,*cell[2:]) for cell in cells]
        replacement=repack(data,image,box)
        for kind,content in [('backup',data),('replacement',replacement)]:
            dest=output/kind/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(content)
        actual=unpack(replacement)[1]
        actual.save(output/(Path(relative).stem+'.png'));sheet.alpha_composite(actual,(column*256,0))
        selection.append(dict(asset=relative,original_sha256=image_patch.sha(data),replacement_sha256=image_patch.sha(replacement)))
        report.append(dict(asset=relative,labels=labels,edit_box=box,metadata=info,display_validated=False))
        if path.read_bytes()!=data:
            raise ValueError('original changed')
    (output/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'render.json').write_text(json.dumps(dict(author='최병호',font=str(font_path),
        font_sha256=image_patch.sha(font_path.read_bytes()),original_font_confirmed=False,assets=report),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    sheet.save(output/'preview.png')
    image_patch.prepare(source,output/'replacement',output/'selection.json',output/'package')
    image_patch.verify(output/'package')
    return output


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--font',type=Path,default=Path('C:/Windows/Fonts/batang.ttc'))
    args=p.parse_args();print(render(args.source,args.output,args.font))
