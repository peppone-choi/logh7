// Same-connection card refresh after 0356. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include "character_cards.h"

// 004bbfaf (dispatcher case 0356) calls 004c0400(record) with the dispatcher ECX live
// (it pops one argument, so it is called here as thiscall). For the own
// character it compares the stored card rows and, when they differ, posts the
// screen refresh 00517cd0(0x356, record) BEFORE copying the new rows into the
// world slot (+0x270). The card panel is rebuilt from the old rows and stays
// stale until the next login. Post the same refresh once more after the copy,
// only when the own rows really changed.
static constexpr unsigned characterUpdateCall=0x4bbfaf, characterUpdate=0x4c0400, characterRefresh=0x517cd0;

static void __fastcall refreshedCharacterUpdate(void* self,void*,void* record) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? *reinterpret_cast<BYTE**>(world+8) : nullptr;
    logh::CharacterCards before;
    bool known=own && logh::CharacterCards::read(own,0x370,before);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(characterUpdate)(self,record);
    logh::CharacterCards after;
    if (known && logh::CharacterCards::read(own,0x370,after) && !before.same(after))
        reinterpret_cast<void(__cdecl*)(unsigned,void*)>(characterRefresh)(0x356,record);
}
static bool installCharacterRefresh() {
    BYTE expected[5]={0xe8};
    *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(characterUpdate-(characterUpdateCall+5));
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&refreshedCharacterUpdate)-
        reinterpret_cast<const BYTE*>(characterUpdateCall)-5);
    return patchBytes(reinterpret_cast<void*>(characterUpdateCall),expected,replacement,5);
}
