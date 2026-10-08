"""Model table IDs, holes, detail pairs and absent assets. Author: 최병호."""
import struct
import unittest
from planet_models import decode_tables


class PlanetModelTests(unittest.TestCase):
    def test_ids_aliases_and_missing_assets(self):
        high, low = [0] * 130, [0] * 130
        paths = {1: '/../data/model/Planets/p000.mdx', 2: '/../data/model/Planets/p000_low.mdx',
                 3: '/../data/model/Planets/y001.mdx', 4: '/../data/model/Planets/y001_low.mdx'}
        for slot in [0, 110, 111]:
            high[slot], low[slot] = (1, 2) if slot == 0 else (3, 4)
        encode = lambda values: struct.pack('<130I', *values)
        rows = decode_tables(encode(high), encode(low), paths.__getitem__, lambda p: 'p000' in p)
        self.assertEqual([r['model_file'] for r in rows], [0, 110, 111])
        self.assertEqual(rows[0]['high'], 'data/model/Planets/p000.mdx')
        self.assertEqual(rows[1]['family'], 'fortress')
        self.assertFalse(rows[1]['high_exists'])
        self.assertEqual(rows[1]['filename'], rows[2]['filename'])
        high[10] = 1
        with self.assertRaises(ValueError):
            decode_tables(encode(high), encode(low), paths.__getitem__, lambda p: True)
        with self.assertRaises(ValueError):
            decode_tables(b'', encode(low), paths.__getitem__, lambda p: True)


if __name__ == '__main__':
    unittest.main()
