// Only two verified native display callers; stable facility IDs and commands stay native. Author: Peppone Choi.
#pragma once
#include "facility_label_data.h"
static const char* __cdecl nativeFacilityLabel(unsigned kind,unsigned id) {
    wchar_t name[64]={};static thread_local std::string stored;
    if(facilityGuideName(id,name) && logh::facilityLabel(id,kind,name,stored))return stored.c_str();
    return reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8d10)(reinterpret_cast<void*>(0x7cd048),kind);
}
static const char* __cdecl nativeFacilityMenuLabel(unsigned kind,unsigned id) {
    const char* label=nativeFacilityLabel(kind,id);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!label || !own || !own[0] || !id || id/100!=at<unsigned>(own,0x358))return label;
    const BYTE* current=nullptr;const BYTE* spot=nullptr;
    reinterpret_cast<void(__thiscall*)(void*,const BYTE**,const BYTE**,void*)>(0x4c9170)
        (reinterpret_cast<void*>(0x7cd048),&current,&spot,own);
    if(!current && at<unsigned>(own,0x40)!=0)return label;
    const bool sameFacility=current && id==at<unsigned>(const_cast<BYTE*>(current),4);
    // Match Navigation.movePlace and CpPayment's full other-pool substitution.
    std::string fare;
    if(!logh::storeDisplayName(sameFacility ? L" · PCP 5 / 대체 MCP 10" : L" · PCP 10 / 대체 MCP 20",fare))return label;
    static thread_local std::string withFare;
    withFare.assign(label);withFare+=fare;
    return withFare.c_str();
}
static void __declspec(naked) facilityMenuLabel() {
    __asm {
        push dword ptr [edi+4]
        push dword ptr [esp+8]
        call nativeFacilityMenuLabel
        add esp, 8
        ret 4
    }
}
static void __declspec(naked) facilityHudLabel() {
    __asm {
        push dword ptr [eax+4]
        push dword ptr [esp+8]
        call nativeFacilityLabel
        add esp, 8
        ret 4
    }
}
static void pollNativeFacilityNames(void* world) {
    // The information pane owns full descriptions while open. Other views only need page zero.
    if(planetInformation.popup)return;
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !own[0] || !at<BYTE>(world,0x2a58f8))return;
    unsigned base=at<unsigned>(own,0x358);
    auto places=base ? reinterpret_cast<BYTE*(__thiscall*)(void*,unsigned)>(0x4c54d0)(world,base) : nullptr;
    if(!places || at<unsigned>(places,0)!=base || places[4]>36)return;
    unsigned target=0;const BYTE* current=nullptr;const BYTE* spot=nullptr;
    reinterpret_cast<void(__thiscall*)(void*,const BYTE**,const BYTE**,void*)>(0x4c9170)
        (reinterpret_cast<void*>(0x7cd048),&current,&spot,own);
    if(current && facilityGuideNeedsRefresh(at<unsigned>(const_cast<BYTE*>(current),4)))target=at<unsigned>(const_cast<BYTE*>(current),4);
    for(unsigned i=0;!target && i<places[4];++i) {
        unsigned id=at<unsigned>(places,8+i*0xfc+4);
        if(id/100==base && facilityGuideNeedsRefresh(id))target=id;
    }
    if(target)pollFacilityGuide(world,target,true);
}
static bool installFacilityLabels() {
    const unsigned calls[]={0x591590,0x58f21d};
    const BYTE expected[][5]={{0xe8,0x7b,0x77,0xf3,0xff},{0xe8,0xee,0x9a,0xf3,0xff}};
    const BYTE* targets[]={reinterpret_cast<const BYTE*>(&facilityMenuLabel),reinterpret_cast<const BYTE*>(&facilityHudLabel)};
    for(unsigned i=0;i<2;++i)if(memcmp(reinterpret_cast<void*>(calls[i]),expected[i],5))return false;
    for(unsigned i=0;i<2;++i) {
        BYTE patch[5]={0xe8};*reinterpret_cast<LONG*>(patch+1)=static_cast<LONG>(targets[i]-reinterpret_cast<const BYTE*>(calls[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(calls[i]),expected[i],patch,5))return false;
    }
    return true;
}
