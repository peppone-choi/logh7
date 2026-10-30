"""run-337 버튼·연결 알림·IME 표시의 실제 CD 경계 대조. 작성: 최병호."""
import re
import struct
import unittest

import catalog
import test_display_hooks as display_tests


class Messenger337LabelTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def captions(self):
        source=(catalog.ROOT/'client-compat/messenger_labels.h').read_text('utf-8')
        rows={int(a,16):text for a,text in re.findall(r'\{(0x[\da-f]+),L"([^"]+)",\{\}\}',source)}
        self.assertEqual(7,len(rows))
        self.assertIn('if(group==0x16)',source)
        for address,text in rows.items():
            self.assertEqual(0x522010,self.target(address))
            self.assertLessEqual(len(text),27)
            self.assertFalse(catalog.JAPANESE.search(text))
            self.assertNotIn('%',text)
            self.assertNotIn('NO DATA',text)
        return rows

    def test_factory_buttons_match_the_original_end_and_send_event_routes(self):
        captions=self.captions()
        self.assertEqual('대화 종료',captions[0x541155])
        self.assertEqual('보내기',captions[0x5412a1])
        # These are two type-3 buttons; the intervening index-74 type-2 text
        # heading is a different control and must not become a send button.
        self.assertEqual(bytes.fromhex('c744246458000000c744246803000000'),self.raw(0x541184,16))
        self.assertEqual(bytes.fromhex('c74424645a000000c744246803000000'),self.raw(0x5412b8,16))
        self.assertEqual(bytes.fromhex('8b8574010000'),self.raw(0x543dd3,6))
        self.assertEqual(0x5015f0,self.target(0x543df2))
        self.assertEqual(bytes.fromhex('6a4a6a00c684246001000001'),self.raw(0x543e31,12))
        self.assertEqual(0x4b78a0,self.target(0x543e44))
        self.assertEqual(bytes.fromhex('6a006a008bcd'),self.raw(0x543e49,6))
        self.assertEqual(0x545630,self.target(0x543e4f))
        self.assertEqual(bytes.fromhex('8b857c010000'),self.raw(0x543e5f,6))
        self.assertEqual(0x5015f0,self.target(0x543e82))
        self.assertEqual(bytes.fromhex('6a4b'),self.raw(0x543f4f,2))

    def test_notices_are_plain_text_with_separate_native_name_color_and_newline_appends(self):
        captions=self.captions()
        self.assertEqual(captions[0x545708],captions[0x54578c])
        self.assertEqual(captions[0x545850],captions[0x5458d9])
        self.assertEqual('대화가 연결되었습니다.',captions[0x545708])
        self.assertEqual('대화 요청이 도착했습니다.',captions[0x545850])
        self.assertEqual('대화 연결이 종료되었습니다.',captions[0x5459a2])
        for address,index in [(0x545708,0x84),(0x54578c,0x85),(0x545850,0x82),
                              (0x5458d9,0x83),(0x5459a2,0x81)]:
            self.assertEqual(bytes([0x68,index,0,0,0,0x6a,0x16,0xb9,0,0x74,0x21,2]),self.raw(address-12,12))
        # Operation 2 follows an accepted connection; operation 1 follows an
        # incoming request. Their original peer pointer remains a separate arg.
        self.assertEqual(bytes.fromhex('6a028bcb'),self.raw(0x545216,4))
        self.assertEqual(0x545630,self.target(0x54521a))
        self.assertEqual(bytes.fromhex('6a018bcb'),self.raw(0x54526e,4))
        self.assertEqual(0x545630,self.target(0x545272))
        self.assertEqual(0x503610,self.target(0x545972)) # Native UTF-16 peer name.
        self.assertEqual(bytes.fromhex('6a016a0a'),self.raw(0x54597e,4))
        self.assertEqual(b'\x08\x08\0',self.raw(0x788a90,3))
        self.assertEqual(b'\x08\x0a\0',self.raw(0x788aa8,3))
        self.assertEqual(b'\x08\x09\0',self.raw(0x788aac,3))
        self.assertEqual(b'\n\n\0',self.raw(0x788a84,3))

    def test_ime_indicator_is_an_acp_cstring_redecoded_as_cp932_not_an_input_buffer_offset(self):
        mode=self.raw(0x779a1c,4).decode('utf-16le').rstrip('\0')
        self.assertEqual('英',mode)
        self.assertEqual('e7c8',mode.encode('cp949').hex())
        self.assertEqual('酳',mode.encode('cp949').decode('cp932'))
        self.assertEqual(bytes.fromhex('681c9a7700'),self.raw(0x4ff74d,5))
        self.assertEqual(0x64c286,self.target(0x4ff75d)) # Wide -> native ANSI CString.
        self.assertEqual(0x4ff6a0,self.target(0x5080ad)) # IMM conversion status formatter.
        self.assertEqual(0x4eac60,self.target(0x5080c0))
        self.assertEqual(0x505250,self.target(0x5080cc)) # Separate indicator field.
        self.assertEqual(0x505000,self.target(0x50809e)) # Actual input text setter.
        self.assertEqual(bytes.fromhex('ff2510b16600'),self.raw(0x616724,6))

    def test_ime_patch_adds_only_the_indicator_decoder_and_keeps_existing_input_hooks(self):
        source=(catalog.ROOT/'client-compat/input_editor.h').read_text('utf-8')
        block=re.search(r'const unsigned addresses\[\] = \{([^}]+)\}',source).group(1)
        addresses=[int(a,16) for a in block.split(',')]
        self.assertEqual([0x508092,0x508137,0x4fff69,0x4ffb84,0x516e3b,0x516e5a,0x51603f,0x5080c0],addresses)
        block=re.search(r'const BYTE expected\[\]\[5\] = \{(.*?)\};',source,re.S).group(1)
        rows=re.findall(r'\{([^}]+)\}',block)
        self.assertEqual(len(addresses),len(rows))
        for address,row in zip(addresses,rows):
            self.assertEqual(bytes(int(value,0)for value in row.split(',')),self.raw(address,5))
        self.assertIn('i==7?reinterpret_cast<BYTE*>(&decodeImeStatus)',source)
        helper=(catalog.ROOT/'client-compat/ime_status.h').read_text('utf-8')
        self.assertIn('PRIMARYLANGID(keyboard)==LANG_KOREAN',helper)
        for forbidden in ['ImmSet','SendInput','ActivateKeyboardLayout','SetKeyboardLayout']:
            self.assertNotIn(forbidden,helper)


if __name__=='__main__':unittest.main()
