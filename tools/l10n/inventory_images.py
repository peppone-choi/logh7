"""이미지의 의미를 추측하지 않고 전체 경로와 구조를 기록한다. 작성: 최병호.

텍스트 유무 판정은 접촉 시트로 직접 확인한다. 자동 OCR 결과로 완료하지 않는다.
"""
import argparse
import json
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw

import image_patch
import render_buttons
import tcf

ROOT = Path(__file__).resolve().parents[2]
SHEETS = {'gamemenu','window','strategy','senryaku_panel','soukan','shokumu_card','Field','chat','icon','rader','planetbattle'}


def inventory(source,output,all_images=False):
    source=Path(source).resolve();output=image_patch.writable(output)
    if not output.is_relative_to((ROOT/'work').resolve()) or output.exists():
        raise ValueError('output must be new and inside this worktree/work')
    output.mkdir(parents=True)
    rows=[];groups={}
    for path in sorted(source.rglob('*')):
        if not path.is_file():continue
        data=path.read_bytes();relative=path.relative_to(source).as_posix()
        row=dict(asset=relative,bytes=len(data),sha256=image_patch.sha(data),text_review='pending',display_validated=False)
        try:
            if path.suffix.lower()=='.tcf':
                frames=tcf.frames(data)
                row['format']=dict(container='tcf',frames=len(frames))
                if all_images:
                    for index,frame in enumerate(frames):
                        _,portrait,_=render_buttons.unpack(data[frame['offset']:frame['offset']+frame['bytes']])
                        groups.setdefault('Face_'+path.stem,[]).append((f'{relative}#{index}',portrait))
                rows.append(row)
                continue
            if relative.casefold()=='face/tcf.hed':
                containers={name:(path.parent/name).read_bytes() for name,_,_ in tcf.INDEX_BLOCKS}
                records=tcf.verify_index(data,containers)
                row['format']=dict(container='tcf_index',frames=len(records),all_frames_indexed=True)
                rows.append(row)
                continue
            if path.suffix.lower()=='.tga':
                info,image,_=render_buttons.unpack(data);row['format']=info
            else:
                image=Image.open(path).convert('RGBA');row['format']=dict(width=image.width,height=image.height)
            if all_images or relative.split('/')[0] in SHEETS or relative.startswith('icon_normal/'):
                groups.setdefault(relative.split('/')[0],[]).append((relative,image))
        except (ValueError,OSError) as error:
            row['inspect_error']=str(error)
        rows.append(row)
    for group,images in groups.items():
        for page,start in enumerate(range(0,len(images),20)):
            batch=images[start:start+20]
            sheet=Image.new('RGB',(4*280,((len(batch)+3)//4)*290),'#1a1e28');draw=ImageDraw.Draw(sheet)
            for i,(relative,image) in enumerate(batch):
                x=i%4*280;y=i//4*290
                draw.text((x+4,y+3),relative,fill='white')
                image.thumbnail((272,266));sheet.paste(image,(x+4,y+22),image)
            sheet.save(output/f'{group}-{page:02}.png')
    (output/'inventory.json').write_text(json.dumps(dict(author='최병호',source=str(source),assets=rows,
        counts=dict(Counter(row['asset'].split('/')[0] for row in rows))),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(assets=len(rows),errors=sum('inspect_error' in row for row in rows),output=str(output))))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--all',action='store_true',help='include every readable image in contact sheets')
    args=p.parse_args();inventory(args.source,args.output,args.all)
