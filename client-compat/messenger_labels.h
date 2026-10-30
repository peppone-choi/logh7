// Messenger buttons and connection notices, limited to observed CD calls. Author: 최병호.
#pragma once
#include "display_text.h"
#include <intrin.h>
#pragma intrinsic(_ReturnAddress)

static struct MessengerControlCaption {
    unsigned call;const wchar_t* text;std::string encoded;
} messengerControlCaptions[]={
    {0x541155,L"대화 종료",{}},
    {0x5412a1,L"보내기",{}},
    {0x545708,L"대화가 연결되었습니다.",{}},
    {0x54578c,L"대화가 연결되었습니다.",{}},
    {0x545850,L"대화 요청이 도착했습니다.",{}},
    {0x5458d9,L"대화 요청이 도착했습니다.",{}},
    {0x5459a2,L"대화 연결이 종료되었습니다.",{}}
};
static const char* __fastcall messengerControlCaption(void* table,void*,unsigned group,unsigned index) {
    const auto caller=reinterpret_cast<uintptr_t>(_ReturnAddress());
    if(group==0x16)for(const auto& row:messengerControlCaptions)
        if(caller==row.call+5)return row.encoded.c_str();
    return reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(table,group,index);
}
static bool installMessengerLabels() {
    // Native event dispatch, name/newline/color appends and wire values remain in place.
    for(auto& row:messengerControlCaptions) {
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        if(memcmp(reinterpret_cast<void*>(row.call),expected,5) ||
            !logh::storeDisplayName(row.text,row.encoded))return false;
    }
    for(const auto& row:messengerControlCaptions) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&messengerControlCaption)-
            reinterpret_cast<const BYTE*>(row.call)-5);
        if(!patchBytes(reinterpret_cast<void*>(row.call),expected,replacement,5))return false;
    }
    return true;
}
