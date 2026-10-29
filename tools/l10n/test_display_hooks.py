"""표시 전용 호출·서식의 실제 CD 원바이트 대조. 작성: 최병호."""
from pathlib import Path
import re
import struct
import unittest

import catalog
import msgdat


class DisplayHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original=Path('E:/logh7-original/extracted/install/ｱﾌﾟﾘｹｰｼｮﾝ実行可能ﾌｧｲﾙ/exe/G7MTClient.exe')
        if not cls.original.is_file():raise unittest.SkipTest('read-only original EXE is not present')
        cls.data=cls.original.read_bytes()
        assert msgdat.sha(cls.data)=='bd19263c10decc3d58373165a82d42a9267868400d407da87d5f4f4109ab6e16'
        pe=struct.unpack_from('<I',cls.data,0x3c)[0];optional=pe+24
        cls.base=struct.unpack_from('<I',cls.data,optional+28)[0]
        table=optional+struct.unpack_from('<H',cls.data,pe+20)[0]
        cls.sections=[struct.unpack_from('<IIII',cls.data,table+i*40+8)
                      for i in range(struct.unpack_from('<H',cls.data,pe+6)[0])]

    def raw(self,address,length):
        rva=address-self.base
        row=next(row for row in self.sections if row[1]<=rva and rva+length<=row[1]+row[2])
        start=row[3]+rva-row[1]
        return self.data[start:start+length]

    def test_display_only_whitelist_calls_original_wide_to_cstring(self):
        header=(catalog.ROOT/'client-compat/display_hooks.h').read_text('utf-8')
        rows=re.findall(r'\{(0x[0-9a-f]+),\{([^}]+)\}\}',header)
        addresses=set()
        for address,encoded in rows:
            address=int(address,16);addresses.add(address)
            expected=bytes(int(value.strip(),16) for value in encoded.split(','))
            self.assertEqual(5,len(expected));self.assertEqual(0xe8,expected[0])
            self.assertEqual(expected,self.raw(address,5))
            self.assertEqual(0x4eacf0,address+5+struct.unpack_from('<i',expected,1)[0])
        # Base display label, HUD, correspondent lists and two unit role summaries.
        # Chat/mail editors, the shared converter and the existing base HUD stay out.
        self.assertEqual({0x4c8e56,0x58f2b1,0x544c3f,0x544dbd,0x532aff,0x57b0ed},addresses)
        self.assertNotIn(0x4eacf0,addresses)
        self.assertNotIn(0x58f3e1,addresses)
        self.assertEqual(self.data,self.original.read_bytes())

    def test_argument_whitelist_has_exact_original_formats_and_only_narrow_strings(self):
        header=(catalog.ROOT/'client-compat/korean_text.h').read_text('utf-8')
        rows=re.findall(r'\{(0x[0-9a-f]+),(0x[0-9a-f]+)ULL,(\d+),(\d+)\}',header)
        self.assertEqual(5,len(rows))
        for address,expected,length,count in rows:
            raw=self.raw(int(address,16),int(length)+1)
            self.assertEqual(0,raw[-1]);self.assertNotIn(0,raw[:-1])
            value=14695981039346656037
            for byte in raw[:-1]:value=((value^byte)*1099511628211)&0xffffffffffffffff
            self.assertEqual(int(expected,16),value)
            specifiers=catalog.PRINTF.findall(raw[:-1].decode('cp932'))
            self.assertEqual(int(count),len(specifiers))
            self.assertTrue(all(re.fullmatch(r'%-?\d*s',item) for item in specifiers))


if __name__=='__main__':unittest.main()
