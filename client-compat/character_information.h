// Connect the spot menu to the existing native character information tabs.
// Author: 최병호.
#pragma once
#include "spot_presence.h"
static void* characterInformationManager=nullptr;
static void* characterInformationWorld=nullptr;
static struct SpotInformationWindow {
    void* manager; void* world; void* root;
    unsigned own, target, spot, owner;
} spotInformationWindow={};
static void* currentSpotInformationTarget(void* world,unsigned id) {
    if (!world || !id) return nullptr;
    auto own=at<void*>(world,8);
    if (!own || !at<BYTE>(own,0)) return nullptr;
    for (unsigned i=0;i<600;++i) {
        auto peer=static_cast<BYTE*>(world)+0xc+i*0x370;
        if (at<BYTE>(peer,0) && at<unsigned>(peer,0x24)==id && at<BYTE>(peer,0xa1) &&
            logh::sameSpotOccupant(at<unsigned>(own,0x24),at<unsigned>(own,0x40),at<unsigned>(own,0x44),
                id,at<unsigned>(peer,0x40),at<unsigned>(peer,0x44))) return peer;
    }
    return nullptr;
}
static void updateCharacterInformation(void* manager) {
    characterInformationManager=manager;
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    characterInformationWorld=world;
    auto& state=spotInformationWindow;
    if (!state.root) return;
    // Shared native info slots can be replaced by another information screen.
    // Never dereference a previous world's or replaced window's pointer.
    if (manager!=state.manager || at<void*>(manager,4+13*4)!=state.root) {state={};return;}
    if (!at<BYTE>(state.root,4)) {state={};return;}
    auto own=world ? at<void*>(world,8) : nullptr;
    if (world!=state.world || !own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2a58f8) ||
        at<BYTE>(world,0x3579cc) || at<unsigned>(own,0x24)!=state.own ||
        at<unsigned>(own,0x40)!=state.spot || at<unsigned>(own,0x44)!=state.owner ||
        !currentSpotInformationTarget(world,state.target)) {
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(state.root,0);
        *reinterpret_cast<BYTE*>(0x2221dfc)=0;
        state={};
    }
}
static void __fastcall spotCharacterInformation(void* context,void*) {
    auto character=reinterpret_cast<void*(__thiscall*)(void*)>(0x546810)(context);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto manager=characterInformationManager;
    character=character ? currentSpotInformationTarget(world,at<unsigned>(character,0x24)) : nullptr;
    if (character && world && world==characterInformationWorld && manager && at<BYTE>(world,0x2a58f8) &&
        !at<BYTE>(world,0x3579cc) && !*reinterpret_cast<void**>(0xc9e2f8)) {
        reinterpret_cast<void(__cdecl*)(void*,int)>(0x54c4b0)(manager,0);
        reinterpret_cast<int(__cdecl*)(void*,int,void*)>(0x54c950)(manager,14,character);
        auto root=at<void*>(manager,4+13*4);
        if (root) {
            auto title=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2);
            auto name=std::wstring(L"인물 정보: ")+spotCharacterLabel(character);
            if (title) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,title,name.c_str());
            reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(root);
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
            reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(manager,root);
            *reinterpret_cast<BYTE*>(0x2221dfc)=1;
            auto own=at<void*>(world,8);
            spotInformationWindow={manager,world,root,at<unsigned>(own,0x24),at<unsigned>(character,0x24),
                at<unsigned>(own,0x40),at<unsigned>(own,0x44)};
        }
    }
    reinterpret_cast<void(__thiscall*)(void*)>(0x546850)(context);
}
static bool installCharacterInformation() {
    const BYTE expected[]={0xe8,0xf7,0xd0,0xff,0xff};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&spotCharacterInformation)-reinterpret_cast<BYTE*>(0x549754)-5);
    return patchBytes(reinterpret_cast<void*>(0x549754),expected,replacement,5);
}
