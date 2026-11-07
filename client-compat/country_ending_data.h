// Country results retain exact 64-bit statistics and actual identities. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
#include <vector>
namespace logh {
struct EndingCountry {
    unsigned id=0,grade=255,characters=0,populationCondition=255,fleetCondition=255,rgb=0;
    unsigned planets=0,fortresses=0;bool hasRgb=false,eliminated=false;
    std::uint64_t population=0,ships=0,troops=0;wchar_t name[25]={};
};
struct CountryEndingPage {
    unsigned session=0,total=0,offset=0,count=0,status=0,viewer=0;
    std::uint64_t generation=0,seconds=0;EndingCountry rows[3]={};
};
struct CountryEndingDraft {
    CountryEndingPage page={};unsigned nonce=0,received=0;bool header=false,identity=false,complete=false;
    bool begin(unsigned session,unsigned token,unsigned offset) {
        if(!session || session>65535 || !token || token>127 || offset>350)return false;
        *this={};page.session=session;page.offset=offset;nonce=token;return true;
    }
    unsigned queryBase() const {return nonce*512+page.offset;}
    int accept(const unsigned char* p,std::size_t size) {
        if(!nonce || complete || !p || size<384 || paintWord(p)!=page.session || paintWord(p+4)!=queryBase())return 0;
        wchar_t f[10][13]={};if(!lobbyCountryFields(p,size,f,14))return 0;
        const unsigned kind=paintWord(p+12),number=paintWord(p+16);
        if(kind==0) {
            if(header || number || std::wcscmp(f[0],L"CE1"))return 0;
            CountryEndingPage next;next.session=page.session;std::uint64_t total=0,offset=0,count=0,phase=0,viewer=0;
            if(!lobbyCountryNumber(f[1],350,total) || !lobbyCountryNumber(f[2],350,offset) || offset!=page.offset ||
                !lobbyCountryNumber(f[3],3,count) || count!=(offset>=total ? 0 : total-offset<3 ? total-offset : 3) ||
                !lobbyCountryRevision(f[4],f[5],next.generation) || !lobbyCountryRevision(f[6],f[7],next.seconds) ||
                !lobbyCountryNumber(f[8],5,phase) || phase==2 || !lobbyCountryNumber(f[9],0x7fffffff,viewer))return 0;
            if(!phase ? total || count || next.generation || next.seconds || viewer : !next.generation)return 0;
            next.total=static_cast<unsigned>(total);next.offset=static_cast<unsigned>(offset);next.count=static_cast<unsigned>(count);
            next.status=static_cast<unsigned>(phase);next.viewer=static_cast<unsigned>(viewer);page=next;header=true;
            complete=!count;return complete ? 2 : 1;
        }
        if(!header || received>=page.count || number!=received)return 0;
        if(kind==1) {
            if(identity)return 0;
            EndingCountry row;std::uint64_t id=0,grade=0,characters=0,population=0,fleet=0,eliminated=0;
            if(!lobbyCountryNumber(f[0],0x7fffffff,id) || !id || (received && id<=page.rows[received-1].id) ||
                !f[1][0] || (f[2][0] && std::wcslen(f[1])!=12) || !lobbyCountryNumber(f[3],255,grade) || (grade>3 && grade!=255) ||
                !lobbyCountryNumber(f[4],0x7fffffff,characters) || !lobbyCountryNumber(f[5],255,population) || (population>1 && population!=255) ||
                !lobbyCountryNumber(f[6],255,fleet) || (fleet>1 && fleet!=255) || !lobbyCountryNumber(f[8],1,eliminated) || f[9][0])return 0;
            if(std::wcscmp(f[7],L"inherit")) {
                if(std::wcslen(f[7])!=6)return 0;
                for(const wchar_t* c=f[7];*c;++c) {
                    unsigned digit=*c>=L'0' && *c<=L'9' ? *c-L'0' : *c>=L'A' && *c<=L'F' ? *c-L'A'+10 : 16;
                    if(digit>15)return 0;row.rgb=row.rgb*16+digit;
                }
                row.hasRgb=true;
            }
            row.id=static_cast<unsigned>(id);row.grade=static_cast<unsigned>(grade);row.characters=static_cast<unsigned>(characters);
            row.populationCondition=static_cast<unsigned>(population);row.fleetCondition=static_cast<unsigned>(fleet);row.eliminated=eliminated!=0;
            wcscpy_s(row.name,f[1]);wcscat_s(row.name,f[2]);page.rows[received]=row;identity=true;return 1;
        }
        if(kind!=2 || !identity || f[8][0] || f[9][0])return 0;
        auto row=page.rows[received];std::uint64_t planets=0,fortresses=0;
        if(!lobbyCountryNumber(f[0],65535,planets) || !lobbyCountryNumber(f[1],65535,fortresses) ||
            !lobbyCountryRevision(f[2],f[3],row.population) || !lobbyCountryRevision(f[4],f[5],row.ships) ||
            !lobbyCountryRevision(f[6],f[7],row.troops))return 0;
        row.planets=static_cast<unsigned>(planets);row.fortresses=static_cast<unsigned>(fortresses);page.rows[received++]=row;
        identity=false;complete=received==page.count;return complete ? 2 : 1;
    }
};
struct CountryEndingCatalog {
    CountryEndingPage header={};std::vector<EndingCountry> rows;
    const EndingCountry* find(unsigned id) const {for(const auto& row:rows)if(row.id==id)return &row;return nullptr;}
};
}
