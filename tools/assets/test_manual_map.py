"""Synthetic map annotation/alignment checks. Author: 최병호."""
import unittest
from manual_map import grid_at, parse_note, reconcile


class ManualMapTests(unittest.TestCase):
    def test_annotation_classification_and_cr_lines(self):
        name, bases = parse_note('テスト星系\r○惑星アルファ\r◎要塞ベータ\r')
        self.assertEqual(name, 'テスト')
        self.assertEqual([b['kind'] for b in bases], ['planet', 'fortress'])
        self.assertEqual([b['kind_code'] for b in bases], [0, 1])
        self.assertTrue(all(b['physical_class'] is None for b in bases))
        self.assertEqual(parse_note('太陽系\r○地球')[1][0]['name'], '地球')
        self.assertEqual(parse_note('テスト星系')[1], [])

    def test_bad_note(self):
        for content in ['', 'Title', '星系', 'テスト星系\n?Unknown', 'テスト星系\n○惑星', 'テスト星系\n○惑星A\n○惑星A']:
            with self.subTest(content=content), self.assertRaises(ValueError):
                parse_note(content)

    def test_grid_center_and_raster_error(self):
        grid, error = grid_at([666.7, 286.2], [36, 110], [7.2, 7.2])
        self.assertEqual(grid, {'x': 87, 'y': 24, 'index': 2487})
        self.assertLess(error, 0.2)
        for point in [[36, 110], [-10, 110], [1000, 1000], [float('nan'), 0]]:
            with self.subTest(point=point), self.assertRaises(ValueError):
                grid_at(point, [36, 110], [7.2, 7.2])

    def test_reconciliation_does_not_use_node_number_as_name_id(self):
        names = {'entries': [{'name': 'Empty', 'kind': i} for i in range(3)] + [{'name': 'Alpha', 'kind': 17}, {'name': 'Beta', 'kind': 66}, {'name': 'Absent', 'kind': 88}]}
        anchors = {'anchors': [{'node': 'star_01_G', 'candidate_grid_scale_2': {'index': 2487}}]}
        systems = [{'name': 'Alpha', 'grid': {'index': 2487}, 'bases': []}, {'name': 'Beta', 'grid': {'index': 2441}, 'bases': []}]
        result = reconcile(systems, anchors, names)
        self.assertEqual(result['systems'][0]['kind'], 17)
        self.assertEqual(result['systems'][0]['stellar_class'], 'G')
        self.assertIsNone(result['systems'][1]['stellar_class'])
        self.assertEqual(result['unmatched_manual_systems'], ['Beta'])
        self.assertEqual(result['names_without_manual_position'], ['Absent'])
        with self.assertRaises(ValueError):
            reconcile(systems[1:], anchors, names)
        with self.assertRaises(ValueError):
            reconcile(systems + systems, anchors, names)


if __name__ == '__main__':
    unittest.main()
