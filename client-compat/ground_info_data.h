// Native 0331 decoded information-unit rows, shared by ships and ground units.
// Mode-2 outfit queries are an explicit revival extension. Author: 최병호.
#pragma once
#include "planet_info_data.h"
namespace logh {
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
