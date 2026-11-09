"""run-337 버튼·연결 알림·IME 표시의 실제 CD 경계 대조. 작성: Peppone Choi."""
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

    def test_cancel_hook_is_on_the_end_branch_and_preserves_the_native_return(self):
        # END follows START subtraction + decrement; COMPOSITION has its own branch.
        self.assertEqual(bytes.fromhex('2d0d0100007427480f85c2000000'),self.raw(0x4e7254,14))
        self.assertEqual(0x5923a0,self.target(0x4e7267))
        self.assertEqual(bytes.fromhex('83c4045e8ac35b8b4c2408'),self.raw(0x4e726c,11))
        self.assertEqual(0x4ff7f0,self.target(0x4e72af))
        self.assertEqual(0x4ffb50,self.target(0x4e72b9))
        source=(catalog.ROOT/'client-compat/input_editor.h').read_text('utf-8')
        helper=source.split('static void __cdecl finishImeComposition',1)[1].split('static unsigned imeMessageResume',1)[0]
        self.assertIn('logh::imeCompositionCanceled(imeCompositionFlags,',helper)
        self.assertIn('GCS_COMPSTR,nullptr,0)',helper)
        self.assertNotIn('GCS_RESULTSTR',helper)
        self.assertIn('*reinterpret_cast<BYTE*>(0xc51598)=0',helper)
        self.assertIn('imeCompositionHandler=0x4ff7f0',source)
        self.assertIn('reinterpret_cast<void*>(0x4e72af),oldComposition,compositionCall',source)
        self.assertNotIn('ImmSet',helper)
        self.assertNotIn('0x2214640',helper)

    def test_result_gate_replaces_only_the_result_length_query_of_the_two_native_calls(self):
        # Both sites call the same thunk with stdcall args; only their flags differ.
        self.assertEqual(bytes.fromhex('e8c26e1100'),self.raw(0x4ff84b,5))
        self.assertEqual(bytes.fromhex('e8b16e1100'),self.raw(0x4ff85c,5))
        self.assertEqual(0x616712,self.target(0x4ff84b))
        self.assertEqual(0x616712,self.target(0x4ff85c))
        self.assertEqual(bytes.fromhex('ff25'),self.raw(0x616712,2))
        gcs_result=bytes.fromhex('6800080000') # push GCS_RESULTSTR
        gcs_composition=bytes.fromhex('6a08') # push GCS_COMPSTR
        self.assertIn(gcs_result,self.raw(0x4ff83b,16))
        between=self.raw(0x4ff850,12)
        self.assertIn(gcs_composition,between)
        self.assertNotIn(gcs_result,between)
        source=(catalog.ROOT/'client-compat/input_editor.h').read_text('utf-8')
        helper=source.split('static LONG WINAPI queryFreshImeResultLength(HIMC context,DWORD index,LPVOID buffer,DWORD length)',1)[1]
        helper=helper.split('static unsigned imeCompositionHandler',1)[0]
        self.assertIn('if (!(imeCompositionFlags&GCS_RESULTSTR)) return 0;',helper)
        self.assertIn('ImmGetCompositionStringA(context,index,buffer,length)',helper)
        self.assertIn('oldResultLength[]={0xe8,0xc2,0x6e,0x11,0}',source)
        self.assertIn('memcmp(reinterpret_cast<void*>(0x4ff84b),oldResultLength,sizeof(oldResultLength))',source)
        self.assertIn('patchBytes(reinterpret_cast<void*>(0x4ff84b),oldResultLength,resultLengthCall,sizeof(oldResultLength))',source)
        self.assertNotIn('reinterpret_cast<void*>(0x4ff85c)',source)

    def test_chat_getter_narrows_the_native_unicode_submission_on_the_acp_boundary(self):
        self.assertEqual(0x504ea0,self.target(0x516dc0))
        self.assertEqual(0x4eacf0,self.target(0x516dcb))
        self.assertEqual(bytes.fromhex('50'),self.raw(0x516dc5,1))
        self.assertEqual(bytes.fromhex('52'),self.raw(0x516dca,1))
        self.assertEqual(bytes.fromhex('83c408'),self.raw(0x516dd0,3))
        # The source is a direct wide pointer (nullable), not the name pool handle.
        self.assertEqual(bytes.fromhex('85c0'),self.raw(0x4ead1d,2))
        source=(catalog.ROOT/'client-compat/input_editor.h').read_text('utf-8')
        helper=source.split('static void* __cdecl readChatInput',1)[1].split('static const wchar_t* __cdecl decodeImeStatus',1)[0]
        self.assertIn('const wchar_t* text',helper)
        self.assertIn('(output,text)',helper)
        self.assertNotIn('0x4eae30',helper)


if __name__=='__main__':unittest.main()
