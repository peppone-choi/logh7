// Actual tactical sides, independently of native power and ship style. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
namespace logh {
struct TacticalSide {
    unsigned kind=0,owner=0,insurgent=0;
    bool valid() const {return kind<=3 && insurgent<=1 && owner<=0x7fffffff && (kind<2 ? owner!=0 : !owner && !insurgent);}
    bool operator==(const TacticalSide& other) const {return kind==other.kind && owner==other.owner && insurgent==other.insurgent;}
    std::uint64_t code() const {return (std::uint64_t(owner)<<3)|(kind<<1)|insurgent;}
    static bool decode(std::uint64_t code,TacticalSide& side) {
        if(code>0x3ffffffffULL)return false;
        TacticalSide candidate={static_cast<unsigned>((code>>1)&3),static_cast<unsigned>(code>>3),static_cast<unsigned>(code&1)};
        if(!candidate.valid())return false;side=candidate;return true;
    }
};
inline unsigned tacticalRelation(unsigned id,unsigned own,const TacticalSide& viewer,const TacticalSide& target) {
    if(id==own)return 0x400;
    return viewer==target ? 0x800 : 0x1000;
}
struct TacticalCampPage {
    struct Row {unsigned id=0;TacticalSide side;};
    unsigned count=0,next=0,grid=5000;bool more=false;
    std::uint64_t generation=0,stamp=0;TacticalSide viewer;Row rows[60]={};
};
struct TacticalCampDraft {
    TacticalCampPage page={};unsigned nonce=0,cursor=0,received=0,batches=0;bool header=false,complete=false;
    void begin(unsigned token,unsigned after,unsigned grid) {*this={};nonce=token;cursor=after;page.grid=grid;}
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || nonce>65535 || cursor>0x7fffffff || page.grid>=5000 || complete || !p || size<24 ||
            paintWord(p)!=nonce || paintWord(p+4)!=cursor || p[8]!=15 || p[9]!=2)return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            wchar_t fields[10][13]={};std::uint64_t count=0,next=0,more=0,grid=0,generation=0,stamp=0,viewer=0;
            TacticalSide side;
            if(header || number || !lobbyCountryFields(p,size,fields,15) || std::wcscmp(fields[0],L"UC1") ||
                !lobbyCountryNumber(fields[1],60,count) || !lobbyCountryNumber(fields[2],0x7fffffff,next) ||
                !lobbyCountryNumber(fields[3],1,more) || !lobbyCountryNumber(fields[4],4999,grid) || grid!=page.grid ||
                !lobbyCountryRevision(fields[5],fields[6],generation) || !generation ||
                !lobbyCountryRevision(fields[7],fields[8],stamp) || !lobbyCountryNumber(fields[9],0x3ffffffffULL,viewer) ||
                !TacticalSide::decode(viewer,side) || (count ? next<=cursor : next!=cursor) || (more && count!=60))return 0;
            page.count=static_cast<unsigned>(count);page.next=static_cast<unsigned>(next);page.more=more!=0;
            page.generation=generation;page.stamp=stamp;page.viewer=side;header=true;complete=!count;return complete ? 2 : 1;
        }
        const unsigned count=page.count-received<10 ? page.count-received : 10;
        if(kind!=1 || !header || number!=batches || !count || p[20]!=count || size<24+count*36)return 0;
        TacticalCampPage::Row rows[10]={};unsigned previous=received ? page.rows[received-1].id : cursor;
        for(unsigned i=0;i<count;++i) {
            auto member=p+24+i*36;wchar_t text[13]={};std::uint64_t owner=0;const unsigned id=paintWord(member);
            if(id<=previous || id>page.next || !countryMetadataText(member,text) || !lobbyCountryNumber(text,0x7fffffffULL,owner))return 0;
            rows[i]={id,{member[4],static_cast<unsigned>(owner),member[5]}};
            if(!rows[i].side.valid())return 0;previous=id;
        }
        if(received+count==page.count && previous!=page.next)return 0;
        for(unsigned i=0;i<count;++i)page.rows[received++]=rows[i];
        ++batches;complete=received==page.count;return complete ? 2 : 1;
    }
};
// A publication always contains every page from one immutable roster fingerprint.
struct TacticalCampRoster {
    std::uint64_t generation=0,stamp=0;TacticalSide viewer;
    unsigned count=0,cursor=0;TacticalCampPage::Row rows[600]={};bool complete=false;
    bool append(const TacticalCampPage& page) {
        if(complete || !page.generation || !page.viewer.valid() || count+page.count>600 ||
            (cursor && (generation!=page.generation || stamp!=page.stamp || !(viewer==page.viewer))) ||
            (page.count ? page.rows[0].id<=cursor : page.next!=cursor) || (page.more && count+page.count>=600))return false;
        for(unsigned i=0;i<page.count;++i)rows[count++]=page.rows[i];
        generation=page.generation;stamp=page.stamp;viewer=page.viewer;cursor=page.next;complete=!page.more;return true;
    }
    const TacticalSide* find(unsigned id) const {
        unsigned first=0,last=count;
        while(first<last) {
            const unsigned middle=first+(last-first)/2;
            if(rows[middle].id<id)first=middle+1;else last=middle;
        }
        return first<count && rows[first].id==id ? &rows[first].side : nullptr;
    }
};
}
