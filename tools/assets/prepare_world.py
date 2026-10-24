"""Prepare an external local world seed from reviewed manual extraction. Author: 최병호.

Placements/names come from the extraction. Unmapped cells, star model numbering,
planet models/classes/orbits and initial spawn policy are local defaults, not recovered originals.
No input is modified. Generated original names belong in work/, never in Git.
"""
import argparse
import json
import math
from pathlib import Path


PLANET_MODELS = {row * 10 + variant for row in range(11)
                 for variant in range(3 if row == 10 else 2)}
HABITABLE_MODELS = (60, 61, 80, 81, 100, 101, 102)
CAPITALS = {(2487, 'オーディン'): 60, (2015, 'ハイネセン'): 61}


def designed_model(grid, name, ordinal):
    """User-authorized fallback, never a claim about original server assignments."""
    if (grid, name) in CAPITALS:
        return CAPITALS[grid, name]
    if grid == 375:
        solar = {'水星': 0, '金星': 70, '地球': 101, '火星': 1}
        if name in solar:
            return solar[name]
    # Catalogue bases are settlements: keep one terrestrial body per system.
    # Outer bodies can represent mining outposts and gas-collection stations.
    if ordinal == 0:
        return HABITABLE_MODELS[grid % len(HABITABLE_MODELS)]
    palette = (0, 1, 10, 11, 20, 21, 30, 31, 40, 41, 50, 51, 70, 71, 90, 91)
    return palette[(grid + ordinal * 5) % len(palette)]


def appearance_overrides(document):
    """Visual families follow the CD pNNN model/atlas numbering, not combat class."""
    if document is None:
        return {}, 'unknown-model-default'
    if document.get('schema') != 1 or not isinstance(document.get('source'), str) or not document['source'].strip():
        raise ValueError('Appearance map needs schema=1 and an explicit source')
    result = {}
    for row in document['bases']:
        key = row['grid'], row['name']
        family, variant = row['planet_type'], row.get('variant', 0)
        if (type(key[0]) is not int or not 0 <= key[0] < 5000 or not isinstance(key[1], str)
                or type(family) is not int or type(variant) is not int
                or not 0 <= family <= 10 or not 0 <= variant <= (2 if family == 10 else 1)):
            raise ValueError('Invalid planet type, variant or target')
        if key in result:
            raise ValueError('Duplicate appearance target')
        result[key] = family * 10 + variant
    return result, document['source']


def prepare(manual, appearances=None, design_fallback=False):
    if (manual['width'], manual['height']) != (100, 50):
        raise ValueError('Expected 100 x 50 map')
    systems = manual['systems']
    if len(systems) != 80 or sum(len(s['bases']) for s in systems) != 287:
        raise ValueError('Expected reviewed 80-system / 287-base extraction')
    result = dict(schema=1, systems=len(systems), bases=287,
                  **{'unmapped.cells': 'local-space-default',
                     'unverified.physical': 'local-model-class-orbit-defaults',
                     'start.empire': 2487, 'start.alliance': 2015})
    # Six fortress model names were independently confirmed by the user.
    fortress_models = {'イゼルローン': 110, 'ガイエスブルク': 112, 'ガルミッシュ': 113,
                       'レンテンベルグ': 114, 'ダヤン・ハーン': 115, 'ルドミラ': 119}
    overrides, appearance_source = appearance_overrides(appearances)
    used = set()
    base_index = 0
    for i, system in enumerate(sorted(systems, key=lambda s: s['kind'])):
        for key, value in dict(kind=system['kind'], grid=system['grid']['index'],
                               star='OBAFGKM'.index(system.get('stellar_class') or 'G'), name=system['name']).items():
            result[f'system.{i}.{key}'] = value
        color = tuple(round(x, 3) for x in system.get('annotation_color', []))
        power = {(0.0, 1.0, 1.0): 2, (0.502, 1.0, 0.502): 3, (1.0, 1.0, 0.0): 1}.get(color, 0)
        result[f'system.{i}.power'] = power
        result[f'system.{i}.ownership-source'] = 'manual-map-annotation' if power else 'unknown'
        for ordinal, base in enumerate(system['bases']):
            target = system['grid']['index'], base['name']
            model = base.get('model_file')
            source = 'manual-extraction' if model is not None else 'unknown-model-default'
            if target in overrides:
                if base['kind_code'] != 0:
                    raise ValueError('Planet appearance cannot target a fortress')
                if model is not None and model != overrides[target]:
                    raise ValueError('Appearance contradicts an existing model assignment')
                model, source = overrides[target], appearance_source
                used.add(target)
            if model is None:
                if base['name'] in fortress_models:
                    model = fortress_models[base['name']]
                    source = 'confirmed-fortress-model'
                elif design_fallback and base['kind_code'] == 0:
                    model = designed_model(target[0], target[1], ordinal)
                    source = 'design-fallback-v1'
                else:
                    model = 0
            if design_fallback and target in CAPITALS and model not in HABITABLE_MODELS:
                raise ValueError('Capital must use a habitable terrestrial model')
            if type(model) is not int or model not in (PLANET_MODELS if base['kind_code'] == 0 else set(range(110, 120))):
                raise ValueError('Unsupported model for base kind')
            display_name = base['name']
            if len(display_name) > 12:
                display_name = display_name.replace('・', '')
                if len(display_name) > 12:
                    raise ValueError('Base name needs an explicit display alias')
                result[f'base.{base_index}.source-name'] = base['name']
            # Preserve supplied orbital data. Defaults remain explicit local placeholders.
            orbit = base.get('orbit') or {}
            fields = dict(id=1000 + base_index, grid=system['grid']['index'],
                          model=model, kind=base['kind_code'], name=display_name,
                          radius=orbit.get('radius', 12.0 + 8 * ordinal),
                          cycle=orbit.get('cycle', 86400 * (ordinal + 1)), direction=orbit.get('direction', 0),
                          angle=orbit.get('angle', ordinal * 47 % 360), diameter=orbit.get('diameter', 2.0),
                          **{'appearance-source': source})
            fields['class'] = base['physical_class'] if base.get('physical_class') is not None else 0
            if (not all(type(fields[key]) in (int, float) and math.isfinite(fields[key])
                        for key in ('radius', 'angle', 'diameter'))
                    or fields['radius'] < 0 or fields['diameter'] <= 0
                    or type(fields['cycle']) is not int or fields['cycle'] <= 0
                    or type(fields['direction']) is not int or fields['direction'] not in (0, 1)
                    or type(fields['class']) is not int or not 0 <= fields['class'] <= 255):
                raise ValueError('Invalid supplied physical fields')
            for key, value in fields.items():
                result[f'base.{base_index}.{key}'] = value
            base_index += 1
    if used != set(overrides):
        raise ValueError('Appearance map targets a base absent from the world')
    for value in result.values():
        if any(c in str(value) for c in '\n\r\t\\'):
            raise ValueError('Unsupported properties value')
    return '\n'.join(f'{key}={value}' for key, value in result.items()) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manual_world', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--appearance-map', type=Path,
                        help='External schema-1 type/variant assignments with explicit provenance')
    parser.add_argument('--design-fallback', action='store_true',
                        help='Use documented gameplay design for missing planet appearances; capitals must be habitable')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2] / 'work'
    if not args.output.resolve().is_relative_to(root.resolve()) or args.output.exists():
        parser.error('Choose a new output file under repository work/')
    appearances = json.loads(args.appearance_map.read_text(encoding='utf-8')) if args.appearance_map else None
    data = prepare(json.loads(args.manual_world.read_text(encoding='utf-8')), appearances, args.design_fallback)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(data, encoding='utf-8')
    print('Prepared 80 systems and 287 bases with explicit local defaults')
