// Flagship information: find the ship specification by kind, not by table position.
// Author: Peppone Choi.
#pragma once
#include <windows.h>

// 0x30b fills the 200-entry specification table at world+0x413604 (0x8c bytes each) in the
// order the server lists ships; every entry starts with its kind. 0054d4e0 alone indexes the
// table with the character's flagship kind (+0x31c) times 0x8c, so a flagship such as kind 89
// showed the 89th listed production ship's name (destroyer VI) beside its battleship class.
// The 19-byte address computation at 0054d558 becomes a call that scans for the kind.
static constexpr unsigned flagshipSpecIndex=0x54d558;
static constexpr unsigned shipSpecTable=0x413604, shipSpecCount=200, shipSpecSize=0x8c;

static BYTE* __cdecl findFlagshipSpec(BYTE* world,unsigned kind) {
    BYTE* table=world+shipSpecTable;
    for (unsigned i=0;i<shipSpecCount;++i) {
        WORD stored=*reinterpret_cast<const WORD*>(table+i*shipSpecSize);
        if (stored==kind || static_cast<WORD>((stored>>8)|(stored<<8))==kind) return table+i*shipSpecSize;
    }
    return table+kind*shipSpecSize; // Unlisted kind: keep the original position lookup.
}
// In: eax = kind, edx = world. Out: esi = entry; ecx keeps the original kind*35 value.
static void __declspec(naked) flagshipSpecEntry() {
    __asm {
        push eax
        push edx
        push eax
        push edx
        call findFlagshipSpec
        add esp, 8
        mov esi, eax
        pop edx
        pop eax
        lea ecx, [eax*8]
        sub ecx, eax
        lea ecx, [ecx+ecx*4]
        ret
    }
}
static bool installFlagshipSpecLookup() {
    const BYTE expected[19]={0x8d,0x0c,0xc5,0x00,0x00,0x00,0x00,0x2b,0xc8,0x8d,0x0c,0x89,
        0x8d,0xb4,0x8a,0x04,0x36,0x41,0x00};
    BYTE replacement[19];
    replacement[0]=0xe8;
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&flagshipSpecEntry)-
        reinterpret_cast<const BYTE*>(flagshipSpecIndex)-5);
    for (int i=5;i<19;++i) replacement[i]=0x90;
    return patchBytes(reinterpret_cast<void*>(flagshipSpecIndex),expected,replacement,19);
}
