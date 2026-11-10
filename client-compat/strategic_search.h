// Explicit empty strategic search report on the CD 0b0d importer. Author: Peppone Choi.
#pragma once
#include "strategic_search_data.h"
// The server sends a clear report only to clients that advertised it in 0b03. The marked row has
// already cleared its power/camp across the map at the seam; skipping the group insert leaves no fake unit.
static volatile LONG strategicSearchClearInstalled=0;
static unsigned strategicSearchContinueAddress=logh::strategicSearchSeamContinue;
static unsigned strategicSearchRedrawAddress=logh::strategicSearchRedraw;
static BYTE __cdecl strategicSearchClearRow(const BYTE* report) {
    return logh::strategicSearchClearReport(report) ? 1 : 0;
}
// Replaces the 6-byte jbe at 004bfe1a; entered with its incoming flags, ECX = decoded report and
// EDX = first group pointer. Nothing from 004bfe20 is replayed: ordinary reports resume there intact,
// so the row loop's jl 004bfe22 keeps landing on original code. EAX/ECX/EDX are preserved.
static void __declspec(naked) strategicSearchImport() {
    __asm {
        jbe redraw
        push eax
        push ecx
        push edx
        push ecx
        call strategicSearchClearRow
        add esp, 4
        test al, al
        pop edx
        pop ecx
        pop eax
        jnz redraw
        jmp dword ptr [strategicSearchContinueAddress]
    redraw:
        jmp dword ptr [strategicSearchRedrawAddress]
    }
}
// Replaces only 004b49a0's serializer call, which is patched after the importer seam succeeded.
static unsigned __fastcall sendStrategicSearch(void* world,void*,char seconds,unsigned selector,BYTE* record) {
    logh::advertiseStrategicSearchClear(selector,record);
    return reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,seconds,selector,record);
}
// Optional: on any mismatch neither hook is used and the server keeps sending native reports only.
static void installStrategicSearchClear() {
    auto seam=reinterpret_cast<BYTE*>(logh::strategicSearchSeam);
    auto send=logh::strategicSearchSendCall(reinterpret_cast<BYTE*>(logh::strategicSearchSender),
        logh::strategicSearchSenderSize,reinterpret_cast<const void*>(0x4b78a0));
    if(!send || !logh::strategicSearchSeamIntact(seam))return;
    BYTE jump[sizeof(logh::strategicSearchSeamBytes)]={0xe9,0,0,0,0,0x90};
    *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&strategicSearchImport)-seam-5);
    if(!patchBytes(seam,logh::strategicSearchSeamBytes,jump,sizeof(jump)))return;
    // Advertise only after the importer can honour the clear row.
    if(patchTacticalCall(send,reinterpret_cast<const void*>(&sendStrategicSearch)))strategicSearchClearInstalled=1;
}
