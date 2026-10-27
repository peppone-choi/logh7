// Only the edit buffer uses ACP; resource strings remain CP932. Author: 최병호.
#pragma once
#include "input_text.h"

static const wchar_t* __cdecl decodeInputText(const char* text) {
    static thread_local std::wstring converted;
    if (!logh::inputFragment(text, converted)) converted.clear();
    // Both consumers immediately copy the value into the native UTF-16 pool.
    return converted.c_str();
}
static int __cdecl inputPrintableByte(unsigned value) {
    return logh::inputPrintableByte(value);
}
static BYTE __fastcall updateImeEvent(void* ime, void*, void* scratch) {
    // WM_CHAR also invokes this updater. A retained result must only be
    // consumed by the fresh WM_IME_COMPOSITION event that set this flag.
    if (!at<BYTE>(ime,3)) return 0;
    BYTE result=reinterpret_cast<BYTE(__thiscall*)(void*,void*)>(0x4ff820)(ime,scratch);
    at<BYTE>(ime,3)=0;
    return result;
}
static unsigned imeMessageResume=0x4e72c6;
static void __declspec(naked) consumedImeResult() {
    __asm {
        test dword ptr [esi+0xc], 0x800
        setnz al
        pop esi
        pop ebx
        mov ecx, dword ptr [esp+8]
        jmp dword ptr [imeMessageResume]
    }
}
static bool installInputEditor() {
    const unsigned addresses[] = {0x508092,0x508137,0x4fff69,0x4ffb84};
    const BYTE expected[][5] = {{0xe8,0xc9,0x2b,0xfe,0xff},{0xe8,0x24,0x2b,0xfe,0xff},
        {0xe8,0x7b,0x0e,0x10,0},{0xe8,0x97,0xfc,0xff,0xff}};
    // Korean IME may commit without a preceding composition string. The CD's
    // fresh-result branch discards everything except a Japanese full-width
    // space, then inserts a literal space. Consume the actual result instead.
    const BYTE oldResult[]={0x88,0x98,0xa4,0x3e,0x21,0x02,0x66,0x81,0x3d,
        0xa4,0x3e,0x21,0x02,0x81,0x40,0x75,0xb7};
    // Accept 0 < result bytes < 1024 before writing its NUL terminator.
    const BYTE newResult[]={0x85,0xc0,0x7e,0xc4,0x3d,0,4,0,0,0x7d,0xbd,
        0x88,0x98,0xa4,0x3e,0x21,0x02};
    const BYTE oldArgument[]={0x68,0x34,0x9a,0x77,0};
    const BYTE newArgument[]={0x68,0xa4,0x3e,0x21,0x02};
    const BYTE oldReturn[]={0x5e,0x32,0xc0,0x5b,0x8b,0x4c,0x24,8};
    if (memcmp(reinterpret_cast<void*>(0x4ff9fd),oldResult,sizeof(oldResult)) ||
        memcmp(reinterpret_cast<void*>(0x4ffa18),oldArgument,sizeof(oldArgument)) ||
        memcmp(reinterpret_cast<void*>(0x4e72be),oldReturn,sizeof(oldReturn))) return false;
    for (unsigned i=0;i<4;++i)
        if (memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5)) return false;
    for (unsigned i=0;i<4;++i) {
        BYTE call[5]={0xe8};
        auto function=i==3?reinterpret_cast<BYTE*>(&updateImeEvent):
            i==2?reinterpret_cast<BYTE*>(&inputPrintableByte):reinterpret_cast<BYTE*>(&decodeInputText);
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(function-reinterpret_cast<BYTE*>(addresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(addresses[i]),expected[i],call,5)) return false;
    }
    BYTE resultReturn[8]={0xe9,0,0,0,0,0x90,0x90,0x90};
    *reinterpret_cast<LONG*>(resultReturn+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&consumedImeResult)-
        reinterpret_cast<BYTE*>(0x4e72be)-5);
    return patchBytes(reinterpret_cast<void*>(0x4e72be),oldReturn,resultReturn,sizeof(oldReturn)) &&
        patchBytes(reinterpret_cast<void*>(0x4ff9fd),oldResult,newResult,sizeof(oldResult)) &&
        patchBytes(reinterpret_cast<void*>(0x4ffa18),oldArgument,newArgument,sizeof(oldArgument));
}
