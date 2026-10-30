"""Prevent partial/unsupported text injection into the owned view. Author: Peppone Choi."""
import unittest
from ui_text import cp949_ui_bytes


class UiTextTest(unittest.TestCase):
    def test_lossless_hangul_mixed_text_and_boundaries(self):
        for text in ('한글ABC 123·끝', '힣' * 511):
            self.assertEqual(cp949_ui_bytes(text, 949).decode('cp949'), text)

    def test_reject_before_any_post(self):
        for text in ('', '가' * 512, '가\n나', '가\x00나', '가\t나', '가\x7f나', '가😀나', '\ud800'):
            with self.subTest(text=repr(text)), self.assertRaises(ValueError):
                cp949_ui_bytes(text, 949)
        with self.assertRaises(ValueError):
            cp949_ui_bytes('한글', 932)


if __name__ == '__main__':
    unittest.main()
