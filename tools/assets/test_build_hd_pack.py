"""build_hd_pack 합성 검사: DLL 헤더와 같은 제외 규칙, 배율 검사, Mid·Lo 축소 생성. 작성자: Peppone Choi (peppone-choi)."""
import json
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from build_hd_pack import build, fnv1a32, read_rules, skipped


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
    def test_fnv_matches_dll_vectors(self):
        self.assertEqual(fnv1a32(b''), 0x811c9dc5)
        self.assertEqual(fnv1a32(b'a'), 0xe40c292c)

    def test_pack_layout_and_checks(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src, upd, up, up2, out = d / 'data', d / 'upd', d / 'up', d / 'up2', d / 'out'
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
            # 업데이트판: Hi s 가 바뀌어(크기 16) 같은 경로에 다른 파일이 생긴다. Mid only 는 그대로(중복 생략).
            tga(upd / 'model/images/Hi/s.bmp.tga', 16, 16)
            tga(upd / 'model/images/Mid/only.tga', 2, 2)
            (up2 / 'hi').mkdir(parents=True)
            (up2 / 'hi/manifest.json').write_text(json.dumps({'items': [{'rel': 'model/images/Hi/s.bmp.tga'}]}), encoding='utf-8')
            png(up2 / 'hi/x2/model/images/Hi/s.bmp.tga.png', 32, 32)
            entries, rejected = build([(src, up), (upd, up2)], out, 2, read_rules())

            def name(root, rel):
                return f"{rel}.{fnv1a32((root / rel).read_bytes()):08x}.png"
            got = sorted((e['file'], tuple(e['size'])) for e in entries)
            self.assertEqual(got, sorted([(name(src, 'image/a.tga'), (8, 8)),
                                          (name(src, 'model/images/Hi/s.bmp.tga'), (16, 16)),
                                          (name(src, 'model/images/Mid/s.bmp.tga'), (8, 8)),
                                          (name(src, 'model/images/Mid/only.tga'), (4, 4)),
                                          (name(upd, 'model/images/Hi/s.bmp.tga'), (32, 32))]))
            for f, _ in got:
                self.assertTrue((out / 'hd' / f).exists(), f)
            reasons = {r['rel']: r['reason'] for r in rejected}
            self.assertEqual(reasons['image/b_col_p.tga'], 'skip rule')
            self.assertTrue(reasons['image/wrong.tga'].startswith('size'))

    def test_faces_go_to_memory_names(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            src, faces, up, out = d / 'data', d / 'faces', d / 'up', d / 'out'
            src.mkdir()
            tga(faces / 'gem/gem_000.tga', 4, 5)
            (up).mkdir()
            (up / 'manifest.json').write_text(json.dumps({'items': [{'rel': 'gem/gem_000.tga'}]}), encoding='utf-8')
            png(up / 'x2/gem/gem_000.tga.png', 8, 10)
            entries, rejected = build([], out, 2, read_rules(), memory=[(faces, up)])
            name = f"mem/tcf.{fnv1a32((faces / 'gem/gem_000.tga').read_bytes()):08x}.png"
            self.assertEqual([(e['file'], e['kind']) for e in entries], [(name, 'face')])
            self.assertTrue((out / 'hd' / name).exists())
            self.assertEqual(rejected, [])


if __name__ == '__main__':
    unittest.main()
