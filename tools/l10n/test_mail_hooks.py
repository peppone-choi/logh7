"""편지·메신저 원본 호출/버퍼/수명 경계 대조. 작성: 최병호."""
import re
import struct
import unittest

import catalog
import test_display_hooks


class MailHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        test_display_hooks.DisplayHookTests.setUpClass.__func__(cls)

    def raw(self,address,length):
        return test_display_hooks.DisplayHookTests.raw(self,address,length)

    def call_target(self,address):
        raw=self.raw(address,5)
        self.assertEqual(0xe8,raw[0])
        return address+5+struct.unpack_from('<i',raw,1)[0]

    def test_every_patch_matches_complete_original_instruction(self):
        header=(catalog.ROOT/'client-compat/mail_input.h').read_text('utf-8')
        rows=re.findall(r'\{(0x[0-9a-f]+),(\d+),\{([^}]+)\},MAIL_TARGET\((\w+)\),(true|false)\}',header)
        self.assertEqual(17,len(rows))
        targets={
            'sendMailInput':0x4b4b60,'mailTitlePool':0x4eaaf0,'mailBodyPool':0x4eaaf0,
            'finishMailInput':0x53a670,'mailDefaultTitle':0x522010,
            'mailLineLeadByte':0x53cf60,'messengerEnterBridge':0x4eaaf0,
            'messengerButtonBridge':0x4eaaf0,'displayNameCString':0x4eacf0}
        seen=set()
        for address,size,encoded,function,call in rows:
            address=int(address,16);size=int(size);self.assertNotIn(address,seen);seen.add(address)
            expected=bytes(int(value.strip(),0) for value in encoded.split(','))
            self.assertEqual(size,len(expected));self.assertEqual(expected,self.raw(address,size))
            if call=='true':
                self.assertEqual(5,size);self.assertEqual(targets[function],self.call_target(address))
            else:
                # Complete 6-byte LEA r32,[r32+disp32] or ADD r32,imm32.
                self.assertEqual(6,size)
                self.assertIn(expected[:2],(b'\x8d\x83',b'\x81\xc3',b'\x81\xc1',b'\x8d\x95',b'\x81\xc2'))
        self.assertEqual(0x53c563,0x53c54f+2+0x12)
        self.assertEqual(b'\x8d\x4c\x24\x24',self.raw(0x53c54f,4))

    def test_native_field_spans_include_terminators_and_keep_native_senders(self):
        # In the native stack record, title[128] ends at the two-byte body
        # length; body[512] ends before the SEH frame. Neither is a name tag.
        self.assertEqual(b'\x8d\xbc\x24\x92\x02\0\0',self.raw(0x4b4c27,7))
        self.assertEqual(b'\x66\x89\x94\x24\x9a\x03\0\0',self.raw(0x4b4c54,8))
        self.assertEqual(256,0x39a-8-0x292) # Two more pending pushes change ESP.
        for address,selector in [(0x4b4c88,0x4c),(0x543d83,0x4b),(0x543f4f,0x4b)]:
            self.assertEqual(bytes([0x6a,selector]),self.raw(address,2))
        # Native UTF-16 assignments and edit-limit handling remain available.
        self.assertEqual(b'\x8b\x44\x24\x08',self.raw(0x4ea8b0,4))
        self.assertEqual(b'\x56\x8b\x74\x24\x08',self.raw(0x505000,5))

    def test_list_bridge_stack_arguments_match_original_callers(self):
        header=(catalog.ROOT/'client-compat/mail_input.h').read_text('utf-8')
        # Original list/read call loads ECX after 3 pushes; refresh loads ESI
        # after its text push. Include those pushes when interpreting offsets.
        self.assertEqual(b'\x8b\x4c\x24\x20',self.raw(0x53b0bc,4))
        self.assertEqual(b'\x8b\x74\x24\x1c',self.raw(0x53c517,4))
        for name,fields,expected in [
            ('mailReadListBridge',{0x14:'list',0x264:'row'},['list','row','eax']),
            ('mailRefreshListBridge',{0x18:'list'},['list','ebx','ebp'])]:
            body=re.search(r'void __declspec\(naked\) '+name+r'\(\) \{(.*?)\n\}',header,re.S).group(1)
            registers={name:name for name in ('eax','ebx','ecx','edx','esi','edi','ebp')}
            stack=dict(fields);sp=0
            def push(value):
                nonlocal sp
                sp-=4;stack[sp]=value
            for line in body.splitlines():
                line=line.split('//')[0].strip()
                if line.startswith('call '):break
                if line=='pushfd':push('flags')
                elif line=='pushad':
                    saved=sp
                    for register in ('eax','ecx','edx','ebx','esp','ebp','esi','edi'):
                        push(saved if register=='esp' else registers[register])
                elif (match:=re.fullmatch(r'push (\w+)',line)):push(registers[match[1]])
                elif (match:=re.fullmatch(r'mov (\w+), dword ptr \[esp\+(0x[0-9a-f]+)\]',line)):
                    registers[match[1]]=stack[sp+int(match[2],16)]
            self.assertEqual(expected,[stack[sp+offset] for offset in (0,4,8)])

    def test_bypassed_cstring_lifetimes_and_messenger_rejection_cleanup(self):
        pairs=[(0x53bc6a,0x53bd6d),(0x53bd8f,0x53bec1),(0x53b041,0x53b0d4),
               (0x53c4c3,0x53c55e),(0x5436bf,0x5437aa)]
        for constructor,destructor in pairs:
            self.assertEqual(0x4eacf0,self.call_target(constructor))
            self.assertEqual(0x64c0d6,self.call_target(destructor))
        # Rejection skips sending AND editor clear; both initialized temporary
        # pools still take their own original cleanup and exception-state write.
        for cleanup,pool_dtor,clear_call in [(0x543da1,0x543dad,0x543d9c),(0x543f67,0x543f73,0x543f62)]:
            self.assertGreater(cleanup,clear_call)
            self.assertEqual(b'\x8d\x4c\x24\x10',self.raw(cleanup,4))
            self.assertEqual(0x4ea740,self.call_target(pool_dtor))
        # Enter bridge's rejected path removes return + 3 cdecl args. The
        # successful native path removes 3 args + 2 getter args before send.
        self.assertEqual(b'\x83\xc4\x14',self.raw(0x543d5e,3))
        self.assertEqual(b'\x83\xc4\x14',self.raw(0x543f2a,3))

    def test_original_list_and_messenger_controls_and_literal_log_route(self):
        self.assertEqual(b'    \0',self.raw(0x788a00,5))
        self.assertEqual(b'Re:\0',self.raw(0x788a08,4))
        self.assertEqual(b'\n\n\0',self.raw(0x788a84,3))
        self.assertEqual('：\n　',self.raw(0x788a88,6)[:-1].decode('cp932'))
        self.assertEqual(b'\x08\x08\0',self.raw(0x788a90,3))
        header=(catalog.ROOT/'client-compat/literal_text.h').read_text('utf-8')
        address=int(re.search(r'koreanSelectionLogPush=(0x[0-9a-f]+)',header).group(1),16)
        self.assertEqual(b'\x68\x58\xbd\x78\0',self.raw(address,5))
        self.assertEqual(0x64c144,self.call_target(address+5))
        self.assertEqual(0x517db0,self.call_target(address+10))
        self.assertEqual(0x5035a0,self.call_target(0x517e3f))
        entries=catalog.read_json(catalog.ROOT/'l10n/exe-ui.ko.json')['entries']
        self.assertEqual('시설/스폿 선택을 완료했습니다.\n',entries['78bd58'])
        self.assertEqual(self.data,self.original.read_bytes())


if __name__=='__main__':unittest.main()
