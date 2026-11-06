// Show SSLoginNG during entry instead of waiting forever for bootstrap. Author: Peppone Choi (peppone-choi).
#pragma once
#include "game_admission_data.h"
static LONG gameAdmissionPending=0;
static BYTE* admissionOwnCharacter() {
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    return world ? at<BYTE*>(world,8) : nullptr;
}
static void CALLBACK pollGameAdmission(HWND window,UINT,UINT_PTR timer,DWORD) {
    auto own=admissionOwnCharacter();
    if(!InterlockedExchange(&gameAdmissionPending,0)) {
        if(own && own[0])KillTimer(window,timer);
        return;
    }
    KillTimer(window,timer);
    if(!window)window=GetActiveWindow();
    DWORD owner=0;if(window)GetWindowThreadProcessId(window,&owner);
    if(owner!=GetCurrentProcessId())window=nullptr;
    MessageBoxW(window,L"현재 세션에 참가할 수 없습니다.\n기존 접속과 세션 상태를 확인한 뒤 다시 접속하세요.\n확인을 누르면 게임을 닫습니다.",
        L"게임 참가 거절",MB_OK|MB_ICONERROR);
    if(window)PostMessageW(window,WM_CLOSE,0,0);
    else PostQuitMessage(0);
}
static unsigned __stdcall decodedGameAdmission(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x44eae0)(record,reader);
    resetFacilityGuideConnection();
    auto own=admissionOwnCharacter();
    if(!result && record && logh::gameAdmissionRejected(0x0202,!own || !own[0]))
        InterlockedExchange(&gameAdmissionPending,1);
    return result;
}
static bool installGameAdmission() {
    const unsigned decoder=0x44eae0;
    if(memcmp(reinterpret_cast<void*>(0x66d170),&decoder,4))return false;
    HWND window=GetActiveWindow();DWORD owner=0;
    DWORD thread=window ? GetWindowThreadProcessId(window,&owner) : 0;
    if(owner!=GetCurrentProcessId() || thread!=GetCurrentThreadId())window=nullptr;
    // DirectInput initialization runs on the game's UI thread. The receiver only
    // publishes a flag; the timer owns the modal dialog and normal window close.
    auto timer=SetTimer(window,window ? 0x4c473741 : 0,250,&pollGameAdmission);
    if(!timer)return false;
    const auto replacement=reinterpret_cast<unsigned>(&decodedGameAdmission);
    if(patchBytes(reinterpret_cast<void*>(0x66d170),&decoder,&replacement,4))return true;
    KillTimer(window,timer);return false;
}
