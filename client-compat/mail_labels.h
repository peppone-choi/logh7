// Mail factory's title and two list headers only. Author: 최병호.
#pragma once
#include "korean_text.h"
#include "display_text.h"
#include <intrin.h>
#pragma intrinsic(_ReturnAddress)
static std::string mailWindowCaption;
static const char* __fastcall mailWindowTitle(void*,void*,unsigned,unsigned) { return mailWindowCaption.c_str(); }
struct MailLabelOperand {unsigned address,source;};
static constexpr MailLabelOperand mailLabelOperands[]={
    {0x536fef,0x7884d0},{0x53709c,0x7889b8},{0x5379c8,0x788934},{0x537bab,0x7884d0}
};
// The supplied constmsg positions disagree with these CD controls. Translate
// their observed action, without changing shared words used by other menus.
static struct MailControlCaption {
    unsigned call;const wchar_t* text;std::string encoded;
} mailControlCaptions[]={
    {0x536dc1,L"보관 편지",{}},
    {0x53950b,L"받는 이",{}},
    {0x539567,L"보내기",{}},
    {0x5395c9,L"취소",{}},
    {0x539632,L"새로 작성",{}},
    {0x539687,L"주소록",{}},
    {0x53992a,L"답장",{}}
};
static const char* __fastcall mailControlCaption(void* table,void*,unsigned group,unsigned index) {
    const auto caller=reinterpret_cast<uintptr_t>(_ReturnAddress());
    for(const auto& row:mailControlCaptions)if(caller==row.call+5)return row.encoded.c_str();
    return reinterpret_cast<const char*(__thiscall*)(void*,unsigned,unsigned)>(0x522010)(table,group,index);
}
static bool installMailLabels() {
    const BYTE titleExpected[]={0xe8,0x06,0xb6,0xfe,0xff};
    if (memcmp(reinterpret_cast<void*>(0x536a05),titleExpected,sizeof(titleExpected))) return false;
    for(const auto& row:mailLabelOperands) {
        BYTE expected[8]={0xc7,0x44,0x24,0x10};memcpy(expected+4,&row.source,4);
        const char* original=reinterpret_cast<const char*>(row.source);
        if (memcmp(reinterpret_cast<void*>(row.address),expected,sizeof(expected)) ||
            logh::koreanStaticResource(original)==original) return false;
    }
    if (!logh::storeDisplayName(L"편지함",mailWindowCaption)) return false;
    for(auto& row:mailControlCaptions) {
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        if(memcmp(reinterpret_cast<void*>(row.call),expected,5) ||
            !logh::storeDisplayName(row.text,row.encoded))return false;
    }
    BYTE titleCall[5]={0xe8};
    *reinterpret_cast<LONG*>(titleCall+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailWindowTitle)-
        reinterpret_cast<const BYTE*>(0x536a05)-5);
    if (!patchBytes(reinterpret_cast<void*>(0x536a05),titleExpected,titleCall,sizeof(titleCall))) return false;
    for(const auto& row:mailControlCaptions) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-row.call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailControlCaption)-
            reinterpret_cast<const BYTE*>(row.call)-5);
        if(!patchBytes(reinterpret_cast<void*>(row.call),expected,replacement,5))return false;
    }
    for(const auto& row:mailLabelOperands) {
        BYTE expected[8]={0xc7,0x44,0x24,0x10};memcpy(expected+4,&row.source,4);
        BYTE replacement[8];memcpy(replacement,expected,sizeof(expected));
        const char* translated=logh::koreanStaticResource(reinterpret_cast<const char*>(row.source));
        memcpy(replacement+4,&translated,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,sizeof(expected))) return false;
    }
    return true;
}
