"""medals.py 검사. 작성자: Peppone Choi (peppone-choi)."""
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import medals as em  # noqa: E402


class MedalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.src = Path(cls.tmp.name) / 'emblem.png'
        im = Image.new('RGB', (300, 200), 'black')
        ImageDraw.Draw(im).polygon([(150, 20), (280, 180), (20, 180)], fill='white')
        im.save(cls.src)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_catalogue_covers_both_factions_once(self):
        rows = em.catalogue()
        idx = sorted(i for _, _, ids, *_ in rows for i in ids)
        self.assertEqual(idx, list(range(52)))  # constmsg 8번 묶음 제국 0~25, 동맹 26~51
        files = [r[0] for r in rows]
        self.assertEqual(len(files), len(set(files)))
        originals = [r[0] for r in rows if r[5] is None]
        self.assertEqual(originals, [f'm_f{i:03d}.png' for i in range(1, 16)])  # 동맹 1~15만 원본 그림
        for file, fac, ids, *_ in rows:
            if ids and ids[0] in (25, 51):
                self.assertIn(file, ('e17.png', 'a17.png'))

    def test_special_grades_use_numerals_and_metal_tiers(self):
        widths = []
        for k in range(1, 11):
            xs = np.nonzero(em.numeral(k, 100).max(0) > 0.5)[0]
            widths.append(xs.max() - xs.min())
        self.assertEqual(widths.index(max(widths)), 7)  # VIII 가 가장 넓다
        self.assertLess(widths[0], widths[1])
        self.assertEqual([em.tier(k) for k in (1, 3, 4, 6, 7, 10)], ['bronze'] * 2 + ['silver'] * 2 + ['gold'] * 2)
        for fac in ('empire', 'alliance'):
            a, b = (np.asarray(em.special(fac, k, self.src)) for k in (1, 8))
            self.assertEqual(a[0, 0, 3], 0)
            self.assertGreater(np.abs(a.astype(int) - b.astype(int)).mean(), 1)  # 등급마다 그림이 다르다
        self.assertEqual(np.asarray(em.alliance_staff())[0, 0, 3], 0)

    def test_emblem_mask_fits_box(self):
        m = em.emblem(self.src, 300)
        xs = np.nonzero(m.max(0) > 0.5)[0]
        self.assertLessEqual(xs.max() - xs.min() + 1, 300)
        self.assertGreater(em.emblem(self.src, 300, thick=4).sum(), m.sum())  # 굵히면 넓어진다

    def test_design_is_rgba_with_transparent_corner(self):
        for no in (1, 8, 18):
            im = em.design(no, self.src)
            self.assertEqual((im.size, im.mode), ((em.S, em.S), 'RGBA'))
            a = np.asarray(im)[..., 3]
            self.assertEqual(a[0, 0], 0)
            self.assertGreater((a > 0).mean(), 0.05)

    def test_rank_scale_shrinks_lower_medals(self):
        im = Image.new('RGBA', (em.S, em.S), (255, 255, 255, 255))
        top, low = em.fit_rank(im, 1), em.fit_rank(im, 17)
        self.assertEqual(np.asarray(top)[..., 3].mean(), 255)
        self.assertLess(np.asarray(low)[..., 3].mean(), 255 * 0.4)
        self.assertTrue(all(em.SCALE[a] >= em.SCALE[a + 1] for a in range(1, 15)))
        self.assertLess(np.asarray(em.fit_rank(im, f=.5))[..., 3].mean(), 255 * 0.3)


if __name__ == '__main__':
    unittest.main()
