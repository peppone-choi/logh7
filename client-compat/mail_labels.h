// Mail factory's title and two list headers only. Author: Peppone Choi.
#pragma once
#include "korean_text.h"
#include "display_text.h"
#include "messenger_text.h"
#include "messenger_labels.h"
#include "mail_caption_text.h"
#include "office_labels.h"
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
    {0x53992a,L"답장",{}},
    {0x5370d1,L"받은 편지함",{}},
    {0x53716f,L"보낸 편지함",{}},
    {0x537211,L"편지 작성",{}},
    {0x53d950,L"주소록",{}},
    {0x5372b3,L"보낸 사람",{}},
    {0x53abd7,L"보낸 사람",{}},
    {0x53ac35,L"보낸 사람",{}},
    {0x53ac92,L"보낸 사람",{}},
    {0x53abf5,L"받는 사람",{}},
    {0x53ac52,L"받는 사람",{}},
    {0x53acb0,L"받는 사람",{}},
    {0x544bac,L"온라인 이용자",{}},
    {0x544d25,L"오프라인 이용자",{}},
    {0x544c8a,L" (자리 비움)",{}},
    {0x544eea,L"명",{}},
    {0x544f81,L"명",{}},
    {0x544f00,L"참가 인원 ",{}},
    {0x544f97,L"참가 인원 ",{}},
    {0x540f3c,L"상태",{}},
    {0x5410a0,L"대화할 상대를 선택하세요.",{}},
    {0x5445d4,L"대화할 상대를 선택하세요.",{}},
    {0x5445f2,L"메시지를 입력하고 보내기를 누르세요.",{}}
};
static struct MessengerStatusCaption {const wchar_t* text;std::string encoded;} messengerStatusCaptions[]={
    {L"대화 가능",{}},{L"자리 비움",{}},{L"접속 해제",{}}
};
static constexpr unsigned messengerStatusCalls[]={0x5433a0,0x545018,0x54503f,0x545066};
static std::string mailImperialEra,mailSpaceEra,mailListHint,mailComposeHint,mailAddressHint,mailAddressTitle;
static std::string officeProposal,officeOrder;
static struct MailLiteralCaption {
    unsigned address,source;const wchar_t* text;std::string encoded;
} mailLiteralCaptions[]={
    {0x536f5e,0x7889c4,L"보낸 사람",{}},
    {0x537ceb,0x788918,L"본문",{}}
};
// 53c809/53c95c inspect an office-row flag, not a calendar allegiance.
static constexpr unsigned mailEraCalls[]={0x53cc60,0x53cc98};
static constexpr unsigned officeKindCalls[]={0x53c809,0x53c95c};
static constexpr unsigned mailHelpCalls[]={0x537eb2,0x537f4e,0x53c2d4,
    0x529d3f,0x52a0fa,0x52d309,0x52d57b,0x52d8e9};
static const char* __fastcall mailControlCaption(void* table,void*,unsigned group,unsigned index) {
    const auto caller=reinterpret_cast<uintptr_t>(_ReturnAddress());
    const auto statusKind=logh::messengerStatusKind(caller,group,index);
    if(statusKind<3)return messengerStatusCaptions[statusKind].encoded.c_str();
    for(const auto& row:mailControlCaptions)if(caller==row.call+5)return row.encoded.c_str();
    const auto calendar=logh::mailCalendarKind(caller,group,index);
    if(calendar<2)return calendar==0 ? mailImperialEra.c_str() : mailSpaceEra.c_str();
    const auto office=logh::officeMailKind(caller,group,index);
    if(office<2)return office==0 ? officeProposal.c_str() : officeOrder.c_str();
    const auto footer=logh::mailFooterKind(caller,group,index);
    if(footer<3)return footer==0 ? mailListHint.c_str() : footer==1 ? mailComposeHint.c_str() : mailAddressHint.c_str();
    if(caller==0x529d44 && group==0x16 && index==0)return mailAddressTitle.c_str();
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
    for(auto& row:messengerStatusCaptions)if(!logh::storeDisplayName(row.text,row.encoded))return false;
    for(auto call:messengerStatusCalls) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        if(memcmp(reinterpret_cast<void*>(call),expected,5))return false;
    }
    if(!logh::mailCalendarText(true,mailImperialEra) || !logh::mailCalendarText(false,mailSpaceEra) ||
        !logh::storeDisplayName(L"목록에서 편지를 선택하세요.",mailListHint) ||
        !logh::storeDisplayName(L"받는 이를 고르고 편지를 작성하세요.",mailComposeHint) ||
        !logh::storeDisplayName(L"인물을 선택한 뒤 결정을 누르세요.",mailAddressHint) ||
        !logh::storeDisplayName(L"인물 선택",mailAddressTitle))return false;
    if(!logh::officeMailKindText(0,officeProposal) || !logh::officeMailKindText(1,officeOrder))return false;
    for(auto& row:mailLiteralCaptions) {
        BYTE expected[8]={0xc7,0x44,0x24,0x10};memcpy(expected+4,&row.source,4);
        if(memcmp(reinterpret_cast<void*>(row.address),expected,8) || !logh::storeDisplayName(row.text,row.encoded))return false;
    }
    for(auto call:mailEraCalls) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        if(memcmp(reinterpret_cast<void*>(call),expected,5))return false;
    }
    for(auto call:mailHelpCalls) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        if(memcmp(reinterpret_cast<void*>(call),expected,5))return false;
    }
    for(auto call:officeKindCalls) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        if(memcmp(reinterpret_cast<void*>(call),expected,5))return false;
    }
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
    for(auto call:mailEraCalls) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailControlCaption)-reinterpret_cast<const BYTE*>(call)-5);
        if(!patchBytes(reinterpret_cast<void*>(call),expected,replacement,5))return false;
    }
    for(auto call:messengerStatusCalls) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailControlCaption)-reinterpret_cast<const BYTE*>(call)-5);
        if(!patchBytes(reinterpret_cast<void*>(call),expected,replacement,5))return false;
    }
    for(auto call:mailHelpCalls) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailControlCaption)-reinterpret_cast<const BYTE*>(call)-5);
        if(!patchBytes(reinterpret_cast<void*>(call),expected,replacement,5))return false;
    }
    for(auto call:officeKindCalls) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(0x522010-call-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&mailControlCaption)-reinterpret_cast<const BYTE*>(call)-5);
        if(!patchBytes(reinterpret_cast<void*>(call),expected,replacement,5))return false;
    }
    for(const auto& row:mailLabelOperands) {
        BYTE expected[8]={0xc7,0x44,0x24,0x10};memcpy(expected+4,&row.source,4);
        BYTE replacement[8];memcpy(replacement,expected,sizeof(expected));
        const char* translated=logh::koreanStaticResource(reinterpret_cast<const char*>(row.source));
        memcpy(replacement+4,&translated,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,sizeof(expected))) return false;
    }
    for(const auto& row:mailLiteralCaptions) {
        BYTE expected[8]={0xc7,0x44,0x24,0x10},replacement[8];memcpy(expected+4,&row.source,4);
        memcpy(replacement,expected,8);const auto text=row.encoded.c_str();memcpy(replacement+4,&text,4);
        if(!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,8))return false;
    }
    return installMessengerLabels() && installOfficeFriendLabels();
}
