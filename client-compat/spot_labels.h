// Only the background-portrait text setter in CD strategy spots. Author: 최병호.
#pragma once
#include "portrait_label.h"

static void __cdecl setSpotPortraitLabel(void* pool,const char* fallback,void* character) {
    if (!pool) return;
    if (!character || !at<BYTE>(character,0)) {
        reinterpret_cast<void(__cdecl*)(void*,const char*,int)>(0x4eaaf0)(pool,fallback,-1);
        return;
    }
    std::wstring text,part;
    if (at<BYTE>(character,0xa1)) {
        auto name=reinterpret_cast<const wchar_t*>(static_cast<BYTE*>(character)+0xde);
        for (unsigned i=0;i<28 && name[i];++i) text+=name[i];
    } else if (logh::japaneseFragment(reinterpret_cast<const char*>(0x7881d4),part)) text=part;
    if (logh::japaneseFragment(reinterpret_cast<const char*>(0x788adc),part)) text+=part;
    auto rank=reinterpret_cast<const char*(__stdcall*)(unsigned)>(0x4c8d70)(at<unsigned short>(character,0xfa));
    if (logh::japaneseFragment(rank,part)) text+=part;
    if (at<unsigned>(character,0x24)==*reinterpret_cast<unsigned*>(0x788ad0) &&
        logh::japaneseFragment(reinterpret_cast<const char*>(0x788ad8),part)) text+=part;
    text=logh::portraitLineBreak(text);
    reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(pool,text.c_str(),-1);
}
static void __declspec(naked) spotPortraitLabel() {
    __asm {
        // Caller saves the current portrait slot at its stack +0x10.
        // Three native arguments and our return address add 0x10 bytes.
        mov eax, dword ptr [esp+0x20]
        mov eax, dword ptr [eax]
        push eax
        push dword ptr [esp+0xc]
        push dword ptr [esp+0xc]
        call setSpotPortraitLabel
        add esp, 0xc
        ret
    }
}
static bool installSpotLabels() {
    const BYTE expected[]={0xe8,0x99,0x0b,0xfa,0xff};
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&spotPortraitLabel)-
        reinterpret_cast<BYTE*>(0x549f52)-5);
    return patchBytes(reinterpret_cast<void*>(0x549f52),expected,call,sizeof(call));
}
