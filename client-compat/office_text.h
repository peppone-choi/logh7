// Office mail kind and picker display boundaries only. Author: 최병호.
#pragma once
#include "unicode_text.h"

namespace logh {
inline unsigned officeMailKind(uintptr_t caller,unsigned group,unsigned index) {
    if(group!=0x16 || (caller!=0x53c80e && caller!=0x53c961))return 2;
    if(index==0x54)return 0;
    if(index==0x55)return 1;
    return 2;
}
inline bool officeMailKindText(unsigned kind,std::string& result) {
    if(kind>1)return false;
    const auto row=koreanResourceEntry((22u<<16)|(0x53u+kind));
    std::wstring text;
    if(!row || strlen(row->text)>=16 || !japaneseFragment(row->text,text) ||
        text!=(kind==0?L"제안":L"명령"))return false;
    result=row->text;
    return true;
}
struct OfficeFriendDisplay {
    const char* buffer=nullptr;
    std::wstring name;
    void clear() {buffer=nullptr;name.clear();}
    bool remember(const wchar_t* text,const char* narrowed) {
        clear();
        if(!text || !narrowed)return false;
        std::wstring value;
        unsigned i=0;
        for(;i<13 && text[i];++i)value+=text[i];
        if(i==13 || !validDisplayUnits(value))return false;
        name.swap(value);buffer=narrowed;
        return true;
    }
    bool take(const char* narrowed,std::wstring& result) {
        const bool match=buffer && narrowed==buffer;
        if(match)result=name;
        clear();
        return match;
    }
};
struct OfficeFriendRows {
    struct Row {unsigned id=0;bool present=false;std::string narrow;};
    uintptr_t owner=0,root=0,table=0;
    Row rows[100];
    void begin(uintptr_t list,uintptr_t manager=0,uintptr_t handle=0) {
        owner=list;root=manager;table=handle;for(auto& row:rows)row=Row{};
    }
    bool bind(uintptr_t list,unsigned id,const char* text) {
        if(!list || !text)return false;
        if(owner!=list)begin(list);
        unsigned length=0;
        for(;length<64 && text[length];++length){}
        if(length==64)return false;
        Row* slot=nullptr;
        for(auto& row:rows) {
            if(row.present && row.id==id) {slot=&row;break;}
            if(!row.present && !slot)slot=&row;
        }
        if(!slot)return false;
        slot->id=id;slot->narrow.assign(text,length);slot->present=true;
        return true;
    }
    bool matches(uintptr_t list,unsigned id,const char* text)const {
        if(!owner || owner!=list || !text)return false;
        for(const auto& row:rows) {
            if(!row.present || row.id!=id)continue;
            for(size_t i=0;i<row.narrow.size();++i)if(text[i]!=row.narrow[i])return false;
            return !text[row.narrow.size()];
        }
        return false;
    }
};
inline bool officeFriendCacheName(const void* memory,unsigned id,std::wstring& result) {
    if(!memory)return false;
    const auto cache=static_cast<const unsigned char*>(memory);
    const unsigned count=cache[0];
    if(count>100)return false;
    for(unsigned i=0;i<count;++i) {
        const auto peer=cache+4+i*0x124;
        unsigned candidate=0;memcpy(&candidate,peer,4);
        if(candidate!=id)continue;
        const auto name=reinterpret_cast<const wchar_t*>(peer+6);
        std::wstring wide;
        unsigned j=0;
        for(;j<13 && name[j];++j)wide+=name[j];
        if(j==13 || !validDisplayUnits(wide))return false;
        result.swap(wide);
        return true;
    }
    return false;
}
struct OfficeGroupDisplay {
    struct Label {bool present=false,bound=false;std::wstring wide;std::string narrow;};
    uintptr_t owner=0,root=0,table=0;
    Label labels[300];
    void clear() {
        owner=0;root=0;table=0;
        for(auto& label:labels) {label.present=false;label.bound=false;label.wide.clear();label.narrow.clear();}
    }
    bool remember(uintptr_t context,unsigned group,const wchar_t* name) {
        if(owner!=context)clear();
        if(!context || group>=300)return false;
        owner=context;
        auto& label=labels[group];label=Label{};
        if(!name)return false;
        std::wstring wide;
        unsigned i=0;
        for(;i<13 && name[i];++i)wide+=name[i];
        if(i==13 || !validDisplayUnits(wide))return false;
        label.wide.swap(wide);label.present=true;
        return true;
    }
    bool bind(uintptr_t nodes,int slot,int id,int kind,int icon,int group,const char* narrow) {
        if(!owner || nodes!=owner+0x5938 || slot<0 || slot>=512 || id!=1 || kind!=0 ||
            icon!=2 || group<0 || group>=300 || !narrow)return false;
        auto& label=labels[group];label.bound=false;label.narrow.clear();
        if(!label.present)return false;
        unsigned length=0;
        for(;length<64 && narrow[length];++length){}
        if(length==64 || length<4 || memcmp(narrow,"    ",4))return false;
        label.narrow.assign(narrow,length);label.bound=true;
        return true;
    }
    bool restore(uintptr_t list,int icon,int group,const char* narrow,std::wstring& result)const {
        if(!owner || list!=owner+0x1038 || icon!=2 || group<0 || group>=300 || !narrow)return false;
        const auto& label=labels[group];
        if(!label.present || !label.bound)return false;
        // Normal CString copies share a buffer, but locked copies can allocate.
        // Compare the bounded original bytes as well as the native group ID.
        for(size_t i=0;i<label.narrow.size();++i)if(narrow[i]!=label.narrow[i])return false;
        if(narrow[label.narrow.size()])return false;
        result=std::wstring(L"    ")+label.wide; // Exact CD format at 00788364.
        return true;
    }
};
}
