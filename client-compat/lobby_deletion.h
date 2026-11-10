// Generated-character deletion through the CD lobby's own delete path.
// Author: Peppone Choi (peppone-choi).
//
// Native path (0051a370, evidence:client): lobby menu panel 0x52 button (1,3) -> stage 0x1b
// "PUSH_DELETE" -> 0x29 shows charged-character panel 0x35 -> 0x2a. Its rows (1,2)/(1,3)
// would continue to the session login (0x30) and an after-login stage 0x39 that the CD
// never handles, and the CD initializer leaves the delete button disabled. This module
// takes over only the rows of a 0x2a panel entered through 0x1b.
#pragma once
#include "lobby_deletion_data.h"
// C2S 0x032e mode 24 {character, session, 24} through the native queued sender 0x4b78a0, the
// same call as requestLobbyCountrySelection/requestLobbyCountries on the lobby connection.
static bool sendLobbyDeletion(void* world,const logh::LobbyDeletionRow& row) {
    unsigned query[3]={};
    if(!world || !logh::lobbyDeletionQuery(row,query))return false;
    return reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query)!=0;
}
static struct LobbyDeletion {
    void* world=nullptr;bool armed=false,awaiting=false,resultReady=false;
    logh::LobbyDeletionRow target;logh::LobbyDeletionOutcome outcome=logh::LobbyDeletionOutcome::Unknown;
    ULONGLONG deadline=0;
} lobbyDeletion;

static HWND lobbyDeletionWindow() {
    HWND window=GetActiveWindow();DWORD owner=0;if(window)GetWindowThreadProcessId(window,&owner);
    return owner==GetCurrentProcessId() ? window : nullptr;
}
static const BYTE* lobbyDeletionList(void* world) {
    return world ? static_cast<const BYTE*>(world)+0x35975c : nullptr;
}
static bool lobbyDeletionCandidate(void* world) {
    logh::LobbyDeletionRow row;const auto list=lobbyDeletionList(world);
    for(unsigned i=0;list && i<2;++i)if(logh::readLobbyDeletionRow(list,logh::lobbyListBytes,i,row))return true;
    return false;
}
// Same native back action as stage 0x2a's (1,4) button.
static void closeLobbyDeletionPanel(void* controller,void* root) {
    at<unsigned>(controller,4)=0x16;
    if(root)reinterpret_cast<void(__thiscall*)(void*)>(0x506920)(root);
}
static void requestLobbyDeletion(void* world,unsigned index,const logh::LobbyDeletionRow& row) {
    wchar_t session[64],text[512];
    if(row.name[0])swprintf_s(session,L"세션: %s (ID %u)",row.name,row.session);
    else swprintf_s(session,L"세션 ID: %u",row.session);
    swprintf_s(text,L"선택한 인물을 삭제합니다.\n\n%s\n인물 ID: %u\n\n"
        L"삭제한 인물은 되돌릴 수 없습니다.\n대령 이하이며 자기 거주구나 호텔 객실에 있고 진행 중인 행동이 없어야 삭제됩니다.\n\n"
        L"정말 삭제하시겠습니까?",session,row.character);
    if(MessageBoxW(lobbyDeletionWindow(),text,L"인물 삭제 확인",MB_YESNO|MB_ICONWARNING|MB_DEFBUTTON2)!=IDYES)return;
    // The modal pumps messages; send only for the exact row that was confirmed.
    logh::LobbyDeletionRow current;
    if(*reinterpret_cast<void**>(0x7ccffc)!=world ||
        !logh::readLobbyDeletionRow(lobbyDeletionList(world),logh::lobbyListBytes,index,current) || !current.same(row)) {
        MessageBoxW(lobbyDeletionWindow(),L"선택한 인물 정보가 바뀌어 삭제 요청을 보내지 않았습니다.\n목록을 다시 확인하세요.",
            L"인물 삭제",MB_OK|MB_ICONINFORMATION);
        return;
    }
    if(!sendLobbyDeletion(world,current)) {
        MessageBoxW(lobbyDeletionWindow(),L"삭제 요청을 보내지 못했습니다.\n잠시 뒤 다시 시도하세요.",L"인물 삭제",MB_OK|MB_ICONINFORMATION);
        return;
    }
    // Native 0051be60: queued 0x2003 after the 032e on the same lobby connection, expecting 0x2004.
    reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)
        (world,1,6,reinterpret_cast<const void*>(0x2217364));
    auto& state=lobbyDeletion;
    state.awaiting=true;state.resultReady=false;state.target=row;state.deadline=GetTickCount64()+8000;
}
// The row click was read without the native consume flag, so every outcome (including cancel)
// leaves 0x2a through the native back action; the click can never reach the native 0x30 path.
static void confirmLobbyDeletion(void* controller,void* world,void* root,unsigned index,const logh::LobbyDeletionRow& row) {
    requestLobbyDeletion(world,index,row);
    closeLobbyDeletionPanel(controller,root);
}
static void reportLobbyDeletion(unsigned stage) {
    auto& state=lobbyDeletion;
    if(state.awaiting) {
        // 0x357ec0 is the native in-flight request count (see lobby_selection_recovery.h).
        const bool drained=!at<unsigned>(state.world,0x357ec0);
        if(!drained && GetTickCount64()<state.deadline)return;
        state.outcome=drained ? logh::lobbyDeletionOutcome(lobbyDeletionList(state.world),logh::lobbyListBytes,state.target)
            : logh::LobbyDeletionOutcome::Unknown;
        state.awaiting=false;state.resultReady=true;
    }
    if(!state.resultReady || stage!=0x17)return;
    state.resultReady=false;
    if(state.outcome==logh::LobbyDeletionOutcome::Deleted)
        MessageBoxW(lobbyDeletionWindow(),L"인물을 삭제했습니다.",L"인물 삭제",MB_OK|MB_ICONINFORMATION);
    else if(state.outcome==logh::LobbyDeletionOutcome::Remaining)
        MessageBoxW(lobbyDeletionWindow(),L"인물이 삭제되지 않았습니다.\n삭제 조건(대령 이하, 자기 거주구나 호텔 객실, 진행 중인 행동 없음)을 "
            L"충족하지 않았거나 서버에서 처리하지 못했습니다.",L"인물 삭제",MB_OK|MB_ICONINFORMATION);
    else MessageBoxW(lobbyDeletionWindow(),L"삭제 결과를 확인하지 못했습니다.\n잠시 뒤 로비 목록을 다시 확인하세요.",
        L"인물 삭제",MB_OK|MB_ICONINFORMATION);
}
// Called first in the lobby frame. Returns true when it consumed this frame.
static bool updateLobbyDeletion(void* controller) {
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto gui=at<void*>(controller,12);
    const unsigned stage=at<unsigned>(controller,4);
    auto& state=lobbyDeletion;
    if(state.world!=world) {state={};state.world=world;}
    if(!world || !gui)return false;
    reportLobbyDeletion(stage);
    if(stage==0x17 && !state.awaiting && lobbyDeletionCandidate(world))
        if(auto menu=at<void*>(gui,4+0x52*4))
            if(auto button=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(menu,1,3))
                reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(menu,button,1);
    if(stage==0x1b)state.armed=true;
    else if(stage!=0x29 && stage!=0x2a)state.armed=false;
    if(!state.armed || stage!=0x2a)return false;
    auto root=at<void*>(gui,4+0x35*4);if(!root)return false;
    for(unsigned i=0;i<2;++i) {
        auto widget=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2+i);
        if(!widget)continue;
        logh::LobbyDeletionRow row;
        const bool valid=!state.awaiting && logh::readLobbyDeletionRow(lobbyDeletionList(world),logh::lobbyListBytes,i,row);
        // Every row is either a confirmed deletion target or disabled; none reaches the native 0x30.
        reinterpret_cast<void(__thiscall*)(void*,void*,int)>(0x5024e0)(root,widget,valid ? 1 : 0);
        BYTE event[52]={};
        if(valid && reinterpret_cast<BYTE(__thiscall*)(void*,int,void*,void*,int)>(0x5015f0)(root,2,widget,event,0)) {
            confirmLobbyDeletion(controller,world,root,i,row);return true;
        }
    }
    return false;
}
