"""원본 대조 한국어 리소스 카탈로그·DLL 헤더 생성. 작성: Peppone Choi."""
import argparse
from collections import Counter
import functools
import json
from pathlib import Path
import re

import msgdat
import static_resources

ROOT = Path(__file__).resolve().parents[2]
JAPANESE = re.compile(r'[\u3040-\u30ff\u3400-\u9fff\uff66-\uff9f]')
PRINTF = re.compile(r'%(?:[-+ #0]*)(?:\d+|\*)?(?:\.(?:\d+|\*))?(?:hh|ll|[hlLwIz])?[diuoxXfFeEgGaAcCsSpn%]')


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def terms(path):
    result = {}
    for line in Path(path).read_text(encoding='utf-8-sig').splitlines():
        if not line or line.startswith('#'):
            continue
        old, new = line.split('\t')
        if old in result and result[old] != new:
            raise ValueError(f'conflicting term: {old}')
        result[old] = new
    return result


def signature(text):
    # Keep argument order and exact width/precision/length; ANSI printf still
    # consumes the resource before the Unicode display boundary.
    return PRINTF.findall(text)


def validate(old, new, remove_dangling_dollar=False):
    if not isinstance(new, str) or '\0' in new or not new and old:
        raise ValueError('empty/non-string/NUL translation')
    dollar_count_ok = old.count('$') == new.count('$')
    if remove_dangling_dollar:
        # Explicitly indexed, source-file-hash-gated repair of a known CD typo.
        dollar_count_ok = old.count('$') % 2 == 1 and new.count('$') == old.count('$')-1
    if msgdat.tokens(old) != msgdat.tokens(new) or not dollar_count_ok:
        raise ValueError('placeholder tokens changed')
    if signature(old) != signature(new):
        raise ValueError('printf arguments changed')
    if JAPANESE.search(new):
        raise ValueError('Japanese remains in translation')
    if any(0xe000 <= ord(c) <= 0xf8ff for c in new):
        raise ValueError('private glyph in translation')
    new.encode('cp949', errors='strict')


def composer(dictionary):
    # Only accept a fully covered label. A sentence with unknown grammar must
    # remain in the explicit missing list, never become mixed machine prose.
    ordered = sorted(dictionary, key=lambda x: (-len(x), x))

    @functools.lru_cache(maxsize=None)
    def translate(text):
        if text in dictionary:
            return dictionary[text]
        if not text:
            return ''
        token = re.match(r'\$[^$]*\$|%[-+ #0\d.*hlLIz]*[diuoxXfFeEgGaAcCsSpn%]', text)
        if token:
            suffix = translate(text[token.end():])
            return token[0] + suffix if suffix is not None else None
        for old in ordered:
            if text.startswith(old):
                suffix = translate(text[len(old):])
                if suffix is not None:
                    return dictionary[old] + suffix
        first = text[0]
        if not JAPANESE.search(first):
            suffix = translate(text[1:])
            if suffix is not None:
                return first + suffix
        return None
    return translate


def fnv(data):
    value = 14695981039346656037
    for byte in data:
        value = ((value ^ byte) * 1099511628211) & ((1 << 64) - 1)
    return value


def glyph_encoding(texts):
    # CP932 has 1,880 user-defined double-byte positions. They are used only
    # inside the DLL's static replacement strings, never on the network/disk.
    def private(c):
        if 0xac00 <= ord(c) <= 0xd7a3:
            return True
        try:
            c.encode('cp932', errors='strict')
            return False
        except UnicodeEncodeError:
            return True
    glyphs = sorted({c for text in texts for c in text if private(c)})
    if len(glyphs) > 1880:
        raise ValueError('CP932 private glyph capacity exceeded')
    mapping = {c: chr(0xe000 + i) for i, c in enumerate(glyphs)}

    def encode(text):
        return ''.join(mapping.get(c, c) for c in text).encode('cp932', errors='strict')
    return glyphs, encode


def collect(source, catalogue, dictionary):
    ref = msgdat.decode(Path(source).read_bytes())
    if ref['source_sha256'] != catalogue['source_sha256']:
        raise ValueError('constmsg source hash mismatch')
    explicit = {}
    repairs=set(catalogue.get('remove_dangling_dollar',[]))
    indices = ref['indices']
    for group, values in catalogue.get('groups', {}).items():
        g = int(group)
        if not 0 <= g < ref['group_count'] - 1 or len(values) != indices[g+1] - indices[g]:
            raise ValueError(f'group {group} length mismatch')
        explicit.update({indices[g] + i: s for i, s in enumerate(values) if s is not None})
    for key, new in catalogue.get('entries', {}).items():
        g, i = map(int, key.split('/'))
        if not 0 <= g < ref['group_count'] - 1 or not 0 <= i < indices[g+1] - indices[g]:
            raise ValueError(f'entry out of range: {key}')
        explicit[indices[g]+i] = new
    translate = composer(dictionary)
    # Each file and index is independent. Store no original sentences in the
    # generated header; runtime replacements also check source length/hash.
    for index, new in explicit.items():
        old = ref['strings'][index]
        try:
            group=next(g for g in range(ref['group_count']-1) if indices[g]<=index<indices[g+1])
            validate(old, new, f'{group}/{index-indices[group]}' in repairs)
        except (ValueError, UnicodeError) as error:
            raise ValueError(f'entry {index}: {error}') from error
    rows, missing = [], []
    for g in range(ref['group_count'] - 1):
        for i, old in enumerate(ref['strings'][indices[g]:indices[g+1]]):
            new = explicit.get(indices[g]+i)
            if new is None and JAPANESE.search(old):
                new = translate(old)
            if new is not None and new != old:
                validate(old, new, f'{g}/{i}' in repairs)
                rows.append(dict(group=g, index=i, source=bytes.fromhex(ref['raw_hex'][indices[g]+i]), text=new))
            elif JAPANESE.search(old):
                missing.append(dict(group=g, index=i, text=old))
    return ref, rows, missing


def header(rows, message_tables=()):
    glyphs, encode = glyph_encoding([row['text'] for row in rows])
    unique = {}
    for row in rows:
        raw = row['source']
        key = (row.get('table',0) << 32) | (row['group'] << 16) | row['index']
        value = encode(row['text'])
        if key in unique and unique[key] != (raw, value):
            raise ValueError('conflicting resource identity')
        unique[key] = (raw, value)
    lines = ['// Generated by tools/l10n/catalog.py. Author: Peppone Choi.',
             '// Only hashes and Korean replacement bytes; original assets stay read-only.',
             '#pragma once', '#include <cstdint>', '#include <cstddef>',
             'namespace logh {', 'struct KoreanResource { uint64_t key; uint64_t hash; size_t length; const char* text; };',
             'inline const wchar_t* koreanGlyphs() { return L"' + ''.join(f'\\u{ord(c):04x}' for c in glyphs) + '"; }',
             f'static constexpr size_t koreanGlyphCount = {len(glyphs)};',
             'static const KoreanResource koreanResources[] = {']
    for key, (raw, value) in sorted(unique.items()):
        escaped = ''.join(f'\\x{byte:02x}' for byte in value)
        lines.append(f'    {{{key}ULL,0x{fnv(raw):016x}ULL,{len(raw)},"{escaped}"}},')
    lines += ['};','struct KoreanMessageTable { unsigned id, kind, variant, groups, strings; };',
              'static const KoreanMessageTable koreanMessageTables[] = {']
    for row in message_tables:
        lines.append('    {'+','.join(str(row[key]) for key in ('id','kind','variant','groups','strings'))+'},')
    # A sentinel also makes a standalone synthetic header valid C++.
    lines += ['    {0,0,0,0,0}', '};',
              'struct KoreanWideResource { unsigned address; uint64_t hash; unsigned units; const wchar_t* text; const char* narrow; };',
              'static const KoreanWideResource koreanWideResources[] = {']
    for row in sorted((r for r in rows if r.get('table') == 65532), key=lambda r: (r['group'], r['index'])):
        address = row['group']*65536+row['index']
        escapes = {'\n': r'\n', '\r': r'\r', '\t': r'\t', '\b': r'\b', '"': r'\"', '\\': r'\\'}
        wide = ''.join(escapes.get(c, c if 32 <= ord(c) < 127 else f'\\u{ord(c):04x}') for c in row['text'])
        narrow = ''.join(f'\\x{byte:02x}' for byte in encode(row['text']))
        lines.append(f'    {{0x{address:x},0x{fnv(row["source"]):016x}ULL,{len(row["source"])//2},L"{wide}","{narrow}"}},')
    lines += ['    {0,0,0,nullptr,nullptr}', '};', '}']
    return '\n'.join(lines) + '\n'


def project(source):
    source=Path(source)
    ref, rows, missing = collect(source, read_json(ROOT/'l10n/constmsg.ko.json'), terms(ROOT/'l10n/ui-terms.ko.tsv'))
    for row in missing:row['file']='constmsg.dat'
    tables, message_coverage = [], {}
    message_doc = read_json(ROOT/'l10n/messages.ko.json')
    known = set()
    paths=sorted(source.parent.glob('messages*.dat'))
    if set(message_doc['files'])-set(path.name for path in paths):
        raise ValueError('catalogued message files are missing from source directory')
    for path in paths:
        info = message_doc['files'].get(path.name)
        if info is None:
            info = dict(source_sha256=msgdat.sha(path.read_bytes()),entries={})
        reference, translated, untranslated = collect(path,info,{})
        message_coverage[path.name] = dict(translated=len(translated),untranslated=len(untranslated),
                                          source_sha256=reference['source_sha256'])
        for row in untranslated: row['file']=path.name
        missing.extend(untranslated)
        if not translated: continue
        kind,variant=info['kind'],info['variant']
        if kind not in (0,4,5) or not 0 <= variant < (2 if kind==4 else 9):
            raise ValueError('unexpected native message table identity')
        identity=kind*9+variant+1
        if identity in known:raise ValueError('duplicate native message table identity')
        known.add(identity)
        tables.append(dict(id=identity,kind=kind,variant=variant,groups=reference['group_count'],strings=len(reference['strings'])))
        for row in translated:row['table']=identity
        rows.extend(translated)
    rows.extend(static_resources.collect(source.parents[2]/'exe/G7MTClient.exe',read_json(ROOT/'l10n/exe-ui.ko.json'),validate))
    rows.extend(static_resources.collect_windows(source.parents[2]/'exe/G7MTClient.exe',read_json(ROOT/'l10n/windows-ui.ko.json'),validate))
    rows.extend(static_resources.collect_wide(source.parents[2]/'exe/G7MTClient.exe',read_json(ROOT/'l10n/exe-wide-ui.ko.json'),validate))
    for index,text in enumerate(('명령 패널','레이더')):
        rows.append(dict(table=65534,group=0,index=index,source=b'NO TABLE',text=text))
    return ref,rows,missing,tables,message_coverage


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('source', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--header', type=Path)
    args = p.parse_args()
    out = args.output.resolve()
    if not out.is_relative_to((ROOT / 'work').resolve()) or out.exists():
        raise ValueError('output must be a new directory in this worktree/work')
    if args.header and not args.header.resolve().is_relative_to(ROOT):
        raise ValueError('generated header must stay inside this worktree')
    ref,rows,missing,tables,message_coverage=project(args.source)
    generated = header(rows,tables)
    out.mkdir(parents=True)
    msgdat.write_json(out/'missing.json', missing)
    msgdat.write_json(out/'coverage.json', dict(author='Peppone Choi', source_sha256=ref['source_sha256'],
        translated_slots=len(rows), untranslated_slots=len(missing),
        constmsg_slots=sum(not row.get('table') for row in rows),
        message_slots=sum(0<row.get('table',0)<65532 for row in rows),
        wide_ui_slots=sum(row.get('table')==65532 for row in rows),
        windows_ui_slots=sum(row.get('table')==65533 for row in rows),
        static_ui_slots=sum(row.get('table')==65535 for row in rows),compat_ui_slots=2,
        groups=dict(Counter(row['group'] for row in rows if not row.get('table'))),
        messages=message_coverage,display_validated=False))
    if args.header:
        args.header.write_text(generated, encoding='utf-8', newline='\n')
    print(json.dumps(dict(translated=len(rows), missing=len(missing), output=str(out))))


if __name__ == '__main__':
    main()
