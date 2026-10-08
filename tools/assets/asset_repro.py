"""Preview extracted geometry/textures and compare native buffers. Author: 최병호.

Derived images and buffers belong under work/, never in version control.
Geometry previews omit materials, animation and node transforms.
"""
import argparse
import json
import math
from pathlib import Path
import re
import struct


MODEL = re.compile(r'[py][0-9]{3}')
BUFFER = re.compile(r'[py][0-9]{3}-[0-9]+-(?:primary|[12]-[0-9]+)-[0-9]+-vertices')


def read_bounded(path, limit):
    if path.stat().st_size > limit:
        raise ValueError(f'{path.name}: input exceeds {limit} bytes')
    return path.read_bytes()


def read_json(path):
    return json.loads(read_bounded(path, 1024 * 1024).decode('utf-8'))


def local_file(root, name):
    path = (root / name).resolve(strict=True)
    if not path.is_relative_to(root.resolve()):
        raise ValueError('Buffer must remain inside its input directory')
    return path


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high:
        raise ValueError('Invalid geometry count or identifier')
    return value


def buffers(root, prefix, nv, ni, stride):
    nv = integer(nv, 3, 50000)
    ni = integer(ni, 3, 200000)
    stride = integer(stride, 12, 128)
    if ni % 3:
        raise ValueError('Indices must be a triangle list')
    vertices = read_bounded(local_file(root, prefix + '-vertices.bin'), 8 * 1024 * 1024)
    indices = read_bounded(local_file(root, prefix + '-indices.bin'), 400000)
    if len(vertices) != nv * stride or len(indices) != ni * 2:
        raise ValueError('Buffer length differs from metadata')
    points = [struct.unpack_from('<3f', vertices, i * stride) for i in range(nv)]
    faces = list(struct.iter_unpack('<3H', indices))
    if any(not math.isfinite(v) or abs(v) > 100000 for p in points for v in p):
        raise ValueError('Invalid vertex coordinates')
    if any(max(face) >= nv for face in faces):
        raise ValueError('Index exceeds vertex count')
    return {'vertices': vertices, 'indices': indices, 'points': points,
            'faces': faces, 'stride': stride}


def catalogue(path, selected=()):
    rows = read_json(path)['models']
    if not isinstance(rows, list) or not 1 <= len(rows) <= 128:
        raise ValueError('Invalid model catalogue')
    models = {}
    for row in rows:
        name = row['model']
        if not isinstance(name, str) or not MODEL.fullmatch(name) or name in models:
            raise ValueError('Invalid or duplicate model name')
        streams = row['streams']
        if not isinstance(streams, list) or not 1 <= len(streams) <= 128:
            raise ValueError('Invalid stream list')
        models[name] = []
        if selected and name not in selected:
            continue
        seen = set()
        for stream in streams:
            group = integer(stream['group'], 0, 127)
            record = integer(stream['record'], 0, 127)
            prefix = f'{name}-{group}-{record}'
            if prefix in seen:
                raise ValueError('Duplicate stream')
            seen.add(prefix)
            models[name].append(buffers(path.parent, prefix, stream['vertex_count'],
                                       stream['index_count'], stream['stride']) | {'stream': prefix})
    if set(selected) - models.keys():
        raise ValueError('Selected model is absent from catalogue')
    return {name: parts for name, parts in models.items() if parts}


def save_png(image, output, input_root):
    output = output.resolve()
    if output.is_relative_to(input_root.resolve()):
        raise ValueError('Output must be outside the input directory')
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also protects a pre-existing image from replacement.
    with output.open('xb') as target:
        image.save(target, format='PNG')


def geometry_image(models):
    from PIL import Image, ImageDraw
    sheet = Image.new('RGB', (1000, math.ceil(len(models) / 2) * 500), '#151b24')
    draw = ImageDraw.Draw(sheet)
    a, t = math.radians(35), math.radians(25)
    rotation = ((math.cos(a), 0, math.sin(a)),
                (math.sin(a) * math.sin(t), math.cos(t), -math.cos(a) * math.sin(t)),
                (-math.sin(a) * math.cos(t), math.sin(t), math.cos(a) * math.cos(t)))
    for cell, (name, parts) in enumerate(models.items()):
        points = [p for part in parts for p in part['points']]
        center = [(min(p[k] for p in points) + max(p[k] for p in points)) / 2
                  for k in range(3)]
        projected = [[[sum((p[k] - center[k]) * row[k] for k in range(3))
                       for row in rotation] for p in part['points']] for part in parts]
        all_points = [p for part in projected for p in part]
        span = max(max(p[k] for p in all_points) - min(p[k] for p in all_points)
                   for k in (0, 1))
        scale = 430 / span if span else 1
        x, y = cell % 2 * 500, cell // 2 * 500
        triangles = []
        for part_id, (part, projection) in enumerate(zip(parts, projected)):
            for face in part['faces']:
                tri = [projection[index] for index in face]
                u = [tri[1][k] - tri[0][k] for k in range(3)]
                v = [tri[2][k] - tri[0][k] for k in range(3)]
                normal = [u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]]
                length = math.sqrt(sum(c*c for c in normal))
                if length < 1e-12:
                    continue
                shade = min(1, .35 + .65 * abs(sum(n*l for n, l in zip(normal, (-.3, -.5, .8)))) / length)
                colour = tuple(int(c * shade) for c in ((155, 185, 210) if part_id == 0 else (210, 170, 120)))
                screen = [(x + 250 + p[0]*scale, y + 270 - p[1]*scale) for p in tri]
                triangles.append((sum(p[2] for p in tri) / 3, screen, colour))
        for _, screen, colour in sorted(triangles, key=lambda item: item[0]):
            draw.polygon(screen, fill=colour)
        draw.text((x + 15, y + 12), name + ' / geometry only', fill='white')
    return sheet


def texture_image(root):
    from PIL import Image, ImageDraw
    files = sorted(p for p in root.glob('*.bmp') if re.fullmatch(r'p[0-9]{3}\.bmp', p.name))
    if not 1 <= len(files) <= 128:
        raise ValueError('Expected 1..128 planet BMP files')
    sheet = Image.new('RGB', (960, math.ceil(len(files) / 4) * 160), '#202020')
    draw = ImageDraw.Draw(sheet)
    for cell, path in enumerate(files):
        with Image.open(path) as source:
            if source.width * source.height > 16 * 1024 * 1024:
                raise ValueError('Texture exceeds supported dimensions')
            image = source.convert('RGB')
        image.thumbnail((232, 132))
        x, y = cell % 4 * 240, cell // 4 * 160
        sheet.paste(image, (x + 4, y + 24))
        draw.text((x + 4, y + 2), path.stem, fill='white')
    return sheet


def compare(models, event_path):
    events = read_json(event_path)
    if not isinstance(events, list) or not 1 <= len(events) <= 4096:
        raise ValueError('Invalid native event list')
    results, loaded, errors, seen = [], set(), [], set()
    for event in events:
        name = event.get('name', '')
        if name.endswith('-loaded'):
            loaded.add(name[:-7])
        if name.endswith('-error'):
            errors.append(name)
        if not name.endswith('-vertices'):
            continue
        if not BUFFER.fullmatch(name) or name in seen or event['model'] != name[:4]:
            raise ValueError('Invalid or duplicate native buffer name')
        seen.add(name)
        native = buffers(event_path.parent, name[:-9], event['nv'], event['ni'], event['stride'])
        matches = [part['stream'] for part in models.get(event['model'], [])
                   if all(part[key] == native[key] for key in ('vertices', 'indices', 'stride'))]
        results.append({'native': name, 'matches': matches})
    # A pair match is not a claim that every model or stream was captured.
    observed = {row['native'][:4] for row in results}
    complete = bool(results) and not errors and observed == loaded and all(row['matches'] for row in results)
    return {'complete_observed_pairs': complete, 'pairs': results,
            'loaded': sorted(loaded), 'errors': errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    geometry = commands.add_parser('geometry')
    geometry.add_argument('catalogue', type=Path)
    geometry.add_argument('output', type=Path)
    geometry.add_argument('--model', action='append', default=[])
    textures = commands.add_parser('textures')
    textures.add_argument('source', type=Path)
    textures.add_argument('output', type=Path)
    comparison = commands.add_parser('compare')
    comparison.add_argument('catalogue', type=Path)
    comparison.add_argument('events', type=Path)
    args = parser.parse_args()
    try:
        if args.command == 'geometry':
            save_png(geometry_image(catalogue(args.catalogue, args.model)), args.output, args.catalogue.parent)
        elif args.command == 'textures':
            save_png(texture_image(args.source), args.output, args.source)
        else:
            result = compare(catalogue(args.catalogue), args.events)
            print(json.dumps(result, indent=2))
            return 0 if result['complete_observed_pairs'] else 1
    except (ValueError, OSError, KeyError, TypeError, ImportError) as error:
        parser.error(str(error))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
