"""편지/명함/메신저 표시의 실제 CD 호출·배열·분기 대조. 작성: Peppone Choi."""
import re
import struct
import unittest

import catalog
import test_display_hooks as display_tests


class CommunicationLabelsTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_namecard_literals_copy_into_native_buffers_before_the_decoder(self):
        header=(catalog.ROOT/'client-compat/namecard_labels.h').read_text('utf-8')
        block=re.search(r'nameCardLiterals\[\]=\{(.*?)\n\};',header,re.S).group(1)
        rows=[tuple(int(v,16)for v in x)for x in re.findall(r'\{(0x[\da-f]+),(0x[\da-f]+)\}',block)]
        self.assertEqual(7,len(rows))
        _,all_rows,_,_,_=catalog.project(self.original.parent.parent/'data/MsgDat/constmsg.dat')
        translations={((r['group']<<16)|r['index']):r for r in all_rows if r.get('table')==65535}
        _,encode=catalog.glyph_encoding([r['text']for r in all_rows])
        for address,source in rows:
            self.assertEqual(b'\xbf'+struct.pack('<I',source),self.raw(address,5))
            row=translations[source];catalog.validate(row['source'].decode('cp932'),row['text'])
            self.assertLessEqual(len(encode(row['text'])),64)
            self.assertLess(54+len(encode(row['text']))+1,128)
        self.assertEqual(bytes.fromhex('f3a5'),self.raw(0x54a5f5,2))
        self.assertEqual(0x5706e0,self.target(0x54a609))

    def test_dynamic_name_capture_is_paired_by_original_scratch_address(self):
        self.assertEqual(0x4eacf0,self.target(0x54966a))
        self.assertEqual(0x4eacf0,self.target(0x54a47d))
        self.assertEqual(0x5706e0,self.target(0x549729))
        self.assertEqual(0x5706e0,self.target(0x54a4f2))
        self.assertEqual(bytes.fromhex('8d4c24145351'),self.raw(0x549664,6))
        self.assertEqual(bytes.fromhex('8d4c241451'),self.raw(0x54a478,5))
        self.assertEqual(bytes.fromhex('8d942414020000'),self.raw(0x549701,7))
        self.assertEqual(bytes.fromhex('8d542414'),self.raw(0x54a4cb,4))
        source=(catalog.ROOT/'client-compat/namecard_labels.h').read_text('utf-8')
        self.assertIn('text==nameCardPendingBuffer',source)
        self.assertIn('nameCardPendingBuffer=nullptr',source)
        self.assertNotIn('strcpy',source)

    def test_mail_and_messenger_captions_override_only_known_lookup_calls(self):
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        block=re.search(r'mailControlCaptions\[\]=\{(.*?)\n\};',header,re.S).group(1)
        rows=re.findall(r'\{(0x[\da-f]+),L"([^"]*)",\{\}\}',block)
        self.assertEqual(29,len(rows));self.assertEqual(29,len({a for a,_ in rows}))
        for address,text in rows:
            self.assertEqual(0x522010,self.target(int(address,16)))
            self.assertLessEqual(len(text),27)
            self.assertFalse(catalog.JAPANESE.search(text))
            self.assertNotIn('%',text)
        for address in (0x53cc60,0x53cc98):self.assertEqual(0x522010,self.target(address))
        self.assertIn('mailEraCalls[]={0x53cc60,0x53cc98}',header)
        self.assertIn('logh::mailCalendarKind(caller,group,index)',header)
        self.assertIn('L"받은 편지함"',header);self.assertIn('L"보낸 편지함"',header)
        self.assertEqual(bytes.fromhex('c7442410c4897800'),self.raw(0x536f5e,8))
        self.assertEqual(bytes.fromhex('c744241018897800'),self.raw(0x537ceb,8))
        self.assertIn('L"본문"',header);self.assertIn('L"편지 작성"',header)

    def test_empty_subject_is_real_acp_text_and_preserves_lead_mail_refresh(self):
        source=(catalog.ROOT/'client-compat/mail_input.h').read_text('utf-8')
        self.assertIn('if (table==0x16 && index==0x4b) text=L"(제목 없음)"',source)
        self.assertIn('WideCharToMultiByte(CP_ACP,WC_NO_BEST_FIT_CHARS',source)
        self.assertIn('mailRefresh.take',source)
        self.assertIn('mailRefresh.observe',source)
        self.assertEqual(0x522010,self.target(0x539e82))
        self.assertEqual(0x522010,self.target(0x53a38b))


if __name__=='__main__':unittest.main()
