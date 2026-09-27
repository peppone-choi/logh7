"""LOGH7 MsgDat codec and copy-only locale patcher. Author: 최병호."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import struct


def sha(data):
    return hashlib.sha256(data).hexdigest()


def decode(data, encoding='cp932'):
    if len(data) < 16:
        raise ValueError('truncated header')
    magic, reserved, a, b = struct.unpack_from('<4sIII', data)
    if reserved != 0:
        raise ValueError('nonzero reserved header')
    result = dict(magic=magic.decode('ascii'), reserved=reserved, encoding=encoding)
    pos = 16
    raw = []
    if magic == b'HFWR':
        count, groups = a, b
        size = ((groups + 3) & ~3) * 4
        if size > len(data) - pos:
            raise ValueError('truncated index')
        indices = list(struct.unpack_from(f'<{size // 4}I', data, pos))
        result.update(group_count=groups, indices=indices)
        active = indices[:groups]
        if active and (active[0] != 0 or active[-1] != count or active != sorted(active)):
            raise ValueError('invalid group indices')
        if any(indices[groups:]):
            raise ValueError('nonzero index padding')
        pos += size
        if count > len(data) - pos:
            raise ValueError('impossible string count')
        for _ in range(count):
            end = data.find(b'\0', pos)
            if end < 0:
                raise ValueError('unterminated string')
            raw.append(data[pos:end])
            pos = end + 1
    elif magic == b'GFWR':
        result.update(seed=a, encoding='utf-16-le')
        if b > (len(data) - pos) // 4:
            raise ValueError('impossible word count')
        for _ in range(b):
            if pos + 4 > len(data):
                raise ValueError('truncated word length')
            length, = struct.unpack_from('<I', data, pos)
            pos += 4
            if length * 2 > len(data) - pos:
                raise ValueError('truncated UTF-16 word')
            raw.append(data[pos:pos + length * 2])
            pos += length * 2
    else:
        raise ValueError('unknown magic')
    if pos != len(data):
        raise ValueError('unexpected trailing bytes')
    result['strings'] = [x.decode(result['encoding'], errors='strict') for x in raw]
    # CP932 has duplicate Unicode mappings; retain bytes for unedited entries.
    result['raw_hex'] = [x.hex() for x in raw]
    result['source_sha256'] = sha(data)
    return result


def tokens(text):
    return Counter(re.findall(r'\$[^$]*\$', text))


def encode(document, reference=None, encoding=None):
    codec = 'utf-16-le' if document['magic'] == 'GFWR' else encoding or document['encoding']
    strings = document['strings']
    if reference is not None:
        keys = ('magic', 'reserved', 'group_count', 'indices', 'seed')
        if any(document.get(k) != reference.get(k) for k in keys):
            raise ValueError('reference structure changed')
        if document.get('source_sha256') != reference['source_sha256']:
            raise ValueError('reference hash mismatch')
        if len(strings) != len(reference['strings']):
            raise ValueError('string count changed')
        for i, (old, new) in enumerate(zip(reference['strings'], strings)):
            if tokens(old) != tokens(new) or old.count('$') != new.count('$'):
                raise ValueError(f'placeholder tokens changed at string {i}')
    baseline = reference or document
    raw = []
    for i, value in enumerate(strings):
        if document['magic'] == 'HFWR' and '\0' in value:
            raise ValueError(f'embedded NUL at string {i}')
        original = bytes.fromhex(baseline['raw_hex'][i])
        if codec == baseline['encoding'] and original.decode(codec) == value:
            raw.append(original)
        else:
            raw.append(value.encode(codec, errors='strict'))
    if document['magic'] == 'HFWR':
        g = document['group_count']
        indices = document['indices']
        if len(indices) != ((g + 3) & ~3):
            raise ValueError('invalid index count')
        output = struct.pack('<4sIII', b'HFWR', 0, len(raw), g)
        output += struct.pack(f'<{len(indices)}I', *indices)
        output += b''.join(x + b'\0' for x in raw)
    elif document['magic'] == 'GFWR':
        output = struct.pack('<4sIII', b'GFWR', 0, document['seed'], len(raw))
        output += b''.join(struct.pack('<I', len(x) // 2) + x for x in raw)
    else:
        raise ValueError('unknown magic')
    decode(output, codec)  # Validate generated structure before writing.
    return output


def write_new(path, data):
    path = Path(path).resolve()
    protected = Path('E:/logh7-original').resolve()
    if path == protected or protected in path.parents:
        raise ValueError('original tree is read-only')
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(data)


def write_json(path, obj):
    write_new(path, (json.dumps(obj, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))


def va_offset(data, va):
    if data[:2] != b'MZ':
        raise ValueError('not PE')
    pe, = struct.unpack_from('<I', data, 0x3c)
    if data[pe:pe+4] != b'PE\0\0':
        raise ValueError('invalid PE signature')
    sections, = struct.unpack_from('<H', data, pe + 6)
    optional_size, = struct.unpack_from('<H', data, pe + 20)
    opt = pe + 24
    if struct.unpack_from('<H', data, opt)[0] != 0x10b:
        raise ValueError('expected PE32')
    base, = struct.unpack_from('<I', data, opt + 28)
    rva = va - base
    for i in range(sections):
        s = opt + optional_size + i * 40
        _, address, size, offset = struct.unpack_from('<IIII', data, s + 8)
        if address <= rva < address + size:
            return offset + rva - address
    raise ValueError(f'VA not file-backed: {va:#x}')


def patch(source, output, font='굴림', expected_sha256=None):
    source, output = Path(source), Path(output)
    data = source.read_bytes()
    before = sha(data)
    if expected_sha256 and before.lower() != expected_sha256.lower():
        raise ValueError('source hash mismatch')
    changed = bytearray(data)
    font_bytes = font.encode('cp949', errors='strict')
    if not font_bytes or b'\0' in font_bytes or len(font_bytes) > 13:
        raise ValueError('font must occupy 1..13 CP949 bytes without NUL')
    changes = []
    for va, old, new in (
        (0x76e3fc, b'Japanese\0', b'Korean\0\0\0'),
        (0x76e240, 'ＭＳ ゴシック'.encode('cp932') + b'\0', font_bytes.ljust(14, b'\0')),
    ):
        offset = va_offset(data, va)
        if data[offset:offset + len(old)] != old:
            raise ValueError(f'expected original bytes absent at {va:#x}')
        changed[offset:offset + len(old)] = new
        changes.append(dict(va=hex(va), offset=hex(offset), old=old.hex(), new=new.hex()))
    report_path = output.with_suffix(output.suffix + '.patch.json')
    if output.resolve() == source.resolve() or output.exists() or report_path.exists():
        raise ValueError('output/report must be new paths')
    if sha(source.read_bytes()) != before:
        raise ValueError('source changed during processing')
    write_new(output, changed)
    report = dict(source=str(source.resolve()), output=str(output.resolve()), source_sha256=before,
                  output_sha256=sha(changed), source_unchanged=sha(source.read_bytes()) == before,
                  font=font, changes=changes, display_validated=False)
    write_json(report_path, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('extract')
    p.add_argument('source', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--encoding', choices=['cp932', 'cp949'], default='cp932')
    p = subs.add_parser('build')
    p.add_argument('document', type=Path); p.add_argument('reference', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--encoding', choices=['cp932', 'cp949'], default='cp932')
    p = subs.add_parser('roundtrip')
    p.add_argument('directory', type=Path); p.add_argument('output', type=Path)
    p = subs.add_parser('patch')
    p.add_argument('source', type=Path); p.add_argument('output', type=Path)
    p.add_argument('--font', default='굴림'); p.add_argument('--expected-sha256')
    args = parser.parse_args()
    if args.command == 'extract':
        write_json(args.output, decode(args.source.read_bytes(), args.encoding))
    elif args.command == 'build':
        doc = json.loads(args.document.read_text(encoding='utf-8'))
        write_new(args.output, encode(doc, decode(args.reference.read_bytes()), args.encoding))
    elif args.command == 'patch':
        print(json.dumps(patch(args.source, args.output, args.font, args.expected_sha256), ensure_ascii=True))
    else:
        files = sorted(args.directory.glob('*.dat'))
        if not files:
            raise ValueError('no DAT files')
        report = []
        for path in files:
            data = path.read_bytes()
            doc = decode(data)
            write_json(args.output / 'text' / (path.name + '.json'), doc)
            # Exercise JSON serialization, not just an in-memory byte roundtrip.
            loaded = json.loads((args.output / 'text' / (path.name + '.json')).read_text(encoding='utf-8'))
            rebuilt = encode(loaded, decode(data))
            write_new(args.output / 'rebuilt' / path.name, rebuilt)
            item = dict(file=path.name, magic=doc['magic'], strings=len(doc['strings']),
                        source_sha256=sha(data), rebuilt_sha256=sha(rebuilt),
                        identical=data == rebuilt, source_unchanged=sha(path.read_bytes()) == sha(data))
            report.append(item)
        write_json(args.output / 'roundtrip.json', report)
        print(json.dumps(dict(files=len(report), identical=sum(x['identical'] for x in report))))
        if not all(x['identical'] and x['source_unchanged'] for x in report):
            raise SystemExit(1)


if __name__ == '__main__':
    main()
