"""친구·그룹의 지속적인 native 목록 재표시 경계. 작성: Peppone Choi."""
import re
import unittest
import catalog
import test_display_hooks as display_tests

class Office343RefreshTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def test_native_full_refresh_redecodes_the_retained_cstring(self):
        self.assertEqual(bytes.fromhex('8bf9'),self.raw(0x577662,2))
        self.assertEqual(bytes.fromhex('8b460c6a005350e8bf35f7ff'),self.raw(0x577695,12))
        self.assertEqual(bytes.fromhex('8b4f1883c4045055e8a2e4f8ff'),self.raw(0x5776a1,13))
        self.assertEqual(bytes.fromhex('e803200000'),self.raw(0x575658,5))

    def test_refresh_bridge_captures_edi_list_without_changing_native_parameters(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'officeListRefreshCellBridge\(\).*?__asm \{(.*?)\n    \}',header,re.S).group(1)
        self.assertIn('push edi',body)
        self.assertNotIn('push esi',body)
        self.assertEqual(4,body.count('push dword ptr [esp+0x14]'))
        self.assertIn('call officeListCell',body)
        self.assertIn('ret 0x10',body)

    def test_friend_restore_requires_original_cstring_id_list_root_and_table(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        for check in ['officeFriendRows.begin(reinterpret_cast<uintptr_t>(list),',
                      'officeFriendRows.bind(reinterpret_cast<uintptr_t>(list),id,text)',
                      'officeFriendRows.matches(context,id,narrow)',
                      'const auto narrow=at<const char*>(item,4)',
                      'const unsigned id=at<unsigned>(item,8)',
                      'officeFriendRows.root==reinterpret_cast<uintptr_t>(manager)',
                      'officeFriendRows.table==reinterpret_cast<uintptr_t>(handle)',
                      'officeGroupDisplay.root==reinterpret_cast<uintptr_t>(manager)',
                      'officeGroupDisplay.table==reinterpret_cast<uintptr_t>(handle)',
                      'at<void*>(list,0x18)==manager','at<void*>(list,0x20)==handle']:
            self.assertIn(check,header)
        helper=(catalog.ROOT/'client-compat/office_text.h').read_text('utf-8')
        for check in ['Row rows[100]','length<64','row.id!=id','owner!=list','text[i]!=row.narrow[i]']:
            self.assertIn(check,helper)

    def test_group_restore_uses_the_same_final_cell_boundary_with_native_metadata(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'void __cdecl officeListCell\(.*?\n\}',header,re.S).group()
        self.assertIn('officeGroupDisplay.owner+0x1038',body)
        self.assertIn('officeGroupDisplay.restore(context,at<int>(item,0xc)',body)
        self.assertIn('at<int>(item,0x10),narrow,wide)',body)
        self.assertEqual(1,body.count('>(0x505b50)'))
        self.assertEqual(bytes.fromhex('8b47048946048b4f08894e08'),self.raw(0x577d6a,12))

    def test_whitelist_contains_only_the_new_refresh_call_in_addition_to_prior_calls(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        calls=re.search(r'calls\[\]=\{([^}]+)\}',header).group(1)
        self.assertEqual([0x52cdb0,0x52cdbb,0x52d5e7,0x52d6e9,0x52d833,0x52cf1c,0x576b05,0x52cd5d,0x5776a9],
                         [int(v,16)for v in calls.split(',')])
        self.assertEqual(bytes.fromhex('e8a2e4f8ff'),self.raw(0x5776a9,5))

if __name__=='__main__':unittest.main()
