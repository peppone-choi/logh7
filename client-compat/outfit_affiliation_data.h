// Paged actual outfit countries; stale and incomplete pages never publish. Author: Peppone Choi.
#pragma once
#include "base_affiliation_data.h"
#include <vector>
namespace logh {
struct OutfitAffiliationPage {
    unsigned count=0,next=0,grid=5000;bool more=false;
    std::uint64_t generation=0;
    BaseAffiliation::Owner rows[60]={};
};
struct OutfitAffiliationDraft {
    OutfitAffiliationPage page={};unsigned nonce=0,cursor=0,received=0,batches=0;bool header=false,complete=false;
    void begin(unsigned token,unsigned after,unsigned grid) {*this={};nonce=token;cursor=after;page.grid=grid;}
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || nonce>65535 || cursor>0x7fffffff || page.grid>=5000 || complete || !p || size<24 ||
            paintWord(p)!=nonce || paintWord(p+4)!=cursor || p[8]!=13 || p[9]!=2)return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            wchar_t fields[10][13]={};std::uint64_t count=0,next=0,more=0,grid=0,generation=0;
            if(header || number || !lobbyCountryFields(p,size,fields,13) || std::wcscmp(fields[0],L"FA1") ||
                !lobbyCountryNumber(fields[1],60,count) || !lobbyCountryNumber(fields[2],0x7fffffff,next) ||
                !lobbyCountryNumber(fields[3],1,more) || !lobbyCountryNumber(fields[4],4999,grid) || grid!=page.grid ||
                !lobbyCountryRevision(fields[5],fields[6],generation) || !generation ||
                (count ? next<=cursor : next!=cursor) || (more && count!=60))return 0;
            for(unsigned i=7;i<10;++i)if(fields[i][0])return 0;
            page.count=static_cast<unsigned>(count);page.next=static_cast<unsigned>(next);page.more=more!=0;page.generation=generation;
            header=true;complete=!count;return complete ? 2 : 1;
        }
        const unsigned count=page.count-received<10 ? page.count-received : 10;
        if(kind!=1 || !header || number!=batches || !count || p[20]!=count || size<24+count*36)return 0;
        BaseAffiliation::Owner rows[10]={};unsigned previous=received ? page.rows[received-1].id : cursor;
        for(unsigned i=0;i<count;++i) {
            auto member=p+24+i*36;wchar_t text[13]={};std::uint64_t country=0;const unsigned id=paintWord(member);
            if(id<=previous || id>page.next || member[4]>1 || member[5]>4 || !countryMetadataText(member,text) ||
                !lobbyCountryNumber(text,0x7fffffffULL,country))return 0;
            rows[i]={id,static_cast<unsigned>(country),member[5],member[4]};previous=id;
        }
        if(received+count==page.count && previous!=page.next)return 0;
        for(unsigned i=0;i<count;++i)page.rows[received++]=rows[i];
        ++batches;complete=received==page.count;return complete ? 2 : 1;
    }
};
}
