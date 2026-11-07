// Keep the native login and reconnect flows while explaining why to retry.
// Author: Peppone Choi (peppone-choi).
#pragma once
static constexpr int sessionRouteFailure=0x100;

static void setLoginFailureBody(void* context,const wchar_t* body) {
    auto dialog=at<void*>(context,0x12c);
    if(!dialog)return;
    auto element=reinterpret_cast<void*(__thiscall*)(void*,unsigned)>(0x5706d0)(dialog,5);
    auto manager=reinterpret_cast<void*(__thiscall*)(void*)>(0x5706c0)(dialog);
    if(manager && element && at<BYTE>(element,8))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(manager,element,body);
}

static BYTE __fastcall showAccountLoginFailure(void* context,void*,int reason) {
    const auto result=reinterpret_cast<BYTE(__thiscall*)(void*,int)>(0x51c930)(context,reason);
    // LGLoginNG code 1 covers both rejected credentials and planned maintenance.
    if(reason==1)setLoginFailureBody(context,L"로그인할 수 없습니다.\n아이디와 비밀번호를 확인하고 다시 시도하세요.\n점검 중이라면 점검이 끝난 뒤 접속하세요.");
    return result;
}

static BYTE __fastcall showSessionRouteFailure(void* context,void*,int reason) {
    const bool transport=reason==sessionRouteFailure;
    const auto result=reinterpret_cast<BYTE(__thiscall*)(void*,int)>(0x51c930)(context,transport ? 0 : reason);
    if(!transport)return result;

    auto world=*reinterpret_cast<void**>(0x7ccffc);
    wchar_t body[144]=L"게임 서버에 연결하지 못했습니다.\n결정을 누르면 로그인 서버에 다시 연결합니다.";
    if(world) {
        const unsigned ip=at<unsigned>(world,0x35f144);
        const unsigned port=at<unsigned short>(world,0x35f148);
        swprintf_s(body,L"게임 서버에 연결하지 못했습니다.\n접속 주소 %u.%u.%u.%u:%u\n결정을 누르면 로그인 서버에 다시 연결합니다.",
            ip&255,(ip>>8)&255,(ip>>16)&255,ip>>24,port);
    }
    setLoginFailureBody(context,body);
    return result;
}

static bool installLoginFailureText() {
    auto accountDialog=reinterpret_cast<BYTE*>(0x51a7c6);
    auto reason=reinterpret_cast<BYTE*>(0x51b6b9);
    auto dialog=reinterpret_cast<BYTE*>(0x51b732);
    const BYTE oldAccountDialog[]={0xe8,0x65,0x21,0x00,0x00};
    const BYTE oldReason[]={0x0f,0xbe,0x82,0x50,0xf1,0x35,0x00};
    const BYTE oldDialog[]={0xe8,0xf9,0x11,0x00,0x00};
    BYTE newReason[]={0xb8,0,0,0,0,0x90,0x90};
    BYTE newDialog[]={0xe8,0,0,0,0};
    BYTE newAccountDialog[]={0xe8,0,0,0,0};
    memcpy(newReason+1,&sessionRouteFailure,4);
    const auto relative=static_cast<LONG>(reinterpret_cast<BYTE*>(&showSessionRouteFailure)-dialog-5);
    memcpy(newDialog+1,&relative,4);
    const auto accountRelative=static_cast<LONG>(reinterpret_cast<BYTE*>(&showAccountLoginFailure)-accountDialog-5);
    memcpy(newAccountDialog+1,&accountRelative,4);
    if(memcmp(accountDialog,oldAccountDialog,sizeof(oldAccountDialog)) ||
        memcmp(reason,oldReason,sizeof(oldReason)) || memcmp(dialog,oldDialog,sizeof(oldDialog)))return false;
    return patchBytes(accountDialog,oldAccountDialog,newAccountDialog,sizeof(oldAccountDialog)) &&
        patchBytes(dialog,oldDialog,newDialog,sizeof(oldDialog)) &&
        patchBytes(reason,oldReason,newReason,sizeof(oldReason));
}
