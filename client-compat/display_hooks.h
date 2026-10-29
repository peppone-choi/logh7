// Only verified display-name callers of 004eacf0 use this bridge. Author: 최병호.
#pragma once
#include "display_text.h"

struct DisplayNameCall { unsigned address; BYTE expected[5]; };
static constexpr DisplayNameCall displayNameCalls[]={
    {0x4c8e56,{0xe8,0x95,0x1e,0x02,0x00}}, // shared base display label, UTF-16 +48
    {0x58f2b1,{0xe8,0x3a,0xba,0xf5,0xff}}, // HUD owner/visitor name, UTF-16 +3a
    {0x544c3f,{0xe8,0xac,0x60,0xfa,0xff}}, // native correspondent list, first state
    {0x544dbd,{0xe8,0x2e,0x5f,0xfa,0xff}}, // native correspondent list, second state
    {0x532aff,{0xe8,0xec,0x81,0xfb,0xff}}, // unit information officer labels
    {0x57b0ed,{0xe8,0xfe,0xfb,0xf6,0xff}}  // unit card/role summary labels
};
// 004c7aa0 selects the ship specification's native UTF-16 name at
// world+2c1a78+kind*2a8+c. Its direct wide CString assignment narrows via ACP
// before later Japanese decoding. Keep this display-only call separate from
// the existing 004eacf0 wrappers and from every input/network conversion.
static constexpr DisplayNameCall displayShipSpecCalls[]={
    {0x4c7b5e,{0xe8,0x23,0x47,0x18,0x00}}
};
static void* __fastcall displayShipSpecCString(void* output,void*,const wchar_t* name) {
    void* result=reinterpret_cast<void*(__thiscall*)(void*,const wchar_t*)>(0x64c286)(output,name);
    std::string stored;
    // The native ship specification has 12 UTF-16 units plus its NUL.
    if (result && logh::storeDisplayName(name,stored,13))
        reinterpret_cast<void*(__thiscall*)(void*,const char*)>(0x64c25f)(result,stored.c_str());
    return result;
}
static void* __cdecl displayNameCString(void* output,const wchar_t* name) {
    // Preserve native construction and its exception/allocator ownership.
    void* result=reinterpret_cast<void*(__cdecl*)(void*,const wchar_t*)>(0x4eacf0)(output,name);
    std::string stored;
    if (result && logh::storeDisplayName(name,stored))
        reinterpret_cast<void*(__thiscall*)(void*,const char*)>(0x64c25f)(result,stored.c_str());
    return result;
}
static bool installDisplayNames() {
    for (const auto& row:displayNameCalls)
        if (memcmp(reinterpret_cast<void*>(row.address),row.expected,sizeof(row.expected))) return false;
    for (const auto& row:displayShipSpecCalls)
        if (memcmp(reinterpret_cast<void*>(row.address),row.expected,sizeof(row.expected))) return false;
    for (const auto& row:displayNameCalls) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&displayNameCString)-
            reinterpret_cast<BYTE*>(row.address)-5);
        if (!patchBytes(reinterpret_cast<void*>(row.address),row.expected,call,sizeof(call))) return false;
    }
    for (const auto& row:displayShipSpecCalls) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&displayShipSpecCString)-
            reinterpret_cast<BYTE*>(row.address)-5);
        if (!patchBytes(reinterpret_cast<void*>(row.address),row.expected,call,sizeof(call))) return false;
    }
    return true;
}
