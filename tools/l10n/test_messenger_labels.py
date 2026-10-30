"""run-332 메신저 상태·그룹·인원·미치환 서식의 CD 경계 검사. 작성: 최병호."""
import re
import struct
import unittest

import catalog
import test_display_hooks as display_tests


class MessengerLabelTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def test_status_menu_has_three_original_indices_and_preserves_selector_values(self):
        self.assertEqual(bytes.fromhex('6a78eb066a7beb026a776a16'),self.raw(0x54338f,12))
        self.assertEqual(bytes.fromhex('83fe03'),self.raw(0x5433f9,3))
        self.assertEqual(bytes.fromhex('8a85'),self.raw(0x5440de,2))
        # Original sender clears the entire peer structure before setting only
        # its trailing status. The labels never reconstruct or send a record.
        self.assertEqual(bytes.fromhex('33c0b94a0000008d7c242cf3ab'),self.raw(0x5440d1,13))
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        block=re.search(r'messengerStatusCalls\[\]=\{([^}]+)\}',header).group(1)
        calls=[int(value,16)for value in block.split(',')]
        self.assertEqual([0x5433a0,0x545018,0x54503f,0x545066],calls)
        for address in calls:
            raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
            self.assertEqual(0x522010,address+5+struct.unpack_from('<i',raw,1)[0])
        block=re.search(r'messengerStatusCaptions\[\]=\{(.*?)\n\};',header,re.S).group(1)
        labels=re.findall(r'L"([^"]+)"',block)
        self.assertEqual(['대화 가능','자리 비움','접속 해제'],labels)
        self.assertTrue(all(7+4*len(value)<128 for value in labels))
        helper=(catalog.ROOT/'client-compat/messenger_text.h').read_text('utf-8')
        self.assertNotIn('4b78a0',helper)
        self.assertNotIn('0x87',helper) # Unknown status feedback keeps original lookup.

    def test_groups_keep_the_native_integer_count_format_and_separate_string_arguments(self):
        self.assertEqual(b'%s%3d%s\0',self.raw(0x788a94,8))
        self.assertEqual(bytes.fromhex('8b95741b000050526a7f6a16'),self.raw(0x544eef,12))
        self.assertEqual(bytes.fromhex('8b85781b0000506a7f6a16'),self.raw(0x544f87,11))
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        for address,text in [(0x544bac,'온라인 이용자'),(0x544d25,'오프라인 이용자'),
                             (0x544f00,'참가 인원 '),(0x544f97,'참가 인원 '),
                             (0x544eea,'명'),(0x544f81,'명')]:
            self.assertIn('{0x%x,L"%s",{}}'%(address,text),header)
        self.assertEqual(b'\xe8',self.raw(0x544f10,1))

    def test_footer_is_plain_text_because_original_path_never_formats_a_count(self):
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        captions=dict((int(a,16),text)for a,text in re.findall(r'\{(0x[\da-f]+),L"([^"]+)",\{\}\}',header))
        for address in [0x5410a0,0x5445d4,0x5445f2]:
            self.assertNotIn('%',captions[address])
            self.assertNotIn('NO DATA',captions[address])
        self.assertEqual(captions[0x5410a0],captions[0x5445d4])
        self.assertEqual(bytes.fromhex('8b86'),self.raw(0x5445bf,2))
        self.assertEqual(bytes.fromhex('e8'),self.raw(0x544605,1))
        self.assertEqual(0x4eaaf0,0x54460a+struct.unpack('<i',self.raw(0x544606,4))[0])


if __name__=='__main__':unittest.main()
