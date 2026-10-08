"""Extract the annotated galaxy map on web-manual PDF page 101. Author: 최병호.

The map's Text annotations carry system and base names, absent from get_text().
Original content and derived full catalogues belong under work/, not in Git.
PDF/image dependencies are imported only for extraction, not pure-data tests.
"""
import argparse
import io
import json
import math
from pathlib import Path

from galaxy_map import decode_anchors, decode_names


def parse_note(content):
    lines = [line.strip() for line in content.splitlines() if line.strip()]
    if not lines or not (lines[0].endswith('星系') or lines[0] == '太陽系'):
        raise ValueError('Expected system-name annotation')
    name = lines[0][:-2] if lines[0].endswith('星系') else lines[0]
    if not name:
        raise ValueError('Empty system name')
    bases = []
    for line in lines[1:]:
        if line.startswith('◎要塞'):
            kind, base_name = 'fortress', line[3:]
        elif line.startswith('○惑星'):
            kind, base_name = 'planet', line[3:]
        elif line.startswith('○') and name == '太陽系':
            kind, base_name = 'planet', line[1:]
        else:
            raise ValueError('Unrecognized base notation')
        if not base_name or base_name in {base['name'] for base in bases}:
            raise ValueError('Empty or duplicate base name')
        bases.append({'name': base_name, 'kind': kind, 'kind_code': 1 if kind == 'fortress' else 0, 'physical_class': None,
                      'model_file': None, 'orbit': None})
    return name, bases


def grid_at(point, origin, pitch, tolerance=0.2):
    if not 0 < tolerance < 0.5 or not all(math.isfinite(v) for v in (*point, *origin, *pitch)) or min(pitch) <= 0:
        raise ValueError('Invalid grid calibration')
    fractional = [(point[i] - origin[i]) / pitch[i] - 0.5 for i in range(2)]
    x, y = map(round, fractional)
    error = max(abs(fractional[0] - x), abs(fractional[1] - y))
    if not 0 <= x < 100 or not 0 <= y < 50 or error > tolerance:
        raise ValueError('Star does not align with a unique grid cell')
    return {'x': x, 'y': y, 'index': y * 100 + x}, error


def reconcile(systems, anchors, names):
    lookup = {entry['name']: entry['kind'] for entry in names['entries'][3:]}
    by_grid = {}
    for anchor in anchors['anchors']:
        grid = anchor['candidate_grid_scale_2']
        if anchor['node'].startswith('star_') and grid:
            key = grid['index']
            if key in by_grid:
                raise ValueError('Duplicate model star grid')
            by_grid[key] = anchor['node']
    seen_names, seen_cells, matched = set(), set(), set()
    result = []
    for source in systems:
        row = dict(source)
        name, cell = row['name'], row['grid']['index']
        if name not in lookup or name in seen_names or cell in seen_cells:
            raise ValueError('Unknown/duplicate system or duplicate grid cell')
        seen_names.add(name)
        seen_cells.add(cell)
        node = by_grid.get(cell)
        if node:
            matched.add(node)
        row.update(kind=lookup[name], model_node=node,
                   stellar_class=node.rsplit('_', 1)[1] if node else None)
        result.append(row)
    if matched != set(by_grid.values()):
        raise ValueError('Manual/model alignment does not cover every model star')
    return {'width': 100, 'height': 50, 'systems': sorted(result, key=lambda r: r['kind']),
            'system_count': len(result), 'base_count': sum(len(r['bases']) for r in result),
            'matched_model_stars': len(matched),
            'names_without_manual_position': sorted(set(lookup) - seen_names),
            'unmatched_manual_systems': sorted(row['name'] for row in result if row['model_node'] is None)}


def star_center(image, rect, image_to_page):
    import numpy as np
    from scipy.ndimage import gaussian_filter
    a, b, c, d, e, f = image_to_page
    if abs(b) > 1e-5 or abs(c) > 1e-5 or a <= 0 or d <= 0:
        raise ValueError('Unsupported rotated/sheared map image')
    # Comment icon is above its star; locate the actual luminous centre nearby.
    cx = ((rect[0] + rect[2]) / 2 - e) / a
    cy = (rect[3] - 5 - f) / d
    left, top = int(cx) - 11, int(cy) - 11
    if left < 0 or top < 0 or left + 23 > image.shape[1] or top + 23 > image.shape[0]:
        raise ValueError('Annotation outside map image')
    patch = image[top:top + 23, left:left + 23].astype(float)
    light = patch.sum(axis=2)
    yy, xx = np.indices(light.shape)
    score = gaussian_filter(light, 0.65) - 1.7 * ((xx - 11) ** 2 + (yy - 11) ** 2)
    py, px = np.unravel_index(score.argmax(), score.shape)
    if light[py, px] < 450:
        raise ValueError('No sufficiently bright star near annotation')
    cut = light[max(0, py - 2):py + 3, max(0, px - 2):px + 3]
    yy, xx = np.indices(cut.shape)
    weights = np.maximum(cut - np.median(light), 0) ** 2
    x = left + max(0, px - 2) + float((xx * weights).sum() / weights.sum())
    y = top + max(0, py - 2) + float((yy * weights).sum() / weights.sum())
    return [x, y]


def extract(pdf_path, mdx_path, messages_path):
    import numpy as np
    import pymupdf
    from PIL import Image
    with pymupdf.open(pdf_path) as document:
        if len(document) != 101:
            raise ValueError('Expected the 101-page web manual')
        page = document[100]
        images = page.get_images(full=True)
        if len(images) != 1 or images[0][2:4] != (844, 579):
            raise ValueError('Unexpected map image layout')
        image_ref = images[0][0]
        pixels = np.array(Image.open(io.BytesIO(document.extract_image(image_ref)['image'])).convert('RGB'))
        placements = page.get_image_rects(image_ref, transform=True)
        if len(placements) != 1:
            raise ValueError('Ambiguous map image placement')
        transform = placements[0][1] * page.rotation_matrix
        transform = [transform.a / 844, transform.b / 844, transform.c / 579,
                     transform.d / 579, transform.e, transform.f]
        rows, max_error = [], 0
        # Visible full grid: boundaries x=36..756, y=110..470 in the source JPEG.
        # Raster rounding is tolerated; all 79 MDX stars must independently match.
        origin, pitch = [36.0, 110.0], [7.2, 7.2]
        for annotation in page.annots() or []:
            if annotation.type[1] != 'Text':
                continue
            name, bases = parse_note(annotation.info['content'])
            rect = annotation.rect * page.rotation_matrix
            center = star_center(pixels, list(rect), transform)
            grid, error = grid_at(center, origin, pitch)
            max_error = max(max_error, error)
            rows.append({'name': name, 'bases': bases, 'grid': grid,
                         'annotation_xref': annotation.xref, 'image_center': center,
                         'annotation_color': annotation.colors['stroke']})
        if len(rows) != 80:
            raise ValueError('Expected 80 named system annotations')
    result = reconcile(rows, decode_anchors(mdx_path.read_bytes()), decode_names(messages_path.read_bytes()))
    result.update(source_page=101, calibration={'image_origin': origin, 'cell_pitch': pitch,
                                              'max_cell_error': max_error},
                  unknown_fields=['base physical class', 'base model file', 'base orbit', 'original server object IDs'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pdf', type=Path)
    parser.add_argument('client_root', type=Path)
    parser.add_argument('output', type=Path, help='Derived JSON under work/')
    args = parser.parse_args()
    inputs = [args.pdf, args.client_root / 'data/model/strategy/Null_galaxy.mdx',
              args.client_root / 'data/MsgDat/constmsg.dat']
    if args.output.resolve() in {p.resolve() for p in inputs} or args.output.resolve().is_relative_to(args.client_root.resolve()):
        parser.error('Output must not overwrite an input or enter the client directory')
    result = extract(*inputs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"{result['system_count']} systems, {result['base_count']} bases; {result['matched_model_stars']} model stars matched")


if __name__ == '__main__':
    main()
