"""Prepare an external local world seed from reviewed manual extraction. Author: 최병호.

Placements/names come from the extraction. Unmapped cells, star model numbering,
planet models/classes/orbits and initial spawn policy are local defaults, not recovered originals.
No input is modified. Generated original names belong in work/, never in Git.
"""
import argparse
import json
from pathlib import Path


def prepare(manual):
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
    base_index = 0
    for i, system in enumerate(sorted(systems, key=lambda s: s['kind'])):
        for key, value in dict(kind=system['kind'], grid=system['grid']['index'],
                               star='OBAFGKM'.index(system.get('stellar_class') or 'G'), name=system['name']).items():
            result[f'system.{i}.{key}'] = value
        for ordinal, base in enumerate(system['bases']):
            display_name = base['name']
            if len(display_name) > 12:
                display_name = display_name.replace('・', '')
                if len(display_name) > 12:
                    raise ValueError('Base name needs an explicit display alias')
                result[f'base.{base_index}.source-name'] = base['name']
            # Stable local object IDs, evenly spaced local orbits, pending original server values.
            fields = dict(id=1000 + base_index, grid=system['grid']['index'],
                          model=fortress_models.get(base['name'], 0), kind=base['kind_code'],
                          name=display_name, radius=12.0 + 8 * ordinal, cycle=86400 * (ordinal + 1),
                          direction=0, angle=ordinal * 47 % 360, diameter=2.0)
            fields['class'] = 0
            for key, value in fields.items():
                result[f'base.{base_index}.{key}'] = value
            base_index += 1
    for value in result.values():
        if any(c in str(value) for c in '\n\r\t\\'):
            raise ValueError('Unsupported properties value')
    return '\n'.join(f'{key}={value}' for key, value in result.items()) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manual_world', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2] / 'work'
    if not args.output.resolve().is_relative_to(root.resolve()) or args.output.exists():
        parser.error('Choose a new output file under repository work/')
    data = prepare(json.loads(args.manual_world.read_text(encoding='utf-8')))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(data, encoding='utf-8')
    print('Prepared 80 systems and 287 bases with explicit local defaults')
