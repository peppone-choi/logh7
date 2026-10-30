"""친구 목록 최종 표시 경계와 원본 ID 대조. 작성: 최병호."""
import re
import unittest
import catalog
import test_display_hooks as display_tests

class Office342FriendTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def test_native_final_cell_has_list_context_and_four_stack_arguments(self):
        self.assertEqual(bytes.fromhex('8b4e1883c4045057e846f0f8ff'),self.raw(0x576afd,13))
        self.assertEqual(bytes.fromhex('c21000'),self.raw(0x505c29,3))
        self.assertEqual(bytes.fromhex('8d8f38070000e85ea70400'),self.raw(0x52cd57,11))

    def test_row_id_is_native_metadata_not_a_display_name_or_row_index(self):
        self.assertEqual(bytes.fromhex('a340252202'),self.raw(0x5770ff,5))
        self.assertEqual(bytes.fromhex('83c7088b1783c6088916'),self.raw(0x577d60,10))
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('(0x576ec0)(list,row)',header)
        self.assertIn('at<unsigned>(item,8)',header)
        self.assertIn('static_cast<BYTE*>(world)+0x448808',header)

    def test_bridge_preserves_native_stack_ownership_and_argument_order(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'officeListCellBridge\(\).*?__asm \{(.*?)\n    \}',header,re.S).group(1)
        self.assertEqual(4,body.count('push dword ptr [esp+0x14]'))
        stack=['return','handle','text','row','column']
        stack.insert(0,'list')
        for _ in range(4):stack.insert(0,stack[0x14//4])
        stack.insert(0,'manager')
        self.assertEqual(['manager','handle','text','row','column','list'],stack[:6])
        self.assertIn('add esp, 0x18',body)
        self.assertIn('ret 0x10',body)

    def test_only_bound_friend_list_column_zero_can_replace_the_unicode_cell(self):
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'void __cdecl officeListCell\(.*?\n\}',header,re.S).group()
        for check in ['context==officeFriendRows.owner','column==0','row>=0','row<100',
                      'at<void*>(list,0x18)==manager','at<void*>(list,0x20)==handle']:
            self.assertIn(check,body)
        self.assertIn('text=wide.c_str()',body)
        self.assertIn('(manager,handle,text,row,column)',body)
        self.assertNotIn('0x64c25f',body)
        self.assertNotIn('officeFriendDisplay.take',body)

    def test_cache_bounds_and_installer_whitelist(self):
        helper=(catalog.ROOT/'client-compat/office_text.h').read_text('utf-8')
        for check in ['count>100','cache+4+i*0x124','peer+6','j<13','j==13']:
            self.assertIn(check,helper)
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('0x576b05,0x52cd5d',header)
        self.assertIn('0x505b50,0x5774c0',header)
        self.assertIn('officeFriendRows.bind(reinterpret_cast<uintptr_t>(list),id,text)',header)
        self.assertEqual(bytes.fromhex('e846f0f8ff'),self.raw(0x576b05,5))

if __name__=='__main__':unittest.main()
