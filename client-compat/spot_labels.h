// Unicode names in CD strategy spot portraits and roster rows. Author: Peppone Choi.
#pragma once
#include "portrait_label.h"
#include "roster_text.h"
#include "spot_name.h"

static std::wstring spotCharacterLabel(void* character) {
    std::wstring text,part;
    if (at<BYTE>(character,0xa1)) {
        auto name=reinterpret_cast<const wchar_t*>(static_cast<BYTE*>(character)+0xde);
        for (unsigned i=0;i<28 && name[i];++i) text+=name[i];
    } else if (logh::japaneseFragment(reinterpret_cast<const char*>(0x7881d4),part)) text=part;
    text=logh::spotName(text,at<unsigned short>(character,0xfa));
    if (at<unsigned>(character,0x24)==*reinterpret_cast<unsigned*>(0x788ad0) &&
        logh::japaneseFragment(reinterpret_cast<const char*>(0x788ad8),part)) text+=part;
    return text;
}
static void __cdecl setSpotPortraitLabel(void* pool,const char* fallback,void* character) {
    if (!pool) return;
    // Native slots retain the character through their departure fade, even
    // after world membership clears its active byte. Keep its embedded name.
    if (!character) {
        reinterpret_cast<void(__cdecl*)(void*,const char*,int)>(0x4eaaf0)(pool,fallback,-1);
        return;
    }
    auto text=logh::portraitLineBreak(spotCharacterLabel(character));
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
static void* __cdecl constructSpotRow(void* string,void* character,const char* fallback) {
    std::string stored=character ? logh::storedRosterText(spotCharacterLabel(character)) : std::string();
    return reinterpret_cast<void*(__thiscall*)(void*,const char*)>(0x64c144)
        (string,stored.empty() ? fallback : stored.c_str());
}
static void __declspec(naked) spotRowConstructor() {
    __asm {
        // The caller's saved roster-entry pointer is at stack +0x14,
        // below five row arguments, the ctor argument and our return address.
        mov eax, dword ptr [esp+0x30]
        mov eax, dword ptr [eax]
        push dword ptr [esp+4]
        push eax
        push ecx
        call constructSpotRow
        add esp, 0xc
        ret 4
    }
}
static bool installSpotLabels() {
    const unsigned addresses[]={0x549f52,0x54adc1};
    const BYTE expected[][5]={{0xe8,0x99,0x0b,0xfa,0xff},{0xe8,0x7e,0x13,0x10,0}};
    BYTE* functions[]={reinterpret_cast<BYTE*>(&spotPortraitLabel),reinterpret_cast<BYTE*>(&spotRowConstructor)};
    for (unsigned i=0;i<2;++i) if (memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5)) return false;
    for (unsigned i=0;i<2;++i) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(functions[i]-reinterpret_cast<BYTE*>(addresses[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(addresses[i]),expected[i],call,sizeof(call))) return false;
    }
    return true;
}
