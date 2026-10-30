"""얼굴 컨테이너의 경계·변조 거부·CD 인덱스 대응 검사. 작성: Peppone Choi."""
from pathlib import Path
import struct
import unittest

import tcf


def fixture():
    header = tcf.MAGIC+bytes(46)
    tga = struct.pack('<BBBHHBHHHHBB', 0, 1, 1, 0, 1, 32, 0, 0, 64, 80, 8, 0)
    return header+tga+bytes((1, 2, 3, 255))+bytes(64*80)


class TcfTests(unittest.TestCase):
    def test_exact_frames_no_trailing_or_partial_payload(self):
        data = fixture()
        self.assertEqual([(50, len(data)-50)], [(r['offset'], r['bytes']) for r in tcf.frames(data)])
        for bad in (data[:49], data[:-1], data+b'\0', data[:50], b'bad!'+data[4:]):
            with self.assertRaises(ValueError):
                tcf.frames(bad)
        bad = bytearray(data);bad[50+16] = 32
        with self.assertRaises(ValueError):tcf.frames(bad)

    def test_index_cannot_omit_duplicate_or_point_inside_a_frame(self):
        data = fixture();length = len(data)-50
        containers = {name:data for name, _, _ in tcf.INDEX_BLOCKS}
        index = bytearray()
        for _, slots, variants in tcf.INDEX_BLOCKS:
            offsets = [50]+[0]*(variants-1);lengths = [length]+[0]*(variants-1)
            index.extend(struct.pack('<'+'I'*(variants*2), *(offsets+lengths)))
            index.extend(bytes((slots-1)*variants*8))
        index.extend(bytes(40))
        self.assertEqual(7, len(tcf.verify_index(index, containers)))
        for pos, value in [(0, 51), (4, length-1), (0, 0), (8, 50), (len(index)-4, 1)]:
            bad = bytearray(index);struct.pack_into('<I', bad, pos, value)
            with self.assertRaises(ValueError):tcf.verify_index(bad, containers)

    def test_actual_cd_all_frames_exactly_once_and_original_unchanged(self):
        source = Path('E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/data/image/Face')
        if not source.is_dir():self.skipTest('read-only CD assets unavailable')
        containers = {name:(source/name).read_bytes() for name, _, _ in tcf.INDEX_BLOCKS}
        expected = dict(oem=158, oam=108, o=25, gem=270, gam=45, gef=54, gaf=9)
        for name, data in containers.items():
            self.assertEqual(expected[Path(name).stem], len(tcf.frames(data)))
        raw_index = (source/'tcf.hed').read_bytes()
        self.assertEqual(669, len(tcf.verify_index(raw_index, containers)))
        self.assertEqual(raw_index, (source/'tcf.hed').read_bytes())
        self.assertTrue(all(data == (source/name).read_bytes() for name, data in containers.items()))


if __name__ == '__main__':unittest.main()
