// Fresh standing-outfit replies, distinct from warehouse inventory. Author: 최병호.
#pragma once
#include "outfit_info_data.h"
namespace logh {
struct PlanetUnitRows {
    struct Row { unsigned id,kind,number,ships,boats,troops; } rows[300]={};
    unsigned ids[300]={},candidates=0,next=0,count=0;
    bool grid(const unsigned char* record,unsigned power) {
        unsigned n=infoShort(record,0);
        if (n>300 || power<2 || power>3) return false;
        unsigned selected[300]={};
        for (unsigned i=0;i<n;++i) {
            auto p=record+4+i*12;unsigned id=infoWord(p,0);
            if (!id || p[4]>3 || p[5]!=power || p[6]) return false;
            for (unsigned j=0;j<i;++j) if (selected[j]==id) return false;
            selected[i]=id;
        }
        memcpy(ids,selected,sizeof(ids));candidates=n;next=count=0;return true;
    }
    bool party(const unsigned char* p,unsigned expected,unsigned base,unsigned power) {
        if (!validOutfitParty(p,expected) || p[9]!=power) return false;
        if (infoWord(p,4)!=base) return true; // A system can contain several planets.
        Row row={expected,infoWord(p,12),infoWord(p,16),0,0,0};
        for (unsigned i=0;i<p[0x180];++i) {
            auto ship=p+0x184+i*0x120;row.ships+=ship[2];row.boats+=infoShort(ship,4);
        }
        for (unsigned i=0;i<p[0x4504];++i) row.troops+=infoShort(p+0x4506+i*6,4);
        if (count==300) return false;
        for (unsigned i=0;i<count;++i) if (rows[i].id==expected) return false;
        rows[count++]=row;return true;
    }
};
}
