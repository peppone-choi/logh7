// Dedicated end-of-session view using native controls and the surviving panel frame.
// The 035a handler otherwise only hides the HUD. Author: 최병호.
#pragma once
#include "session_ending_data.h"
static struct {void* world=nullptr;void* root=nullptr;void* gui=nullptr;bool active=false;ULONGLONG acceptAfter=0;logh::SessionEndingData data;} sessionEnding;
// Reuse allocated table labels. Native lookup does not bounds-check categories/slots;
// its button table has only 120 slots, and 108..110 belong to the directory scrollbar.
static constexpr int endingControl(int id) {return id==132 ? 26 : 40+id-110;}
static_assert(endingControl(110)==40 && endingControl(131)==61 && endingControl(132)==26,"Allocated native table labels");
static void endingText(int id,int x,int y,int width,const wchar_t* text,bool button=false) {
    id=endingControl(id);auto widget=planetWidget(id);
    planetRect(id,x,y,width,button ? 42 : 26);planetCell(id,text);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5029e0)(planetWindowRoot,widget,button ? 2 : 3);
    if(button)planetButtonArt(widget,width,42);
    planetEnabled(id,true);
    reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(planetWindowRoot,widget,1);
}
static bool showSessionEnding(void* gui) {
    if(!createPlanetWindow(gui,false))return false;
    auto root=planetWindowRoot;
    const int ranges[][2]={{0,3},{10,13},{20,32},{40,84},{100,103}};
    for(const auto& range:ranges)for(int id=range[0];id<=range[1];++id) {
        auto widget=planetWidget(id);if(!widget)continue;
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,widget,0);
        planetEnabled(id,false);
    }
    for(int id=0;id<planetDecorationCount;++id) {
        auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,2,id);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,widget,id==planetDecorationCount-1 ? 1 : 0);
    }
    if(planetDirectoryList) {
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,planetDirectoryList,0);
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,planetDirectoryList,0);
        for(int id=108;id<114;++id) {
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x502eb0)(root,planetWidget(id),0);
            planetEnabled(id,false);
        }
    }
    const auto& data=sessionEnding.data;
    wchar_t text[160];swprintf_s(text,L"세션 결과  ·  %s",logh::endingGrade(data.sides[data.power-2].grade));
    endingText(110,184,91,620,text);
    const wchar_t* reason=data.reason==3 ? L"수도 함락" : data.reason==4 ? L"잔존 성계 감소" : L"기간 만료";
    swprintf_s(text,L"%s  |  %s로 세션이 종료되었습니다.",data.name[0] ? data.name : L"현재 세션",reason);
    endingText(111,184,126,640,text);
    for(unsigned i=0;i<2;++i) {
        int x=190+static_cast<int>(i)*320;const auto& side=data.sides[i];const auto& v=side.statistics;
        endingText(112+static_cast<int>(i),x,164,290,i ? L"자유행성동맹" : L"은하제국");
        endingText(114+static_cast<int>(i),x,196,290,logh::endingGrade(side.grade));
        const unsigned values[]={v.planets,v.fortresses,v.population,v.ships,v.troops,side.characters};
        const wchar_t* labels[]={L"행성",L"요새",L"인구(만 명)",L"함정 유닛",L"육전대 유닛",L"등록 인물"};
        for(int row=0;row<6;++row) {
            if(side.present || row==5)swprintf_s(text,L"%s   %u",labels[row],values[row]);
            else swprintf_s(text,L"%s   집계 중",labels[row]);
            endingText(116+static_cast<int>(i)*6+row,x,238+row*28,290,text);
        }
        const wchar_t* population=side.populationCondition==255 ? L"집계 중" : side.populationCondition ? L"충족" : L"미달";
        const wchar_t* fleet=side.fleetCondition==255 ? L"집계 중" : side.fleetCondition ? L"충족" : L"미달";
        swprintf_s(text,L"인구 90%%: %s  /  전력 10배: %s",population,fleet);
        endingText(128+static_cast<int>(i),x,414,294,text);
    }
    endingText(130,184,450,640,L"평가·명성 보상은 아직 확정되지 않았습니다.");
    endingText(131,184,486,520,L"다음 세션 준비 후 다시 접속해 주세요.");
    endingText(132,720,477,100,L"게임 종료",true);
    reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
    reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(gui,root);
    sessionEnding.root=root;sessionEnding.gui=gui;sessionEnding.acceptAfter=GetTickCount64()+500;return true;
}
static void receivedSessionEnding(void* world,const BYTE* record) {
    logh::SessionEndingData data;if(!world || !data.read(record))return;
    auto own=at<BYTE*>(world,8);if(!own || at<unsigned>(own,0x24)!=data.character)return;
    // Packet dispatch can run during native GUI transitions. Only publish data here;
    // create/show controls on the normal menu update, just like other information views.
    sessionEnding={};sessionEnding.world=world;sessionEnding.data=data;sessionEnding.active=true;
}
static bool updateSessionEnding(void* gui) {
    if(!sessionEnding.active)return false;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(world!=sessionEnding.world || !own || at<unsigned>(own,0x24)!=sessionEnding.data.character) {
        if(sessionEnding.root && sessionEnding.gui==gui)reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(sessionEnding.root,0);
        sessionEnding={};return false;
    }
    if(sessionEnding.gui!=gui || !sessionEnding.root) {
        closePlanetInformation();closeTaxWindow();showSessionEnding(gui);
    }
    BYTE event[52]={};
    if(sessionEnding.root && GetTickCount64()>=sessionEnding.acceptAfter && reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
        (sessionEnding.root,2,planetWidget(endingControl(132)),event,0)) {
        HWND window=GetActiveWindow();DWORD owner=0;if(window)GetWindowThreadProcessId(window,&owner);
        if(owner==GetCurrentProcessId())PostMessageW(window,WM_CLOSE,0,0);
    }
    return true;
}

// Original ended-state frame 0054ef20 calls the general menu even after it clears
// the online/tactical flags. Enter before that menu's disconnected early return.
static void __cdecl sessionEndingFrame(void* gui) {
    if(!updateSessionEnding(gui))reinterpret_cast<void(__cdecl*)(void*)>(0x54bb50)(gui);
}
static bool installSessionEnding() {
    const BYTE expected[]={0xe8,0x17,0xcc,0xff,0xff};BYTE call[5]={0xe8};
    auto address=reinterpret_cast<BYTE*>(0x54ef34);
    *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&sessionEndingFrame)-address-5);
    return patchBytes(address,expected,call,5);
}
