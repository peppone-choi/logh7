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
}
