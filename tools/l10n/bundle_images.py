"""검증한 이미지 후보 패키지를 중복 없이 묶는다. 작성: 최병호."""
import argparse
import json
from pathlib import Path

import image_patch
from render_buttons import ROOT


def bundle(source,output,packages):
    output=image_patch.writable(output)
    if output.exists() or not output.is_relative_to((ROOT/'work').resolve()):
        raise ValueError('output must be new and inside this worktree/work')
    selected=[];seen=set();content=[]
    for package in packages:
        for row,backup,replacement in image_patch.verify(package):
            relative=row['asset']
            if relative.casefold() in seen:raise ValueError('duplicate bundled asset')
            seen.add(relative.casefold());content.append((relative,replacement))
            selected.append({key:row[key] for key in ('asset','original_sha256','replacement_sha256')})
    output.mkdir(parents=True)
    for relative,replacement in content:
        path=output/'replacement'/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(replacement)
    (output/'selection.json').write_text(json.dumps(selected,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    image_patch.prepare(source,output/'replacement',output/'selection.json',output/'package')
    image_patch.verify(output/'package')
    return dict(author='최병호',assets=len(selected),package=str(output/'package'),display_validated=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source',type=Path);parser.add_argument('output',type=Path);parser.add_argument('packages',type=Path,nargs='+')
    args=parser.parse_args();print(json.dumps(bundle(args.source,args.output,args.packages),ensure_ascii=False))
