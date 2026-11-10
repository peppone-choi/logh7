// HUD spot label for the player's own residence. Author: Peppone Choi.
#pragma once
#include "home_spot_label_data.h"
// 0058ee70 calls 004c9170 and, when its spot pointer is null, assigns catalog group 0x68
// (99 after the HUD group patch) index 0x1a through the call at 0058f33f. For a residence
// 004c9170 also needs own+0xa1/+0x120 rows, so the own home stays on that fallback.
// Only this call is redirected: native lookup first, then the native spot name 004c8cf0
// (group 74, same thiscall shape as 004c8d10) when the own record and facility cache agree.
static constexpr unsigned homeSpotFallbackCall=0x58f33f, homeSpotFallbackIndex=0x1a;
static const char* __fastcall homeSpotHudLabel(void* catalog,void*,unsigned group,unsigned index) {
    const char* original=reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(catalog,group,index);
    if(index!=homeSpotFallbackIndex)return original;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !own[0])return original;
    const BYTE* current=nullptr;const BYTE* spot=nullptr;
    reinterpret_cast<void(__thiscall*)(void*,const BYTE**,const BYTE**,void*)>(0x4c9170)
        (reinterpret_cast<void*>(0x7cd048),&current,&spot,own);
    if(spot || !logh::ownResidenceSpot(at<unsigned>(own,0x24),at<unsigned>(own,0x44),at<unsigned>(own,0x40),current))
        return original;
    const char* name=reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8cf0)
        (reinterpret_cast<void*>(0x7cd048),logh::residenceSpotKind);
    return name && name[0] ? name : original;
}
static bool installHomeSpotLabel() {
    const BYTE expected[5]={0xe8,0xcc,0x2c,0xf9,0xff}; // call 00522010
    auto push=reinterpret_cast<const BYTE*>(homeSpotFallbackCall-7);
    if(push[0]!=0x6a || (push[1]!=0x68 && push[1]!=99))return false;
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&homeSpotHudLabel)-
        reinterpret_cast<const BYTE*>(homeSpotFallbackCall)-5);
    return patchBytes(reinterpret_cast<void*>(homeSpotFallbackCall),expected,call,5);
}
