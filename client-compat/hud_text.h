// Keep authoritative UTF-16 location names out of the CD's ANSI round trip.
// Author: Peppone Choi.
#pragma once
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
static bool installHudText() {
    // Only HUD ID 14's text assignment, after its normal CString copy.
    const BYTE expected[]={0xe8,0xea,0x40,0xf7,0xff};
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&setHudBaseText)-reinterpret_cast<BYTE*>(0x58f3e1)-5);
    return patchBytes(reinterpret_cast<void*>(0x58f3e1),expected,call,5);
}
