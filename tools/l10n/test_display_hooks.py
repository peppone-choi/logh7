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
        block=re.search(r'displayNameCalls\[\]=\{(.*?)\n\};',header,re.S).group(1)
        rows=re.findall(r'\{(0x[0-9a-f]+),\{([^}]+)\}\}',block)
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

    def test_dynamic_ship_spec_assignment_has_a_bounded_display_only_bridge(self):
        header=(catalog.ROOT/'client-compat/display_hooks.h').read_text('utf-8')
        block=re.search(r'displayShipSpecCalls\[\]=\{(.*?)\n\};',header,re.S).group(1)
        rows=re.findall(r'\{(0x[0-9a-f]+),\{([^}]+)\}\}',block)
        self.assertEqual(1,len(rows))
        address,encoded=rows[0];address=int(address,16)
        expected=bytes(int(value.strip(),16) for value in encoded.split(','))
        self.assertEqual(0x4c7b5e,address)
        self.assertEqual(expected,self.raw(address,5))
        self.assertEqual(0x64c286,address+5+struct.unpack_from('<i',expected,1)[0])
        # kind:u16 at +8bc, kind*680 (17*5*8), UTF-16 name +c.
        self.assertEqual(bytes.fromhex('668b8ebc0800008bc1c1e00403c18b0dfccf7c008d04808d84c1781a2c0083c00c50'),
                         self.raw(0x4c7b38,34))
        # Native wide assignment reaches the ACP helper; original member and
        # CString lifetime/allocator remain in the new wrapper.
        self.assertEqual(b'\xe8\xd1\x02\0\0',self.raw(0x64c2ae,5))
        self.assertIn('storeDisplayName(name,stored,13)',header)
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

    def test_message_box_calls_use_verified_import_and_literal_pushes_keep_stack_shape(self):
        header=(catalog.ROOT/'client-compat/dialog_text.h').read_text('utf-8')
        values=re.search(r'dialogMessageBoxCalls\[\]=\{([^}]+)\}',header).group(1)
        addresses=[int(value,16) for value in values.split(',')]
        expected=b'\xff\x15\xdc\xb5\x66\0'
        self.assertEqual(4,len(addresses))
        for address in addresses:self.assertEqual(expected,self.raw(address,6))
        target=struct.unpack('<I',self.raw(0x66b5dc,4))[0]
        self.assertEqual(b'MessageBoxA\0',self.raw(self.base+target+2,12))
        literal=(catalog.ROOT/'client-compat/literal_text.h').read_text('utf-8')
        values=re.search(r'koreanLiteralPushes\[\]=\{([^}]+)\}',literal).group(1)
        for value in values.split(','):
            self.assertEqual(b'\x68\x4c\x19\x77\0',self.raw(int(value,16),5))
        self.assertEqual(b'\xc3',self.raw(0x5923a0,1)) # Disabled trace function.

    def test_exact_dialog_copies_exclude_formats_and_general_names(self):
        header=(catalog.ROOT/'client-compat/dialog_text.h').read_text('utf-8')
        document=catalog.read_json(catalog.ROOT/'l10n/exe-ui.ko.json')
        bodies=re.search(r'dialogBodyCopies\[\]=\{([^}]+)\}',header).group(1)
        captions=re.search(r'dialogCaptionCopies\[\]=\{([^}]+)\}',header).group(1)
        body_addresses=[int(value.strip(),16) for value in bodies.split(',')]
        addresses=body_addresses+[int(value.strip(),16) for value in captions.split(',')]
        self.assertEqual(len(addresses),len(set(addresses)))
        for address in addresses:
            self.assertIn(format(address,'x'),document['entries'])
            raw=self.raw(address,2048).split(b'\0')[0]
            self.assertFalse(catalog.PRINTF.findall(raw.decode('cp932')))
            if address in body_addresses:self.assertGreaterEqual(len(raw),8)
        self.assertNotIn(0x771df4,addresses) # Generic planet/name labels stay out.

    def test_static_cstring_bridges_use_three_narrow_overloads_and_whole_instructions(self):
        header=(catalog.ROOT/'client-compat/korean_resources.h').read_text('utf-8')
        expected = {
            'construct': (0x64c144, b'\xa1\x08\x36\x7b\0', 0x64c193),
            'assign': (0x64c25f, b'\x56\x57\x8b\x7c\x24\x0c', 0x64c283),
            'append': (0x64c43e, b'\x56\x57\x8b\x7c\x24\x0c', 0x64c462),
        }
        for name,(address,prologue,return_address) in expected.items():
            encoded=re.search(r'const BYTE '+name+r'\[\] = \{([^}]+)\}',header).group(1)
            self.assertEqual(prologue,bytes(int(v.strip(),0) for v in encoded.split(',')))
            self.assertEqual(prologue,self.raw(address,len(prologue)))
            # Each member consumes exactly one pointer argument; the selected
            # prologues contain no relative call/jump that needs relocation.
            self.assertEqual(b'\xc2\x04\0',self.raw(return_address,3))
            self.assertIn('{'+hex(address)+','+name+',sizeof('+name+')',header)
        # Constructor/assign/append determine the source's NUL-terminated length
        # using the same lstrlenA import. Counted and wide overloads stay intact.
        for address in (0x64c16e,0x64c270,0x64c44f):
            self.assertEqual(b'\xff\x15\x9c\xb2\x66\0',self.raw(address,6))
        target=struct.unpack('<I',self.raw(0x66b29c,4))[0]
        self.assertEqual(b'lstrlenA\0',self.raw(self.base+target+2,9))
        self.assertEqual(self.data,self.original.read_bytes())

    def test_wide_hooks_match_native_overloads_pool_lengths_and_bounded_format(self):
        header=(catalog.ROOT/'client-compat/korean_resources.h').read_text('utf-8')
        expected = {
            'wideConstruct': (0x64c196,b'\xa1\x08\x36\x7b\0'),
            'wideAssign': (0x64c286,b'\x53\x8b\x5c\x24\x08'),
            'widePool': (0x4ea8b0,b'\x8b\x44\x24\x08\x53'),
            'wideAppend': (0x4eabb0,b'\x51\x53\x8b\x5c\x24\x10'),
            'wideFormat': (0x4ebe20,b'\xb8\0\x10\0\0'),
        }
        for name,(address,prologue) in expected.items():
            encoded=re.search(r'const BYTE '+name+r'\[\] = \{([^}]+)\}',header).group(1)
            self.assertEqual(prologue,bytes(int(v.strip(),0) for v in encoded.split(',')))
            self.assertEqual(prologue,self.raw(address,len(prologue)))
            self.assertIn('{'+hex(address)+','+name+',sizeof('+name+')',header)
        self.assertEqual(b'\xc2\x04\0',self.raw(0x64c1df,3))
        self.assertEqual(b'\xc2\x04\0',self.raw(0x64c2c4,3))
        # Native pool count is a UTF-16 substring limit, -1 means all. Keep
        # source slices untranslated; do not use a byte limit on Korean text.
        self.assertEqual(b'\x83\xf8\xff',self.raw(0x4ea8ca,3))
        self.assertEqual(b'\x68\0\x08\0\0',self.raw(0x4ebe3e,5))
        self.assertEqual(b'\x3d\0\x08\0\0',self.raw(0x4ebe4c,5))

    def test_wide_catalogue_uses_original_utf16_bytes_and_separate_resource_transport(self):
        import static_resources
        document=catalog.read_json(catalog.ROOT/'l10n/exe-wide-ui.ko.json')
        rows=static_resources.collect_wide(self.original,document,catalog.validate)
        self.assertEqual(121,len(rows))
        for row in rows:
            address=row['group']*65536+row['index']
            self.assertEqual(row['source']+b'\0\0',self.raw(address,len(row['source'])+2))
        generated=catalog.header(rows)
        self.assertIn('KoreanWideResource',generated)
        self.assertNotIn(r'\u000a',generated)
        self.assertIn(r'\n',generated)
        # ACP edit buffers and generic network names are never selected by
        # matching arbitrary copied text against a translated UI sentence.
        self.assertEqual('전체',document['entries']['786430'])
        self.assertEqual('함대',document['entries']['786428'])
        self.assertEqual('동진영',document['entries']['786420'])
        with self.assertRaisesRegex(ValueError,'hash'):
            static_resources.collect_wide(self.original,dict(document,source_sha256='0'*64),catalog.validate)

    def test_lobby_wide_suffix_pushes_stay_within_original_fixed_arrays(self):
        header=(catalog.ROOT/'client-compat/literal_text.h').read_text('utf-8')
        block=re.search(r'koreanWideLiteralPushes\[\]=\{(.*?)\};',header,re.S).group(1)
        pairs=re.findall(r'\{(0x[0-9a-f]+),(0x[0-9a-f]+)\}',block)
        self.assertEqual(5,len(pairs))
        document=catalog.read_json(catalog.ROOT/'l10n/exe-wide-ui.ko.json')
        for address,source in pairs:
            self.assertEqual(b'\x68'+struct.pack('<I',int(source,16)),self.raw(int(address,16),5))
            translated=document['entries'][format(int(source,16),'x')]
            original=self.raw(int(source,16),32)
            units=next(i for i in range(0,len(original),2) if original[i:i+2]==b'\0\0')//2
            self.assertLessEqual(len(translated.encode('utf-16-le'))//2,units)


if __name__=='__main__':unittest.main()
