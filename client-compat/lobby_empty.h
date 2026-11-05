// The native lobby otherwise shows an empty notice after a valid unassigned session. Author: Peppone Choi.
#pragma once
#include "lobby_empty_data.h"
static void __cdecl showLobbyNotice(void* root,int mode) {
    reinterpret_cast<void(__cdecl*)(void*,int)>(0x51c980)(root,mode);
    if(!logh::emptyPlayableLobby(reinterpret_cast<const unsigned char*>(0x2216c88),0x6d8))return;
    auto label=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,2,1);
    wchar_t text[256];logh::catalogueText(logh::koreanResource(65534,1,0,"NO TABLE"),text);
    if(label && text[0])reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,label,text);
}
static bool installLobbyEmptyNotice() {
    constexpr unsigned address=0x51ab21;
    const BYTE expected[]={0xe8,0x5a,0x1e,0,0};
    if(!strcmp(logh::koreanResource(65534,1,0,"NO TABLE"),"NO TABLE"))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&showLobbyNotice)-reinterpret_cast<BYTE*>(address)-5);
    return patchBytes(reinterpret_cast<void*>(address),expected,call,sizeof(call));
}
