"""사용 여부 미확정 제목판 2개의 별도 한국어 후보. 작성: Peppone Choi.

기존 29개 묶음에 자동 포함하지 않는다. 원본 제작 폰트는 미확인이며,
같은 행의 빈 제목판 배경과 맑은 고딕 11px로 검토 후보를 만든다.
"""
import argparse
from collections import Counter
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import image_patch
from render_buttons import ROOT, unpack, repack

HEADERS={
    'trush/soukanpanel_dummy.tga':dict(label='그림멜스하우젠',size=(343,190),
        sha='c27e8071a6481f494eeab93af14bcf42e935b1b3b4ccfdd22b5412fc1fe8ecd4',static_reference=False),
    'trush/status_window.tga':dict(label='정보 창',size=(260,174),
        sha='46768f51eeb24aa87bdae06dccf7f1ab603dfa0ec57040435b4a88649cc3c517',static_reference=True)
}
EDIT_BOX=(10,3,175,17)


def replace_header(data,spec,font_path):
    if image_patch.sha(data)!=spec['sha']:raise ValueError('prototype source hash mismatch')
    info,original,_=unpack(data)
    if original.size!=spec['size']:raise ValueError('prototype canvas changed')
    image=original.copy()
    blue=[(x,y) for y in range(3,17) for x in range(10,140)
          if (lambda c:c[2]>c[0]+40 and c[2]>c[1]+20 and c[0]<100)(original.getpixel((x,y)))]
    if not blue:raise ValueError('prototype title glyphs not found')
    source_box=(min(x for x,y in blue),min(y for x,y in blue),max(x for x,y in blue)+1,max(y for x,y in blue)+1)
    color=Counter(original.getpixel(point) for point in blue).most_common(1)[0][0]
    # Text-free plate at x=145, same scanline. Keep frame/control pixels out.
    for y in range(EDIT_BOX[1],EDIT_BOX[3]):
        for x in range(EDIT_BOX[0],EDIT_BOX[2]):image.putpixel((x,y),original.getpixel((145,y)))
    font=ImageFont.truetype(str(font_path),11)
    draw=ImageDraw.Draw(image);x,y=12,3
    bounds=draw.textbbox((x,y),spec['label'],font=font)
    if not (EDIT_BOX[0]<=bounds[0]<bounds[2]<=EDIT_BOX[2] and EDIT_BOX[1]<=bounds[1]<bounds[3]<=EDIT_BOX[3]):
        raise ValueError('Korean title exceeds original plate')
    draw.text((x,y),spec['label'],font=font,fill=color)
    replacement=repack(data,image,EDIT_BOX)
    return replacement,dict(label=spec['label'],edit_box=EDIT_BOX,source_glyph_box=source_box,
        korean_glyph_box=bounds,font_pixels=11,original_font_confirmed=False,
        static_reference_confirmed=spec['static_reference'],
        actual_reference_confirmed=False,display_validated=False,metadata=info)


def render(source,output,font_path):
    source=Path(source);output=image_patch.writable(output);font_path=Path(font_path).resolve()
    if output.exists() or not output.is_relative_to((ROOT/'work').resolve()):
        raise ValueError('output must be new and inside this worktree/work')
    output.mkdir(parents=True);selection=[];report=[]
    preview=Image.new('RGBA',(700,400),(22,25,35,255))
    for index,(relative,spec) in enumerate(HEADERS.items()):
        path=source/relative;data=path.read_bytes()
        replacement,metadata=replace_header(data,spec,font_path)
        for kind,content in [('backup',data),('replacement',replacement)]:
            dest=output/kind/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(content)
        original=unpack(data)[1];actual=unpack(replacement)[1]
        preview.alpha_composite(original,(index*350,0));preview.alpha_composite(actual,(index*350,200))
        selection.append(dict(asset=relative,original_sha256=image_patch.sha(data),replacement_sha256=image_patch.sha(replacement)))
        report.append(dict(asset=relative,**metadata))
        if path.read_bytes()!=data:raise ValueError('original changed')
    (output/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'render.json').write_text(json.dumps(dict(author='Peppone Choi',font=str(font_path),
        font_sha256=image_patch.sha(font_path.read_bytes()),assets=report,
        actual_reference_confirmed=False,display_validated=False),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    preview.save(output/'preview.png')
    image_patch.prepare(source,output/'replacement',output/'selection.json',output/'package');image_patch.verify(output/'package')
    return output


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('source',type=Path);parser.add_argument('output',type=Path)
    parser.add_argument('--font',type=Path,default=Path('C:/Windows/Fonts/malgun.ttf'))
    args=parser.parse_args();print(render(args.source,args.output,args.font))
