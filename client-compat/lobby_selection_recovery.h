// Retire the native lobby wait when a session selection receives LobbySessionLoginNG.
// Author: Peppone Choi (peppone-choi).
#pragma once
static BYTE lobbySelectionRefusal=0;

static void matchLobbySelectionRefusal(void* world,unsigned opcode,const void* record) {
    if((opcode&65535)!=0x200b || !world || !record)return;
    const unsigned pending=at<unsigned>(world,0x357ec0);
    if(!pending || pending>99 || at<unsigned>(world,0x357ec4)!=0x2009 ||
        at<unsigned>(world,0x357ec8)!=0x200a)return;
    // The native dispatcher removes the in-flight request only on an exact opcode match.
    at<unsigned>(world,0x357ec8)=0x200b;
    lobbySelectionRefusal=*static_cast<const BYTE*>(record)==2 ? 2 : 1;
}

static void recoverLobbySelection(void* controller) {
    const unsigned stage=at<unsigned>(controller,4);
    if(!lobbySelectionRefusal || stage==0x31)return;
    const BYTE reason=lobbySelectionRefusal;
    lobbySelectionRefusal=0;
    if(stage!=0x16)return;
    HWND window=GetActiveWindow();
    DWORD owner=0;if(window)GetWindowThreadProcessId(window,&owner);
    if(owner!=GetCurrentProcessId())window=nullptr;
    const wchar_t* message=reason==2
        ? L"이 세션은 현재 점검 중입니다.\n다른 세션을 선택하거나 점검이 끝난 뒤 다시 시도하세요."
        : L"이 세션에 지금 참가할 수 없습니다.\n다른 세션을 선택하거나 다시 시도하세요.";
    MessageBoxW(window,message,L"세션 선택 실패",MB_OK|MB_ICONINFORMATION);
    // Match the native picker Back action so its controls do not cover the lobby.
    if(auto gui=at<void*>(controller,12)) {
        for(unsigned panel:{0x35u,0x36u}) {
            if(auto root=at<void*>(gui,4+panel*4))
                reinterpret_cast<void(__thiscall*)(void*)>(0x506920)(root);
        }
    }
}
