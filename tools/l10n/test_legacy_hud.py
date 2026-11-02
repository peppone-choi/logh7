"""기존 ANSI HUD의 원바이트·숫자 서식·글리프 호출 경계 검사. 작성: Peppone Choi."""
import re
import struct
import unittest

import catalog
import test_display_hooks as display_tests


class LegacyHudTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def test_legacy_hud_pushes_and_outline_calls_are_complete_instructions(self):
        header=(catalog.ROOT/'client-compat/legacy_hud_text.h').read_text('utf-8')
        block=re.search(r'koreanLegacyHudPushes\[\]=\{(.*?)\n\};',header,re.S).group(1)
        rows=[tuple(int(v,16) for v in row) for row in re.findall(r'\{(0x[\da-f]+),(0x[\da-f]+)\}',block)]
        self.assertEqual(5,len(rows))
        self.assertEqual({0x78de40,0x78de28,0x78ddf8,0x78ddb4,0x78dd44},{original for _,original in rows})
        doc=catalog.read_json(catalog.ROOT/'l10n/exe-ui.ko.json')
        for address,original in rows:
            self.assertEqual(b'\x68'+struct.pack('<I',original),self.raw(address,5))
            old=self.raw(original,128).split(b'\0',1)[0].decode('cp932')
            translated=doc['entries'][f'{original:x}']
            catalog.validate(old,translated)
            self.assertTrue(all(re.fullmatch(r'%\d*d',item) for item in re.findall(r'%[^ ]*d',old)))
            # Integer expansion, transport bytes and NUL fit every native 256-byte array.
            _,encode=catalog.glyph_encoding([translated])
            worst=len(encode(translated))+11*translated.count('%')+1
            self.assertLess(worst,256)
        for address in (0x52478b,0x5247b9):
            self.assertEqual(bytes.fromhex('ff 15 c0 b0 66 00'),self.raw(address,6))
        # The old scanner accepts our CP932 EUDC as two bytes; the outline
        # renderer passes those bytes in big-endian order, not a Unicode key.
        self.assertEqual(bytes.fromhex('8a398a5901'),self.raw(0x524660,5))
        self.assertEqual(bytes.fromhex('3ca172073cdf770332c0c3b001c3'),self.raw(0x5274f5,14))


if __name__=='__main__':unittest.main()
