"""Read the CD client's planet/fortress model-number tables. Author: 최병호.

Input assets are read-only; save derived catalogues under work/.
pefile is needed only when reading the executable, not in synthetic tests.
"""
import argparse
import json
from pathlib import Path
import re
import struct


def decode_tables(high, low, read_string, exists):
    if len(high) != 520 or len(low) != 520:
        raise ValueError('Expected two 130-entry model tables')
    rows = []
    for model_id, (hp, lp) in enumerate(zip(struct.unpack('<130I', high), struct.unpack('<130I', low))):
        if not hp and not lp:
            continue
        if not hp or not lp:
            raise ValueError('Mismatched model detail tables')
        paths = [read_string(p) for p in (hp, lp)]
        match = re.fullmatch(r'/\.\./data/model/Planets/([py])(\d{3})\.mdx', paths[0])
        if not match or paths[1] != paths[0][:-4] + '_low.mdx':
            raise ValueError('Unexpected model path/profile')
        rows.append({'model_file': model_id, 'family': 'planet' if match[1] == 'p' else 'fortress',
                     'filename': match[1] + match[2] + '.mdx',
                     'high': paths[0][4:], 'low': paths[1][4:],
                     'high_exists': exists(paths[0][4:]), 'low_exists': exists(paths[1][4:])})
    if not rows or rows[0]['model_file'] != 0 or rows[0]['filename'] != 'p000.mdx':
        raise ValueError('Unsupported executable model table')
    return rows


def extract(client_root):
    import pefile
    pe = pefile.PE(str(client_root / 'exe/G7MTClient.exe'), fast_load=True)
    try:
        if pe.FILE_HEADER.Machine != 0x14c or pe.OPTIONAL_HEADER.Magic != 0x10b or pe.OPTIONAL_HEADER.ImageBase != 0x400000:
            raise ValueError('Expected CD client PE32/i386 profile')

        def read_at(va, size):
            offset = pe.get_offset_from_rva(va - pe.OPTIONAL_HEADER.ImageBase)
            raw = bytes(pe.__data__[offset:offset + size])
            if len(raw) != size:
                raise ValueError('Truncated model table/string')
            return raw

        def read_string(va):
            raw = read_at(va, 96)
            if b'\0' not in raw:
                raise ValueError('Unterminated model path')
            return raw.split(b'\0', 1)[0].decode('ascii')

        rows = decode_tables(read_at(0x775108, 520), read_at(0x775310, 520), read_string,
                             lambda path: (client_root / path).is_file())
    finally:
        pe.close()
    return {'profile': 'CD G7MTClient.exe', 'models': rows,
            'warning': 'Model numbers do not identify individual planets or physical class values.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('client_root', type=Path)
    parser.add_argument('output', type=Path, help='Derived JSON under work/')
    args = parser.parse_args()
    if args.output.resolve().is_relative_to(args.client_root.resolve()):
        parser.error('Output must be outside the input client directory')
    result = extract(args.client_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"{len(result['models'])} model slots; {sum(not r['high_exists'] for r in result['models'])} missing high-detail files")


if __name__ == '__main__':
    main()
