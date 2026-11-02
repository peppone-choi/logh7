// Edit buffers and the native IME status CString use ACP. Author: Peppone Choi.
#pragma once
#include "input_text.h"
#include "ime_status.h"

static const wchar_t* __cdecl decodeInputText(const char* text) {
    static thread_local std::wstring converted;
    if (!logh::inputFragment(text, converted)) converted.clear();
    // Consumers immediately copy into the native UTF-16 pool or name field.
    return converted.c_str();
}
static const wchar_t* __cdecl decodeImeStatus(const char* text) {
    static thread_local std::wstring converted;
    const auto keyboard=LOWORD(reinterpret_cast<ULONG_PTR>(GetKeyboardLayout(0)));
    if(!logh::imeStatusFragment(text,converted,keyboard))converted.clear();
    return converted.c_str();
}
static int __cdecl inputPrintableByte(unsigned value) {
    return logh::inputPrintableByte(value);
}
static const wchar_t* __cdecl chatCastLabel(const char* text) {
    // The factory already narrowed its UTF-16 literals through MFC's ACP.
    // Some Japanese characters become '?' on CP949; compare that same
    // conversion before decoding, rather than comparing the lossy result.
    const wchar_t* original[]={L"全体",L"艦隊",L"同陣営"};
    const wchar_t* korean[]={L"전체",L"함대",L"동진영"};
    for (unsigned i=0;i<3;++i) {
        const unsigned addresses[]={0x786430,0x786428,0x786420};
        const auto translated=logh::koreanWideEntry(addresses[i]);
        if (text && translated && !strcmp(text,translated->narrow)) return translated->text;
        char narrowed[32];
        if (text && WideCharToMultiByte(CP_ACP,0,original[i],-1,narrowed,sizeof(narrowed),nullptr,nullptr)>0 &&
            !strcmp(text,narrowed)) return korean[i];
    }
    return decodeInputText(text);
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
    const unsigned addresses[] = {0x508092,0x508137,0x4fff69,0x4ffb84,0x516e3b,0x516e5a,0x51603f,0x5080c0};
    const BYTE expected[][5] = {{0xe8,0xc9,0x2b,0xfe,0xff},{0xe8,0x24,0x2b,0xfe,0xff},
        {0xe8,0x7b,0x0e,0x10,0},{0xe8,0x97,0xfc,0xff,0xff},
        {0xe8,0x20,0x3e,0xfd,0xff},{0xe8,0x01,0x3e,0xfd,0xff},{0xe8,0x1c,0x4c,0xfd,0xff},
        {0xe8,0x9b,0x2b,0xfe,0xff}};
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
    // Confirmed ANSI CString getters in generated surname/given-name and
    // flagship-name submission. Keep resource text on the CP932 boundary.
    const unsigned nameAddresses[]={0x596af5,0x596b10,0x596b67,0x596bc6,0x5978ef,0x597973};
    const BYTE nameExpected[][5]={{0xe8,0x66,0x41,0xf5,0xff},{0xe8,0x4b,0x41,0xf5,0xff},
        {0xe8,0xf4,0x40,0xf5,0xff},{0xe8,0x95,0x40,0xf5,0xff},
        {0xe8,0x6c,0x33,0xf5,0xff},{0xe8,0xe8,0x32,0xf5,0xff}};
    if (memcmp(reinterpret_cast<void*>(0x4ff9fd),oldResult,sizeof(oldResult)) ||
        memcmp(reinterpret_cast<void*>(0x4ffa18),oldArgument,sizeof(oldArgument)) ||
        memcmp(reinterpret_cast<void*>(0x4e72be),oldReturn,sizeof(oldReturn))) return false;
    for (unsigned i=0;i<8;++i)
        if (memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5)) return false;
    for (unsigned i=0;i<6;++i)
        if (memcmp(reinterpret_cast<void*>(nameAddresses[i]),nameExpected[i],5)) return false;
    for (unsigned i=0;i<8;++i) {
        BYTE call[5]={0xe8};
        auto function=i==7?reinterpret_cast<BYTE*>(&decodeImeStatus):i==6?reinterpret_cast<BYTE*>(&chatCastLabel):i==3?reinterpret_cast<BYTE*>(&updateImeEvent):
            i==2?reinterpret_cast<BYTE*>(&inputPrintableByte):reinterpret_cast<BYTE*>(&decodeInputText);
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(function-reinterpret_cast<BYTE*>(addresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(addresses[i]),expected[i],call,5)) return false;
    }
    BYTE resultReturn[8]={0xe9,0,0,0,0,0x90,0x90,0x90};
    for (unsigned i=0;i<6;++i) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&decodeInputText)-
            reinterpret_cast<BYTE*>(nameAddresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(nameAddresses[i]),nameExpected[i],call,5)) return false;
    }
    *reinterpret_cast<LONG*>(resultReturn+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&consumedImeResult)-
        reinterpret_cast<BYTE*>(0x4e72be)-5);
    return patchBytes(reinterpret_cast<void*>(0x4e72be),oldReturn,resultReturn,sizeof(oldReturn)) &&
        patchBytes(reinterpret_cast<void*>(0x4ff9fd),oldResult,newResult,sizeof(oldResult)) &&
        patchBytes(reinterpret_cast<void*>(0x4ffa18),oldArgument,newArgument,sizeof(oldArgument));
}
