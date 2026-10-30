"""run-339 날짜·편지 안내·공용 인물 선택의 CD 문맥 대조. 작성: 최병호."""
import re
import struct
import unittest

import catalog
import msgdat
import test_display_hooks as display_tests


class Mail339LabelTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_real_date_uses_viewer_allegiance_three_and_preserves_native_309_year_offset(self):
        self.assertEqual(bytes.fromhex('8a4128'),self.raw(0x53cc4a,3))
        self.assertEqual(bytes.fromhex('3c03577438'),self.raw(0x53cc52,5))
        self.assertEqual(bytes.fromhex('6a566a16b900742102'),self.raw(0x53cc57,9))
        self.assertEqual(bytes.fromhex('6a576a16b900742102'),self.raw(0x53cc8f,9))
        for address in [0x53cc60,0x53cc98]:self.assertEqual(0x522010,self.target(address))
        self.assertEqual(bytes.fromhex('bdcbfeffff'),self.raw(0x53cc88,5)) # -309
        self.assertEqual(bytes.fromhex('8d8c2a1b030000'),self.raw(0x53cd5d,7)) # base year 795
        self.assertEqual(b'%d/%2d/%2d %02d:%02d\0',self.raw(0x788a2c,21))
        self.assertEqual(b'%s %s\0',self.raw(0x788a24,6))
        # The personal list refresh and both office list sides use this same
        # formatter, not a per-row string catalogue or the office kind suffix.
        for address in [0x53c538,0x53c8bf,0x53c9fb]:self.assertEqual(0x53cc30,self.target(address))
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        block=re.search(r'mailEraCalls\[\]=\{([^}]+)\}',header).group(1)
        self.assertEqual([0x53cc60,0x53cc98],[int(v,16)for v in block.split(',')])

    def test_calendar_prefix_is_compact_and_fits_before_the_adjacent_numeric_buffer(self):
        self.assertEqual(bytes.fromhex('8d542410'),self.raw(0x53cc79,4))
        self.assertEqual(bytes.fromhex('8d542410'),self.raw(0x53ccb1,4))
        # Five number arguments are already pushed at this LEA: 0x34-20=0x20,
        # so prefix +0x10 and numeric +0x20 leave exactly 16 prefix bytes.
        self.assertEqual(bytes.fromhex('8d542434'),self.raw(0x53cd65,4))
        self.assertEqual(16,0x34-5*4-0x10)
        source=msgdat.decode((self.original.parent.parent/'data/MsgDat/constmsg.dat').read_bytes())
        start=source['indices'][22]
        self.assertEqual(['帝国暦','宇宙暦','50音順'],source['strings'][start+0x55:start+0x58])
        _,rows,_,_,_=catalog.project(self.original.parent.parent/'data/MsgDat/constmsg.dat')
        _,encode=catalog.glyph_encoding([r['text']for r in rows])
        for label in ['제국력','우주력']:
            self.assertEqual(6,len(encode(label)))
            self.assertLessEqual(len(encode(label))+1,16)
            self.assertGreater(6+4*len(label)+1,16) # Display-name escape cannot fit.
        helper=(catalog.ROOT/'client-compat/mail_caption_text.h').read_text('utf-8')
        self.assertIn('strlen(row->text)>=16',helper)
        self.assertNotIn('storeDisplayName',helper)
        # These old calls branch on office-row +25a, rather than viewer +28.
        self.assertEqual(bytes.fromhex('8a875a020000'),self.raw(0x53c7e0,6))
        self.assertEqual(bytes.fromhex('8a875a020000'),self.raw(0x53c933,6))
        self.assertIn('caller==0x53cc65 && index==0x56',helper)
        self.assertIn('caller==0x53cc9d && index==0x57',helper)

    def test_list_and_compose_footers_are_distinct_static_labels_with_native_view_modes(self):
        # Original mode 1 exposes the wide list footer 4e; mode 2 exposes the
        # shorter compose footer 4f. Mode 2 also clears the actual two editors.
        self.assertEqual((0,0x4e),struct.unpack('<ii',self.raw(0x671c60+840+16*8,8)))
        self.assertEqual((0,0x4f),struct.unpack('<ii',self.raw(0x671c60+1680+5*8,8)))
        self.assertEqual(bytes.fromhex('83fd02'),self.raw(0x53a845,3))
        self.assertEqual(0x505000,self.target(0x53a8a7))
        self.assertEqual(0x505000,self.target(0x53a905))
        self.assertEqual(bytes.fromhex('c74424644e000000c744246801000000'),self.raw(0x537ed3,16))
        self.assertEqual(bytes.fromhex('c74424644f000000b80100000089442468'),self.raw(0x537f6e,17))
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        self.assertIn('L"목록에서 편지를 선택하세요.",mailListHint',header)
        self.assertIn('L"받는 이를 고르고 편지를 작성하세요.",mailComposeHint',header)
        self.assertEqual(0x522010,self.target(0x53c2d4))
        self.assertEqual(0x4eaaf0,self.target(0x53c2e7)) # Plain help, no printf args.

    def test_shared_picker_title_and_footer_calls_preserve_friends_offices_and_confirm_controls(self):
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        calls=[int(v,16)for v in re.search(r'mailHelpCalls\[\]=\{([^}]+)\}',header,re.S).group(1).split(',')]
        self.assertEqual([0x537eb2,0x537f4e,0x53c2d4,0x529d3f,0x52a0fa,0x52d309,0x52d57b,0x52d8e9],calls)
        for address in calls:self.assertEqual(0x522010,self.target(address))
        self.assertEqual(bytes.fromhex('c744242848000000c744242c07000000'),self.raw(0x529d66,16))
        self.assertEqual(bytes.fromhex('c744242844000000c744242c01000000'),self.raw(0x52a126,16))
        for address in [0x529f0f,0x529fb0,0x52a1a0]:self.assertEqual(0x522010,self.target(address))
        self.assertIn('L"인물 선택",mailAddressTitle',header)
        self.assertIn('L"인물을 선택한 뒤 결정을 누르세요.",mailAddressHint',header)
        self.assertIn('caller==0x529d44 && group==0x16 && index==0',header)
        # Loading/no-target feedback (indices 7/8) keeps its original path.
        helper=(catalog.ROOT/'client-compat/mail_caption_text.h').read_text('utf-8')
        self.assertIn('if(group!=0x16 || index!=4)return 3',helper)
        self.assertNotIn('0x52d203',helper)


if __name__=='__main__':unittest.main()
