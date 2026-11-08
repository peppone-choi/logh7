// Authenticated, paged country descriptions shared by all pre-game selectors. Author: Peppone Choi.
#pragma once
#include "country_metadata_data.h"
#include <cstdint>
#include <cwchar>
#include <cstring>

namespace logh {
constexpr unsigned lobbyCountryRows=6,lobbyCountryMaximum=350;
struct LobbyCountry {
    unsigned id=0,rgb=0,characters=0,bases=0,nativePower=0,nativeCamp=0,eligibility=0;
    bool hasRgb=false;
    wchar_t name[25]={};
};
struct LobbyCountryPage {
    unsigned session=0,total=0,offset=0,count=0,seconds=0,totalBases=0;
    std::uint64_t revision=0,generation=0;
    bool unaffiliatedCreation=false;
    LobbyCountry rows[lobbyCountryRows]={};
};
inline bool lobbyCountryNumber(const wchar_t* text,std::uint64_t maximum,std::uint64_t& value) {
    if(!text || !*text || (*text==L'0' && text[1]))return false;
    std::uint64_t result=0;
    for(;*text;++text) {
        if(*text<L'0' || *text>L'9')return false;
        const unsigned digit=*text-L'0';
        if(result>maximum/10 || (result==maximum/10 && digit>maximum%10))return false;
        result=result*10+digit;
    }
    value=result;return true;
}
inline bool lobbyCountryRevision(const wchar_t* first,const wchar_t* second,std::uint64_t& value) {
    if(!first || !second || std::wcslen(first)>12 || std::wcslen(second)>7 || (*second && std::wcslen(first)!=12))return false;
    wchar_t joined[20]={};wcscpy_s(joined,first);wcscat_s(joined,second);
    return lobbyCountryNumber(joined,0x7fffffffffffffffULL,value);
}
inline bool lobbyCountryFields(const unsigned char* p,std::size_t size,wchar_t (&fields)[10][13],unsigned mode=7) {
    if(!p || size<24+10*36 || p[8]!=mode || p[9]!=2 || p[20]!=10)return false;
    for(unsigned i=0;i<10;++i) {
        const auto member=p+24+i*36;
        if(paintWord(member)!=i+1 || member[4]!=i || member[5] || !countryMetadataText(member,fields[i]))return false;
    }
    return true;
}
struct LobbyCountrySelection {
    unsigned session=0,country=0,mode=0,nonce=0,power=0;
    bool pending=false,complete=false,accepted=false;
    bool begin(unsigned target,unsigned id,unsigned purpose,unsigned token) {
        if(!target || target>65535 || purpose<8 || purpose>10 || !token || token>32767 ||
            (purpose==10 ? id!=0 : (purpose==9 && !id) || id>0x7fffffff))return false;
        *this={};session=target;country=id;mode=purpose;nonce=token;pending=true;return true;
    }
    unsigned queryBase() const {return nonce*65536+session;}
    bool accept(const unsigned char* p,std::size_t size) {
        if(!pending || !p || size<24+10*36 || paintWord(p)!=country || paintWord(p+4)!=queryBase() ||
            paintWord(p+12) || paintWord(p+16))return false;
        wchar_t fields[10][13]={};if(!lobbyCountryFields(p,size,fields,mode) || std::wcscmp(fields[0],L"NS1"))return false;
        for(unsigned i=4;i<10;++i)if(fields[i][0])return false;
        std::uint64_t ok=0,display=0;
        if(!lobbyCountryNumber(fields[1],1,ok) || !lobbyCountryNumber(fields[2],3,display))return false;
        if(ok && mode!=10) {
            if((display!=2 && display!=3) || (std::wcscmp(fields[3],L"empire") && std::wcscmp(fields[3],L"alliance")))return false;
        } else if(display || fields[3][0])return false;
        accepted=ok!=0;power=static_cast<unsigned>(display);pending=false;complete=true;return true;
    }
};
struct LobbyCountryDraft {
    LobbyCountryPage page={};unsigned nonce=0,received=0;bool header=false,complete=false;
    bool begin(unsigned session,unsigned token,unsigned offset) {
        if(!session || session>65535 || !token || token>127 || offset>lobbyCountryMaximum)return false;
        *this={};page.session=session;page.offset=offset;nonce=token;return true;
    }
    unsigned queryBase() const {return nonce*512+page.offset;}
    // Publish only a complete header and its ordered rows, never a partly refreshed list.
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || complete || !p || size<24+10*36 || paintWord(p)!=page.session || paintWord(p+4)!=queryBase())return 0;
        wchar_t fields[10][13]={};if(!lobbyCountryFields(p,size,fields))return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            if(header || number || std::wcscmp(fields[0],L"NC1"))return 0;
            LobbyCountryPage candidate;candidate.session=page.session;
            std::uint64_t total=0,offset=0,count=0,seconds=0,bases=0;
            if(!lobbyCountryNumber(fields[1],lobbyCountryMaximum,total) || !lobbyCountryNumber(fields[2],lobbyCountryMaximum,offset) ||
                !lobbyCountryNumber(fields[3],lobbyCountryRows,count) || offset!=page.offset ||
                count!=(offset>=total ? 0 : total-offset<lobbyCountryRows ? total-offset : lobbyCountryRows) ||
                !lobbyCountryRevision(fields[4],fields[5],candidate.revision) ||
                !lobbyCountryNumber(fields[6],0xffffffffULL,seconds) ||
                !lobbyCountryRevision(fields[7],fields[8],candidate.generation) ||
                !lobbyCountryNumber(fields[9],65535,bases))return 0;
            candidate.unaffiliatedCreation=(p[10]&1)!=0;
            if(!candidate.generation && (total || count || candidate.revision || seconds || bases || candidate.unaffiliatedCreation))return 0;
            candidate.total=static_cast<unsigned>(total);candidate.offset=static_cast<unsigned>(offset);
            candidate.count=static_cast<unsigned>(count);candidate.seconds=static_cast<unsigned>(seconds);
            candidate.totalBases=static_cast<unsigned>(bases);page=candidate;header=true;
            complete=!page.count;return complete ? 2 : 1;
        }
        if(kind!=1 || !header || number!=received || received>=page.count)return 0;
        LobbyCountry row;std::uint64_t id=0,characters=0,bases=0,power=0,camp=0,eligible=0;
        if(!lobbyCountryNumber(fields[0],0x7fffffffULL,id) || !id || !fields[1][0] ||
            (fields[2][0] && std::wcslen(fields[1])!=12) ||
            !lobbyCountryNumber(fields[4],0xffffffffULL,characters) || !lobbyCountryNumber(fields[5],65535,bases) ||
            !lobbyCountryNumber(fields[6],4,power) || !lobbyCountryNumber(fields[7],1,camp) || fields[8][0] ||
            !lobbyCountryNumber(fields[9],3,eligible) || (received && id<=page.rows[received-1].id))return 0;
        if(std::wcscmp(fields[3],L"inherit")) {
            if(std::wcslen(fields[3])!=6)return 0;
            for(const wchar_t* c=fields[3];*c;++c) {
                unsigned digit=*c>=L'0' && *c<=L'9' ? *c-L'0' : *c>=L'A' && *c<=L'F' ? *c-L'A'+10 : 16;
                if(digit==16)return 0;row.rgb=row.rgb*16+digit;
            }
            row.hasRgb=true;
        }
        row.id=static_cast<unsigned>(id);row.characters=static_cast<unsigned>(characters);row.bases=static_cast<unsigned>(bases);
        row.nativePower=static_cast<unsigned>(power);row.nativeCamp=static_cast<unsigned>(camp);row.eligibility=static_cast<unsigned>(eligible);
        unsigned sum=row.bases;for(unsigned i=0;i<received;++i)sum+=page.rows[i].bases;
        if(sum>page.totalBases)return 0;
        wcscpy_s(row.name,fields[1]);wcscat_s(row.name,fields[2]);page.rows[received++]=row;
        complete=received==page.count;return complete ? 2 : 1;
    }
};
struct LobbySessionDescription {unsigned id=0,seconds=0;wchar_t name[13]={};};
struct LobbySessionList {unsigned count=0;LobbySessionDescription rows[64]={};};
inline bool readLobbySessionList(const unsigned char* p,std::size_t size,LobbySessionList& output) {
    if(!p || size<4 || p[0] || p[1]>64 || size<4+static_cast<std::size_t>(p[1])*0x14c)return false;
    LobbySessionList candidate;
    for(unsigned i=0;i<p[1];++i) {
        auto q=p+4+i*0x14c;const unsigned id=q[0]|(q[1]<<8),n=q[3];
        if(!id || (q[2]!=1 && q[2]!=2) || !n || n>13 || q[4+(n-1)*2] || q[5+(n-1)*2])return false;
        auto& row=candidate.rows[candidate.count];row.id=id;row.seconds=paintWord(q+0xa4);
        for(unsigned j=0;j+1<n;++j) {
            unsigned c=q[4+j*2]|(q[5+j*2]<<8);
            if(c<32 || (c>=127 && c<=159) || (c>=0xd800 && c<=0xdfff))return false;
            row.name[j]=static_cast<wchar_t>(c);
        }
        if(!row.name[0])return false;
        for(unsigned j=0;j<candidate.count;++j)if(candidate.rows[j].id==id)return false;
        ++candidate.count;
    }
    output=candidate;return true;
}
}
