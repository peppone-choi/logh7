"""Offline NPC worksheet and read-only CD portrait extraction. Author: 최병호.

Generated names, portraits and JSON belong under work/, never in Git.
This tool does not change a running server or claim to recover original stats.
"""
import argparse
import base64
import csv
import json
from pathlib import Path
import re
import struct
import zlib


ABILITIES = ('leadership', 'politics', 'administration', 'intelligence',
             'command', 'mobility', 'attack', 'defense')
TABLES = (('oem', 0, 200, 'empire'), ('oam', 200, 150, 'alliance'),
          ('o', 350, 100, 'other'))
WORK = Path(__file__).resolve().parents[2] / 'work'
NAMES = Path(__file__).resolve().parents[2] / 'l10n/npc-names.ko.json'
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


def blank_character(key, faction, name=''):
    return dict(key=key, faction=faction, name=name, manual_holder='', posts=[],
                face_id=None, face_evidence=dict(status='unknown', source=''),
                abilities={key: None for key in ABILITIES},
                abilities_evidence=dict(status='unknown', source=''),
                rank='', location='', ai_profile='', notes='')


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
        if 'name_translation' in row:
            translation = row['name_translation']
            require(type(translation) is dict and translation.get('status') in ('adopted', 'pending') and
                    all(type(translation.get(k)) is str and len(translation[k]) <= 4000
                        for k in ('japanese', 'korean', 'source', 'note')), 'Invalid name translation')
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
    parser.add_argument('--output', type=Path, required=True, help='New directory under work/')
    args = parser.parse_args()
    destination = args.output.resolve()
    if not destination.is_relative_to(WORK.resolve()) or destination == WORK.resolve():
        parser.error('Output must be a new subdirectory under repository work/')
    # Resolve symlinks before checking ancestry; output can never contain an input.
    if destination.exists():
        parser.error('Output directory already exists; use a new directory')
    portraits = extract(args.face_dir.resolve(strict=True))
    document = (json.loads(args.catalogue.read_text(encoding='utf-8-sig'))
                if args.catalogue else manual_roster(args.manual))
    validate(document, portraits)
    template = Path(__file__).with_name('npc_catalogue.html').read_text(encoding='utf-8')
    script = Path(__file__).with_name('npc_catalogue.js').read_text(encoding='utf-8')
    payload = json.dumps(dict(portraits=portraits, catalogue=document), ensure_ascii=False)
    # Keep user text from terminating the inert JSON script element.
    payload = payload.replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')
    rendered = template.replace('/*APPLICATION*/', script).replace('/*PAYLOAD*/', payload)
    destination.mkdir(parents=True)
    (destination / 'index.html').write_text(rendered, encoding='utf-8')
    (destination / 'catalogue.json').write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    metadata = [{k: v for k, v in p.items() if k != 'image'} for p in portraits]
    (destination / 'portraits.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(output=str(destination), portraits=len(portraits),
                          characters=len(document['characters'])), ensure_ascii=True))


if __name__ == '__main__':
    main()
