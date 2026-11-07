// Public base ownership is staged atomically; native power stays a display field. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
namespace logh {
struct BaseAffiliation {
    struct Owner {unsigned id=0,country=0,power=0,camp=0;};
    unsigned count=0;Owner rows[350]={};
    const Owner* find(unsigned id) const {for(unsigned i=0;i<count;++i)if(rows[i].id==id)return rows+i;return nullptr;}
    unsigned affiliation(const unsigned* ids,unsigned n,unsigned& country) const {
        country=0;if(!ids || !n || n>350)return 10;
        const Owner* first=nullptr;bool mixed=false;
        for(unsigned i=0;i<n;++i) {
            const auto row=find(ids[i]);if(!row)return 10;
            if(!first)first=row;
            else if(row->country!=first->country || row->power!=first->power || row->camp!=first->camp)mixed=true;
        }
        if(mixed)return 11;
        country=first->country;return first->power*2+first->camp;
    }
};
struct BaseAffiliationDraft {
    BaseAffiliation value={};unsigned nonce=0,total=0,batches=0;bool header=false,complete=false;
    void begin(unsigned token) {*this={};if(token && token<=65535)nonce=token;}
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || complete || !p || size<24 || paintWord(p) || paintWord(p+4)!=nonce || p[8]!=12 || p[9]!=2)return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            wchar_t fields[10][13]={};std::uint64_t count=0;
            if(header || number || !lobbyCountryFields(p,size,fields,12) || std::wcscmp(fields[0],L"BA1") ||
                !lobbyCountryNumber(fields[1],350,count))return 0;
            for(unsigned i=2;i<10;++i)if(fields[i][0])return 0;
            total=static_cast<unsigned>(count);header=true;complete=!total;return complete ? 2 : 1;
        }
        const unsigned count=total-value.count<10 ? total-value.count : 10;
        if(kind!=1 || !header || number!=batches || !count || p[20]!=count || size<24+count*36)return 0;
        BaseAffiliation::Owner rows[10]={};
        unsigned previous=value.count ? value.rows[value.count-1].id : 0;
        for(unsigned i=0;i<count;++i) {
            const auto member=p+24+i*36;wchar_t text[13]={};std::uint64_t country=0;
            const unsigned id=paintWord(member);
            if(id<=previous || id>65535 || member[4]>1 || member[5]>4 || !countryMetadataText(member,text) ||
                !lobbyCountryNumber(text,0x7fffffffULL,country))return 0;
            rows[i]={id,static_cast<unsigned>(country),member[5],member[4]};previous=id;
        }
        for(unsigned i=0;i<count;++i)value.rows[value.count++]=rows[i];
        ++batches;complete=value.count==total;return complete ? 2 : 1;
    }
};
}
