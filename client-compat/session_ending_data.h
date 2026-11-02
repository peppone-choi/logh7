// Validated decoded 035a object. Revival presentation codes, not recovered enum meanings.
// Author: Peppone Choi.
#pragma once
#include "planet_info_data.h"
namespace logh {
struct EndingStatistics { unsigned planets=0,fortresses=0,population=0,ships=0,troops=0; };
struct EndingPower { unsigned power=0,characters=0,grade=255;bool present=false;
    unsigned populationCondition=255,fleetCondition=255;EndingStatistics statistics; };
struct SessionEndingData {
    unsigned character=0,power=0,reason=0,seconds=0;wchar_t name[14]={};
    EndingPower sides[2];EndingStatistics total;bool totalPresent=false;
    static EndingStatistics statistics(const unsigned char* p) {
        return {infoShort(p,0),infoShort(p,2),infoWord(p,4),infoWord(p,8),infoWord(p,12)};
    }
    // Decoded lobby 2004: up to two 0x36c-byte records, each including a charged character.
    // Its InformationSession at +4 has the same padded layout as 035a at +0x2e8.
    bool readLobby(const unsigned char* p,size_t length,unsigned index) {
        if(!p || !p[0] || p[0]>2 || index>=p[0] || length<(index+1)*0x36c)return false;
        auto q=p+index*0x36c;
        if(q[0x2a0]!=2 || q[0x2a1]!=1 || q[0x358]>1)return false;
        unsigned char record[0x434]={};
        memcpy(record,q+0x2a4,4);record[4]=q[0x2a8];memcpy(record+0x2e8,q+4,0x14c);
        return read(record);
    }
    bool read(const unsigned char* p,size_t length=0x434) {
        if(!p || length!=0x434 || !infoWord(p,0) || (p[4]!=2 && p[4]!=3) ||
            p[0x2ea]<3 || p[0x2ea]>5 || p[0x2eb]>13 || p[0x420]>1)return false;
        SessionEndingData next;next.character=infoWord(p,0);next.power=p[4];next.reason=p[0x2ea];next.seconds=infoWord(p,0x38c);
        if(p[0x2eb]) {
            if(infoShort(p,0x2ec+(p[0x2eb]-1)*2))return false;
            memcpy(next.name,p+0x2ec,p[0x2eb]*2);
        }
        unsigned mask=0;
        for(unsigned i=0;i<2;++i) {
            auto q=p+0x390+i*0x48;unsigned faction=q[0];
            if((faction!=2 && faction!=3) || q[0x10]>1 || (mask&(1u<<(faction-2))))return false;
            mask|=1u<<(faction-2);auto& side=next.sides[faction-2];side.power=faction;side.characters=infoWord(q,8);side.present=q[0x10]!=0;
            if(side.present) {
                if(q[0x14]>13 || (q[0x32]>3 && q[0x32]!=255) ||
                    (q[0x33]>1 && q[0x33]!=255) || (q[0x34]>1 && q[0x34]!=255))return false;
                side.grade=q[0x32];side.populationCondition=q[0x33];side.fleetCondition=q[0x34];side.statistics=statistics(q+0x38);
            }
        }
        next.totalPresent=p[0x420]!=0;if(next.totalPresent)next.total=statistics(p+0x424);
        *this=next;return true;
    }
};
inline const wchar_t* endingGrade(unsigned grade) {
    return grade==0 ? L"한정적 승리" : grade==1 ? L"국지적 승리" : grade==2 ? L"결정적 승리" : grade==3 ? L"패배" : L"승패 집계 중";
}
}
