"""World metadata must survive seed generation. Author: 최병호."""
import copy
import unittest

from prepare_world import prepare


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
