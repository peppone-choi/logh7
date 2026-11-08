// Flagship information: posture and weapon names from the groups this constmsg has.
// Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <string>
#include "korean_text.h"
#include "flagship_weapon_data.h"

// 0054d4e0 builds the flagship sheet. It reads posture names from group 0x78 (120) with
// the character byte +0x31e, and the four weapon names from group 0x7b (123) with the
// flagship record bytes +0x6e/+0x73/+0x77/+0x7e. The held constmsg.dat has 119 groups:
// the same tables are group 113 (posture: 格納/駐留/航行/戦闘...) and 116 (weapons: 光子砲...),
// seven lower, so 00522010 answered NO TABLE. Only these nine pushes are redirected.
static constexpr unsigned flagshipGroupPushes[]={0x54d8e8,0x54db2c,0x54db46,0x54db6a,0x54db89,0x54e000,0x54e0b9,0x54e16b,0x54e217};

// Those weapon names appear in both sheet layouts: +6e/+73/+77/+7e at 0054db2c.. and again
// at 0054e000... Kind 255 means the slot has no weapon, which 00522010 answers as NO DATA.
// Only these eight calls show that absence as a label; posture and every other caller stay native.
struct FlagshipWeaponLookup { unsigned push,call; };
static constexpr FlagshipWeaponLookup flagshipWeaponLookups[]={
    {0x54db2c,0x54db2e},{0x54db46,0x54db4d},{0x54db6a,0x54db6c},{0x54db89,0x54db8b},
    {0x54e000,0x54e002},{0x54e0b9,0x54e0bb},{0x54e16b,0x54e16d},{0x54e217,0x54e21e}};
static std::string flagshipWeaponAbsentText;

static const char* __fastcall flagshipWeaponName(void* catalog,void*,unsigned group,unsigned index) {
    const char* original=reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(catalog,group,index);
    return logh::flagshipWeaponAbsent(group,index,original) ? flagshipWeaponAbsentText.c_str() : original;
}

static bool installFlagshipInfoGroups() {
    for (unsigned address : flagshipGroupPushes) {
        auto p=reinterpret_cast<const BYTE*>(address);
        if (p[0]!=0x6a || (p[1]!=0x78 && p[1]!=0x7b)) return false;
    }
    for (const auto& row : flagshipWeaponLookups) {
        auto push=reinterpret_cast<const BYTE*>(row.push);
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        if (push[0]!=0x6a || push[1]!=0x7b || memcmp(reinterpret_cast<const void*>(row.call),expected,5)) return false;
    }
    if (!logh::encodeKoreanResource(L"미장착",flagshipWeaponAbsentText)) return false;
    for (unsigned address : flagshipGroupPushes) {
        auto p=reinterpret_cast<const BYTE*>(address);
        const BYTE expected[2]={0x6a,p[1]};
        const BYTE replacement[2]={0x6a,static_cast<BYTE>(p[1]==0x78 ? 0x71 : logh::flagshipWeaponGroup)};
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,2)) return false;
    }
    for (const auto& row : flagshipWeaponLookups) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&flagshipWeaponName)-
            reinterpret_cast<const BYTE*>(row.call)-5);
        if (!patchBytes(reinterpret_cast<void*>(row.call),expected,replacement,5)) return false;
    }
    return true;
}
