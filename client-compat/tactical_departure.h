// A destroyed/removed unit may already be absent when its warp notice arrives.
// Keep native character departure processing; flag only an extant tactical unit.
// Author: Peppone Choi.
#pragma once
static unsigned tacticalDepartureResume=0x4c1a60;
static void __declspec(naked) flagDepartingTacticalUnit() {
    __asm {
        pushfd
        test eax,eax
        jz absent
        popfd
        mov byte ptr [eax+0x5b8],1
        mov byte ptr [eax+0x5b9],1
        jmp dword ptr [tacticalDepartureResume]
    absent:
        popfd
        jmp dword ptr [tacticalDepartureResume]
    }
}
static bool installTacticalDeparture() {
    auto site=reinterpret_cast<BYTE*>(0x4c1a52);
    const BYTE expected[]={0xc6,0x80,0xb8,0x05,0,0,1,0xc6,0x80,0xb9,0x05,0,0,1};
    if(memcmp(site,expected,sizeof(expected)))return false;
    BYTE replacement[sizeof(expected)];memset(replacement,0x90,sizeof(replacement));replacement[0]=0xe9;
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&flagDepartingTacticalUnit)-site-5);
    return patchBytes(site,expected,replacement,sizeof(replacement));
}
