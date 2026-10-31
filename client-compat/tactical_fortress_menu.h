// Restore fortress commands from the authenticated character's exact office seat.
// Author: Peppone Choi.
#pragma once
#include "tactical_fortress_menu_data.h"
static bool selectedCommandFortress(unsigned base) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if (!world || !own || !own[0] || !at<BYTE>(world,0x126718) ||
        !logh::commandsFortress(own,0x370,base)) return false;
    return reinterpret_cast<void*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
        (world+0x126718,base,0,255,255,255)!=nullptr;
}
static volatile LONG ownSupremeCommander=0;
static unsigned __stdcall decodedCommandCorps(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x422d80)(record,reader);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    int chief=!result && own ? logh::corpsChief(static_cast<const BYTE*>(record),at<unsigned>(own,0x24)) : -1;
    if(chief>=0)InterlockedExchange(&ownSupremeCommander,chief);
    return result;
}
static void __cdecl fortressCommandMenu(void* manager,BYTE wait,unsigned tab,unsigned forts,unsigned ships) {
    if (manager && tab==4) {
        BYTE enabled[24];logh::commanderMenu(InterlockedCompareExchange(&ownSupremeCommander,0,0)!=0,enabled);
        auto menu=at<void*>(manager,0xd4);
        if (menu) reinterpret_cast<void(__thiscall*)(void*,unsigned,const BYTE*,unsigned,unsigned)>(0x508930)
            (menu,1,enabled,6,24);
    }
    if (manager && tab==5) {
        BYTE enabled[24]={};
        unsigned base=*reinterpret_cast<unsigned*>(0x2215528);
        if (forts==1 && selectedCommandFortress(base)) enabled[6]=enabled[9]=enabled[15]=enabled[21]=enabled[22]=1;
        auto menu=at<void*>(manager,0xd4);
        if (menu) reinterpret_cast<void(__thiscall*)(void*,unsigned,const BYTE*,unsigned,unsigned)>(0x508930)
            (menu,1,enabled,6,24);
    }
    reinterpret_cast<void(__cdecl*)(void*,BYTE,unsigned,unsigned,unsigned)>(0x519bf0)(manager,wait,tab,forts,ships);
}
static BYTE __cdecl stopSelectedFortress() {
    if (*reinterpret_cast<unsigned*>(0x77a8f8)!=5 || *reinterpret_cast<unsigned*>(0x221666c)!=1) return 0;
    unsigned base=*reinterpret_cast<unsigned*>(0x2215528);
    if (!selectedCommandFortress(base)) return 0;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    if (!at<BYTE>(world,0x35837e)) return 0;
    auto own=at<BYTE*>(world,8);
    unsigned character=reinterpret_cast<unsigned(__cdecl*)()>(0x4b4a90)();
    if (character!=at<unsigned>(own,0x24)) return 0;
    unsigned record[4]={reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4c53b0)(world),0,character,base};
    reinterpret_cast<void(__thiscall*)(void*,BYTE,unsigned short,void*)>(0x4b78a0)(world,0,0x5f,record);
    return 1;
}
static unsigned originalFortressStopState=0x50eec6,completedFortressStopState=0x5108f2;
static bool __cdecl selectedFortressSupport() {
    auto ctx=*reinterpret_cast<BYTE**>(0x2215e2c);
    if (!ctx || *reinterpret_cast<unsigned*>(0x77a8f8)!=5 || *reinterpret_cast<unsigned*>(0x221666c)!=1) return false;
    unsigned state=at<unsigned>(ctx,4);
    return (state==0x12 || state==0x13 || state==0x14) && selectedCommandFortress(*reinterpret_cast<unsigned*>(0x2215528));
}
static void __fastcall fortressSupportRow(void* menu,void*,void* widget,const wchar_t* original,int row,int column) {
    wchar_t caption[128];const wchar_t* text=original;
    if(menu && widget && row>=0 && row<32 && column==0 && selectedFortressSupport()) {
        // CD menu 57 attaches trush/status_window.tga test buttons to rows.
        // Its renderer forces these over the text even when the child is hidden.
        // Detach only those exact row/button links, keeping the native sheet.
        for(unsigned i=0;i<8;++i) {
            auto button=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(menu,1,10+i);
            if(!button)continue;
            bool linked=false;
            for(unsigned col=0;col<3;++col)if(at<void*>(menu,0x9334+i*12+col*4)==button)linked=true;
            if(!linked)continue;
            reinterpret_cast<void(__thiscall*)(void*,void*,void*,int,BYTE)>(0x506020)(menu,widget,button,i,1);
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(menu,button,0);
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(menu,button,0);
        }
        auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
        unsigned id=reinterpret_cast<unsigned*>(0x2214cb0)[row];
        auto unit=reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
            (world+0x126718,id,1,255,255,255);
        if(unit && at<unsigned short>(unit,0x8bc)<179) {
            // Native 4c7aa0 narrows the server's Unicode name through ANSI.
            // Keep the actual model name and the live damage counts in UTF-16.
            wchar_t name[33];wcsncpy_s(name,reinterpret_cast<const wchar_t*>(world+0x2c1a78+
                at<unsigned short>(unit,0x8bc)*0x2a8+12),32);
            unsigned alive=at<unsigned>(unit,0x8d8),normal=at<unsigned>(unit,0x8d4);
            if(name[0]) {
                _snwprintf_s(caption,_countof(caption),_TRUNCATE,L"%s (%u척, 손상 %u)",name,alive,alive>normal?alive-normal:0);
                text=caption;
            }
        }
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)(menu,widget,text,row,column);
}
static unsigned moraleReadyResume=0x5100c2,moraleConsumeResume=0x5100d1,moraleFortressContinue=0x5100d7;
static unsigned supportReadyResume=0x5103a6,supportConsumeResume=0x510595,supportFortressContinue=0x51059b;
// These shared handlers otherwise read and consume the player's flagship readiness.
// Preserve all registers, including the selection count and the already pushed UI argument.
static void __declspec(naked) fortressMoraleReady() {
    __asm {
        pushfd
        pushad
        call selectedFortressSupport
        test al,al
        jz original
        popad
        popfd
        mov cl,1
        jmp dword ptr [moraleReadyResume]
    original:
        popad
        popfd
        mov cl,byte ptr [eax+0x8c5]
        jmp dword ptr [moraleReadyResume]
    }
}
static void __declspec(naked) fortressMoraleConsume() {
    __asm {
        pushfd
        pushad
        call selectedFortressSupport
        test al,al
        jz original
        popad
        popfd
        jmp dword ptr [moraleFortressContinue]
    original:
        popad
        popfd
        mov byte ptr [eax+0x8c5],0
        jmp dword ptr [moraleConsumeResume]
    }
}
static void __declspec(naked) fortressSupportReady() {
    __asm {
        pushfd
        pushad
        call selectedFortressSupport
        test al,al
        jz original
        popad
        popfd
        mov dl,1
        jmp dword ptr [supportReadyResume]
    original:
        popad
        popfd
        mov dl,byte ptr [ecx+0x8c5]
        jmp dword ptr [supportReadyResume]
    }
}
static void __declspec(naked) fortressSupportConsume() {
    __asm {
        pushfd
        pushad
        call selectedFortressSupport
        test al,al
        jz original
        popad
        popfd
        jmp dword ptr [supportFortressContinue]
    original:
        popad
        popfd
        mov byte ptr [eax+0x8c5],0
        jmp dword ptr [supportConsumeResume]
    }
}
static void* __cdecl selectedFortressCannon() {
    auto ctx=*reinterpret_cast<BYTE**>(0x2215e2c);
    if (!ctx || at<unsigned>(ctx,4)!=9 || *reinterpret_cast<unsigned*>(0x77a8f8)!=5 ||
        *reinterpret_cast<unsigned*>(0x221666c)!=1 || *reinterpret_cast<BYTE*>(0x22166a9)!=8) return nullptr;
    unsigned base=*reinterpret_cast<unsigned*>(0x2215528);
    if (!selectedCommandFortress(base)) return nullptr;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    return reinterpret_cast<void*(__cdecl*)(void*,unsigned,BYTE,BYTE,BYTE,BYTE)>(0x4c7cd0)
        (world+0x126718,base,0,255,255,255);
}
static unsigned cannonReadyResume=0x50f477,cannonBaseResume=0x50f4fb,cannonDirectionResume=0x50f550;
static unsigned cannonSendResume=0x50f72d,cannonFortressSend=0x50f746;
static unsigned cannonDone=0x50f8c7,cannonStateEight=0x50f78d,cannonShipSend=0x50f7ce;
static void __declspec(naked) fortressCannonDone() {
    __asm {
        pushfd
        pushad
        call selectedFortressCannon
        test eax,eax
        jz original
        popad
        popfd
        jmp dword ptr [cannonDone]
    original:
        popad
        popfd
        cmp dword ptr [ebp+4],8
        jne ships
        jmp dword ptr [cannonStateEight]
    ships:
        jmp dword ptr [cannonShipSend]
    }
}
static void __declspec(naked) fortressCannonReady() {
    __asm {
        pushfd
        pushad
        call selectedFortressCannon
        test eax,eax
        jz original
        popad
        popfd
        mov al,1
        jmp dword ptr [cannonReadyResume]
    original:
        popad
        popfd
        mov al,byte ptr [ecx+0x8c5]
        jmp dword ptr [cannonReadyResume]
    }
}
static void __declspec(naked) fortressCannonBase() {
    __asm {
        pushfd
        pushad
        call selectedFortressCannon
        test eax,eax
        jz original
        mov dword ptr [esp+0x1c],eax
        popad
        popfd
        mov esi,eax
        mov dword ptr [esp+0x28],eax
        mov edi,dword ptr ds:[0x7ccffc]
        mov edi,dword ptr [edi+8]
        mov edi,dword ptr [edi+0x24]
        jmp dword ptr [cannonDirectionResume]
    original:
        popad
        popfd
        mov eax,dword ptr ds:[0x2216674]
        jmp dword ptr [cannonBaseResume]
    }
}
static void __declspec(naked) fortressCannonSend() {
    __asm {
        pushfd
        pushad
        call selectedFortressCannon
        test eax,eax
        jz original
        popad
        popfd
        jmp dword ptr [cannonFortressSend]
    original:
        popad
        popfd
        mov eax,dword ptr [esp+0x2c]
        mov cl,byte ptr [eax+0x8c5]
        jmp dword ptr [cannonSendResume]
    }
}
static void __declspec(naked) fortressStopState() {
    __asm {
        pushfd
        pushad
        call stopSelectedFortress
        test al,al
        jz original
        popad
        popfd
        mov dword ptr [ebp+4],1
        jmp dword ptr [completedFortressStopState]
    original:
        popad
        popfd
        jmp dword ptr [originalFortressStopState]
    }
}
static bool installFortressCommandMenu() {
    const BYTE rowExpected[5]={0xe8,0xe4,0x56,0xff,0xff};
    if(memcmp(reinterpret_cast<void*>(0x510467),rowExpected,sizeof(rowExpected)))return false;
    const unsigned sites[]={0x50d8a0,0x510c7a};
    const BYTE expected[2][5]={{0xe8,0x4b,0xc3,0,0},{0xe8,0x71,0x8f,0,0}};
    for(unsigned i=0;i<2;++i)if(memcmp(reinterpret_cast<void*>(sites[i]),expected[i],5))return false;
    if (*reinterpret_cast<unsigned*>(0x512278)!=originalFortressStopState) return false;
    const unsigned cannonSites[]={0x50f471,0x50f4f6,0x50f723,0x50f787},lengths[]={6,5,10,6};
    const BYTE cannonExpected[4][10]={{0x8a,0x81,0xc5,8,0,0},{0xa1,0x74,0x66,0x21,2},
        {0x8b,0x44,0x24,0x2c,0x8a,0x88,0xc5,8,0,0},{0x83,0x7d,4,8,0x75,0x41}};
    const void* cannonTargets[]={reinterpret_cast<void*>(&fortressCannonReady),reinterpret_cast<void*>(&fortressCannonBase),
        reinterpret_cast<void*>(&fortressCannonSend),reinterpret_cast<void*>(&fortressCannonDone)};
    for(unsigned i=0;i<4;++i)if(memcmp(reinterpret_cast<void*>(cannonSites[i]),cannonExpected[i],lengths[i]))return false;
    const unsigned supportSites[]={0x5100bc,0x5100ca,0x5103a0,0x51058e},supportLengths[]={6,7,6,7};
    const BYTE supportExpected[4][7]={{0x8a,0x88,0xc5,8,0,0},{0xc6,0x80,0xc5,8,0,0,0},
        {0x8a,0x91,0xc5,8,0,0},{0xc6,0x80,0xc5,8,0,0,0}};
    const void* supportTargets[]={reinterpret_cast<void*>(&fortressMoraleReady),reinterpret_cast<void*>(&fortressMoraleConsume),
        reinterpret_cast<void*>(&fortressSupportReady),reinterpret_cast<void*>(&fortressSupportConsume)};
    for(unsigned i=0;i<4;++i)if(memcmp(reinterpret_cast<void*>(supportSites[i]),supportExpected[i],supportLengths[i]))return false;
    for(unsigned i=0;i<2;++i) {
        BYTE call[5]={0xe8};
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&fortressCommandMenu)-reinterpret_cast<BYTE*>(sites[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(sites[i]),expected[i],call,5))return false;
    }
    for(unsigned i=0;i<4;++i) {
        BYTE jump[10];memset(jump,0x90,sizeof(jump));jump[0]=0xe9;
        *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(static_cast<const BYTE*>(cannonTargets[i])-reinterpret_cast<BYTE*>(cannonSites[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(cannonSites[i]),cannonExpected[i],jump,lengths[i]))return false;
    }
    for(unsigned i=0;i<4;++i) {
        BYTE jump[7];memset(jump,0x90,sizeof(jump));jump[0]=0xe9;
        *reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(static_cast<const BYTE*>(supportTargets[i])-reinterpret_cast<BYTE*>(supportSites[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(supportSites[i]),supportExpected[i],jump,supportLengths[i]))return false;
    }
    unsigned replacement=static_cast<unsigned>(reinterpret_cast<uintptr_t>(&fortressStopState));
    if(!patchBytes(reinterpret_cast<void*>(0x512278),&originalFortressStopState,&replacement,sizeof(replacement)))return false;
    const unsigned corpsDecoder=0x422d80,corpsReplacement=static_cast<unsigned>(reinterpret_cast<uintptr_t>(&decodedCommandCorps));
    if(memcmp(reinterpret_cast<void*>(0x66c400),&corpsDecoder,4) ||
       !patchBytes(reinterpret_cast<void*>(0x66c400),&corpsDecoder,&corpsReplacement,4))return false;
    BYTE rowCall[5]={0xe8};
    *reinterpret_cast<LONG*>(rowCall+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&fortressSupportRow)-reinterpret_cast<BYTE*>(0x510467)-5);
    return patchBytes(reinterpret_cast<void*>(0x510467),rowExpected,rowCall,sizeof(rowCall));
}
