"""Offline NPC worksheet and read-only CD portrait extraction. Author: Peppone Choi.

Generated names, portraits and JSON belong under work/, never in Git.
This tool does not change a running server or claim to recover original stats.
"""
import argparse
import base64
import csv
import copy
import json
from pathlib import Path
import re
import shutil
import struct
import zlib


ABILITIES = ('leadership', 'politics', 'administration', 'intelligence',
             'command', 'mobility', 'attack', 'defense')
TABLES = (('oem', 0, 200, 'empire'), ('oam', 200, 150, 'alliance'),
          ('o', 350, 100, 'other'))
WORK = Path(__file__).resolve().parents[2] / 'work'
NAMES = Path(__file__).resolve().parents[2] / 'l10n/npc-names.ko.json'
ROSTER = Path(__file__).resolve().parents[2] / 'l10n/npc-roster.ko.json'
MATCHES = Path(__file__).resolve().parents[2] / 'l10n/npc-face-matches.json'
NAME_PARTS = Path(__file__).resolve().parents[2] / 'l10n/npc-name-parts.ko.json'
RANKS = dict(zip(('元帥', '上級大将', '大将', '中将', '少将', '准将', '大佐', '中佐', '少佐'),
                 ('원수', '상급대장', '대장', '중장', '소장', '준장', '대령', '중령', '소령')))


def tga_length(data, offset):
    """The observed CD records are uncompressed 8-bit color-mapped TGA."""
    if not 0 <= offset <= len(data) - 18:
        raise ValueError('Truncated TGA header')
    ident, cmap, kind, first, colors, bits, x, y, width, height, depth, desc = \
        struct.unpack_from('<BBBHHBHHHHBB', data, offset)
    if (ident != 0 or cmap != 1 or kind != 1 or first != 0 or
            not 1 <= colors <= 256 or bits != 32 or depth != 8 or
            not 1 <= width <= 256 or not 1 <= height <= 256 or desc & 0xc0):
        raise ValueError('Unsupported CD portrait TGA')
    size = 18 + colors * 4 + width * height
    if offset + size > len(data):
        raise ValueError('Truncated TGA palette or pixels')
    if max(data[offset + 18 + colors * 4:offset + size]) >= colors:
        raise ValueError('TGA pixel exceeds palette')
    return size


def face_identity(group, index):
    """Canonical original IDs from CD 00592c30; never the character ID."""
    if group == 'oem':
        return index, 'empire'
    if group == 'oam':
        return 100000 + index, 'alliance'
    if 1 <= index <= 6:
        return 10000 + index, 'empire'
    if 11 <= index <= 14:
        return 110000 + index - 10, 'alliance'
    if 21 <= index <= 23:
        return 200000 + index - 20, 'other'
    # Remaining o.tcf records are shared with generated age variants.
    return None, 'other'


def tga_png(data, offset):
    """Expand BGRA palette, preserve TGA origin, emit standard RGBA PNG."""
    size = tga_length(data, offset)
    colors = struct.unpack_from('<H', data, offset + 5)[0]
    width, height = struct.unpack_from('<HH', data, offset + 12)
    palette = [bytes((r, g, b, a)) for b, g, r, a in
               struct.iter_unpack('<4B', data[offset + 18:offset + 18 + colors * 4])]
    pixels = data[offset + 18 + colors * 4:offset + size]
    rows = [pixels[y * width:(y + 1) * width] for y in range(height)]
    if not data[offset + 17] & 0x20:
        rows.reverse()
    if data[offset + 17] & 0x10:
        rows = [row[::-1] for row in rows]
    raw = b''.join(b'\0' + b''.join(palette[index] for index in row) for row in rows)

    def chunk(kind, value):
        return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value))

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)) +
            chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def extract(face_dir):
    hed = (face_dir / 'tcf.hed').read_bytes()
    if len(hed) != 10840:
        raise ValueError('Expected CD tcf.hed (10840 bytes)')
    portraits = []
    for group, base, count, faction in TABLES:
        data = (face_dir / (group + '.tcf')).read_bytes()
        covered = []
        for index in range(count):
            offset, size = struct.unpack_from('<II', hed, (base + index) * 8)
            if not size:
                if offset:
                    raise ValueError('Empty HED record has an offset')
                continue
            if offset < 50 or tga_length(data, offset) != size:
                raise ValueError('HED length differs from TGA length')
            covered.append((offset, offset + size))
            width, height = struct.unpack_from('<HH', data, offset + 12)
            png = base64.b64encode(tga_png(data, offset)).decode('ascii')
            face_id, faction = face_identity(group, index)
            portraits.append(dict(key=f'{group}:{index}', group=group, index=index,
                                  face_id=face_id, faction=faction, offset=offset,
                                  size=size, width=width, height=height,
                                  image='data:image/png;base64,' + png))
        covered.sort()
        end = 50
        for begin, stop in covered:
            if begin != end:
                raise ValueError('HED records overlap or leave an unaccounted gap')
            end = stop
        if end != len(data):
            raise ValueError('HED does not cover the whole TCF payload')
    return portraits


def blank_name_parts():
    return dict(surname=None, given_name=None, prefix='', order='unknown', status='pending', source='', note='')


def migrate_name_parts(document):
    """Upgrade old exports without changing display names, faces or abilities."""
    result = copy.deepcopy(document)
    if not isinstance(result, dict) or not isinstance(result.get('characters'), list):
        return result
    for row in result['characters']:
        parts = row.get('name_parts') if isinstance(row, dict) else None
        if not isinstance(parts, dict) or 'prefix' in parts:
            continue
        parts['prefix'] = ''
        surname = parts.get('surname')
        if isinstance(surname, str) and surname.startswith('폰 ') and surname[2:].strip():
            parts['prefix'], parts['surname'] = '폰', surname[2:]
            if parts.get('note') == '폰은 성 쪽에 보존한 편집 규칙. 원작 wire 입력 표기는 미확인.':
                parts['note'] = '폰은 표시용 접두사로 별도 보존하며 시스템 성·이름에서 제외한다.'
    return result


def blank_character(key, faction, name=''):
    return dict(key=key, faction=faction, name=name, manual_holder='', posts=[],
                face_id=None, face_evidence=dict(status='unknown', source=''),
                abilities={key: None for key in ABILITIES},
                abilities_evidence=dict(status='unknown', source=''),
                rank='', location='', ai_profile='', notes='', name_parts=blank_name_parts())


def manual_roster(path):
    """Merge repeated holders' posts; retain raw manual labels for review."""
    people = {}
    translation = json.loads(NAMES.read_text(encoding='utf-8'))
    names = {entry['ja']: entry for entry in translation['names']}
    if len(names) != len(translation['names']):
        raise ValueError('Duplicate Japanese name translation')
    with path.open(encoding='utf-8-sig', newline='') as stream:
        for row in csv.DictReader(stream):
            if row['faction'] not in ('empire', 'alliance'):
                raise ValueError('Unknown manual faction')
            holder = row['initial_holder'].strip()
            if not holder:
                continue
            key = row['faction'], holder
            if key not in people:
                name = re.sub(r'^(政治家)', '', holder)
                name = re.sub(r'(元帥|上級大将|大将|中将|少将|准将|大佐|中佐|少佐)$', '', name)
                person = blank_character(f'manual-{len(people) + 1:03}', row['faction'], name)
                person['manual_holder'] = holder
                rank = holder[len(name):] if holder.startswith(name) else ''
                person['rank'] = RANKS.get(rank, rank)
                if name in names:
                    entry = names[name]
                    person['name'] = entry['ko']
                    person['name_translation'] = dict(japanese=name, korean=entry['ko'],
                        status=entry['status'], source=translation['sources'][entry['source']],
                        note=entry.get('note', ''))
                people[key] = person
            people[key]['posts'].append(dict(post=row['post'], unit=row['unit'],
                                             department=row['department'],
                                             source='gin7manual.pdf p.' + row['page']))
    return dict(schema=1, characters=list(people.values()))


def expanded_roster(manual):
    """Keep manual placement/faction; add wiki candidates without invented stats."""
    document = manual_roster(manual)
    people = {c['name']: c for c in document['characters']}
    for entry in json.loads(ROSTER.read_text(encoding='utf-8'))['characters']:
        name = entry['name']
        if name not in people:
            people[name] = blank_character(f"wiki-{len(people) + 1:03}", entry['faction'], name)
        people[name]['wiki'] = {k: entry[k] for k in ('page', 'mirror', 'section')}
        people[name]['wiki']['reference_name'] = name
    for entry in json.loads(NAME_PARTS.read_text(encoding='utf-8'))['names']:
        people[entry['name']]['name_parts'] = {k: v for k, v in entry.items() if k != 'name'}
    for match in json.loads(MATCHES.read_text(encoding='utf-8'))['matches']:
        person = people[match['name']]
        person['face_id'] = match['face_id']
        person['face_evidence'] = dict(status='guess', source=(
            'OVA 그림 직접 대조 · 높은 확신 · ' + match['source'] + ' · ' + match['features']))
        person['face_comparison'] = {k: v for k, v in match.items() if k != 'name'}
    document['characters'] = list(people.values())
    return document


def validate(document, portraits):
    """Unverified values stay null; byte limits are wire limits, not game rules."""
    def require(condition, message):
        if not condition:
            raise ValueError(message)
    require(type(document) is dict and document.get('schema') == 1, 'Expected schema=1')
    rows = document.get('characters')
    require(type(rows) is list and len(rows) <= 2000, 'Invalid character list')
    faces = {p['face_id']: p for p in portraits if p['face_id'] is not None}
    keys = set()
    assigned = set()
    for row in rows:
        require(type(row) is dict, 'Invalid character')
        key = row.get('key')
        require(type(key) is str and re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', key) and
                key not in keys, 'Duplicate or invalid character key')
        keys.add(key)
        require(row.get('faction') in ('empire', 'alliance', 'other'), 'Invalid faction')
        for field in ('name', 'manual_holder', 'rank', 'location', 'ai_profile', 'notes'):
            require(type(row.get(field)) is str and len(row[field]) <= 4000, f'Invalid {field}')
        if 'name_parts' in row:
            parts = row['name_parts']
            require(type(parts) is dict and parts.get('order') in ('unknown', 'given_first', 'family_first') and
                    parts.get('status') in ('pending', 'reviewed'), 'Invalid name parts')
            prefix = parts.get('prefix', '')
            require(type(prefix) is str and len(prefix) <= 40 and
                    not any(ch == '\x00' or ord(ch) > 0xffff or 0xd800 <= ord(ch) <= 0xdfff for ch in prefix),
                    'Invalid display-only name prefix')
            aliases = parts.get('aliases', [])
            require(type(aliases) is list and len(aliases) <= 20 and
                    all(type(a) is str and a.strip() and len(a) <= 200 and
                        not any(ch == '\x00' or ord(ch) > 0xffff or 0xd800 <= ord(ch) <= 0xdfff for ch in a)
                        for a in aliases), 'Invalid name aliases')
            for field in ('surname', 'given_name'):
                value = parts.get(field)
                require(field in parts and (value is None or type(value) is str and 1 <= len(value) <= 12 and value.strip() and
                        not any(ch == '\x00' or 0xd800 <= ord(ch) <= 0xdfff or ord(ch) > 0xffff for ch in value)),
                        'Name fields must be null or 1..12 BMP characters (wire limit)')
            require('prefix' not in parts or not (parts['surname'] or '').startswith('폰 '),
                    'Store von in prefix, not system surname')
            require(all(type(parts.get(k)) is str and len(parts[k]) <= 2000 for k in ('source', 'note')),
                    'Invalid name-part source')
            require(parts['status'] != 'reviewed' or parts['surname'] and parts['given_name'] and
                    parts['order'] != 'unknown' and parts['source'].strip(), 'Reviewed name parts need both fields, order and source')
        if 'name_translation' in row:
            translation = row['name_translation']
            require(type(translation) is dict and translation.get('status') in ('adopted', 'pending') and
                    all(type(translation.get(k)) is str and len(translation[k]) <= 4000
                        for k in ('japanese', 'korean', 'source', 'note')), 'Invalid name translation')
        if 'wiki' in row:
            wiki = row['wiki']
            require(type(wiki) is dict and all(type(wiki.get(k)) is str and len(wiki[k]) <= 2000
                    for k in ('page', 'mirror', 'section')), 'Invalid wiki reference')
            require(wiki['page'].startswith('https://namu.wiki/w/') and
                    wiki['mirror'].startswith('https://namu.moe/w/'), 'Invalid wiki source URL')
            require('reference_name' not in wiki or type(wiki['reference_name']) is str and
                    len(wiki['reference_name']) <= 200, 'Invalid reference name')
        require(type(row.get('posts')) is list and len(row['posts']) <= 100, 'Invalid posts')
        for post in row['posts']:
            require(type(post) is dict and all(type(post.get(k)) is str and len(post[k]) <= 1000
                    for k in ('post', 'unit', 'department', 'source')), 'Invalid post source')
        face = row.get('face_id')
        require(face is None or type(face) is int and face in faces, 'Unknown face ID')
        if face is not None:
            require(faces[face]['faction'] == row['faction'], 'Portrait faction mismatch')
            require(face not in assigned, 'Duplicate portrait assignment')
            assigned.add(face)
        if 'face_comparison' in row:
            comparison = row['face_comparison']
            require(type(comparison) is dict and type(comparison.get('face_id')) is int and
                    comparison['face_id'] == face and comparison.get('edition') == 'OVA' and
                    comparison.get('confidence') == 'high', 'Stale or invalid OVA comparison')
            require(all(type(comparison.get(k)) is str and len(comparison[k]) <= 2000
                    for k in ('source', 'image_url', 'image_label', 'edition_caption', 'features')),
                    'Invalid comparison source')
            require(comparison['source'].startswith('https://namu.wiki/w/') and
                    comparison['image_url'].startswith('https://i.namu.wiki/i/') and
                    comparison['edition_caption'] == 'OVA' and comparison['features'].strip(),
                    'Expected a reviewed OVA image and visual features')
        abilities = row.get('abilities')
        require(type(abilities) is dict and set(abilities) == set(ABILITIES), 'Expected eight abilities')
        require(all(v is None or type(v) is int and 0 <= v <= 65535
                    for v in abilities.values()), 'Ability must be null or u16 (0..65535)')
        for field, populated in (('face_evidence', face is not None),
                                 ('abilities_evidence', any(v is not None for v in abilities.values()))):
            evidence = row.get(field)
            require(type(evidence) is dict and evidence.get('status') in
                    ('unknown', 'guess', 'manual', 'client') and
                    type(evidence.get('source')) is str and len(evidence['source']) <= 4000,
                    'Invalid evidence')
            require(not populated or evidence['status'] != 'unknown' and evidence['source'].strip(),
                    'Assigned values need evidence status and source')
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--face-dir', type=Path, required=True)
    parser.add_argument('--manual', type=Path, default=Path(__file__).resolve().parents[2] /
                        'docs/manual/data/initial-card-holders.csv')
    parser.add_argument('--catalogue', type=Path, help='Previously exported JSON to reload')
    parser.add_argument('--references', type=Path, help='npc_wiki.py cache directory (optional)')
    parser.add_argument('--output', type=Path, required=True, help='New directory under work/')
    args = parser.parse_args()
    destination = args.output.resolve()
    if not destination.is_relative_to(WORK.resolve()) or destination == WORK.resolve():
        parser.error('Output must be a new subdirectory under repository work/')
    # Resolve symlinks before checking ancestry; output can never contain an input.
    if destination.exists():
        parser.error('Output directory already exists; use a new directory')
    portraits = extract(args.face_dir.resolve(strict=True))
    document = (migrate_name_parts(json.loads(args.catalogue.read_text(encoding='utf-8-sig')))
                if args.catalogue else expanded_roster(args.manual))
    validate(document, portraits)
    template = Path(__file__).with_name('npc_catalogue.html').read_text(encoding='utf-8')
    script = Path(__file__).with_name('npc_catalogue.js').read_text(encoding='utf-8')
    references = {}
    files = {}
    if args.references:
        cache = args.references.resolve(strict=True)
        source = json.loads((cache / 'references.json').read_text(encoding='utf-8'))
        for name, reference in source['references'].items():
            copied = dict(reference, images=[])
            for item in reference['images']:
                path = (cache / item['path']).resolve(strict=True)
                if not path.is_relative_to(cache / 'images') or path.suffix not in ('.png', '.jpg', '.webp'):
                    raise ValueError('Reference image must stay inside cache/images')
                target = 'reference-images/' + path.name
                files[target] = path
                copied['images'].append(dict(item, path=target))
            references[name] = copied
    payload = json.dumps(dict(portraits=portraits, catalogue=document, references=references), ensure_ascii=False)
    # Keep user text from terminating the inert JSON script element.
    payload = payload.replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')
    rendered = template.replace('/*APPLICATION*/', script).replace('/*PAYLOAD*/', payload)
    destination.mkdir(parents=True)
    if files:
        (destination / 'reference-images').mkdir()
        for target, source in files.items():
            shutil.copyfile(source, destination / target)
    (destination / 'index.html').write_text(rendered, encoding='utf-8')
    (destination / 'catalogue.json').write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    metadata = [{k: v for k, v in p.items() if k != 'image'} for p in portraits]
    (destination / 'portraits.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(output=str(destination), portraits=len(portraits),
                          characters=len(document['characters'])), ensure_ascii=True))


if __name__ == '__main__':
    main()
