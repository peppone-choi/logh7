"""build_hd_pack 합성 검사: DLL 헤더와 같은 제외 규칙, 배율 검사, Mid·Lo 축소 생성. 작성자: Peppone Choi (peppone-choi)."""
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from build_hd_pack import build, read_rules, skipped


def tga(path, w, h):
    """비압축 24비트 TGA."""
    path.parent.mkdir(parents=True, exist_ok=True)
    head = bytes([0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, w & 255, w >> 8, h & 255, h >> 8, 24, 0])
    path.write_bytes(head + bytes(w * h * 3))


def png(path, w, h):
    path.parent.mkdir(parents=True, exist_ok=True)
    Image.new('RGBA', (w, h), (10, 20, 30, 255)).save(path)


class RulesTest(unittest.TestCase):
    def test_rules_come_from_dll_header(self):
        rules = read_rules()
        self.assertIn('_col_p', rules['kSkipMarkers'])
        self.assertIn('_normalmap', rules['kSkipMarkers'])
        self.assertEqual(rules['kSkipNames'], ['icon_country32.tga'])

    def test_skip_matches_file_name_only(self):
        rules = read_rules()
        self.assertTrue(skipped('image/a_COL_P.tga', rules))
        self.assertTrue(skipped('image/icon/ICON_COUNTRY32.TGA', rules))
        self.assertFalse(skipped('image/a_alpha_x.bmp', rules))
        self.assertFalse(skipped('_col_p/plain.tga', rules))


class BuildTest(unittest.TestCase):
    def test_pack_layout_and_checks(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src, up, out = d / 'data', d / 'up', d / 'out'
            tga(src / 'image/a.tga', 4, 4)
            tga(src / 'image/b_col_p.tga', 4, 4)
            tga(src / 'image/wrong.tga', 4, 4)
            tga(src / 'model/images/Hi/s.bmp.tga', 8, 8)
            tga(src / 'model/images/Mid/s.bmp.tga', 4, 4)
            tga(src / 'model/images/Mid/only.tga', 2, 2)
            for s, rels in (('ui', ['image/a.tga', 'image/b_col_p.tga', 'image/wrong.tga']),
                            ('spot', []), ('hi', ['model/images/Hi/s.bmp.tga'])):
                (up / s).mkdir(parents=True)
                (up / s / 'manifest.json').write_text(json.dumps({'items': [{'rel': r} for r in rels]}), encoding='utf-8')
            png(up / 'ui/x2/image/a.tga.png', 8, 8)
            png(up / 'ui/x2/image/b_col_p.tga.png', 8, 8)
            png(up / 'ui/x2/image/wrong.tga.png', 9, 8)
            png(up / 'hi/x2/model/images/Hi/s.bmp.tga.png', 16, 16)
            png(up / 'hi/x4/model/images/Hi/s.bmp.tga.png', 32, 32)
            png(up / 'midlo/x2/model/images/Mid/only.tga.png', 4, 4)
            entries, rejected = build(src, up, out, 2, read_rules())
            got = {e['rel']: e['size'] for e in entries}
            self.assertEqual(got, {'image/a.tga': [8, 8], 'model/images/Hi/s.bmp.tga': [16, 16],
                                   'model/images/Mid/s.bmp.tga': [8, 8], 'model/images/Mid/only.tga': [4, 4]})
            self.assertTrue((out / 'hd/image/a.tga.png').exists())
            self.assertTrue((out / 'hd/model/images/Mid/s.bmp.tga.png').exists())
            reasons = {r['rel']: r['reason'] for r in rejected}
            self.assertEqual(reasons['image/b_col_p.tga'], 'skip rule')
            self.assertTrue(reasons['image/wrong.tga'].startswith('size'))


if __name__ == '__main__':
    unittest.main()
