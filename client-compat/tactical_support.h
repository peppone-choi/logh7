// Apply authoritative support completions to live CD tactical objects. Author: Peppone Choi.
#pragma once
#include "tactical_support_data.h"
static BYTE* supportUnit(void* world,unsigned id) {
    if (!world || !id || !at<BYTE>(world,0x126718)) return nullptr;
    for(unsigned i=0;i<600;++i) {
        auto unit=static_cast<BYTE*>(world)+0x12671c+i*0x9ec;
        if (at<BYTE>(unit,0) && at<unsigned>(unit,4)==id) return unit;
    }
    return nullptr;
}
static void __fastcall receivedTacticalRepair(void* world,void*,void* message) {
    auto source=supportUnit(world,at<unsigned>(message,0));
    unsigned previous=source ? at<unsigned>(source,0x8d4) : 0;
    // Preserve the original completion UI/command cleanup, then undo its source-as-target bug.
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x4c15a0)(world,message);
    if (source) {
        at<unsigned>(source,0x8d4)=previous;
        at<unsigned>(source,0x950)=at<unsigned>(message,8);
    }
    auto target=supportUnit(world,at<unsigned>(message,4));
    if (!target || target==source) return;
    unsigned kind=at<unsigned short>(target,0x8bc);
    unsigned maximum=at<unsigned>(world,0x2c1c90+kind*0x2a8),normal=0;
    if (logh::repairedNormal(maximum,at<unsigned>(target,0x8d8),at<unsigned short>(message,12),normal))
        at<unsigned>(target,0x8d4)=normal;
}
static void __fastcall receivedTacticalSupply(void* world,void*,void* message) {
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x4c1680)(world,message);
    auto source=supportUnit(world,at<unsigned>(message,0));
    auto target=supportUnit(world,at<unsigned>(message,4));
    if (source) at<unsigned>(source,0x950)=at<unsigned>(message,8);
    if (target) at<unsigned>(target,0x950)=at<unsigned>(message,12);
}
static void applyTacticalSupplies(void* world) {
    if (!world || !at<BYTE>(world,0x126718)) return;
    unsigned count=at<unsigned short>(world,0x41a364);
    if (count>600) return;
    for(unsigned i=0;i<count;++i) {
        auto row=static_cast<BYTE*>(world)+0x41a368+i*0x58;
        auto unit=supportUnit(world,at<unsigned>(row,0));
        if (!unit || at<BYTE>(unit,8)!=1) continue;
        unsigned kind=at<unsigned short>(unit,0x8bc);
        if(kind>=179 || at<unsigned short>(row,4)!=kind)continue;
        at<unsigned>(unit,0x950)=at<unsigned>(row,0x4c);
        unsigned maximum=at<unsigned>(world,0x2c1c90+kind*0x2a8),normal=0,alive=0;
        if(logh::definitionShipCounts(maximum,at<unsigned short>(row,0x46),at<unsigned short>(row,0x48),normal,alive)) {
            at<unsigned>(unit,0x8d4)=normal;at<unsigned>(unit,0x8d8)=alive;at<unsigned>(unit,0x8dc)=maximum;
        }
    }
}
static void __fastcall importedTacticalUnits(void* world,void*,BYTE announce) {
    reinterpret_cast<void(__thiscall*)(void*,BYTE)>(0x4c32a0)(world,announce);
    // The CD importer leaves existing damage/cargo untouched. Restore absolute
    // counts and cargo by ID/kind from the committed 0325 definition cache.
    applyTacticalSupplies(world);
    applyTacticalLabels(world);
    applyTacticalCommandRange(world);
}
static bool installTacticalSupport() {
    auto repair=reinterpret_cast<BYTE*>(0x4bc4fd),supply=reinterpret_cast<BYTE*>(0x4bc53a);
    const BYTE repairExpected[]={0xe8,0x9e,0x50,0,0},supplyExpected[]={0xe8,0x41,0x51,0,0};
    if (memcmp(repair,repairExpected,5) || memcmp(supply,supplyExpected,5)) return false;
    BYTE a[5]={0xe8},b[5]={0xe8};
    *reinterpret_cast<LONG*>(a+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&receivedTacticalRepair)-repair-5);
    *reinterpret_cast<LONG*>(b+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&receivedTacticalSupply)-supply-5);
    auto initial=reinterpret_cast<BYTE*>(0x4b6b37),joined=reinterpret_cast<BYTE*>(0x4bceda);
    const BYTE initialExpected[]={0xe8,0x64,0xc7,0,0},joinedExpected[]={0xe8,0xc1,0x63,0,0};
    if (memcmp(initial,initialExpected,5) || memcmp(joined,joinedExpected,5)) return false;
    BYTE c[5]={0xe8},d[5]={0xe8};
    *reinterpret_cast<LONG*>(c+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&importedTacticalUnits)-initial-5);
    *reinterpret_cast<LONG*>(d+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&importedTacticalUnits)-joined-5);
    return patchBytes(repair,repairExpected,a,5) && patchBytes(supply,supplyExpected,b,5) &&
        patchBytes(initial,initialExpected,c,5) && patchBytes(joined,joinedExpected,d,5);
}
