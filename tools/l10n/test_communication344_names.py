"""수신인·메일상대·본인HUD 이름의 실제 CD 경계. 작성: Peppone Choi."""
import re
import struct
import unittest
import catalog
import test_display_hooks as display_tests

class Communication344NameTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw
    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(raw[0],0xe8)
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_only_five_verified_new_calls_are_installed(self):
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        calls=[int(v,16)for v in re.search(r'calls\[\]=\{([^}]+)\}',header).group(1).split(',')]
        targets=[int(v,16)for v in re.search(r'targets\[\]=\{([^}]+)\}',header).group(1).split(',')]
        self.assertEqual([0x52c6c8,0x53c484,0x53c34c,0x53c366,0x58d3b2],calls)
        for address,target in zip(calls,targets):self.assertEqual(target,self.target(address))
        office=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('return installCommunicationNameLabels()',office)

    def test_recipient_keeps_native_ids_and_updates_only_the_wide_pool(self):
        self.assertEqual(bytes.fromhex('8b4b086aff6a00516a00'),self.raw(0x52c6b0,10))
        self.assertEqual(0x4eaaf0,self.target(0x53c1c6))
        self.assertEqual(bytes.fromhex('8981440a0000'),self.raw(0x53c19c,6))
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        body=re.search(r'void __fastcall selectedMailRecipient.*?\n\}',header,re.S).group()
        self.assertLess(body.index('0x53c180'),body.index('0x4ea8b0'))
        for term in ['kind!=0','extra!=-1','0xa44','0xa48','0xa4c','officeFriendRows.matches','widget+0xb04']:
            self.assertIn(term,body)
        self.assertNotIn('0x64c25f',body)

    def test_mail_counterpart_source_and_native_cache_stride(self):
        self.assertEqual(bytes.fromhex('8d4512'),self.raw(0x53c463,3))
        self.assertEqual(bytes.fromhex('8d9536010000'),self.raw(0x53c470,6))
        self.assertEqual(bytes.fromhex('81c55c070000'),self.raw(0x53c581,6))
        self.assertEqual(bytes.fromhex('b9d7010000f3a5'),self.raw(0x53c5cb,7))
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        for term in ['0x14e8214','0x14e9414','0xa5c:0x2ea50','0xa60:0x2ea54',
                     'record==world+0x457774+index*0x75c','record+(box==0?0xc:0x130)']:
            self.assertIn(term,header)

    def test_mail_row_bridge_passes_ebp_record_and_preserves_native_cstring_ownership(self):
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        body=re.search(r'mailPeerRowBridge\(\).*?__asm \{(.*?)\n    \}',header,re.S).group(1)
        self.assertIn('push ebp',body);self.assertEqual(5,body.count('push dword ptr [esp+0x18]'))
        stack=['return','text','flags','kind','index','extra'];stack.insert(0,'record')
        for _ in range(5):stack.insert(0,stack[0x18//4])
        stack.insert(0,'list')
        self.assertEqual(['list','text','flags','kind','index','extra','record'],stack[:7])
        self.assertIn('ret 0x14',body)
        self.assertIn('(list,text,flags,kind,index,extra)',header)

    def test_mail_refresh_uses_native_cache_index_and_stable_mail_and_peer_ids(self):
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        for term in ['at<int>(item,0x10)','mailPeerFrame.list==list','mailPeerRows.restore',
                     'at<int>(item,0xc),at<int>(item,0x14)','at<const char*>(item,4)']:
            self.assertIn(term,header)
        helper=(catalog.ROOT/'client-compat/mail_peer_text.h').read_text('utf-8')
        for term in ['row.mail!=mail','row.peer!=peer','b.owner!=list','b.root!=root','b.table!=table',
                     'narrow[i]!=row.narrow[i]','index>=100','kind!=2','extra!=-1']:
            self.assertIn(term,helper)
        self.assertIn('mailPeerCellText(list,manager,handle,row,column,wide)',
                      (catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8'))

    def test_hud_preserves_original_rank_order_and_newline_without_changing_cstrings(self):
        self.assertEqual(b'%s%s\n\0',self.raw(0x78d5bc,6))
        self.assertEqual(bytes.fromhex('663d0100'),self.raw(0x58d2ee,4))
        self.assertEqual(0x4eacf0,self.target(0x58d2fd));self.assertEqual(0x4eacf0,self.target(0x58d347))
        header=(catalog.ROOT/'client-compat/communication_labels.h').read_text('utf-8')
        body=re.search(r'void __fastcall hudPersonalNameLabel.*?\n\}',header,re.S).group()
        self.assertLess(body.index('0x5034d0'),body.index('0x503560'))
        for term in ['0x4b5b80','0x4c7290','profile,0x56','profile+0x3a','0x4c8d70']:
            self.assertIn(term,body)
        self.assertNotIn('storeDisplayName',body)

if __name__=='__main__':unittest.main()
