"""Extract bounded CD-client MDX render-stream candidates. Author: 최병호.

This is a signature scanner for the observed planet/fortress assets, not a
general MDX parser. Serialized heap pointers are not file offsets. Output omits
materials, animation and node transforms; raw geometry is not a game screenshot.
Keep all derived asset data outside version control, normally under work/.
"""
import argparse
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import struct


FORMATS = {0x10102: 24, 0x10112: 36, 0x12112: 72}
MAX_FILE_SIZE = 32 * 1024 * 1024


@dataclass(frozen=True)
class Stream:
    group: int
    record: int
    header_offset: int
    vertex_offset: int
    index_offset: int
    remap_offset: int
    vertex_count: int
    index_count: int
    flags: int
    category: int
    stride: int
    material: int
    vertices: bytes
    indices: bytes

    def positions(self):
        for offset in range(0, len(self.vertices), self.stride):
            yield struct.unpack_from('<3f', self.vertices, offset)

    def summary(self):
        points = list(self.positions())
        return {key: getattr(self, key) for key in (
            'group', 'record', 'header_offset', 'vertex_offset', 'index_offset',
            'remap_offset', 'vertex_count', 'index_count', 'flags', 'category',
            'stride', 'material')} | {
                'triangles': self.index_count // 3,
                'bounds': [[fn(p[axis] for p in points) for axis in range(3)]
                           for fn in (min, max)]}


def _header(data, offset):
    if offset + 36 > len(data):
        return None
    row = struct.unpack_from('<9I', data, offset)
    if (row[5] not in FORMATS or row[7] != FORMATS[row[5]]
            or row[6] not in (0, 1, 3) or row[8] > 30
            or not 3 <= row[1] <= 50000
            or not 3 <= row[3] <= 200000 or row[3] % 3
            or min(row[0], row[2], row[4]) < 0x10000):
        return None
    return row


def _group(data, offset, group):
    headers, cursor = [], offset
    while (row := _header(data, cursor)) is not None:
        headers.append(row)
        if len(headers) > 128:
            return [], cursor + 36
        cursor += 36
    payload_start = cursor
    streams = []
    for i, row in enumerate(headers):
        nv, ni, stride = row[1], row[3], row[7]
        index_offset = cursor + nv * stride
        remap_offset = index_offset + ni * 2
        end = remap_offset + nv * 2
        if end > len(data):
            return [], payload_start
        vertices = data[cursor:index_offset]
        indices = data[index_offset:remap_offset]
        stream = Stream(group, i, offset + i * 36, cursor, index_offset,
                        remap_offset, nv, ni, row[5], row[6], stride, row[8],
                        vertices, indices)
        if (any(not math.isfinite(v) or abs(v) > 100000
                for point in stream.positions() for v in point)
                or max(v[0] for v in struct.iter_unpack('<H', indices)) >= nv):
            return [], payload_start
        streams.append(stream)
        cursor = end
    return streams, cursor


def scan(data):
    """Return validated candidates; unmatched/invalid regions remain unparsed."""
    if len(data) > MAX_FILE_SIZE:
        raise ValueError('Input exceeds the supported 32 MiB profile')
    streams, offset, group = [], 0, 0
    while offset + 36 <= len(data):
        if not _header(data, offset):
            offset += 2
            continue
        found, end = _group(data, offset, group)
        streams.extend(found)
        group += bool(found)
        offset = end + end % 2
    return streams


def write_obj(path, streams):
    # Mesh-local coordinates only: node hierarchy transforms are not applied.
    with path.open('w', encoding='ascii', newline='\n') as target:
        target.write('# MDX stream candidates; no materials or node transforms\n')
        base = 1
        for stream in streams:
            target.write(f'o group_{stream.group}_record_{stream.record}\n')
            for point in stream.positions():
                target.write('v ' + ' '.join(format(v, '.9g') for v in point) + '\n')
            for triangle in struct.iter_unpack('<3H', stream.indices):
                target.write('f ' + ' '.join(str(v + base) for v in triangle) + '\n')
            base += stream.vertex_count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='One MDX file or Planets directory')
    parser.add_argument('output', type=Path, help='Derived output directory under work/')
    parser.add_argument('--buffers', action='store_true', help='Export raw vertex/index buffers')
    parser.add_argument('--obj', action='store_true', help='Export geometry-only OBJ previews')
    args = parser.parse_args()
    input_root = args.source if args.source.is_dir() else args.source.parent
    if args.output.resolve().is_relative_to(input_root.resolve()):
        parser.error('Output must be outside the source directory')
    paths = ([args.source] if args.source.is_file() else
             sorted(p for p in args.source.glob('*.mdx')
                    if re.fullmatch(r'[py]\d{3}\.mdx', p.name)))
    if not paths:
        parser.error('No supported input files')
    models = []
    for path in paths:
        if path.stat().st_size > MAX_FILE_SIZE:
            parser.error(f'{path.name}: input exceeds 32 MiB')
        streams = scan(path.read_bytes())
        if not streams:
            parser.error(f'{path.name}: no validated stream candidates')
        models.append((path.stem, streams))
    args.output.mkdir(parents=True, exist_ok=True)
    catalogue = {'profile': 'CD MDX bounded render-stream candidates',
                 'warning': 'Partial scan; no materials, animation, node transforms or named-base assignments.',
                 'models': []}
    for model, streams in models:
        catalogue['models'].append({'model': model, 'streams': [s.summary() for s in streams]})
        if args.buffers:
            for stream in streams:
                prefix = args.output / f'{model}-{stream.group}-{stream.record}'
                Path(str(prefix) + '-vertices.bin').write_bytes(stream.vertices)
                Path(str(prefix) + '-indices.bin').write_bytes(stream.indices)
        if args.obj:
            write_obj(args.output / (model + '.obj'), streams)
    (args.output / 'geometry.json').write_text(
        json.dumps(catalogue, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{len(models)} models; {sum(len(s) for _, s in models)} stream candidates')


if __name__ == '__main__':
    main()
