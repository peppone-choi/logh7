// Bounded display-only metadata; malformed records never replace the cache. Author: Peppone Choi.
#pragma once
#include "country_colors_data.h"
namespace logh {
struct CountryMetadata {unsigned id=0;wchar_t name[25]={},calendar[13]={};int yearOffset=0;std::uint64_t revision=0;};
inline bool countryMetadataText(const unsigned char* member,wchar_t (&text)[13]) {
    unsigned n=member[6];text[0]=0;
    if(!n)return true;
    if(n>13 || member[8+(n-1)*2] || member[9+(n-1)*2])return false;
    for(unsigned i=0;i+1<n;++i) {
        unsigned value=member[8+i*2]|(member[9+i*2]<<8);
        if(value<32 || (value>=127 && value<=159) || (value>=0xd800 && value<=0xdfff))return false;
        text[i]=static_cast<wchar_t>(value);
    }
    text[n-1]=0;return true;
}
inline bool readCountryMetadata(const unsigned char* p,std::size_t size,unsigned country,unsigned nonce,CountryMetadata& output) {
    if(!p || size<24+6*36 || !country || !nonce || paintWord(p)!=country || paintWord(p+4)!=nonce || p[8]!=5 || p[20]!=6)return false;
    CountryMetadata candidate;candidate.id=country;wchar_t text[6][13]={};
    for(unsigned i=0;i<6;++i) {
        auto row=p+24+i*36;
        if(paintWord(row)!=i+1 || row[4]!=i || row[5] || !countryMetadataText(row,text[i]))return false;
    }
    if(!text[0][0] || !text[2][0] || text[1][0] && std::wcslen(text[0])!=12)return false;
    wcscpy_s(candidate.name,text[0]);wcscat_s(candidate.name,text[1]);wcscpy_s(candidate.calendar,text[2]);
    const wchar_t* offset=text[3];bool negative=*offset==L'-';if(negative)++offset;
    if(!*offset || *offset==L'0' && (offset[1] || negative))return false;
    unsigned value=0;
    for(;*offset;++offset) {if(*offset<L'0' || *offset>L'9')return false;value=value*10+unsigned(*offset-L'0');if(value>10000)return false;}
    candidate.yearOffset=negative?-static_cast<int>(value):static_cast<int>(value);
    const auto first=std::wcslen(text[4]),second=std::wcslen(text[5]);
    if(!first || first+second>19 || (second && first!=12) || (first+second>1 && text[4][0]==L'0'))return false;
    for(unsigned i=4;i<6;++i)for(const wchar_t* c=text[i];*c;++c) {
        if(*c<L'0' || *c>L'9')return false;unsigned digit=*c-L'0';
        if(candidate.revision>(0x7fffffffffffffffULL-digit)/10)return false;
        candidate.revision=candidate.revision*10+digit;
    }
    output=candidate;return true;
}
}
