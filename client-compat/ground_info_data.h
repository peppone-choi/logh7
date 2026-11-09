// Native 0331 decoded information-unit rows, shared by ships and ground units.
// Mode-2 outfit queries are an explicit revival extension. Author: Peppone Choi.
#pragma once
#include "planet_info_data.h"
namespace logh {
// Decoded 0b04/0b05 reply: the single value ('LG' << 16 | reason) is a revival extension.
inline bool groundTransferReason(const unsigned char* record,unsigned& reason) {
    if(record[20]!=1 || (infoWord(record,24)>>16)!=0x4c47)return false;
    reason=infoWord(record,24)&0xffff;return true;
}
// 032f mode 23 (revival extension): the explicit result of one 0417/0418 sent with wait 0x4c470000|token.
// +0 unit, +4 token, one member: +24 character, +28 accepted 1/refused 0, +29 attack 1/clear 0.
// Returns 1 accepted, 0 refused, -1 when the record belongs to another order.
constexpr unsigned groundOrderResultMode=23;
inline int groundOrderResult(const unsigned char* record,unsigned unit,unsigned token,unsigned character,bool attack) {
    if(record[8]!=groundOrderResultMode || record[20]!=1 || !unit || !token || token>0xffff || !character ||
        infoWord(record,0)!=unit || infoWord(record,4)!=token || infoWord(record,24)!=character ||
        record[28]>1 || record[29]!=(attack ? 1 : 0))return -1;
    return record[28];
}
inline unsigned nextGroundOrderToken(unsigned token) {return token>=0xffff ? 1 : token+1;}
struct GroundPage {
    unsigned count=0;
    unsigned char rows[70*88]={};
    bool read(const unsigned char* record,unsigned outfit,unsigned cursor) {
        unsigned n=record[0],previous=cursor;
        if(n>70 || !outfit || cursor>999999)return false;
        for(unsigned i=0;i<n;++i) {
            auto row=record+4+i*88;
            unsigned id=infoWord(row,0),kind=infoShort(row,4),mode=row[6],carrier=infoWord(row,16),base=infoWord(row,64);
            if(id<=previous || id>999999 || kind<1000 || kind>1015 || infoWord(row,8)>4999 ||
                infoWord(row,12)!=outfit || carrier>999999 || carrier==id || base>65535 || row[20])return false;
            if(mode==0 ? carrier || base : mode==10 ? !carrier || base :
                (mode==4 || mode==9) ? carrier || !base : true)return false;
            previous=id;
        }
        count=n;memcpy(rows,record+4,n*88);return true;
    }
    static bool sameRow(const unsigned char* a,const unsigned char* b) {
        return infoWord(a,0)==infoWord(b,0) && infoShort(a,4)==infoShort(b,4) && a[6]==b[6] &&
            infoWord(a,8)==infoWord(b,8) && infoWord(a,12)==infoWord(b,12) &&
            infoWord(a,16)==infoWord(b,16) && a[20]==b[20] && infoWord(a,64)==infoWord(b,64);
    }
    bool same(const GroundPage& other)const {
        if(count!=other.count)return false;
        for(unsigned i=0;i<count;++i)if(!sameRow(rows+i*88,other.rows+i*88))return false;
        return true;
    }
    bool matches(const unsigned char* record)const {
        if(record[0]!=count)return false;
        for(unsigned i=0;i<count;++i)if(!sameRow(rows+i*88,record+4+i*88))return false;
        return true;
    }
    unsigned last()const {return count ? infoWord(rows+(count-1)*88,0) : 0;}
};
}
