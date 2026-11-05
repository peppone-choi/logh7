// Country labels use a copied metadata snapshot; display columns bound narrow native cells.
// Author: Peppone Choi.
#pragma once
#include "country_metadata_data.h"
#include "planet_info_data.h"
#include <string>
namespace logh {
inline std::wstring fitCountryLabel(const std::wstring& text,unsigned columns) {
    unsigned used=0;size_t end=0;
    for(;end<text.size();++end) {
        unsigned width=text[end]<0x80 ? 1u : 2u;
        if(used+width>columns)break;
        used+=width;
    }
    if(end==text.size())return text;
    if(columns<3)return L"";
    used=0;end=0;
    for(;end<text.size();++end) {
        unsigned width=text[end]<0x80 ? 1u : 2u;
        if(used+width>columns-3)break;
        used+=width;
    }
    return text.substr(0,end)+L"...";
}
inline std::wstring countryAffiliationLabel(unsigned id,unsigned camp,const CountryMetadata* metadata,unsigned columns=80) {
    if(camp>1)return fitCountryLabel(L"미확인",columns);
    std::wstring name;
    if(metadata && metadata->id==id && metadata->name[0]) {
        name=metadata->name;
        if(camp)name+=L" 반란군";
    } else name=planetAffiliation(id,camp);
    return fitCountryLabel(name,columns);
}
}
