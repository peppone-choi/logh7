// Colour only the original strategy system-name body call. Author: 최병호.
#pragma once
#include "system_affiliation_data.h"
static logh::SystemAffiliation systemAffiliation;
static void* systemAffiliationWorld=nullptr;
static unsigned systemGridAffiliation(BYTE* world,unsigned grid) {
    unsigned ids[350],count=0;
    for (unsigned i=0;i<350;++i) {
        auto base=world+0x2eb800+i*0x250;
        if (base[0] && at<int>(base,8)==2 && at<unsigned short>(base,0x10)==grid)
            ids[count++]=at<unsigned>(world,0x2eb288+i*4);
    }
    return systemAffiliation.affiliation(ids,count,grid);
}
static void receiveSystemAffiliation(void* world) {
    if (!world) return;
    if (systemAffiliationWorld!=world) {systemAffiliation={};systemAffiliationWorld=world;}
    auto response=static_cast<BYTE*>(world)+0x3facf4;
    if (response[0]>4) return;
    for (unsigned i=0;i<response[0];++i) {
        auto record=response+4+i*0x180;
        systemAffiliation.accept(at<unsigned>(record,0),at<unsigned>(record,8),record[4],record[5]);
    }
}
static unsigned __cdecl systemNameColor(const char* name,unsigned color) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    if (!world || world!=systemAffiliationWorld || !name) return color;
    unsigned kind=0;
    for (unsigned i=3;i<=88;++i) {
        auto text=reinterpret_cast<const char*(__stdcall*)(unsigned)>(0x4c8c90)(i);
        if (text==name) {kind=i;break;}
    }
    if (!kind) return color;
    unsigned grid=5000;
    for (unsigned i=0;i<5000;++i) if (world[0x2c03cc+i]==kind) {grid=i;break;}
    if (grid==5000) return color;
    unsigned affiliation=systemGridAffiliation(world,grid);
    unsigned rgb=logh::systemAffiliationRgb(affiliation);
    if (affiliation>=4 && affiliation<=7) {
        auto palette=reinterpret_cast<const BYTE*>(0x771f68)+affiliation*3;
        rgb=(palette[0]<<16)|(palette[1]<<8)|palette[2];
    }
    return (color&0xff000000)|rgb;
}
static void drawSystemAffiliationIcons(void* map) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto display=*reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!map || !at<BYTE>(map,0) || at<int>(map,4)==8 || !world || !display ||
        world!=systemAffiliationWorld || !at<BYTE>(world,0x2a58f8) || at<BYTE>(world,0x126718) ||
        (at<int>(map,0x4c) && at<float>(map,0x44)>0)) return;
    int width=at<int>(display,0x2a604),height=at<int>(display,0x2a608);
    if (width<743 || width>4096 || height<600 || height>4096) return;
    auto sprites=reinterpret_cast<void**>(0x9d12b8);
    for (unsigned grid=0;grid<5000;++grid) {
        auto node=reinterpret_cast<BYTE*>(0x983098)+grid*64;
        if (!at<void*>(node,0)) continue; // original star node, not a visiting fleet
        float alpha=at<float>(node,0x38);
        if (!_finite(alpha) || alpha<=0) continue;
        unsigned affiliation=systemGridAffiliation(world,grid);
        if (affiliation>9 || !sprites[affiliation]) continue; // no invented mixed/unknown glyph
        float position[3],screen[3];
        // Verified cdecl helpers: identical origin/projection to the original name.
        reinterpret_cast<void(__cdecl*)(float*,int,int)>(0x4d3500)(position,grid%100,grid/100);
        position[0]+=0.55f;
        // 004d89ee subtracts half a grid from z before the name projection.
        // A missing z adjustment produces a camera-dependent upper-left offset.
        position[2]-=0.5f;
        reinterpret_cast<void(__cdecl*)(float*,const float*)>(0x4d2fe0)(screen,position);
        if (!_finite(screen[0]) || !_finite(screen[1]) || screen[0]<-32 || screen[0]>width ||
            screen[1]<0 || screen[1]>height) continue;
        float x=static_cast<float>(static_cast<int>(screen[0])+14);
        float y=static_cast<float>(static_cast<int>(screen[1])-47);
        if (x<0 || x>width-16 || y<0 || y>height-192) continue;
        unsigned color=(static_cast<unsigned>((alpha>1 ? 1 : alpha)*255)<<24)|0xffffff;
        float scale[]={1,1,1};
        // Queue in the existing HUD/sprite phase, before this frame's native flush.
        reinterpret_cast<int(__thiscall*)(void*,const float*,float,float,float,float,void*,int,unsigned,int,int)>(0x4cb2b0)
            (reinterpret_cast<void*>(0x9d29a0),scale,x,y,16,16,sprites[affiliation],0,color,0,0);
    }
}
static unsigned systemNameOriginal=0x4ce350;
static void __declspec(naked) drawSystemName() {
    __asm {
        pushfd
        pushad
        lea edx,[esp+36]
        push dword ptr [edx+16]
        push dword ptr [edx+12]
        call systemNameColor
        add esp,8
        mov [esp+52],eax
        popad
        popfd
        jmp dword ptr [systemNameOriginal]
    }
}
static bool installSystemAffiliation() {
    auto call=reinterpret_cast<BYTE*>(0x4d8b97);
    const BYTE expected[]={0xe8,0xb4,0x57,0xff,0xff};
    BYTE replacement[]={0xe8,0,0,0,0};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&drawSystemName)-call-5);
    // One shared table feeds native grid counts/icons and our system labels.
    // Preserve every non-military entry, and verify this exact CD palette first.
    const BYTE oldPalette[]={0x40,0xc0,0xff,0x40,0x80,0xff,0x96,0xff,0x40,0,0xdc,0x80};
    BYTE palette[12];
    for (unsigned i=0;i<4;++i) {
        unsigned rgb=logh::systemAffiliationRgb(i+4);
        palette[i*3]=static_cast<BYTE>(rgb>>16);palette[i*3+1]=static_cast<BYTE>(rgb>>8);palette[i*3+2]=static_cast<BYTE>(rgb);
    }
    if (memcmp(call,expected,5)) return false;
    return patchBytes(reinterpret_cast<void*>(0x771f74),oldPalette,palette,12) && patchBytes(call,expected,replacement,5);
}

