// Bounded facility descriptions in decoded 032f mode 6 pages. Author: Peppone Choi.
#pragma once
#include "country_metadata_data.h"
#include <cwchar>
#include <cstring>
namespace logh {
struct FacilityGuide {
    unsigned id=0;unsigned long long revision=0;
    wchar_t name[25]={},purpose[201]={},usage[201]={},commands[193]={};
};
struct FacilityGuideDraft {
    FacilityGuide value={};unsigned nonce=0,next=0;
    void begin(unsigned id,unsigned token) {value={};value.id=id;nonce=token;next=0;}
    // 0 malformed/stale; -1 revision changed; 1 more pages; 2 complete. No partial publication.
    int accept(const unsigned char* p,std::size_t size) {
        if(!p || size<24+10*36 || !nonce || nonce>4095 || next>=9 ||
            paintWord(p)!=value.id || paintWord(p+4)!=nonce*16+next || p[8]!=6 || p[20]!=10)return 0;
        wchar_t fields[10][13]={};
        for(unsigned i=0;i<10;++i) {
            const auto row=p+24+i*36;
            if(paintWord(row)!=i+1 || row[4]!=i || row[5] || !countryMetadataText(row,fields[i]))return 0;
        }
        const auto first=std::wcslen(fields[0]),second=std::wcslen(fields[1]);
        if(!first || first+second>19 || (second && first!=12) || (first+second>1 && fields[0][0]==L'0'))return 0;
        unsigned long long revision=0;
        for(unsigned i=0;i<2;++i)for(const wchar_t* c=fields[i];*c;++c) {
            if(*c<L'0' || *c>L'9' || revision>(0x7fffffffffffffffULL-unsigned(*c-L'0'))/10)return 0;
            revision=revision*10+unsigned(*c-L'0');
        }
        if(next && revision!=value.revision)return -1;
        wchar_t text[97]={};bool ended=false;
        for(unsigned i=2;i<10;++i) {
            auto n=std::wcslen(fields[i]);if(ended && n)return 0;
            if(n<12)ended=true;wcscat_s(text,fields[i]);
        }
        unsigned offset=0,capacity=24;auto candidate=value;wchar_t* destination=candidate.name;
        if(next>=1 && next<=3){destination=candidate.purpose;offset=(next-1)*96;capacity=200;}
        else if(next>=4 && next<=6){destination=candidate.usage;offset=(next-4)*96;capacity=200;}
        else if(next>=7){destination=candidate.commands;offset=(next-7)*96;capacity=192;}
        auto length=std::wcslen(text);
        if(offset+length>capacity || (length && std::wcslen(destination)!=offset))return 0;
        if(length)std::memcpy(destination+offset,text,(length+1)*sizeof(wchar_t));
        candidate.revision=revision;value=candidate;++next;return next==9 ? 2 : 1;
    }
};
// Eight 56-column lines fit the facility pane; preserve every character at the 200-character limit.
inline void facilityGuideLine(const wchar_t* text,unsigned line,wchar_t (&output)[97]) {
    output[0]=0;if(!text)return;unsigned start=0;
    for(unsigned row=0;row<=line && text[start];++row) {
        unsigned end=start,columns=0;
        while(text[end] && columns+(text[end]>=256 ? 2u : 1u)<=56) {
            columns+=text[end]>=256 ? 2 : 1;++end;
        }
        if(row==line){auto n=end-start;std::memcpy(output,text+start,n*sizeof(wchar_t));output[n]=0;return;}
        start=end;
    }
}
}
