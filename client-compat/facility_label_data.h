// Display-only labels for native facility buttons. Author: Peppone Choi.
#pragma once
#include "display_text.h"
namespace logh {
inline bool facilityLabel(unsigned id,unsigned kind,const wchar_t* name,std::string& stored) {
    if(id/100<1 || id/100>65535 || kind>=39 || id%100!=kind+1 || !name)return false;
    std::wstring text;
    for(unsigned i=0;i<25 && name[i];++i) {
        if(name[i]<32 || name[i]==127 || (name[i]>=0xd800 && name[i]<=0xdfff))return false;
        text+=name[i];
    }
    if(text.empty() || text.size()>24)return false;
    if(text.size()>12){text.resize(11);text+=L'…';}
    return storeDisplayName(text.c_str(),stored,13);
}
inline std::wstring facilityMoveName(const std::wstring& name) {
    // The movement row also draws the fare, native facility ID and disclosure arrow.
    auto columns=[](wchar_t ch) {return ch>=256 ? 2u : 1u;};
    unsigned width=0;
    for(wchar_t ch:name)width+=columns(ch);
    if(width<=16)return name;
    // Keep both ends so long names with a shared institution prefix remain distinguishable.
    size_t first=0,last=name.size();
    for(unsigned left=8;first<name.size() && columns(name[first])<=left;++first)left-=columns(name[first]);
    for(unsigned right=6;last>first && columns(name[last-1])<=right;--last)right-=columns(name[last-1]);
    auto result=name.substr(0,first);
    result+=L'…';result+=name.substr(last);
    return result;
}
}
