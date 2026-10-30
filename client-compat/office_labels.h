// Preserve native CString lengths, identifiers and selection data. Author: 최병호.
#pragma once
#include "office_text.h"

static thread_local logh::OfficeFriendDisplay officeFriendDisplay;
static thread_local logh::OfficeFriendRows officeFriendRows;
static thread_local logh::OfficeGroupDisplay officeGroupDisplay;
#include "communication_labels.h"
static void __fastcall officeFriendBegin(void* list,void*) {
    officeFriendRows.begin(reinterpret_cast<uintptr_t>(list),
        reinterpret_cast<uintptr_t>(at<void*>(list,0x18)),reinterpret_cast<uintptr_t>(at<void*>(list,0x20)));
    officeFriendDisplay.clear();
    reinterpret_cast<void(__thiscall*)(void*)>(0x5774c0)(list);
}
static void* __fastcall officeFriendConstruct(void* output,void*,const wchar_t* name) {
    const auto result=reinterpret_cast<void*(__thiscall*)(void*,const wchar_t*)>(0x64c196)(output,name);
    officeFriendDisplay.remember(name,result?*static_cast<const char**>(result):nullptr);
    return result;
}
static int __fastcall officeFriendRow(void* list,void*,const char* text,unsigned id,int value1,int value2,int value3) {
    const auto context=reinterpret_cast<uintptr_t>(list);
    const auto manager=reinterpret_cast<uintptr_t>(at<void*>(list,0x18));
    const auto handle=reinterpret_cast<uintptr_t>(at<void*>(list,0x20));
    if(officeFriendRows.owner!=context || officeFriendRows.root!=manager || officeFriendRows.table!=handle)
        officeFriendRows.begin(context,manager,handle);
    officeFriendRows.bind(reinterpret_cast<uintptr_t>(list),id,text);
    std::wstring name;
    const bool captured=officeFriendDisplay.take(text,name);
    // Forward the original by-value CString exactly once. The native adder
    // copies its metadata and consumes its destructor, as it did before.
    const auto row=reinterpret_cast<int(__thiscall*)(void*,const char*,unsigned,int,int,int)>(0x577050)
        (list,text,id,value1,value2,value3);
    if(captured && row>=0 && at<void*>(list,0x18) && at<void*>(list,0x20))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
            (at<void*>(list,0x18),at<void*>(list,0x20),name.c_str(),row,0);
    return row;
}
// Native list refreshes decode their retained ANSI CString again. Resolve the
// current row's unchanged ID and exact retained CString before restoring wide
// text. Both the row setter and full refresh use this same guarded boundary.
static void __cdecl officeListCell(void* manager,void* handle,const wchar_t* text,int row,int column,void* list) {
    std::wstring wide;
    const auto context=reinterpret_cast<uintptr_t>(list);
    const bool friendList=list && context==officeFriendRows.owner && row<100 &&
        officeFriendRows.root==reinterpret_cast<uintptr_t>(manager) && officeFriendRows.table==reinterpret_cast<uintptr_t>(handle);
    const bool groupList=officeGroupDisplay.owner && context==officeGroupDisplay.owner+0x1038 &&
        officeGroupDisplay.root==reinterpret_cast<uintptr_t>(manager) && officeGroupDisplay.table==reinterpret_cast<uintptr_t>(handle);
    if(list && (friendList || groupList) && column==0 && row>=0 && row<512 &&
       at<void*>(list,0x18)==manager && at<void*>(list,0x20)==handle) {
        const auto item=reinterpret_cast<void*(__thiscall*)(void*,int)>(0x576ec0)(list,row);
        if(item) {
            const auto narrow=at<const char*>(item,4);
            const unsigned id=at<unsigned>(item,8);
            if(friendList && officeFriendRows.matches(context,id,narrow)) {
                const auto world=*reinterpret_cast<void**>(0x7ccffc);
                if(world && logh::officeFriendCacheName(static_cast<BYTE*>(world)+0x448808,id,wide))text=wide.c_str();
            } else if(groupList && officeGroupDisplay.restore(context,at<int>(item,0xc),
                       at<int>(item,0x10),narrow,wide))text=wide.c_str();
        }
    }
    else if(mailPeerCellText(list,manager,handle,row,column,wide))text=wide.c_str();
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
        (manager,handle,text,row,column);
}
static void __declspec(naked) officeListCellBridge() {
    __asm {
        push esi
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push ecx
        call officeListCell
        add esp, 0x18
        ret 0x10
    }
}
static void __declspec(naked) officeListRefreshCellBridge() {
    __asm {
        push edi
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push dword ptr [esp+0x14]
        push ecx
        call officeListCell
        add esp, 0x18
        ret 0x10
    }
}
static void __stdcall officeGroupBegin(void* memory,unsigned size,unsigned count,void* construct,void* destroy) {
    officeGroupDisplay.clear();
    reinterpret_cast<void(__stdcall*)(void*,unsigned,unsigned,void*,void*)>(0x6004cf)
        (memory,size,count,construct,destroy);
}
static void* __cdecl officeGroupConstruct(void* output,const wchar_t* name,unsigned group,void* context) {
    const auto result=reinterpret_cast<void*(__cdecl*)(void*,const wchar_t*)>(0x4eacf0)(output,name);
    officeGroupDisplay.remember(reinterpret_cast<uintptr_t>(context),group,name);
    return result;
}
static void __declspec(naked) officeGroupConstructBridge() {
    __asm {
        push ebp
        push ebx
        push dword ptr [esp+0x10]
        push dword ptr [esp+0x10]
        call officeGroupConstruct
        add esp, 0x10
        ret
    }
}
static int __fastcall officeGroupNode(void* nodes,void*,int slot,int id,int kind,const void* text,
                                    int value1,int icon,int group,int value4) {
    const auto result=reinterpret_cast<int(__thiscall*)(void*,int,int,int,const void*,int,int,int,int)>(0x535680)
        (nodes,slot,id,kind,text,value1,icon,group,value4);
    if(result==0 && slot>=0 && slot<512)
        officeGroupDisplay.bind(reinterpret_cast<uintptr_t>(nodes),slot,id,kind,icon,group,
            at<const char*>(nodes,static_cast<unsigned>(slot)*32+8));
    return result;
}
static int __fastcall officeGroupRow(void* list,void*,const char* text,unsigned id,int icon,int group,int extra) {
    if(officeGroupDisplay.owner && reinterpret_cast<uintptr_t>(list)==officeGroupDisplay.owner+0x1038) {
        officeGroupDisplay.root=reinterpret_cast<uintptr_t>(at<void*>(list,0x18));
        officeGroupDisplay.table=reinterpret_cast<uintptr_t>(at<void*>(list,0x20));
    }
    std::wstring wide;
    const bool captured=officeGroupDisplay.restore(reinterpret_cast<uintptr_t>(list),icon,group,text,wide);
    const auto row=reinterpret_cast<int(__thiscall*)(void*,const char*,unsigned,int,int,int)>(0x577050)
        (list,text,id,icon,group,extra);
    if(captured && row>=0 && at<void*>(list,0x18) && at<void*>(list,0x20))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*,int,int)>(0x505b50)
            (at<void*>(list,0x18),at<void*>(list,0x20),wide.c_str(),row,0);
    return row;
}
static bool installOfficeFriendLabels() {
    const unsigned calls[]={0x52cdb0,0x52cdbb,0x52d5e7,0x52d6e9,0x52d833,0x52cf1c,0x576b05,0x52cd5d,0x5776a9};
    const unsigned targets[]={0x64c196,0x577050,0x6004cf,0x4eacf0,0x535680,0x577050,0x505b50,0x5774c0,0x505b50};
    const auto construct=reinterpret_cast<const BYTE*>(&officeFriendConstruct);
    const auto add=reinterpret_cast<const BYTE*>(&officeFriendRow);
    const BYTE* replacements[]={construct,add,reinterpret_cast<const BYTE*>(&officeGroupBegin),
        reinterpret_cast<const BYTE*>(&officeGroupConstructBridge),reinterpret_cast<const BYTE*>(&officeGroupNode),
        reinterpret_cast<const BYTE*>(&officeGroupRow),reinterpret_cast<const BYTE*>(&officeListCellBridge),
        reinterpret_cast<const BYTE*>(&officeFriendBegin),reinterpret_cast<const BYTE*>(&officeListRefreshCellBridge)};
    if(memcmp(reinterpret_cast<const void*>(0x788364),"    %s",7))return false;
    for(unsigned i=0;i<9;++i) {
        BYTE expected[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(targets[i]-calls[i]-5);
        if(memcmp(reinterpret_cast<void*>(calls[i]),expected,5))return false;
    }
    for(unsigned i=0;i<9;++i) {
        BYTE expected[5]={0xe8},replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(expected+1)=static_cast<LONG>(targets[i]-calls[i]-5);
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(replacements[i]-reinterpret_cast<const BYTE*>(calls[i])-5);
        if(!patchBytes(reinterpret_cast<void*>(calls[i]),expected,replacement,5))return false;
    }
    return installCommunicationNameLabels();
}
