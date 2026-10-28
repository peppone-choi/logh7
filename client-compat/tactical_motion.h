// Reconcile authoritative origins before the CD builds its local path. Author: 최병호.
#pragma once
#include "tactical_motion_data.h"
static void receivedTacticalMotion(void* world,void* message,BYTE pending,bool parallel) {
    logh::TacticalMoveOrigins origins;
    bool apply=!pending && world && at<BYTE>(world,0x126718) && origins.read(static_cast<BYTE*>(message));
    if (apply) for (unsigned i=0;i<origins.count;++i) {
        const auto& origin=origins.units[i];auto unit=supportUnit(world,origin.id);
        if (!unit) continue;
        float pose[]={origin.x,0,origin.y,0,origin.direction,0};
        memcpy(unit+0x14,pose,sizeof(pose));
        at<BYTE>(unit,0x61)=0;
        // Update the existing render object directly; flag +0x61 would discard the
        // scheduled wait by replacing +0x64 with the current game clock.
        for (unsigned j=0;j<600;++j) {
            auto model=reinterpret_cast<BYTE*>(0x7db3c8)+j*0xb4c;
            if (at<BYTE>(model,0) && at<unsigned>(model,4)==origin.id) {
                memcpy(model+0x88c,pose,sizeof(pose));break;
            }
        }
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,BYTE)>(parallel?0x4bf320:0x4be8f0)(world,message,pending);
    // A stationary snapshot has no interpolation work. Clear the old render plan
    // as well, or the initial importer can advance its previous path once more.
    if (apply && origins.stationary) {
        auto unit=supportUnit(world,origins.units[0].id);
        if (unit) {
            at<BYTE>(unit,0x60)=0;at<BYTE>(unit,0x62)=0;
            for (unsigned j=0;j<600;++j) {
                auto model=reinterpret_cast<BYTE*>(0x7db3c8)+j*0xb4c;
                if (at<BYTE>(model,0) && at<unsigned>(model,4)==origins.units[0].id) {
                    logh::applyStationaryTacticalPose(unit,model,origins.units[0]);break;
                }
            }
        }
    }
}
static void __fastcall receivedNormalMotion(void* world,void*,void* message,BYTE pending) {
    receivedTacticalMotion(world,message,pending,false);
}
static void __fastcall receivedParallelMotion(void* world,void*,void* message,BYTE pending) {
    receivedTacticalMotion(world,message,pending,true);
}
static bool installTacticalMotion() {
    const unsigned sites[]={0x4b8faf,0x4b901a,0x4bb606,0x4bb69d};
    const BYTE expected[4][5]={{0xe8,0x3c,0x59,0,0},{0xe8,1,0x63,0,0},
        {0xe8,0xe5,0x32,0,0},{0xe8,0x7e,0x3c,0,0}};
    for (unsigned i=0;i<4;++i) if (memcmp(reinterpret_cast<void*>(sites[i]),expected[i],5)) return false;
    for (unsigned i=0;i<4;++i) {
        auto site=reinterpret_cast<BYTE*>(sites[i]);BYTE jump[5]={0xe8};
        auto target=i%2 ? &receivedParallelMotion : &receivedNormalMotion;
        *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(target)-site-5);
        if (!patchBytes(site,expected[i],jump,5)) return false;
    }
    return true;
}
