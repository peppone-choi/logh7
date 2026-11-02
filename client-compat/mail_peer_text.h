// Mail counterpart names remain outside native ANSI row storage. Author: Peppone Choi.
#pragma once
#include "office_text.h"

namespace logh {
inline bool boundedPeerName(const wchar_t* text,std::wstring& result) {
    if(!text)return false;
    std::wstring wide;
    unsigned i=0;for(;i<13 && text[i];++i)wide+=text[i];
    if(i==13 || !validDisplayUnits(wide))return false;
    result.swap(wide);return true;
}
inline bool hudPersonalName(const wchar_t* name,unsigned rank,const std::wstring& rankText,std::wstring& result) {
    std::wstring wide;
    if(!boundedPeerName(name,wide))return false;
    // Exact CD format at 0078d5bc: %s%s followed by a newline.
    result=rank>1 ? wide+rankText+L"\n" : rankText+wide+L"\n";
    return true;
}
struct MailPeerRows {
    struct Row {bool present=false;unsigned mail=0,peer=0;std::string narrow;std::wstring wide;};
    struct Box {uintptr_t owner=0,root=0,table=0;Row rows[100];};
    Box boxes[2];
    void begin(unsigned box,uintptr_t list,uintptr_t root,uintptr_t table) {
        if(box>=2)return;
        auto& b=boxes[box];b.owner=list;b.root=root;b.table=table;
        for(auto& row:b.rows)row=Row{};
    }
    bool bind(unsigned box,uintptr_t list,uintptr_t root,uintptr_t table,int index,
              unsigned mail,unsigned peer,const char* narrow,const wchar_t* name) {
        if(box>=2 || !list || !root || !table || index<0 || index>=100 || !mail || !peer || !narrow)return false;
        auto& b=boxes[box];
        if(b.owner!=list || b.root!=root || b.table!=table || index==0)begin(box,list,root,table);
        auto& row=b.rows[index];row=Row{};
        unsigned length=0;for(;length<64 && narrow[length];++length){}
        if(length==64 || !boundedPeerName(name,row.wide))return false;
        row.mail=mail;row.peer=peer;row.narrow.assign(narrow,length);row.present=true;
        return true;
    }
    bool restore(unsigned box,uintptr_t list,uintptr_t root,uintptr_t table,int index,int kind,int extra,
                 unsigned mail,unsigned peer,const char* narrow,std::wstring& result)const {
        if(box>=2 || index<0 || index>=100 || kind!=2 || extra!=-1 || !narrow)return false;
        const auto& b=boxes[box];const auto& row=b.rows[index];
        if(!b.owner || b.owner!=list || b.root!=root || b.table!=table || !row.present ||
           row.mail!=mail || row.peer!=peer)return false;
        for(size_t i=0;i<row.narrow.size();++i)if(narrow[i]!=row.narrow[i])return false;
        if(narrow[row.narrow.size()])return false;
        result=row.wide;return true;
    }
};
}
