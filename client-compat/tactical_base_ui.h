// Fortress charge, authoritative partial base hits and emergency-supply HUD. Author: 최병호.
#pragma once
#include "tactical_base_data.h"
static void __fastcall receivedFortressCharge(void* world,void*,void* message,BYTE pending) {
    // The original ACK handler starts the beam even with a future wait. Its
    // existing pending branch can display that wait without starting the effect.
    if (message && at<unsigned>(message,4)>0) pending=1;
    reinterpret_cast<void(__thiscall*)(void*,void*,BYTE)>(0x4bfa10)(world,message,pending);
}
static void __fastcall receivedBaseDamage(void* world,void*,void* message) {
    if (!message || at<BYTE>(message,9)!=0x81) {
        reinterpret_cast<void(__thiscall*)(void*,void*)>(0x4c0df0)(world,message);return;
    }
    auto target=supportUnit(world,at<unsigned>(message,12));
    if (!target || at<BYTE>(target,8)!=1) return;
    unsigned kind=at<unsigned short>(target,0x8bc);
    if (kind>=179) return;
    auto spec=static_cast<BYTE*>(world)+0x2c1a78+kind*0x2a8;
    logh::TacticalBaseDamage hit;
    if (!hit.read(static_cast<BYTE*>(message),at<unsigned>(spec,0x218),at<unsigned short>(spec,0x288))) return;
    auto base=reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
        (static_cast<BYTE*>(world)+0x126718,at<unsigned>(message,4),0,255,255,255);
    if (!base) return;
    unsigned previous=at<unsigned>(target,0x8d8),destroyed=previous>hit.alive ? previous-hit.alive : 0;
    unsigned shield=0xffffffffu;
    if (hit.face<6) {
        float old=at<float>(target,0x8e8+hit.face*4);
        if (_finite(old))shield=old>hit.shield ? static_cast<unsigned>(old-hit.shield) : 0;
        at<BYTE>(target,0x8e0+hit.face)=1;at<float>(target,0x8e8+hit.face*4)=hit.shield;
    }
    at<unsigned>(target,0x8d4)=hit.normal;at<unsigned>(target,0x8d8)=hit.alive;at<BYTE>(target,0x954)=static_cast<BYTE>(hit.morale);
    // Complete losses get the native 0436 explosion; do not draw that twice.
    if (hit.alive)reinterpret_cast<void(__cdecl*)(void*,void*,unsigned,unsigned,unsigned)>(0x4b3500)
        (base,target,destroyed,shield,0);
}
static logh::TacticalEmergencySupply emergencySupplyStatus;
static void* emergencySupplyWorld=nullptr;
static ULONGLONG emergencySupplyUntil=0;
static void __cdecl receivedEmergencySupplyRecord(void* message) {
    if (!message) return;
    auto world=static_cast<BYTE*>(message)-0x438540;
    if (world!=*reinterpret_cast<BYTE**>(0x7ccffc) || !at<BYTE>(world,0x126718)) return;
    logh::TacticalEmergencySupply state;
    if (!state.read(static_cast<BYTE*>(message))) return;
    if (state.target) {
        auto target=supportUnit(world,state.target);
        if (target)at<unsigned>(target,0x950)=state.supplies;
    }
    emergencySupplyStatus=state;emergencySupplyWorld=world;emergencySupplyUntil=GetTickCount64()+8000;
}
static unsigned emergencySupplyResume=0x4bdd33;
static void __declspec(naked) emergencySupplyReceived() {
    __asm {
        pushfd
        pushad
        push esi
        call receivedEmergencySupplyRecord
        add esp, 4
        popad
        popfd
        jmp dword ptr [emergencySupplyResume]
    }
}
static void drawTacticalBaseStatus() {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc),display=*reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!world || world!=emergencySupplyWorld || !at<BYTE>(world,0x126718) || GetTickCount64()>=emergencySupplyUntil) {
        emergencySupplyWorld=nullptr;return;
    }
    if (!display || !at<void*>(display,0x2a418)) return;
    int width=at<int>(display,0x2a604),height=at<int>(display,0x2a608);
    if (width<743 || width>4096 || height<600 || height>4096) return;
    wchar_t name[32]={},title[96],balance[96];pickerName(emergencySupplyStatus.base,name);
    if (!name[0])_snwprintf_s(name,_countof(name),_TRUNCATE,L"거점 %u",emergencySupplyStatus.base);
    _snwprintf_s(title,_countof(title),_TRUNCATE,L"긴급보급 %ls: %ls",emergencySupplyStatus.target ? L"완료" : L"개방",name);
    if (emergencySupplyStatus.target)_snwprintf_s(balance,_countof(balance),_TRUNCATE,L"부대 보급 가능 %u / 대상 잔량 %u",
        emergencySupplyStatus.available,emergencySupplyStatus.supplies);
    else _snwprintf_s(balance,_countof(balance),_TRUNCATE,L"부대 보급 가능 물자: %u",emergencySupplyStatus.available);
    reinterpret_cast<void(__thiscall*)(void*,float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48),1.0f);
    auto draw=reinterpret_cast<void(__cdecl*)(void*,void*,const wchar_t*,int,int,unsigned,int)>(0x4e8af0);
    draw(at<void*>(display,0x2a418),reinterpret_cast<void*>(0xbffa48),title,16,84,0xff80ff80,6);
    draw(at<void*>(display,0x2a418),reinterpret_cast<void*>(0xbffa48),balance,16,100,0xff80ff80,6);
}
static void __fastcall drawBaseStatusRadar(void* manager,void*,int x,int y) {
    drawSignedRadar(manager,nullptr,x,y);drawTacticalBaseStatus();
}
static bool installTacticalBaseUi() {
    const unsigned sites[]={0x4b9167,0x4bb9c4,0x4bc2fa,0x4bc3f2,0x50d1d2};
    BYTE expected[5][5]={{0xe8,0xa4,0x68,0,0},{0xe8,0x47,0x40,0,0},{0xe8,0xf1,0x4a,0,0},{0xe9,0x3c,0x19,0,0},{0xe8}};
    // Compose with installTacticalRadar, already verified and installed by the caller.
    *reinterpret_cast<LONG*>(expected[4]+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&drawSignedRadar)-reinterpret_cast<BYTE*>(sites[4])-5);
    for(unsigned i=0;i<5;++i)if(memcmp(reinterpret_cast<void*>(sites[i]),expected[i],5))return false;
    const void* targets[]={reinterpret_cast<void*>(&receivedFortressCharge),reinterpret_cast<void*>(&receivedFortressCharge),
        reinterpret_cast<void*>(&receivedBaseDamage),reinterpret_cast<void*>(&emergencySupplyReceived),reinterpret_cast<void*>(&drawBaseStatusRadar)};
    for(unsigned i=0;i<5;++i) {
        BYTE jump[5]={i==3 ? static_cast<BYTE>(0xe9) : static_cast<BYTE>(0xe8)};
        *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(static_cast<const BYTE*>(targets[i])-reinterpret_cast<BYTE*>(sites[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(sites[i]),expected[i],jump,5))return false;
    }
    return true;
}
