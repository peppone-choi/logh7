"""타이틀 4종의 한글 조판 후보·원본 복구 패키지. 작성: 최병호.

원작 로고의 제작 글꼴과 가려진 배경은 미확인이다. 원래 로고 폭·높이 안에
바탕 은색 글자를 조판하고 주변 별 배경을 복사한 후보다. 실제 화면 검수는 미완료다.
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageFilter

import image_patch
from render_buttons import ROOT, repack, unpack

NAMES = ('title','title_japan','title_korea','title_korea_china')


def korean_logo(image,font_path):
    box=(114,34,528,102);original=image.copy()
    for y in range(box[1],box[3]):
        for x in range(box[0],box[2]):
            image.putpixel((x,y),original.getpixel((18+(x-box[0])%90,y)))
    font=ImageFont.truetype(str(font_path),62)
    mask=Image.new('L',image.size);draw=ImageDraw.Draw(mask)
    bounds=draw.textbbox((0,0),'은하영웅전설',font=font)
    position=((640-(bounds[2]-bounds[0]))/2-bounds[0],36-bounds[1])
    draw.text(position,'은하영웅전설',font=font,fill=255)
    shadow=Image.new('RGBA',image.size);shadow.putalpha(mask.filter(ImageFilter.MaxFilter(5)))
    image.alpha_composite(shadow,(2,2))
    outline=Image.new('RGBA',image.size,(225,213,232,255));outline.putalpha(mask.filter(ImageFilter.MaxFilter(3)))
    image.alpha_composite(outline)
    fill=Image.new('RGBA',image.size)
    for y in range(36,101):
        phase=(y-36)/max(1,bounds[3]-bounds[1])
        value=round(246-170*phase) if phase<0.55 else round(110+130*(phase-0.55)/0.45)
        ImageDraw.Draw(fill).line((0,y,639,y),fill=(max(0,min(255,value)),max(0,min(255,value)),max(0,min(255,value+5)),255))
    fill.putalpha(mask);image.alpha_composite(fill)


def render(source,output,font_path,logo_font_path=Path('C:/Windows/Fonts/batang.ttc')):
    source,font_path,logo_font_path=Path(source).resolve(),Path(font_path).resolve(),Path(logo_font_path).resolve()
    output=image_patch.writable(output)
    if not output.is_relative_to((ROOT/'work').resolve()) or output.exists():
        raise ValueError('output must be new and inside this worktree/work')
    output.mkdir(parents=True)
    plain=unpack((source/'gamemenu/title_japan.tga').read_bytes())[1]
    normal_font=ImageFont.truetype(str(font_path),13)
    credit_font=ImageFont.truetype(str(font_path),10)
    selection,report=[],[]
    for name in NAMES:
        relative=f'gamemenu/{name}.tga'; path=source/relative; data=path.read_bytes()
        info,image,_=unpack(data)
        if image.size!=(640,480):raise ValueError('unexpected title canvas')
        korean_logo(image,logo_font_path);draw=ImageDraw.Draw(image)
        if name=='title':
            # The Japan variant contains the same clean input-panel background.
            box=(148,221,492,420);image.paste(plain.crop(box),box);draw=ImageDraw.Draw(image)
            def centered(text,y):
                width=draw.textlength(text,font=normal_font);draw.text(((640-width)/2,y),text,font=normal_font,fill='white')
            centered('게임 서버에 접속합니다.',225);centered('아이디와 비밀번호를 입력하세요.',244)
            draw.text((217,273),'ID',font=normal_font,fill='white')
            draw.text((178,299),'비밀번호',font=normal_font,fill='white')
            for box,text in [((283,331,361,353),'로그인'),((228,397,327,419),'공식 웹사이트'),((338,397,417,419),'종료')]:
                draw.rectangle(box,fill=(216,217,236,255),outline=(91,94,115,255))
                width=draw.textlength(text,font=normal_font)
                draw.text(((box[0]+box[2]-width)/2,box[1]+1),text,font=normal_font,fill=(10,13,21,255))
            centered('아이디가 없으면 공식 웹사이트에서',361);centered('게임 회원으로 등록하세요.',377)
        draw.rectangle((6,446,380,475),fill=(1,4,10,255))
        draw.text((8,447),'©2004 다나카 요시키 · ©2004 BOTHTEC · ©2004 MICROVISION INC.',font=credit_font,fill='white')
        draw.text((8,461),'ORIGINAL MECHANIC DESIGN 가토 나오유키',font=credit_font,fill='white')
        draw.rectangle((450,460,639,479),fill=(1,4,10,255))
        draw.text((466,461),'클라이언트 버전 Ver.B1.00',font=credit_font,fill='white')
        replacement=repack(data,image,(0,0,640,480))
        for kind,content in [('backup',data),('replacement',replacement)]:
            dest=output/kind/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(content)
        unpack(replacement)[1].save(output/(name+'.png'))
        selection.append(dict(asset=relative,original_sha256=image_patch.sha(data),replacement_sha256=image_patch.sha(replacement)))
        report.append(dict(asset=relative,metadata=info,display_validated=False,background_reconstructed=False,
                           logo_treatment='바탕 은색 조판·주변 별 복사',original_font_confirmed=False))
        if path.read_bytes()!=data:raise ValueError('original changed')
    (output/'selection.json').write_text(json.dumps(selection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (output/'render.json').write_text(json.dumps(dict(author='최병호',font=str(font_path),font_sha256=image_patch.sha(font_path.read_bytes()),
        logo_font=str(logo_font_path),logo_font_sha256=image_patch.sha(logo_font_path.read_bytes()),
        assets=report),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    image_patch.prepare(source,output/'replacement',output/'selection.json',output/'package');image_patch.verify(output/'package')
    return output


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--font',type=Path,default=Path('C:/Windows/Fonts/malgun.ttf'))
    p.add_argument('--logo-font',type=Path,default=Path('C:/Windows/Fonts/batang.ttc'))
    args=p.parse_args();print(render(args.source,args.output,args.font,args.logo_font))
