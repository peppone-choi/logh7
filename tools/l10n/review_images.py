"""이미지 전수 목록과 해시가 고정된 육안 분류·교체 패키지를 대조한다. 작성: 최병호.

접촉 시트에서 글자가 없다는 판정은 실제 사용·확대 표시·게임 화면 검증이 아니다.
새 파일·변경 파일은 기존 분류를 계승하지 않고 pending으로 되돌린다.
"""
import argparse
from collections import Counter
import json
from pathlib import Path

import image_patch
from render_buttons import ROOT

CLASSIFICATIONS = {'no_japanese_visible', 'translation_candidate', 'inscription_unread',
                   'opaque_container', 'non_image_metadata'}


def review(inventory, document, packages=()):
    if document.get('schema') != 1:
        raise ValueError('unknown image review schema')
    known={}
    for row in document['assets']:
        key=row['asset'].casefold()
        if key in known or row['classification'] not in CLASSIFICATIONS:
            raise ValueError('duplicate image review or unknown classification')
        if len(row['sha256'])!=64 or any(c not in '0123456789abcdef' for c in row['sha256']):
            raise ValueError('invalid review hash')
        known[key]=row
    replacements={}
    for package in packages:
        for row,_,_ in image_patch.verify(package):
            key=row['asset'].casefold()
            if key in replacements:
                raise ValueError('duplicate replacement asset')
            replacements[key]=row
    rows=[];seen=set()
    for source in inventory['assets']:
        row=dict(source);key=row['asset'].casefold()
        if key in seen:
            raise ValueError('duplicate inventory asset')
        seen.add(key)
        prior=known.get(key)
        row['text_review']='pending'
        row['replacement_verified']=False
        row['display_validated']=False
        if prior and prior['sha256']==row['sha256']:
            row['text_review']=prior['classification']
            row['review_sheet']=prior['sheet']
            candidate=replacements.get(key)
            if candidate and candidate['original_sha256']==row['sha256']:
                row['replacement_verified']=True
        rows.append(row)
    removed=sorted(row['asset'] for key,row in known.items() if key not in seen)
    counts=dict(Counter(row['text_review'] for row in rows))
    missing=[row['asset'] for row in rows if row['text_review']=='translation_candidate' and not row['replacement_verified']]
    return dict(author='최병호',assets=rows,counts=counts,removed_assets=removed,
        missing_replacements=missing,display_validated=False,
        inventory_matches_review=not removed and not counts.get('pending',0),
        translation_candidates_packaged=not missing)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inventory',type=Path)
    parser.add_argument('review',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--package',type=Path,action='append',default=[])
    args=parser.parse_args()
    output=image_patch.writable(args.output)
    if output.exists() or not output.is_relative_to((ROOT/'work').resolve()):
        raise ValueError('output must be new and inside this worktree/work')
    result=review(image_patch.read_json(args.inventory),image_patch.read_json(args.review),args.package)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    assert output.read_text(encoding='utf-8').endswith('}\n')
    print(json.dumps({key:value for key,value in result.items() if key!='assets'},ensure_ascii=False))
    if not result['inventory_matches_review'] or not result['translation_candidates_packaged']:
        raise SystemExit(1)


if __name__=='__main__':main()
