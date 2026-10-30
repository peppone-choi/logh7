// Outfit rows in the information list: ordinal prefix. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <string>
#include "display_text.h"

// 0054bf40 builds an outfit row as catalog(99,1) + "%d" number + catalog(83,kind).
// The client expects the ordinal prefix there ("第"), but in the held constmsg group 99
// is the ability list, so rows read "統率1艦隊". Only this call is redirected; the
// prefix is the Korean ordinal used elsewhere for outfit titles ("제1함대").
static constexpr unsigned outfitOrdinalCall=0x54c34a, catalogLookup=0x522010;

static const char* __fastcall outfitOrdinalPrefix(void* catalog,void*,unsigned group,unsigned index) {
    static std::string stored;
    if (group==0x63 && index==1 && (!stored.empty() || logh::storeDisplayName(L"제",stored))) return stored.c_str();
    return reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(catalogLookup)(catalog,group,index);
}
static bool installOutfitListNames() {
    BYTE expected[5]={0xe8};
    *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(catalogLookup-(outfitOrdinalCall+5));
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&outfitOrdinalPrefix)-
        reinterpret_cast<const BYTE*>(outfitOrdinalCall)-5);
    return patchBytes(reinterpret_cast<void*>(outfitOrdinalCall),expected,replacement,5);
}
