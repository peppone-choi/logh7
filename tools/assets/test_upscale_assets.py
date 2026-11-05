"""upscale_assets의 TGA 해석·투명 영역 채움 합성 검사. 작성자: Peppone Choi (peppone-choi)."""
import struct
import unittest

import numpy as np
from PIL import Image

from upscale_assets import bleed, decode_tga, restore_detail


def tga(kind, pixels, palette=None, bits=32, depth=8, top=True, width=2, height=2):
    cmap = 1 if palette else 0
    colors = len(palette) if palette else 0
    head = struct.pack('<BBBHHBHHHHBB', 0, cmap, kind, 0, colors, bits if cmap else 0,
                       0, 0, width, height, depth, 0x20 if top else 0)
    body = b''.join(bytes(p) for p in palette) if palette else b''
    return head + body + bytes(pixels)


class DecodeTgaTest(unittest.TestCase):
    def test_palette_with_32bit_alpha(self):
        # 팔레트 항목은 BGRA. 0번은 투명, 1번은 불투명 빨강.
        data = tga(1, [0, 1, 1, 0], palette=[(0, 0, 0, 0), (0, 0, 255, 255)])
        im = decode_tga(data)
        self.assertEqual(im.getpixel((0, 0)), (0, 0, 0, 0))
        self.assertEqual(im.getpixel((1, 0)), (255, 0, 0, 255))

    def test_bottom_up_origin_is_flipped(self):
        data = tga(1, [1, 1, 0, 0], palette=[(0, 0, 0, 0), (0, 255, 0, 255)], top=False)
        im = decode_tga(data)
        self.assertEqual(im.getpixel((0, 0))[3], 0)       # 파일 첫 줄은 화면 아래 줄
        self.assertEqual(im.getpixel((0, 1)), (0, 255, 0, 255))

    def test_rle_truecolor(self):
        # 반복 패킷 4픽셀(BGRA 파랑)
        data = tga(10, [0x83, 255, 0, 0, 255], bits=0, depth=32)
        im = decode_tga(data)
        self.assertTrue((np.asarray(im) == (0, 0, 255, 255)).all())

    def test_truncated_rejected(self):
        with self.assertRaises(ValueError):
            decode_tga(tga(2, [0] * 5, bits=0, depth=32))


class BleedTest(unittest.TestCase):
    def test_transparent_pixels_take_neighbour_color(self):
        im = Image.new('RGBA', (8, 1), (0, 0, 0, 0))
        im.putpixel((0, 0), (200, 100, 50, 255))
        out = np.asarray(bleed(im))
        self.assertTrue((out[0, 7] == (200, 100, 50)).all())
        self.assertTrue((out[0, 0] == (200, 100, 50)).all())

    def test_opaque_image_unchanged(self):
        im = Image.new('RGBA', (3, 3), (10, 20, 30, 255))
        self.assertTrue((np.asarray(bleed(im)) == (10, 20, 30)).all())


class RestoreDetailTest(unittest.TestCase):
    def test_flat_source_adds_nothing(self):
        up = Image.new('RGB', (8, 8), (40, 80, 120))
        out = restore_detail(up, Image.new('RGB', (2, 2), (40, 80, 120)), 0.9)
        self.assertTrue((np.asarray(out) == (40, 80, 120)).all())

    def test_edges_gain_contrast(self):
        src = Image.new('RGB', (4, 4), (0, 0, 0))
        src.paste((255, 255, 255), (2, 0, 4, 4))
        up = src.resize((16, 16), Image.Resampling.BILINEAR)
        out = np.asarray(restore_detail(up, src, 1.0), dtype=int)
        base = np.asarray(up, dtype=int)
        self.assertGreater(out[8, 9, 0], base[8, 9, 0] - 1)   # 밝은 쪽은 더 밝거나 같고
        self.assertLess(out[8, 6, 0], base[8, 6, 0] + 1)      # 어두운 쪽은 더 어둡거나 같다


if __name__ == '__main__':
    unittest.main()
