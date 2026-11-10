// Dedicated end-of-session view using native controls and the surviving panel frame.
// The 035a handler otherwise only hides the HUD. Author: Peppone Choi.
#pragma once
#include "session_ending_data.h"
#include "lobby_deletion.h"
static struct {void* world=nullptr;void* root=nullptr;void* gui=nullptr;bool active=false,lobby=false,partial=false;
    unsigned offset=0,countrySequence=0;ULONGLONG acceptAfter=0,pollAt=0;logh::SessionEndingData data;} sessionEnding;
// Reuse allocated table labels. Native lookup does not bounds-check categories/slots;
// its button table has only 120 slots, and 108..110 belong to the directory scrollbar.
static constexpr int endingControl(int id) {
    return id==132 ? 26 : id==142 ? 23 : id==143 ? 24 : id==144 ? 80 : (id>132 ? 39 : 40)+id-110;
}
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
    const int ranges[][2]={{0,3},{10,13},{20,32},{40,88},{100,103}};
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
    const auto& data=sessionEnding.data;logh::CountryEndingCatalog countries;
    AcquireSRWLockShared(&countryEndingLock);
    if(countryEndingInbox.world==sessionEnding.world && countryEndingInbox.published.header.session==data.session &&
        (sessionEnding.lobby || countryEndingInbox.character==data.character))countries=countryEndingInbox.published;
    sessionEnding.countrySequence=countryEndingInbox.sequence;
    ReleaseSRWLockShared(&countryEndingLock);
    if(countries.rows.size()!=countries.header.total && countries.header.offset!=sessionEnding.offset)countries.rows.clear();
    const auto me=countries.find(countries.header.viewer);
    const bool unavailable=countries.header.session==data.session && countries.header.status==0;
    wchar_t text[160];swprintf_s(text,L"%s  ·  %s",sessionEnding.partial ? L"국가 패배" : L"세션 결과",
        me ? logh::endingGrade(me->grade) : unavailable ? L"국가별 기록 없음" : countries.header.status ? L"국가별 결과" : L"국가별 결과 조회 중");
    endingText(110,184,91,620,text);
    const wchar_t* reason=data.reason==3 ? L"수도 함락" : data.reason==4 ? L"잔존 성계 감소" : L"기간 만료";
    if(sessionEnding.partial)wcscpy_s(text,L"소속 국가가 탈락했습니다. 나머지 국가의 세션은 계속 진행됩니다.");
    else swprintf_s(text,L"%s  |  %s로 세션이 종료되었습니다.",data.name[0] ? data.name : L"현재 세션",reason);
    endingText(111,184,126,640,text);
    unsigned shown=0;
    for(unsigned i=0;i<3;++i) {
        const unsigned index=countries.rows.size()==countries.header.total ? sessionEnding.offset+i : i;
        if(index>=countries.rows.size())break;
        const auto& side=countries.rows[index];++shown;
        const int x=184+static_cast<int>(i)*216;
        auto label=[&](int row,const wchar_t* value) {int id=112+static_cast<int>(i)*9+row;if(id>=132)++id;endingText(id,x,164+row*28,208,value);};
        label(0,logh::fitCountryLabel(side.name,24).c_str());
        const unsigned rgb=side.hasRgb ? side.rgb : 0xffffff;
        reinterpret_cast<void(__thiscall*)(void*,void*,float,float,float,float)>(0x502f40)
            (root,planetWidget(endingControl(112+static_cast<int>(i)*9)),((rgb>>16)&255)/255.f,((rgb>>8)&255)/255.f,(rgb&255)/255.f,1.f);
        swprintf_s(text,L"%s%s",side.id==countries.header.viewer ? L"내 국가 · " : L"",
            side.grade==255 && countries.header.status==1 ? L"진행 중" : logh::endingGrade(side.grade));label(1,text);
        const std::uint64_t values[]={side.planets,side.fortresses,side.population/10000,side.ships,side.troops,side.characters};
        const wchar_t* labels[]={L"행성",L"요새",L"인구(만 명)",L"함정 유닛",L"육전대 유닛",L"등록 인물"};
        for(int row=0;row<6;++row) {
            if(countries.header.status!=1 || side.id==countries.header.viewer && side.eliminated || row==5)
                swprintf_s(text,L"%s   %llu",labels[row],static_cast<unsigned long long>(values[row]));
            else swprintf_s(text,L"%s   비공개",labels[row]);
            label(row+2,text);
        }
        const wchar_t* population=side.populationCondition==255 ? L"대기" : side.populationCondition ? L"충족" : L"미달";
        const wchar_t* fleet=side.fleetCondition==255 ? L"대기" : side.fleetCondition ? L"충족" : L"미달";
        swprintf_s(text,L"인구90%% %s · 전력10배 %s",population,fleet);
        label(8,text);
    }
    if(data.evaluationPresent)swprintf_s(text,L"평가 %u → %u  |  명성 %u → %u",
        data.previousEvaluation,data.finalEvaluation,data.previousFame,data.finalFame);
    else wcscpy_s(text,L"개인 정산 기록이 없습니다.");
    endingText(140,184,450,640,text);
    endingText(141,184,486,210,sessionEnding.lobby ? L"저장된 국가별 결과" : sessionEnding.partial ? L"정산은 세션 종료 후 반영" : L"국가별 최종 결과");
    endingText(142,400,477,80,L"이전",true);planetEnabled(endingControl(142),sessionEnding.offset>0);
    endingText(143,490,477,80,L"다음",true);planetEnabled(endingControl(143),sessionEnding.offset+shown<countries.header.total);
    swprintf_s(text,L"%u / %u",countries.header.total ? sessionEnding.offset/3+1 : 0,(countries.header.total+2)/3);
    endingText(144,590,486,110,text);
    endingText(132,720,477,100,sessionEnding.lobby ? L"돌아가기" : L"게임 종료",true);
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
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!sessionEnding.active && own && own[0]) {
        AcquireSRWLockShared(&countryEndingLock);const auto& inbox=countryEndingInbox;
        const auto& data=inbox.published;const auto me=data.find(data.header.viewer);
        if(inbox.world==world && inbox.character==at<unsigned>(own,0x24) && data.header.status==1 && me && me->eliminated) {
            sessionEnding={};sessionEnding.active=sessionEnding.partial=true;sessionEnding.world=world;
            sessionEnding.data.character=inbox.character;sessionEnding.data.session=data.header.session;
            sessionEnding.data.power=own[0x28];sessionEnding.data.reason=3;
        }
        ReleaseSRWLockShared(&countryEndingLock);
    }
    if(!sessionEnding.active)return false;
    if(world!=sessionEnding.world || (!sessionEnding.lobby && (!own || at<unsigned>(own,0x24)!=sessionEnding.data.character))) {
        if(sessionEnding.root && sessionEnding.gui==gui)reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(sessionEnding.root,0);
        sessionEnding={};return false;
    }
    if(sessionEnding.gui!=gui || !sessionEnding.root) {
        closePlanetInformation();closeTaxWindow();showSessionEnding(gui);
    }
    unsigned sequence=0;bool complete=false;
    AcquireSRWLockShared(&countryEndingLock);
    sequence=countryEndingInbox.sequence;
    complete=countryEndingInbox.world==world && countryEndingInbox.published.header.session==sessionEnding.data.session &&
        (countryEndingInbox.published.rows.size()==countryEndingInbox.published.header.total ||
            countryEndingInbox.published.header.offset==sessionEnding.offset);
    ReleaseSRWLockShared(&countryEndingLock);
    if(sequence!=sessionEnding.countrySequence)showSessionEnding(gui);
    if(sessionEnding.lobby && !complete && GetTickCount64()>=sessionEnding.pollAt) {
        requestCountryEndings(world,sessionEnding.data.session,sessionEnding.offset);sessionEnding.pollAt=GetTickCount64()+5000;
    }
    if(sessionEnding.root && GetTickCount64()>=sessionEnding.acceptAfter)for(int id=142;id<=143;++id) {
        BYTE pageEvent[52]={};
        if(planetWidget(endingControl(id)) && reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
            (sessionEnding.root,2,planetWidget(endingControl(id)),pageEvent,0)) {
            unsigned offset=sessionEnding.offset;
            if(id==142 && offset>=3)offset-=3;
            else if(id==143)offset+=3;
            bool cached=false;
            AcquireSRWLockShared(&countryEndingLock);
            const auto& all=countryEndingInbox.published;
            cached=countryEndingInbox.world==world && all.header.session==sessionEnding.data.session &&
                all.rows.size()==all.header.total;
            const bool valid=offset<all.header.total;
            ReleaseSRWLockShared(&countryEndingLock);
            if(valid && (cached || sessionEnding.lobby && requestCountryEndings(world,sessionEnding.data.session,offset)))
                sessionEnding.offset=offset;
            showSessionEnding(gui);return true;
        }
    }
    BYTE event[52]={};
    if(sessionEnding.root && GetTickCount64()>=sessionEnding.acceptAfter && reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)
        (sessionEnding.root,2,planetWidget(endingControl(132)),event,0)) {
        if(sessionEnding.lobby) {
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(sessionEnding.root,0);
            sessionEnding={};return true;
        }
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
// The native charged-character picker disables rows when session status is not 1/2.
// Ended rows open this read-only local view, never request a playable server ticket.
static unsigned __fastcall lobbyEndingFrame(void* controller,void*) {
    recoverLobbySelection(controller);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto gui=at<void*>(controller,12);
    pollLobbyPortraits(world,at<unsigned>(controller,4)==0x2a);
    if(sessionEnding.active && sessionEnding.lobby) {
        if(at<unsigned>(controller,4)==0x2a && updateSessionEnding(gui))return 0;
        if(sessionEnding.root)reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(sessionEnding.root,0);
        sessionEnding={};
    }
    // The delete path (stage 0x1b) reuses this panel; its rows never open the read-only view.
    if(!sessionEnding.active && updateLobbyDeletion(controller))return 0;
    if(world && gui && at<unsigned>(controller,4)==0x2a && !lobbyDeletion.armed) {
        auto root=at<void*>(gui,4+0x35*4);
        if(root)for(unsigned i=0;i<2;++i) {
            logh::SessionEndingData data;
            if(!data.readLobby(world+0x35975c,0x6d8,i))continue;
            auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2+i);
            if(!widget)continue;
            reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,widget,1);
            BYTE event[52]={};
            if(reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(root,2,widget,event,0)) {
                sessionEnding={};sessionEnding.world=world;sessionEnding.data=data;sessionEnding.active=true;sessionEnding.lobby=true;
                updateSessionEnding(gui);return 0;
            }
        }
    }
    if(!sessionEnding.active && updateLobbyCountryWindow(controller))return 0;
    return reinterpret_cast<unsigned(__thiscall*)(void*)>(0x51a370)(controller);
}
// After a validated ending, the old session's socket closes on generation change.
// Its modal would cover the saved result. Other disconnects retain the native dialog.
static bool hasCurrentSessionEnding() {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    return sessionEnding.active && !sessionEnding.lobby && world==sessionEnding.world &&
        own && at<unsigned>(own,0x24)==sessionEnding.data.character;
}
static BYTE __fastcall sessionEndingDisconnect(void* controller,void*,int kind) {
    if(kind==0 && hasCurrentSessionEnding())return 1;
    return reinterpret_cast<BYTE(__thiscall*)(void*,int)>(0x54eda0)(controller,kind);
}
static int __fastcall sessionEndingDisconnectFrame(void* controller,void*) {
    if(hasCurrentSessionEnding()) {
        // The disconnected frame otherwise returns early or treats no modal (-1) as exit.
        updateSessionEnding(at<void*>(controller,12));return 0;
    }
    return reinterpret_cast<int(__thiscall*)(void*)>(0x54ee60)(controller);
}
static bool installSessionEnding() {
    const unsigned addresses[]={0x54ef34,0x4b698a,0x4b6914,0x4b691f};
    const BYTE expected[][5]={{0xe8,0x17,0xcc,0xff,0xff},{0xe8,0xe1,0x39,0x06,0x00},
        {0xe8,0x87,0x84,0x09,0x00},{0xe8,0x3c,0x85,0x09,0x00}};
    const void* functions[]={&sessionEndingFrame,&lobbyEndingFrame,&sessionEndingDisconnect,&sessionEndingDisconnectFrame};
    for(unsigned i=0;i<4;++i)if(memcmp(reinterpret_cast<void*>(addresses[i]),expected[i],5))return false;
    for(unsigned i=0;i<4;++i) {
        BYTE call[5]={0xe8};auto address=reinterpret_cast<BYTE*>(addresses[i]);
        *reinterpret_cast<LONG*>(call+1)=static_cast<LONG>(static_cast<const BYTE*>(functions[i])-address-5);
        if(!patchBytes(address,expected[i],call,5))return false;
    }
    return true;
}
