"""Synthetic malformed-input checks; no original assets. Author: 최병호."""
import copy
import struct
import unittest
import galaxy_map as m


def mdx():
    data = bytearray(0x800)
    base, channels, binding, keys = 0x12340000, 0x180, 0x300, 0x400
    struct.pack_into('<6I', data, 0, base + 0x50, 1, 0, 0, base + channels, 9)
    data[0x58:0x60] = b'star_01\0'
    struct.pack_into('<II', data, 0xe0, base + binding, 1)
    struct.pack_into('<9I', data, binding + 12, *range(9))
    for i, value in enumerate([18.75, 0, 0.25, 0, 0, 0, 1, 1, 1]):
        struct.pack_into('<III', data, channels + i * 28 + 8, base + keys + i * 36, 1, i)
        struct.pack_into('<f', data, keys + i * 36 + 8, value)
    return data


class MapTests(unittest.TestCase):
    def test_types_and_rle_row_order(self):
        self.assertEqual(m.decode_types(bytes([1, 17, 3, 2])), [{'kind': 17, 'type': 3, 'fixedstar': 2}])
        self.assertEqual(m.decode_grid(bytes([3, 2, 0, 4, 4, 7, 2, 8]))['rows'], [[7, 7, 7], [7, 8, 8]])

    def test_bad_rle_and_truncation(self):
        valid = bytes([3, 2, 0, 4, 4, 7, 2, 8])
        for data in [valid[:i] for i in range(len(valid))] + [valid + b'x', bytes([3, 2, 0, 2, 7, 1]), bytes([3, 2, 0, 2, 5, 1]), bytes([3, 2, 0, 2, 0, 1]), bytes([3, 2, 0, 3, 6, 1, 2])]:
            with self.subTest(data=data), self.assertRaises(ValueError):
                m.decode_grid(data)

    def test_base_float_endian_and_utf16(self):
        name = 'Test\0'.encode('utf-16-be')
        data = struct.pack('>HIHHHB', 1, 42, 2550, 7, 9, 5) + name + struct.pack('>BfIBff', 2, 1.5, 24, 1, 0.25, 3.5)
        result = m.decode_bases(data)[0]
        self.assertEqual((result['name'], result['grid'], result['revolution_radius'], result['diameter']), ('Test', 2550, 1.5, 3.5))
        for length in range(len(data)):
            with self.subTest(length=length), self.assertRaises((ValueError, UnicodeError)):
                m.decode_bases(data[:length])

    def test_mdx_relocation_and_alignment(self):
        anchor = m.decode_anchors(mdx())['anchors'][0]
        self.assertEqual(anchor['position'], [18.75, 0, 0.25])
        self.assertEqual(anchor['candidate_grid_scale_2'], {'x': 87, 'y': 24, 'index': 2487})

    def test_mdx_rejects_unsupported_or_corrupt(self):
        source = mdx()
        mutations = [(0xe0, '<I', 0xffffffff), (0x30c, '<I', 9), (0x18c, '<I', 2),
                     (0x190, '<I', 8), (0x408, '<f', float('nan')), (0x474, '<f', 1.0)]
        for offset, fmt, value in mutations:
            data = copy.copy(source)
            struct.pack_into(fmt, data, offset, value)
            with self.subTest(offset=offset), self.assertRaises(ValueError):
                m.decode_anchors(data)
        with self.assertRaises(ValueError):
            m.decode_anchors(source[:0x500])


if __name__ == '__main__':
    unittest.main()
