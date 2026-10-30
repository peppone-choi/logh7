// Verified mail/HUD display-only boundaries after office343. Author: 최병호.
#pragma once
#include "mail_peer_text.h"

static constexpr uintptr_t nativeMailContext=0xcb0038;
static int mailPeerBox(void* list) {
    const auto value=reinterpret_cast<uintptr_t>(list);
    if(value==nativeMailContext+0x14e8214)return 0;
    if(value==nativeMailContext+0x14e9414)return 1;
    return -1;
}
static thread_local logh::MailPeerRows mailPeerRows;
struct MailPeerFrame {void* list;int index;const BYTE* record;};
static thread_local MailPeerFrame mailPeerFrame={nullptr,-1,nullptr};
static void __fastcall mailPeerBegin(void* list,void*) {
    const int box=mailPeerBox(list);
    if(box>=0)mailPeerRows.begin(static_cast<unsigned>(box),reinterpret_cast<uintptr_t>(list),
        reinterpret_cast<uintptr_t>(at<void*>(list,0x18)),reinterpret_cast<uintptr_t>(at<void*>(list,0x20)));
    reinterpret_cast<void(__thiscall*)(void*)>(0x5774c0)(list);
}
static int __cdecl mailPeerRow(void* list,const char* text,unsigned flags,int kind,int index,int extra,const BYTE* record) {
    const int box=mailPeerBox(list);
    const auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    bool bound=false;
    if(box>=0 && world && index>=0 && index<100 && kind==2 && extra==-1 &&
       index<at<int>(world,0x457770) && record==world+0x457774+index*0x75c) {
        const auto peer=record+(box==0?0xc:0x130);
        bound=mailPeerRows.bind(static_cast<unsigned>(box),reinterpret_cast<uintptr_t>(list),
            reinterpret_cast<uintptr_t>(at<void*>(list,0x18)),reinterpret_cast<uintptr_t>(at<void*>(list,0x20)),
            index,*reinterpret_cast<const unsigned*>(record),*reinterpret_cast<const unsigned*>(peer),text,
            reinterpret_cast<const wchar_t*>(peer+6));
    }
    const auto previous=mailPeerFrame;
    struct Restore {MailPeerFrame previous;~Restore(){mailPeerFrame=previous;}} restore={previous};
    mailPeerFrame=bound?MailPeerFrame{list,index,record}:MailPeerFrame{nullptr,-1,nullptr};
    return reinterpret_cast<int(__thiscall*)(void*,const char*,unsigned,int,int,int)>(0x577050)
        (list,text,flags,kind,index,extra);
}
static void __declspec(naked) mailPeerRowBridge() {
    __asm {
        push ebp
        push dword ptr [esp+0x18]
        push dword ptr [esp+0x18]
        push dword ptr [esp+0x18]
        push dword ptr [esp+0x18]
        push dword ptr [esp+0x18]
        push ecx
        call mailPeerRow
        add esp, 0x1c
        ret 0x14
    }
}
static bool mailPeerCellText(void* list,void* root,void* table,int row,int column,std::wstring& wide) {
    const int box=mailPeerBox(list);
    if(box<0 || row<0 || row>=100 || column!=0 ||
       at<void*>(list,0x18)!=root || at<void*>(list,0x20)!=table)return false;
    const auto item=reinterpret_cast<void*(__thiscall*)(void*,int)>(0x576ec0)(list,row);
    if(!item)return false;
    const int index=at<int>(item,0x10);
    if(index<0 || index>=100)return false;
    const BYTE* record=nullptr;
    if(mailPeerFrame.list==list && mailPeerFrame.index==index)record=mailPeerFrame.record;
    else {
        const auto context=reinterpret_cast<BYTE*>(nativeMailContext);
        const unsigned count=at<unsigned>(context,box==0?0xa5c:0x2ea50);
        if(count>100 || static_cast<unsigned>(index)>=count)return false;
        record=context+(box==0?0xa60:0x2ea54)+index*0x75c;
    }
    if(!record)return false;
    const auto peer=record+(box==0?0xc:0x130);
    return mailPeerRows.restore(static_cast<unsigned>(box),reinterpret_cast<uintptr_t>(list),
        reinterpret_cast<uintptr_t>(root),reinterpret_cast<uintptr_t>(table),index,at<int>(item,0xc),at<int>(item,0x14),
        *reinterpret_cast<const unsigned*>(record),*reinterpret_cast<const unsigned*>(peer),at<const char*>(item,4),wide);
}
static void __fastcall selectedMailRecipient(void* context,void*,const char* text,unsigned id,int kind,int extra) {
    reinterpret_cast<void(__thiscall*)(void*,const char*,unsigned,int,int)>(0x53c180)(context,text,id,kind,extra);
    if(reinterpret_cast<uintptr_t>(context)!=nativeMailContext || kind!=0 || extra!=-1 ||
       at<unsigned>(context,0xa44)!=id || at<int>(context,0xa48)!=kind || at<int>(context,0xa4c)!=extra)return;
    const auto list=reinterpret_cast<void*>(officeFriendRows.owner);
    if(!list || officeFriendRows.root!=reinterpret_cast<uintptr_t>(at<void*>(list,0x18)) ||
       officeFriendRows.table!=reinterpret_cast<uintptr_t>(at<void*>(list,0x20)) ||
       !officeFriendRows.matches(officeFriendRows.owner,id,text))return;
    const auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    const auto widget=at<BYTE*>(context,0x150);
    std::wstring wide;
    if(world && widget && at<void*>(context,0xa0c)==widget &&
       logh::officeFriendCacheName(world+0x448808,id,wide))
        reinterpret_cast<void(__cdecl*)(void*,const wchar_t*,int)>(0x4ea8b0)(widget+0xb04,wide.c_str(),-1);
}
static void __fastcall hudPersonalNameLabel(void* root,void*,void* widget,const char* original) {
    reinterpret_cast<void(__thiscall*)(void*,void*,const char*)>(0x5034d0)(root,widget,original);
    const auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    const auto own=world?at<void*>(world,8):nullptr;
    if(!own || !widget || !at<BYTE>(widget,8))return;
    const unsigned id=reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4b5b80)(own);
    const auto profile=reinterpret_cast<BYTE*(__cdecl*)(unsigned)>(0x4c7290)(id);
    if(!profile)return;
    const auto rank=at<unsigned short>(profile,0x56);
    const auto rankText=reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8d70)
        (reinterpret_cast<void*>(0x7cd048),rank);
    std::wstring decoded,wide;
    if(logh::japaneseFragment(rankText,decoded) &&
       logh::hudPersonalName(reinterpret_cast<const wchar_t*>(profile+0x3a),rank,decoded,wide))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,wide.c_str());
}
static bool installCommunicationNameLabels() {
    const unsigned calls[]={0x52c6c8,0x53c484,0x53c34c,0x53c366,0x58d3b2};
    const unsigned targets[]={0x53c180,0x577050,0x5774c0,0x5774c0,0x5034d0};
    const BYTE* replacements[]={reinterpret_cast<const BYTE*>(&selectedMailRecipient),reinterpret_cast<const BYTE*>(&mailPeerRowBridge),
        reinterpret_cast<const BYTE*>(&mailPeerBegin),reinterpret_cast<const BYTE*>(&mailPeerBegin),reinterpret_cast<const BYTE*>(&hudPersonalNameLabel)};
    if(memcmp(reinterpret_cast<const void*>(0x78d5bc),"%s%s\n",6))return false;
    for(unsigned i=0;i<5;++i) {
        BYTE expected[5]={0xe8};*reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(targets[i]-calls[i]-5);
        if(memcmp(reinterpret_cast<void*>(calls[i]),expected,5))return false;
    }
    for(unsigned i=0;i<5;++i) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(targets[i]-calls[i]-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(replacements[i]-reinterpret_cast<const BYTE*>(calls[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(calls[i]),expected,replacement,5))return false;
    }
    return true;
}
