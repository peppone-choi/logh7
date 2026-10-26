// Rebuild the CD's missing command 30 screen with native widgets and transport.
// UI layout is reconstructed; authority and payment remain on the server. Author: 최병호.
#pragma once
#include "planet_tax_data.h"

static struct PlanetTaxWindow {
    void* manager;
    void* root;
    void* world;
    unsigned character, base, card, current, selected;
    bool visible, loaded, pending;
    ULONGLONG acceptAfter, sentAt;
    const wchar_t* message;
} taxWindow = {};
// 0050bb40 accepts IDs 0..0x72. Slot 0x70 has no native direct create/show
// references and is empty in the supported CD strategy GUI. Refuse a collision.
static constexpr int taxWindowId = 0x70;

static void* taxWidget(int category, int index) { return returnWidget(taxWindow.root,category,index); }
static void taxText(int index, const wchar_t* text) {
    auto widget = taxWidget(1,index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(taxWindow.root,widget,text);
}
static void taxEnabled(int index, bool enabled) {
    auto widget = taxWidget(1,index);
    if (widget) reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(taxWindow.root,widget,enabled ? 1 : 0);
}
static void taxControl(int index, int x, int y, int width, int height, const wchar_t* label, bool button) {
    // Keep the planet atlas's four bevels. Four states use the same dark plate.
    if (button) {
        int decoration = index*6;
        const int sx[] = {459,467,659}, widths[] = {8,width-16,8};
        int top=y;
        for (int row=0;row<2;++row) {
            int h=row ? height-height/2 : height/2, left=x;
            for (int col=0;col<3;++col) {
                unsigned art[40] = {};
                for (unsigned state=0;state<4;++state) {
                    art[state*5]=reinterpret_cast<unsigned>(planetPartsPath);
                    art[state*5+1]=sx[col]; art[state*5+2]=row ? 314-h : 264;
                    art[state*5+3]=widths[col]; art[state*5+4]=h;
                }
                auto piece=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)
                    (taxWindow.root,2,decoration++,art);
                int rect[]={left,top,0,widths[col],h};
                reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(taxWindow.root,piece,rect);
                reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,piece,1);
                left+=widths[col];
            }
            top+=h;
        }
    }
    // Native text/button control; art is supplied separately from the same atlas.
    auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(taxWindow.root,1,index,nullptr);
    int rect[]={x,y,0,width,height};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(taxWindow.root,widget,rect);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(taxWindow.root,widget,1);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(taxWindow.root,widget,1);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(taxWindow.root,widget,button ? 2 : 3);
    taxText(index,label);
    if (button) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b00)(taxWindow.root,widget,1);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502b30)(taxWindow.root,widget,1);
    }
}
static bool createTaxWindow() {
    auto& state=taxWindow;
    if (state.root && at<void*>(state.manager,4+taxWindowId*4)==state.root) return true;
    if (!state.manager || at<void*>(state.manager,4+taxWindowId*4)) return false;
    state.root=reinterpret_cast<void*(__thiscall*)(void*,int)>(0x50bb40)(state.manager,taxWindowId);
    if (!state.root) return false;
    static const char path[]="/../data/image/window/wakusei_window.tga";
    unsigned art[40]={};
    for (unsigned i=0;i<4;++i) { art[i*5]=reinterpret_cast<unsigned>(path);art[i*5+3]=611;art[i*5+4]=463; }
    auto frame=reinterpret_cast<void*(__thiscall*)(void*,int,int,const void*)>(0x503a10)(state.root,0,0,art);
    int rect[]={206,110,0,611,463};
    reinterpret_cast<void(__thiscall*)(void*,void*,const int*)>(0x502940)(state.root,frame,rect);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(state.root,frame,1);
    taxControl(0,234,126,550,30,L"행성 세율 변경",false);
    taxControl(1,234,172,550,30,L"",false);
    taxControl(2,234,218,550,30,L"현재 위치의 행성을 맡은 통치자가 실행합니다.",false);
    taxControl(3,234,268,260,32,L"",false);
    taxControl(4,520,268,260,32,L"",false);
    taxControl(5,234,376,550,30,L"MCP 160 · 부족 시 PCP 320 전액 대용",false);
    taxControl(6,234,412,550,50,L"",false);
    taxControl(10,234,316,124,42,L"−10%",true);
    taxControl(11,372,316,124,42,L"−1%",true);
    taxControl(12,510,316,124,42,L"+1%",true);
    taxControl(13,648,316,124,42,L"+10%",true);
    taxControl(14,234,492,208,44,L"변경 실행",true);
    taxControl(15,564,492,208,44,L"닫기",true);
    reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(state.root);
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(state.root,0);
    return true;
}
static unsigned heldGovernorBase(void* own, unsigned card) {
    return logh::heldLocalTaxBase(static_cast<const BYTE*>(own),card);
}
static void closeTaxWindow() {
    if (taxWindow.root) reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(taxWindow.root,0);
    taxWindow.visible=false;
}
static BYTE __fastcall dispatchTaxCard(void* manager,void*,int command,unsigned card) {
    if (command!=30 || !logh::civilTaxCard(card))
        return reinterpret_cast<BYTE(__thiscall*)(void*,int,unsigned)>(0x4f93c0)(manager,command,card);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<void*>(world,8) : nullptr;
    unsigned base=own ? heldGovernorBase(own,card) : 0;
    if (!base || at<void*>(manager,0x18) || *reinterpret_cast<void**>(0x21aa888)!=own || !createTaxWindow()) return 0;
    closePlanetInformation();
    auto& state=taxWindow;
    state.world=world;state.character=at<unsigned>(own,0x24);state.base=base;state.card=card;
    state.visible=true;state.loaded=false;state.pending=false;state.message=L"행성의 현재 세율을 조회하고 있습니다.";
    state.acceptAfter=GetTickCount64()+250;
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(state.root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(state.manager,state.root);
    struct Query { BYTE count; BYTE pad[3]; unsigned base; } query={1,{},base};
    reinterpret_cast<void(__thiscall*)(void*,char,unsigned,void*)>(0x4b78a0)(world,0,0x21,&query);
    return 1;
}
static BYTE __fastcall taxCommandAvailable(void* manager,void*,unsigned command) {
    if (command!=30) return reinterpret_cast<BYTE(__thiscall*)(void*,unsigned)>(0x4f9990)(manager,command);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<void*>(world,8) : nullptr;
    return own && *reinterpret_cast<void**>(0x21aa888)==own &&
        (heldGovernorBase(own,54) || heldGovernorBase(own,190) || heldGovernorBase(own,192)) ? 1 : 0;
}
static void taxConditions(void* world) {
    auto& state=taxWindow;
    if (!state.visible || state.world!=world) return;
    auto base=pickerBase(state.base);
    if (!base) return;
    auto record=logh::findPlanetCondition(static_cast<BYTE*>(world)+0x3facf4,state.base,logh::infoShort(base,0x10));
    if (!record) return;
    state.current=logh::infoShort(record,0x128);
    if (!state.loaded) { state.selected=state.current;state.loaded=true;state.message=L"변경할 세율을 선택한 뒤 실행하십시오."; }
}
static unsigned __fastcall readTaxResponse(void* codec,void*,void* record,void* stream) {
    auto result=reinterpret_cast<unsigned(__thiscall*)(void*,void*,void*)>(0x435e80)(codec,record,stream);
    auto& state=taxWindow;
    if (state.pending && at<unsigned>(record,4)==state.character && at<unsigned>(record,20)==state.base &&
        at<unsigned short>(record,26)==state.card && at<BYTE>(record,24)==state.selected) {
        state.pending=false;
        if (!at<BYTE>(record,28)) { state.current=state.selected;state.message=L"서버에 세율 변경이 저장됐습니다."; }
        else state.message=L"변경이 거부됐습니다. 직무·정박 위치·CP·진행 작업을 확인하십시오.";
    }
    return result;
}
static bool updateTaxWindow(void* gui) {
    auto& state=taxWindow;
    state.manager=gui;
    if (!state.visible) return false;
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto own=world ? at<void*>(world,8) : nullptr;
    if (world!=state.world || !own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2b6a6c) ||
        at<BYTE>(world,0x3579cc) || at<unsigned>(own,0x24)!=state.character ||
        heldGovernorBase(own,state.card)!=state.base || *reinterpret_cast<void**>(0xc9e2f8)) {
        closeTaxWindow();return false;
    }
    wchar_t text[180],name[32]={};pickerName(state.base,name);
    _snwprintf_s(text,_countof(text),_TRUNCATE,L"%ls · %ls",name,state.card==54 ? L"행성 총독" : state.card==192 ? L"수도시정관" : L"주지사");taxText(1,text);
    if (state.loaded) {
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"현재 세율  %u%%",state.current);taxText(3,text);
        _snwprintf_s(text,_countof(text),_TRUNCATE,L"변경할 세율  %u%%",state.selected);taxText(4,text);
    } else { taxText(3,L"현재 세율 조회 중");taxText(4,L""); }
    for (int i=10;i<=14;++i) taxEnabled(i,state.loaded && !state.pending && (i!=14 || state.current!=state.selected));
    if (state.pending && GetTickCount64()-state.sentAt>10000) state.message=L"응답을 기다리고 있습니다. 연결 상태를 확인하십시오.";
    taxText(6,state.message ? state.message : L"");
    if (GetTickCount64()<state.acceptAfter) return true;
    BYTE event[52]={};
    for (int i=10;i<=15;++i) if (reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(state.root,2,taxWidget(1,i),event,0)) {
        state.acceptAfter=GetTickCount64()+200;
        if (i==15) closeTaxWindow();
        else if (state.loaded && !state.pending && i<14) {
            const int steps[]={-10,-1,1,10};int value=static_cast<int>(state.selected)+steps[i-10];
            state.selected=static_cast<unsigned>(value<0 ? 0 : value>100 ? 100 : value);
            state.message=L"실행 버튼을 눌러야 서버에 변경이 적용됩니다.";
        } else if (i==14 && state.loaded && !state.pending && state.current!=state.selected) {
            BYTE record[32]={};
            at<unsigned>(record,4)=state.character;at<unsigned>(record,20)=state.base;
            at<BYTE>(record,24)=static_cast<BYTE>(state.selected);at<unsigned short>(record,26)=static_cast<unsigned short>(state.card);
            auto service=*reinterpret_cast<BYTE**>(0x7c25f4);
            if (!service) { state.message=L"통신 연결이 없습니다.";break; }
            auto transport=at<void*>(service,0x44);auto table=at<void**>(transport,0);
            state.pending=true;state.sentAt=GetTickCount64();state.message=L"서버에 변경을 요청했습니다.";
            reinterpret_cast<unsigned(__thiscall*)(void*,void*,unsigned,void*)>(table[6])(transport,at<void*>(service,0x40),0x0a00,record);
        }
        break;
    }
    return true;
}
static bool installTaxWindow() {
    const BYTE expected[]={0xe8,0x35,0x3a,0,0};
    auto call=reinterpret_cast<BYTE*>(0x4f5986);
    const unsigned reader=0x435e80;
    auto slot=reinterpret_cast<unsigned*>(0x66c9f8);
    const BYTE availableExpected[]={0xe8,0xe9,0x3d,0,0};
    auto availableCall=reinterpret_cast<BYTE*>(0x4f5ba2);
    if (memcmp(call,expected,5) || memcmp(availableCall,availableExpected,5) || *slot!=reader) return false;
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&dispatchTaxCard)-call-5);
    if (!patchBytes(call,expected,replacement,5)) return false;
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&taxCommandAvailable)-availableCall-5);
    if (!patchBytes(availableCall,availableExpected,replacement,5)) return false;
    unsigned target=reinterpret_cast<unsigned>(&readTaxResponse);
    return patchBytes(slot,&reader,&target,4);
}
