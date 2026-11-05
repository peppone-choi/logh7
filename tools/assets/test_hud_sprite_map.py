"""hud_sprite_map 합성 검사: 조각 분리, 불투명 마스크 대조, 가려진 부분이 있는 타일 검증. 작성자: Peppone Choi (peppone-choi)."""
import unittest

import numpy as np

from hud_sprite_map import components, dedupe, solid_error, template_hits


def sprite(w, h, seed):
    rng = np.random.default_rng(seed)
    a = np.zeros((h, w, 4), np.uint8)
    a[..., :3] = rng.integers(0, 255, (h, w, 3))
    a[..., 3] = 255
    return a


class HudSpriteMapTest(unittest.TestCase):
    def test_components_split_by_alpha(self):
        a = np.zeros((40, 80, 4), np.uint8)
        a[5:25, 5:25, 3] = 255
        a[10:30, 50:75, 3] = 255
        from PIL import Image
        rects = sorted(components(Image.fromarray(a, 'RGBA')))
        self.assertEqual(rects, [(5, 5, 20, 20), (50, 10, 25, 20)])

    def test_template_finds_exact_position(self):
        cap = np.random.default_rng(1).integers(0, 255, (120, 160, 3)).astype(np.uint8)
        spr = sprite(24, 16, 2)
        cap[40:56, 70:94] = spr[..., :3]
        hits = template_hits(cap, spr, 5.0)
        self.assertEqual(hits[0][:2], (70, 40))

    def test_semi_transparent_pixels_are_ignored(self):
        cap = np.zeros((60, 60, 3), np.uint8)
        spr = sprite(20, 20, 3)
        cap[10:30, 10:30] = spr[..., :3]
        spr[0:5, :, 3] = 100            # 반투명 줄은 배경과 섞여 화면값이 달라도 비교하지 않는다
        cap[10:15, 10:30] = 0
        err, n = solid_error(cap, spr, 10, 10)
        self.assertEqual(err, 0.0)
        self.assertEqual(n, 15 * 20)

    def test_out_of_bounds_is_none(self):
        self.assertEqual(solid_error(np.zeros((10, 10, 3), np.uint8), sprite(8, 8, 4), 5, 5), (None, 0))


class DedupeTest(unittest.TestCase):
    def test_icon_inside_hollow_frame_is_kept(self):
        frame = np.zeros((64, 64, 4), np.uint8)
        frame[:4, :, 3] = frame[-4:, :, 3] = frame[:, :4, 3] = frame[:, -4:, 3] = 255
        icon = np.full((24, 24, 4), 255, np.uint8)
        atlases = {'frame.tga': frame, 'icon.tga': icon}
        found = [dict(file='frame.tga', atlas_rect=[0, 0, 64, 64], screen=[10, 10, 64, 64], err=1.0),
                 dict(file='icon.tga', atlas_rect=[0, 0, 24, 24], screen=[30, 30, 24, 24], err=1.0)]
        kept, cover = dedupe(found, (100, 100), atlases)
        self.assertEqual([k['file'] for k in kept], ['frame.tga', 'icon.tga'])
        self.assertEqual(int(cover.sum()), int((frame[..., 3] == 255).sum()) + 24 * 24)

    def test_duplicate_of_same_pixels_is_dropped(self):
        icon = np.full((24, 24, 4), 255, np.uint8)
        found = [dict(file='a.tga', atlas_rect=[0, 0, 24, 24], screen=[5, 5, 24, 24], err=1.0),
                 dict(file='b.tga', atlas_rect=[0, 0, 24, 24], screen=[5, 5, 24, 24], err=2.0)]
        kept, _ = dedupe(found, (50, 50), {'a.tga': icon, 'b.tga': icon})
        self.assertEqual([k['file'] for k in kept], ['a.tga'])


if __name__ == '__main__':
    unittest.main()
