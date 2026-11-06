"""tcf_faces 합성 검사: 머리말 뒤 TGA 연속 분할과 끝이 맞지 않는 묶음 거절. 작성자: Peppone Choi (peppone-choi)."""
import struct
import unittest

from tcf_faces import HEADER, split_tcf


def tga(width, height, fill):
    palette = bytes((fill, fill, fill, 255)) + bytes(4)
    header = struct.pack('<BBBHHBHHHHBB', 0, 1, 1, 0, 2, 32, 0, 0, width, height, 8, 0)
    return header + palette + bytes(width * height)


class SplitTcfTest(unittest.TestCase):
    def test_records_follow_header_back_to_back(self):
        a, b = tga(4, 5, 10), tga(3, 2, 20)
        records = split_tcf(bytes(HEADER) + a + b)
        self.assertEqual([(o, len(r)) for o, r in records], [(HEADER, len(a)), (HEADER + len(a), len(b))])
        self.assertEqual(records[1][1], b)

    def test_trailing_bytes_are_rejected(self):
        with self.assertRaises(ValueError):
            split_tcf(bytes(HEADER) + tga(4, 4, 1) + b'\x00' * 7)

    def test_short_file_is_rejected(self):
        with self.assertRaises(ValueError):
            split_tcf(bytes(10))


if __name__ == '__main__':
    unittest.main()
