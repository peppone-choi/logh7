"""Stream grouping, offsets and malformed asset rejection. Author: 최병호."""
from pathlib import Path
import struct
import tempfile
import unittest
from mdx_geometry import scan, write_obj


def fixture(formats=(24,), invalid_index=False, invalid_float=False):
    headers, payload = [], []
    flags = {24: 0x10102, 36: 0x10112, 72: 0x12112}
    for material, stride in enumerate(formats):
        # Old heap pointers deliberately have no relationship to file offsets.
        headers.append(struct.pack('<9I', 0xdead0000, 3, 0xbeef0000, 3,
                                   0xcafe0000, flags[stride], 0, stride, material))
        vertices = b''.join(struct.pack('<3f', *p) + bytes(stride - 12)
                            for p in ((float('nan') if invalid_float else 0, 0, 0),
                                      (1, 0, 0), (0, 1, 0)))
        payload.append(vertices + struct.pack('<3H', 0, 1, 3 if invalid_index else 2)
                       + bytes(6))
    return b''.join(headers + payload)


class MdxGeometryTests(unittest.TestCase):
    def test_header_array_precedes_all_payloads(self):
        streams = scan(b'noise!' + fixture((24, 72)))
        self.assertEqual(len(streams), 2)
        self.assertEqual([s.vertex_offset for s in streams], [78, 162])
        self.assertEqual([s.header_offset for s in streams], [6, 42])
        self.assertEqual(list(streams[1].positions())[1], (1, 0, 0))
        self.assertEqual(streams[1].summary()['bounds'], [[0, 0, 0], [1, 1, 0]])
        self.assertEqual(streams[1].material, 1)

    def test_multiple_groups_and_obj_index_base(self):
        streams = scan(fixture((36,)) + b'padding!' + fixture())
        self.assertEqual([s.group for s in streams], [0, 1])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'preview.obj'
            write_obj(path, streams)
            self.assertEqual([s for s in path.read_text().splitlines() if s.startswith('f ')],
                             ['f 1 2 3', 'f 4 5 6'])

    def test_invalid_or_truncated_payload_does_not_become_geometry(self):
        for data in (fixture(invalid_index=True), fixture(invalid_float=True),
                     fixture()[:-1], fixture((24, 72))[:-1]):
            with self.subTest(length=len(data)):
                self.assertEqual(scan(data), [])

    def test_unknown_format_and_non_triangle_indices(self):
        for offset, value in ((20, 0), (28, 48), (12, 4)):
            data = bytearray(fixture())
            struct.pack_into('<I', data, offset, value)
            self.assertEqual(scan(data), [])


if __name__ == '__main__':
    unittest.main()
