"""직무행 종류/인물 선택 이름의 표시 전용 CD 경계. 작성: Peppone Choi."""
import re
import struct
import unittest

import catalog
import msgdat
import test_display_hooks as display_tests


class Office340LabelTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_office_suffix_is_order_suggest_type_not_date_or_an_office_name(self):
        self.assertEqual(b'order_suggest_type=\0',self.raw(0x767d4c,20))
        self.assertEqual(bytes.fromhex('684c7d7600'),self.raw(0x464a58,5))
        self.assertEqual(bytes.fromhex('8a935a020000'),self.raw(0x464a66,6))
        for address in [0x53c809,0x53c95c]:self.assertEqual(0x522010,self.target(address))
        self.assertEqual(bytes.fromhex('6a55eb026a546a16'),self.raw(0x53c7fc,8))
        self.assertEqual(bytes.fromhex('6a55eb026a546a16'),self.raw(0x53c94f,8))
        # Both suffixes append to column 1. Column 2 is formatted separately.
        self.assertEqual(bytes.fromhex('6a01f3a4'),self.raw(0x53c89a,4))
        self.assertEqual(0x576ad0,self.target(0x53c8a3))
        self.assertEqual(0x53cc30,self.target(0x53c8bf))
        self.assertEqual(0x53cc30,self.target(0x53c9fb))
        source=msgdat.decode((self.original.parent.parent/'data/MsgDat/constmsg.dat').read_bytes())
        first=source['indices'][22]
        self.assertEqual(['提案','命令','帝国暦'],source['strings'][first+0x53:first+0x56])
        header=(catalog.ROOT/'client-compat/mail_labels.h').read_text('utf-8')
        self.assertIn('officeKindCalls[]={0x53c809,0x53c95c}',header)
        self.assertIn('logh::officeMailKind(caller,group,index)',header)
        helper=(catalog.ROOT/'client-compat/office_text.h').read_text('utf-8')
        self.assertIn('(0x53u+kind)',helper)
        self.assertNotIn('L"제국력"',helper)

    def test_friend_constructor_and_adder_are_adjacent_and_preserve_native_argument_ownership(self):
        self.assertEqual(0x64c196,self.target(0x52cdb0))
        self.assertEqual(0x577050,self.target(0x52cdbb))
        # Source cache starts world+448808. First peer is cache+4, stride124;
        # its name is cache+a / peer+6. Keep metadata meanings unmodified.
        self.assertEqual(bytes.fromhex('8d96eefeffff'),self.raw(0x52cda3,6))
        self.assertEqual(bytes.fromhex('6aff508b442418505551'),self.raw(0x52cd99,10))
        self.assertEqual(bytes.fromhex('c21400'),self.raw(0x5771ce,3)) # CString + four metadata args.
        self.assertEqual(0x64c0d6,self.target(0x5771b5))
        self.assertEqual(bytes.fromhex('8bc5'),self.raw(0x5771ba,2)) # Return row index.
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('officeFriendDisplay.take(text,name)',header)
        self.assertIn('(list,text,id,value1,value2,value3)',header)
        self.assertIn('name.c_str(),row,0',header)
        self.assertNotIn('storeDisplayName',header)

    def test_visible_name_replacement_uses_existing_unicode_cell_setter_not_the_row_cstring(self):
        self.assertEqual(bytes.fromhex('8b7e20'),self.raw(0x576af2,3))
        self.assertEqual(0x4eac60,self.target(0x576af8))
        self.assertEqual(0x505b50,self.target(0x576b05))
        self.assertEqual(bytes.fromhex('85db7519'),self.raw(0x576b0a,4))
        self.assertEqual(0x64c25f,self.target(0x576b22)) # Native row retains original narrow text.
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('>(0x505b50)',header)
        self.assertNotIn('>(0x64c25f)',header)
        self.assertNotIn('0x4b78a0',header)

    def test_office_tree_byte_width_path_is_preserved_and_not_fed_display_name_escapes(self):
        # Group names are different from friend names. The tree computes the
        # child x-position from strlen(ANSI group label)*6+20, then copies its
        # selected parent and child through 128-byte scratch buffers.
        self.assertEqual(bytes.fromhex('8d0c498d540914'),self.raw(0x52d02b,7))
        self.assertEqual(0x4eacf0,self.target(0x52d6e9))
        self.assertEqual(bytes.fromhex('8d542424'),self.raw(0x52c57e,4))
        self.assertEqual(bytes.fromhex('8d9424a4000000'),self.raw(0x52c5cf,7))
        self.assertEqual(128,0xa4-0x24)
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        addresses=re.search(r'calls\[\]=\{([^}]+)\}',header).group(1)
        self.assertEqual([0x52cdb0,0x52cdbb,0x52d5e7,0x52d6e9,0x52d833,0x52cf1c,0x576b05,0x52cd5d,0x5776a9],
                         [int(v,16)for v in addresses.split(',')])


if __name__=='__main__':unittest.main()
