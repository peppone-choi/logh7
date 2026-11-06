"""empire_medals.py 검사. 작성자: Peppone Choi (peppone-choi)."""
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import empire_medals as em  # noqa: E402


class EmpireMedalTest(unittest.TestCase):
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

    def test_names_cover_empire_block_once(self):
        idx = sorted(i for _, ids, _, _ in em.EMPIRE for i in ids)
        self.assertEqual(idx, list(range(26)))  # constmsg 8번 묶음 제국 쪽 0~25

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


if __name__ == '__main__':
    unittest.main()
