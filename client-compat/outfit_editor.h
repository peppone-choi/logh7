// Restore the native editor's initial tab and unit labels. Author: 최병호.
#pragma once
#include "outfit_editor_labels.h"
static void __fastcall outfitEditorReady(void* editor, void*) {
    reinterpret_cast<void(__thiscall*)(void*)>(0x530e70)(editor);
    int mode=at<int>(editor,0x2c4);
    if ((mode!=2 && mode!=3) || !at<BYTE>(editor,0xc88)) return;
    if (at<int>(editor,0x2d4)==-1) {
        for (int tab=0;tab<4;++tab) if (at<BYTE>(editor,0x2dc+tab)) {
            // The original tab action sets selection/history and rebuilds its
            // layout and rows. It takes the already-loaded path, without queries.
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x531b60)(editor,tab);
            return;
        }
    } else {
        reinterpret_cast<void(__thiscall*)(void*)>(0x531eb0)(editor);
    }
}
static const char* __fastcall supplementBalanceFormat(void*, void*, int, int) {
    // Missing CD constmsg 22/136 and 137 format (label, amount, native unit).
    return "%s: %d%s";
}
static bool installOutfitEditor() {
    const BYTE ready[]={0xe8,0xf1,0x2f,0,0};
    const unsigned labels[]={0x531c5b,0x531c70,0x52fb28,0x52fc25};
    const BYTE oldLabel[]={0x6a,0x22},unitLabel[]={0x6a,0x21};
    const unsigned supplementLabels[]={0x531db4,0x531df3,0x52fae7,0x52fbca};
    const BYTE oldBoatLabel[]={0x6a,0x1f},boatLabel[]={0x6a,0x1e};
    const BYTE oldSupplyName[]={0x6a,0x2b},supplyName[]={0x6a,0x2a};
    const unsigned balanceCalls[]={0x52fc03,0x52fc56};
    const BYTE oldBalanceCalls[][5]={{0xe8,0x08,0x24,0xff,0xff},{0xe8,0xb5,0x23,0xff,0xff}};
    if (memcmp(reinterpret_cast<void*>(0x5320dd),oldSupplyName,sizeof(oldSupplyName))) return false;
    if (memcmp(reinterpret_cast<void*>(0x52de7a),ready,sizeof(ready))) return false;
    for (unsigned address:labels)
        if (memcmp(reinterpret_cast<void*>(address),oldLabel,sizeof(oldLabel))) return false;
    for (unsigned address:supplementLabels)
        if (memcmp(reinterpret_cast<void*>(address),oldBoatLabel,sizeof(oldBoatLabel))) return false;
    for (unsigned i=0;i<_countof(balanceCalls);++i)
        if (memcmp(reinterpret_cast<void*>(balanceCalls[i]),oldBalanceCalls[i],5)) return false;
    if (!installOutfitEditorLabels(false)) return false;
    if (!patchBytes(reinterpret_cast<void*>(0x5320dd),oldSupplyName,supplyName,sizeof(oldSupplyName))) return false;
    for (unsigned address:labels)
        if (!patchBytes(reinterpret_cast<void*>(address),oldLabel,unitLabel,sizeof(oldLabel))) return false;
    for (unsigned address:supplementLabels)
        if (!patchBytes(reinterpret_cast<void*>(address),oldBoatLabel,boatLabel,sizeof(boatLabel))) return false;
    for (unsigned i=0;i<_countof(balanceCalls);++i) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&supplementBalanceFormat)-
            reinterpret_cast<BYTE*>(balanceCalls[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(balanceCalls[i]),oldBalanceCalls[i],call,5)) return false;
    }
    BYTE call[5]={0xe8};
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&outfitEditorReady)-
        reinterpret_cast<BYTE*>(0x52de7a)-5);
    return installOutfitEditorLabels(true) && patchBytes(reinterpret_cast<void*>(0x52de7a),ready,call,sizeof(call));
}
