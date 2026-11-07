// Keep the native reconnect flow and label failures before game authentication.
// Author: Peppone Choi (peppone-choi).
#pragma once
static constexpr int sessionRouteFailure=0x100;

static BYTE __fastcall showSessionRouteFailure(void* context,void*,int reason) {
    const bool transport=reason==sessionRouteFailure;
    const auto result=reinterpret_cast<BYTE(__thiscall*)(void*,int)>(0x51c930)(context,transport ? 0 : reason);
    if(!transport)return result;

    auto dialog=at<void*>(context,0x12c);
    auto element=reinterpret_cast<void*(__thiscall*)(void*,unsigned)>(0x5706d0)(dialog,5);
    auto manager=reinterpret_cast<void*(__thiscall*)(void*)>(0x5706c0)(dialog);
    if(!manager || !element || !at<BYTE>(element,8))return result;
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    wchar_t body[144]=L"게임 서버에 연결하지 못했습니다.\n결정을 누르면 로그인 서버에 다시 연결합니다.";
    if(world) {
        const unsigned ip=at<unsigned>(world,0x35f144);
        const unsigned port=at<unsigned short>(world,0x35f148);
        swprintf_s(body,L"게임 서버에 연결하지 못했습니다.\n접속 주소 %u.%u.%u.%u:%u\n결정을 누르면 로그인 서버에 다시 연결합니다.",
            ip&255,(ip>>8)&255,(ip>>16)&255,ip>>24,port);
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(manager,element,body);
    return result;
}

static bool installSessionRouteFailure() {
    auto reason=reinterpret_cast<BYTE*>(0x51b6b9);
    auto dialog=reinterpret_cast<BYTE*>(0x51b732);
    const BYTE oldReason[]={0x0f,0xbe,0x82,0x50,0xf1,0x35,0x00};
    const BYTE oldDialog[]={0xe8,0xf9,0x11,0x00,0x00};
    BYTE newReason[]={0xb8,0,0,0,0,0x90,0x90};
    BYTE newDialog[]={0xe8,0,0,0,0};
    memcpy(newReason+1,&sessionRouteFailure,4);
    const auto relative=static_cast<LONG>(reinterpret_cast<BYTE*>(&showSessionRouteFailure)-dialog-5);
    memcpy(newDialog+1,&relative,4);
    if(memcmp(reason,oldReason,sizeof(oldReason)) || memcmp(dialog,oldDialog,sizeof(oldDialog)))return false;
    return patchBytes(dialog,oldDialog,newDialog,sizeof(oldDialog)) &&
        patchBytes(reason,oldReason,newReason,sizeof(oldReason));
}
