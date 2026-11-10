// Keep authoritative UTF-16 location names out of the CD's ANSI round trip.
// Author: Peppone Choi.
#pragma once
#include "hud_system_label_data.h"
static void __fastcall setHudBaseText(void* root,void*,void* widget,const char* original) {
    // This consumes the original by-value CString and retains its cleanup.
    reinterpret_cast<void(__thiscall*)(void*,void*,const char*)>(0x5034d0)(root,widget,original);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    auto base=own ? pickerBase(at<unsigned>(own,0x358)) : nullptr;
    if(!base || !widget || !at<BYTE>(widget,8))return;
    unsigned kind=logh::infoShort(base,0x14);
    if(kind>1)return;
    wchar_t name[13]={};memcpy(name,base+0x48,12*sizeof(wchar_t));
    if(!name[0])return;
    std::wstring label=kind==1 ? L"요새 " : L"행성 ";label+=name;
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,label.c_str());
}
// HUD slot 13 (system title / grid) follows the canonical strategy grid; see hud_system_label_data.h.
// Same world gates, HUD table and wide setter as refreshHudPersonalName; the catalogue lookups are
// the native 0x4c8b70/0x4c8c90 pair. Any missing piece keeps the native label and retries next frame.
static logh::HudSystemLabelRefresh hudSystemLabelRefresh;
static void refreshHudSystemLabel() {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    if (!world || !at<BYTE>(world,0x2a58f8) || at<BYTE>(world,0x3579cc) || !at<BYTE>(world,0x2b6a6c)) return;
    auto hud=reinterpret_cast<BYTE*>(0x22225c8);
    auto root=at<void*>(hud,0);
    const unsigned grid=at<unsigned>(world,0x2b6a70);
    if (!root || !at<BYTE>(root,4) || !hudSystemLabelRefresh.due(root,grid)) return;
    auto widget=at<void*>(hud,logh::hudSystemLabelWidget);
    if (!widget || !at<BYTE>(widget,8)) return;
    auto cell=reinterpret_cast<const BYTE*(__stdcall*)(int,int)>(logh::hudSystemCatalogueCell)(grid%100,grid/100);
    if (!cell) return;
    const bool system=cell[1]==3;
    std::wstring name;
    if (system) {
        if (cell[0]<3 || cell[0]>88) return;
        auto catalog=reinterpret_cast<const char*(__stdcall*)(unsigned)>(logh::hudSystemCatalogueName)(cell[0]);
        if (!catalog) return;
        // Catalogue names are Korean in the private CP932 glyph range (as planet_directory.h).
        if (!logh::japaneseFragment(catalog,name)) {
            wchar_t raw[64]={};MultiByteToWideChar(932,0,catalog,-1,raw,_countof(raw));name=raw;
        }
    }
    wchar_t label[96];
    if (!logh::hudSystemLabel(grid,system,name.c_str(),label,_countof(label))) return;
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,label);
    hudSystemLabelRefresh.applied(root,grid);
}
static bool installHudText() {
    // Only HUD ID 14's text assignment, after its normal CString copy.
    const BYTE expected[]={0xe8,0xea,0x40,0xf7,0xff};
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&setHudBaseText)-reinterpret_cast<BYTE*>(0x58f3e1)-5);
    return patchBytes(reinterpret_cast<void*>(0x58f3e1),expected,call,5);
}
