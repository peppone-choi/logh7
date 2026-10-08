"""Extract LOGH7 map payloads and Null_galaxy MDX anchors. Author: 최병호.

Inputs are read-only; output contains derived data and belongs under work/.
This is the observed LOGH7 MDX layout, not a general MDX file reader.
"""
import argparse
import json
import math
from pathlib import Path
import struct
import sys


class Reader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def take(self, size):
        if size < 0 or self.offset + size > len(self.data):
            raise ValueError("Truncated map payload")
        value = self.data[self.offset:self.offset + size]
        self.offset += size
        return value

    def unpack(self, fmt):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))

    def end(self):
        if self.offset != len(self.data):
            raise ValueError("Trailing map payload bytes")


def decode_types(data):
    r = Reader(data)
    count, = r.unpack('>B')
    if count > 100:
        raise ValueError("Too many grid types")
    result = [dict(zip(('kind', 'type', 'fixedstar'), r.unpack('>BBB'))) for _ in range(count)]
    r.end()
    return result


def decode_grid(data):
    r = Reader(data)
    width, height, size = r.unpack('>BBH')
    if not width or not height or width * height > 5000 or not 0 < size <= 5000 or size % 2:
        raise ValueError("Invalid RLE grid header")
    packed = r.take(size)
    r.end()
    cells = bytearray()
    for count, value in zip(packed[::2], packed[1::2]):
        if not count or len(cells) + count > width * height:
            raise ValueError("Invalid RLE run")
        cells.extend([value] * count)
    if len(cells) != width * height:
        raise ValueError("RLE grid length mismatch")
    return {'width': width, 'height': height,
            'rows': [list(cells[y * width:(y + 1) * width]) for y in range(height)]}


def decode_bases(data):
    r = Reader(data)
    count, = r.unpack('>H')
    if count > 350:
        raise ValueError("Too many bases")
    result = []
    for _ in range(count):
        ident, grid, model, kind, length = r.unpack('>IHHHB')
        if length > 13:
            raise ValueError("Base name exceeds client buffer")
        raw_name = r.take(length * 2)
        if raw_name and not raw_name.endswith(b'\0\0'):
            raise ValueError("Base name lacks terminator")
        name = raw_name[:-2].decode('utf-16-be') if raw_name else ''
        if '\0' in name:
            raise ValueError("Embedded base name terminator")
        class_, radius, cycle, direction, angle, diameter = r.unpack('>BfIBff')
        if not all(math.isfinite(v) for v in (radius, angle, diameter)):
            raise ValueError("Non-finite base geometry")
        result.append(dict(id=ident, grid=grid, model_file=model, kind=kind, name=name,
                           class_=class_, revolution_radius=radius, revolution_cycle=cycle,
                           revolution_direction=direction, revolution_init_angle=angle,
                           diameter=diameter))
    r.end()
    return result


def decode_anchors(data):
    if len(data) < 0x50:
        raise ValueError("Truncated MDX header")
    header = struct.unpack_from('<20I', data)
    base = header[0] - 0x50

    def region(pointer, size):
        offset = pointer - base
        if offset < 0x50 or size < 0 or offset + size > len(data):
            raise ValueError("MDX pointer outside file")
        return offset

    node_count, channel_count = header[1], header[5]
    if not 0 < node_count <= 100 or channel_count != node_count * 9:
        raise ValueError("Expected static Null_galaxy nodes with nine channels each")
    nodes = region(header[0], node_count * 0xe8)
    channels = region(header[4], channel_count * 28)
    result, names = [], set()
    for index in range(node_count):
        node = nodes + index * 0xe8
        raw_name = data[node + 8:node + 0x88]
        if b'\0' not in raw_name:
            raise ValueError("Unterminated MDX node name")
        name = raw_name.split(b'\0', 1)[0].decode('ascii')
        if not name or name in names:
            raise ValueError("Empty or duplicate MDX node name")
        names.add(name)
        binding_pointer, binding_count = struct.unpack_from('<II', data, node + 0x90)
        if binding_count != 1:
            raise ValueError("Expected one static motion binding per node")
        binding = region(binding_pointer, 48)
        ids = struct.unpack_from('<9I', data, binding + 12)
        values = []
        for kind, channel_id in enumerate(ids):
            if channel_id >= channel_count:
                raise ValueError("Invalid MDX channel index")
            pointer, count, actual_kind = struct.unpack_from('<III', data, channels + channel_id * 28 + 8)
            if count != 1 or actual_kind != kind:
                raise ValueError("Expected one static key per ordered channel")
            key = region(pointer, 36)
            time, value = struct.unpack_from('<ff', data, key + 4)
            if time != 0 or not math.isfinite(value):
                raise ValueError("Expected finite time-zero MDX key")
            values.append(value)
        if values[3:6] != [0, 0, 0] or values[6:] != [1, 1, 1]:
            raise ValueError("Rotated/scaled node requires unsupported transform composition")
        x, y, z = values[:3]
        # Candidate alignment only: all observed anchors align at scale 2.
        gx, gy = 2 * x + 49.5, 24.5 - 2 * z
        candidate = None
        if y == 0 and gx.is_integer() and gy.is_integer() and 0 <= gx < 100 and 0 <= gy < 50:
            candidate = {'x': int(gx), 'y': int(gy), 'index': int(gy) * 100 + int(gx)}
        result.append({'node': name, 'position': [x, y, z], 'candidate_grid_scale_2': candidate})
    return {'node_count': node_count, 'anchors': result,
            'warning': 'Model node numbers are not confirmed star-system IDs; scale-2 grid alignment is a candidate.'}


def decode_names(data):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'l10n'))
    from msgdat import decode
    table = decode(data)
    if len(table['indices']) < 26:
        raise ValueError('Missing constmsg map-name group 24')
    start, end = table['indices'][24:26]
    names = table['strings'][start:end]
    return {'group': 24, 'non_system_entries': 3, 'system_name_count': max(0, len(names) - 3),
            'entries': [{'kind': i, 'name': name} for i, name in enumerate(names)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('format', choices=['mdx', 'names', '0313', '0315', '031d'])
    parser.add_argument('input', type=Path, help='MDX file or decrypted message body, excluding opcode')
    parser.add_argument('output', type=Path, help='Derived JSON; use a work/ path')
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve():
        parser.error('Input and output must differ')
    decoder = {'mdx': decode_anchors, 'names': decode_names, '0313': decode_types,
               '0315': decode_grid, '031d': decode_bases}[args.format]
    result = decoder(args.input.read_bytes())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
