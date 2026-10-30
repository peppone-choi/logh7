"""직무 그룹 Unicode 캡처·노드·표시의 CD ABI 경계. 작성: Peppone Choi."""
import re
import struct
import unittest

import catalog
import test_display_hooks as display_tests


class Office341GroupTests(unittest.TestCase):
    setUpClass=classmethod(display_tests.DisplayHookTests.setUpClass.__func__)
    raw=display_tests.DisplayHookTests.raw

    def target(self,address):
        raw=self.raw(address,5);self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_group_batch_start_preserves_the_native_vector_constructor_and_its_twenty_byte_cleanup(self):
        self.assertEqual(0x6004cf,self.target(0x52d5e7))
        self.assertEqual(bytes.fromhex('68d6c064006820334f00682c0100006a04'),self.raw(0x52d5c7,17))
        self.assertEqual(bytes.fromhex('5f5e5bc9c21400'),self.raw(0x600532,7))
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('void __stdcall officeGroupBegin',header)
        self.assertIn('(memory,size,count,construct,destroy)',header)
        body=re.search(r'void __stdcall officeGroupBegin.*?\{(.*?)\n\}',header,re.S).group(1)
        self.assertLess(body.index('officeGroupDisplay.clear()'),body.index('0x6004cf'))

    def test_group_unicode_constructor_bridge_passes_original_arguments_and_saved_register_context(self):
        self.assertEqual(0x4eacf0,self.target(0x52d6e9))
        self.assertEqual(bytes.fromhex('8a8cc684000000'),self.raw(0x52d6ad,7))
        self.assertEqual(bytes.fromhex('8d4c243051'),self.raw(0x52d6e4,5))
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'void __declspec\(naked\) officeGroupConstructBridge\(\) \{(.*?)\n\}',header,re.S).group(1)
        stack={0:'return',4:'output',8:'name'};sp=0;registers={'ebp':'context','ebx':'group'}
        for line in body.splitlines():
            line=line.strip()
            if line.startswith('call '):break
            if line.startswith('push '):
                argument=line[5:]
                if argument in registers:value=registers[argument]
                else:
                    offset=int(re.fullmatch(r'dword ptr \[esp\+(0x[\da-f]+)\]',argument)[1],16)
                    value=stack[sp+offset]
                sp-=4;stack[sp]=value
        self.assertEqual(['output','name','group','context'],[stack[sp+i]for i in range(0,16,4)])
        self.assertIn('add esp, 0x10',body)
        self.assertEqual(bytes.fromhex('83c408'),self.raw(0x52d6ee,3)) # Original two args remain for caller.

    def test_node_binding_uses_group_metadata_and_keeps_the_original_four_space_format(self):
        self.assertEqual(b'    %s\0',self.raw(0x788364,7))
        self.assertEqual(0x535680,self.target(0x52d833))
        self.assertEqual(bytes.fromhex('6a02538d4c2428516a006a0152'),self.raw(0x52d80b,13))
        self.assertEqual(bytes.fromhex('894610894e14895618'),self.raw(0x5356c7,9))
        self.assertEqual(bytes.fromhex('c22000'),self.raw(0x5356d3,3))
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        self.assertIn('(nodes,slot,id,kind,text,value1,icon,group,value4)',header)
        self.assertIn('static_cast<unsigned>(slot)*32+8',header)
        self.assertNotIn('storeDisplayName',header)

    def test_group_row_adder_forwards_original_cstring_and_metadata_before_the_unicode_cell(self):
        self.assertEqual(0x577050,self.target(0x52cf1c))
        self.assertEqual(0x64be4b,self.target(0x52cf15))
        self.assertEqual(bytes.fromhex('8b43188b4b148b5310508b430c515250'),self.raw(0x52cefa,16))
        self.assertEqual(0x5030f0,self.target(0x52cf2c))
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        body=re.search(r'int __fastcall officeGroupRow.*?\{(.*?)\n\}',header,re.S).group(1)
        self.assertIn('(list,text,id,icon,group,extra)',body)
        self.assertLess(body.index('0x577050'),body.index('0x505b50'))
        self.assertIn('wide.c_str(),row,0',body)
        helper=(catalog.ROOT/'client-compat/office_text.h').read_text('utf-8')
        self.assertIn('nodes!=owner+0x5938',helper)
        self.assertIn('list!=owner+0x1038',helper)
        self.assertIn('group>=300',helper)
        self.assertIn('slot>=512',helper)

    def test_place_label_path_keeps_existing_base_name_unicode_and_translated_resource_fragments(self):
        self.assertEqual(0x4c90c0,self.target(0x52d747))
        self.assertEqual(0x4c9010,self.target(0x4c90eb))
        self.assertEqual(0x4c8de0,self.target(0x4c9073))
        self.assertEqual(0x4eacf0,self.target(0x4c8e56))
        self.assertEqual(0x522010,self.target(0x4c8dac))
        self.assertEqual(bytes.fromhex('6a53'),self.raw(0x4c8da5,2))
        existing=(catalog.ROOT/'client-compat/display_hooks.h').read_text('utf-8')
        self.assertIn('{0x4c8e56,',existing)
        header=(catalog.ROOT/'client-compat/office_labels.h').read_text('utf-8')
        calls=[int(v,16)for v in re.search(r'calls\[\]=\{([^}]+)\}',header).group(1).split(',')]
        self.assertEqual([0x52cdb0,0x52cdbb,0x52d5e7,0x52d6e9,0x52d833,0x52cf1c,0x576b05,0x52cd5d,0x5776a9],calls)
        self.assertNotIn(0x4c8e56,calls) # Existing bridge is not replaced or double-hooked.


if __name__=='__main__':unittest.main()
