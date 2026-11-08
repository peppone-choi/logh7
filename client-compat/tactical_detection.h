// Honor authoritative 033b search status on native tactical surfaces. Author: Peppone Choi.
#pragma once
#include "tactical_detection_data.h"
// The CD never reads unit+0x9ac in the audited paths: 004b2c80 recomputes contact from distance alone.
// Every surface therefore applies the server byte after that native result, using native level 4.
enum TacticalDetectionSurface:LONG {
    tacticalDetectionDeltas=1,tacticalDetectionModels=2,tacticalDetectionRadar=4,
    tacticalDetectionMarkers=8,tacticalDetectionPick=16
};
static volatile LONG tacticalDetectionCoverage=0;
static SRWLOCK tacticalDetectionLock=SRWLOCK_INIT;
static logh::TacticalDetectionInbox tacticalDetectionInbox;
static struct {void* world;unsigned grid,character,session;} tacticalDetectionContext={nullptr,5000,0,0};
static unsigned tacticalUnitsUpdateNative=0x4be560;

static unsigned __stdcall decodedTacticalStates(void* record,void* reader) {
    const unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x421f80)(record,reader);
    if(!result) {
        AcquireSRWLockExclusive(&tacticalDetectionLock);
        tacticalDetectionInbox.merge(static_cast<const BYTE*>(record));
        ReleaseSRWLockExclusive(&tacticalDetectionLock);
    }
    return result;
}
static void clearTacticalDetection() {
    AcquireSRWLockExclusive(&tacticalDetectionLock);
    tacticalDetectionInbox.clear();
    ReleaseSRWLockExclusive(&tacticalDetectionLock);
}
// A later import stamps unit+0x9ac from the newest 033b, so rows of a replaced context are never kept.
static void updateTacticalDetectionContext(void* world) {
    const auto grid=tacticalCampGrid(world);
    const auto own=grid<5000 ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own ? at<unsigned>(own,0x24) : 0;
    const unsigned session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
    auto& context=tacticalDetectionContext;
    const bool changed=context.world!=world || context.grid!=grid || context.character!=character || context.session!=session;
    if(grid>=5000 || (changed && context.grid<5000))clearTacticalDetection();
    context={world,grid,character,session};
}
static BYTE* tacticalDetectionUnits(unsigned& ownId) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    ownId=0;
    if(!world || !at<BYTE>(world,0x126718))return nullptr;
    const auto own=at<BYTE*>(world,8);
    if(own)ownId=at<unsigned>(own,0x48);
    return world+0x12671c;
}
// 004c9a80 copies unit+0x95e to record+0xb20 next; 004f2020 sends a value above 3 to hidden model state 5.
static void __cdecl beforeTacticalUnitsUpdate() {
    unsigned ownId;
    const auto units=tacticalDetectionUnits(ownId);
    if(!units)return;
    static logh::TacticalDetectionInbox pending;
    pending.clear();
    AcquireSRWLockExclusive(&tacticalDetectionLock);
    if(tacticalDetectionInbox.count) {pending=tacticalDetectionInbox;tacticalDetectionInbox.clear();}
    ReleaseSRWLockExclusive(&tacticalDetectionLock);
    logh::applyTacticalDetection(units,pending);
    logh::forceTacticalUndetected(units,ownId);
}
static void __declspec(naked) tacticalUnitsUpdate() {
    __asm {
        pushfd
        pushad
        call beforeTacticalUnitsUpdate
        popad
        popfd
        jmp dword ptr [tacticalUnitsUpdateNative]
    }
}
static void __fastcall drawTacticalMarkers(void* renderer,void*) {
    unsigned ownId;
    const auto units=tacticalDetectionUnits(ownId);
    logh::TacticalHiddenMarkers hidden(static_cast<BYTE*>(renderer),units,ownId,false);
    reinterpret_cast<void(__thiscall*)(void*)>(0x4effe0)(renderer);
}
// 004f12b0 is the shared filter of the marker ring, click pick (004ef910) and area pick (004efa90).
static BYTE __fastcall filterTacticalMarker(void* renderer,void*,const BYTE* node,unsigned mask,BYTE third) {
    unsigned ownId;
    const auto units=tacticalDetectionUnits(ownId);
    if(logh::tacticalMarkerHidden(node,units,ownId))return 0;
    return reinterpret_cast<BYTE(__thiscall*)(void*,const BYTE*,unsigned,BYTE)>(0x4f12b0)(renderer,node,mask,third);
}
static bool patchTacticalCall(BYTE* site,const void* target) {
    BYTE expected[5],call[5]={0xe8};
    memcpy(expected,site,5);
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(static_cast<const BYTE*>(target)-site-5);
    return patchBytes(site,expected,call,5);
}
static bool patchSingleTacticalCall(unsigned function,unsigned size,unsigned target,const void* replacement) {
    BYTE* site=nullptr;
    return logh::findTacticalCalls(reinterpret_cast<BYTE*>(function),size,reinterpret_cast<const void*>(target),&site,1)==1 &&
        patchTacticalCall(site,replacement);
}
// The 033b decoder has no direct caller. Its pointer must be unique in the section of the known decoder table.
static unsigned* tacticalStateDecoderSlot() {
    const auto image=reinterpret_cast<BYTE*>(0x400000);
    const auto headers=reinterpret_cast<IMAGE_NT_HEADERS*>(image+reinterpret_cast<IMAGE_DOS_HEADER*>(image)->e_lfanew);
    const auto sections=IMAGE_FIRST_SECTION(headers);
    const unsigned known=0x66c4a0-0x400000;
    unsigned* found=nullptr;
    for(unsigned i=0;i<headers->FileHeader.NumberOfSections;++i) {
        const auto& section=sections[i];
        if(known<section.VirtualAddress || known>=section.VirtualAddress+section.Misc.VirtualSize)continue;
        const auto words=reinterpret_cast<unsigned*>(image+section.VirtualAddress);
        for(unsigned j=0;j<section.Misc.VirtualSize/4;++j) {
            if(words[j]!=0x421f80)continue;
            if(found)return nullptr;
            found=&words[j];
        }
    }
    return found;
}
// Each surface fails closed on its own, and the coverage export reports what the client really patched.
static void installTacticalDetection() {
    LONG coverage=tacticalDetectionRadar;
    if(const auto slot=tacticalStateDecoderSlot()) {
        const unsigned decoder=0x421f80,replacement=reinterpret_cast<unsigned>(&decodedTacticalStates);
        if(patchBytes(slot,&decoder,&replacement,4))coverage|=tacticalDetectionDeltas;
    }
    if(patchSingleTacticalCall(0x4b6840,85,0x4be560,reinterpret_cast<const void*>(&tacticalUnitsUpdate)))coverage|=tacticalDetectionModels;
    if(patchSingleTacticalCall(0x4ec280,31,0x4effe0,reinterpret_cast<const void*>(&drawTacticalMarkers)))coverage|=tacticalDetectionMarkers;
    const struct {unsigned function,size;} pickers[]={{0x4effe0,639},{0x4ef910,307},{0x4efa90,274}};
    BYTE* sites[8];unsigned total=0;bool complete=true;
    for(const auto& picker:pickers) {
        const auto count=logh::findTacticalCalls(reinterpret_cast<BYTE*>(picker.function),picker.size,
            reinterpret_cast<const void*>(0x4f12b0),sites+total,_countof(sites)-total);
        if(!count || count>_countof(sites)-total) {complete=false;break;}
        total+=count;
    }
    if(complete) {
        for(unsigned i=0;i<total;++i)complete=patchTacticalCall(sites[i],reinterpret_cast<const void*>(&filterTacticalMarker)) && complete;
        if(complete)coverage|=tacticalDetectionPick;
    }
    tacticalDetectionCoverage=coverage;
}
