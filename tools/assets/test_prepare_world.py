"""World metadata must survive seed generation. Author: Peppone Choi."""
import copy
import unittest

from prepare_world import prepare, CAPITALS, HABITABLE_MODELS, designed_model


def world():
    systems = []
    for i in range(80):
        grid = (2487, 2015)[i] if i < 2 else i
        systems.append(dict(kind=i + 3, grid={'index': grid}, name=f'S{i}', stellar_class='G',
                            bases=[dict(name=f'B{i}-{j}', kind_code=0, model_file=None,
                                        physical_class=None, orbit=None)
                                   for j in range(4 if i < 47 else 3)]))
    return dict(width=100, height=50, systems=systems)


def properties(text):
    return dict(line.split('=', 1) for line in text.splitlines())


class PrepareWorldTest(unittest.TestCase):
    def test_map_annotations_keep_empire_alliance_and_neutral_distinct(self):
        data = world()
        for i, color in enumerate([(0, 1, 1), (.50197, 1, .50197), (1, 1, 0)]):
            data['systems'][i]['annotation_color'] = color
        p = properties(prepare(data))
        self.assertEqual([p[f'system.{i}.power'] for i in range(4)], ['2', '3', '1', '0'])
        self.assertEqual(p['system.0.ownership-source'], 'manual-map-annotation')
    def test_designed_capitals_are_habitable_and_cannot_be_overridden_to_gas(self):
        data = world()
        for i, ((grid, name), expected) in enumerate(CAPITALS.items()):
            data['systems'][i]['bases'][0]['name'] = name
            self.assertIn(designed_model(grid, name, 0), HABITABLE_MODELS)
        p = properties(prepare(data, design_fallback=True))
        self.assertEqual(p['base.0.model'], '60')
        self.assertEqual(p['base.4.model'], '61')
        self.assertEqual(p['base.0.appearance-source'], 'design-fallback-v1')
        with self.assertRaisesRegex(ValueError, 'Capital'):
            prepare(data, dict(schema=1, source='bad', bases=[
                dict(grid=2487, name=next(iter(CAPITALS))[1], planet_type=3)]), design_fallback=True)

    def test_design_keeps_supplied_values_and_is_independent_of_system_order(self):
        data = world()
        data['systems'][0]['bases'][0]['model_file'] = 81
        text = prepare(data, design_fallback=True)
        self.assertEqual(properties(text)['base.0.model'], '81')
        data['systems'].reverse()
        self.assertEqual(text, prepare(data, design_fallback=True))

    def test_preserves_supplied_model_class_and_orbit_without_mutation(self):
        data = world()
        data['systems'][0]['bases'][1].update(model_file=61, physical_class=7,
            orbit=dict(radius=31.5, cycle=123456, direction=1, angle=182.5, diameter=4.5))
        before = copy.deepcopy(data)
        p = properties(prepare(data))
        self.assertEqual(data, before)
        for key, value in dict(model=61, **{'class': 7}, radius=31.5, cycle=123456,
                               direction=1, angle=182.5, diameter=4.5).items():
            self.assertEqual(p['base.1.' + key], str(value))
        self.assertEqual(p['base.1.appearance-source'], 'manual-extraction')

    def test_type_targets_grid_and_name_with_provenance(self):
        data = world()
        data['systems'][1]['bases'][0]['name'] = 'B0-1'
        p = properties(prepare(data, dict(schema=1, source='local-preview',
            bases=[dict(grid=2487, name='B0-1', planet_type=10, variant=2)])))
        self.assertEqual(p['base.1.model'], '102')
        self.assertEqual(p['base.1.appearance-source'], 'local-preview')
        self.assertEqual(p['base.4.model'], '0')

    def test_rejects_unknown_duplicate_or_conflicting_target(self):
        for bases in [[dict(grid=1, name='missing', planet_type=0)],
                      [dict(grid=2487, name='B0-0', planet_type=0)] * 2]:
            with self.assertRaises(ValueError):
                prepare(world(), dict(schema=1, source='test', bases=bases))
        data = world()
        data['systems'][0]['bases'][0]['model_file'] = 61
        with self.assertRaises(ValueError):
            prepare(data, dict(schema=1, source='test',
                bases=[dict(grid=2487, name='B0-0', planet_type=9)]))

    def test_rejects_unavailable_variant_and_bad_physical_values(self):
        for family, variant in [(3, 2), (-1, 0), (11, 0), (True, 0)]:
            with self.assertRaises(ValueError):
                prepare(world(), dict(schema=1, source='test',
                    bases=[dict(grid=2487, name='B0-0', planet_type=family, variant=variant)]))
        for update in [dict(physical_class=-1), dict(orbit={'radius': float('nan')}),
                       dict(orbit={'cycle': 0}), dict(model_file=2)]:
            data = world()
            data['systems'][0]['bases'][0].update(update)
            with self.assertRaises(ValueError):
                prepare(data)


if __name__ == '__main__':
    unittest.main()
