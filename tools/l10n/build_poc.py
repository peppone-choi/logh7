"""오프라인 한글 표시 PoC 생성. 작성자: 최병호."""
import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import sys

import msgdat

EXPECTED_EXE = 'bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'
LABELS = {
    2423: '서버 접속', 2424: '아이디와 암호 입력', 2425: 'ID',
    2426: '암호', 2427: '로그인', 2428: '종료', 2429: '게임 시작',
    2430: '캐릭터 생성', 2431: '캐릭터 추첨', 2432: '캐릭터 삭제',
    2433: '세션 변경', 2434: '환경 설정', 2435: '제작진',
    2436: '게임 종료', 2437: '서버 알림', 2438: '캐릭터 선택',
    2443: '뒤로', 2444: '캐릭터 없음',
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('install', type=Path)
    p.add_argument('output', type=Path)
    args = p.parse_args()
    if args.output.exists():
        raise ValueError('output must be a new directory')
    def unique(name):
        paths = list(args.install.rglob(name))
        if len(paths) != 1:
            raise ValueError(f'expected one {name}, got {len(paths)}')
        return paths[0]
    exe, dat = unique('G7MTClient.exe'), unique('constmsg.dat')
    original = {str(exe): msgdat.sha(exe.read_bytes()), str(dat): msgdat.sha(dat.read_bytes())}
    if original[str(exe)] != EXPECTED_EXE:
        raise ValueError('source exe hash mismatch')
    ref = msgdat.decode(dat.read_bytes())
    doc = copy.deepcopy(ref)
    rows = []
    for i, old in enumerate(ref['strings']):
        if not old:
            continue
        # Preserve all placeholders and control whitespace, replacing only literal runs.
        parts = re.split(r'(\$[^$]*\$|[\r\n\t]+)', old)
        literal = f'{LABELS.get(i, "표식")} C{i:04d}'
        doc['strings'][i] = ''.join(
            part if not part or part.startswith('$') or re.fullmatch(r'[\r\n\t]+', part)
            else literal for part in parts)
        group = max(j for j, start in enumerate(ref['indices'][:ref['group_count'] - 1]) if start <= i)
        if 2423 <= i <= 2444 or 3071 <= i <= 3087 or i == 1276:
            rows.append(dict(file='data/MsgDat/constmsg.dat', index=i, group=group,
                             group_index=i-ref['indices'][group], marker=doc['strings'][i],
                             tokens=dict(msgdat.tokens(old)), status='candidate'))
    out = args.output
    msgdat.write_json(out / 'constmsg.cp949.json', doc)
    subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('msgdat.py')),
                    'patch', str(exe), str(out/'payload/G7MTClient.exe'),
                    '--font', '굴림', '--expected-sha256', EXPECTED_EXE], check=True,
                   stdout=subprocess.PIPE)
    subprocess.run([sys.executable, '-B', str(Path(__file__).with_name('msgdat.py')),
                    'build', str(out/'constmsg.cp949.json'), str(dat),
                    str(out/'payload/data/MsgDat/constmsg.dat'), '--encoding', 'cp949'], check=True)
    generated = msgdat.decode((out/'payload/data/MsgDat/constmsg.dat').read_bytes(), 'cp949')
    assert generated['strings'] == doc['strings']
    assert generated['indices'] == ref['indices']
    assert all(msgdat.tokens(a) == msgdat.tokens(b) for a,b in zip(ref['strings'], generated['strings']))
    manifest = []
    for source, relative in [(exe, 'G7MTClient.exe'), (dat, 'data/MsgDat/constmsg.dat')]:
        msgdat.write_new(out/'rollback'/relative, source.read_bytes())
        manifest.append(dict(relative_path=relative, source=str(source),
                             original_sha256=original[str(source)],
                             payload_sha256=msgdat.sha((out/'payload'/relative).read_bytes()),
                             rollback_sha256=msgdat.sha((out/'rollback'/relative).read_bytes())))
    assert all(msgdat.sha(Path(path).read_bytes()) == digest for path,digest in original.items())
    msgdat.write_json(out/'manifest.json', manifest)
    msgdat.write_json(out/'candidates.json', rows)
    msgdat.write_json(out/'verification.json', dict(author='최병호', evidence='evidence:client',
                     original_unchanged=True, strings=len(ref['strings']),
                     rewritten=sum(bool(s) for s in ref['strings']), tokens_preserved=True,
                     structure_preserved=True, cp949_roundtrip=True, display_validated=False))
    print(json.dumps(dict(output=str(out), candidates=len(rows), files=len(manifest))))


if __name__ == '__main__':
    main()
