// Preserve the CD menu distinction lost by its shared missile/AA arm byte. Author: Peppone Choi.
#pragma once
#include <string.h>
namespace logh {
struct TacticalWeaponSelection {
    unsigned menu = 0;
    const void* world = nullptr;
    unsigned count = 0, units[32] = {};
    static bool canSelect(const unsigned* ids,const unsigned* powers,unsigned size) {
        if (!ids || !powers || !size || size>32) return false;
        for (unsigned i=0;i<size;++i) {
            if (!ids[i] || !powers[i] || powers[i]>65535) return false;
            for (unsigned j=0;j<i;++j) if (ids[i]==ids[j]) return false;
        }
        return true;
    }
    void select(unsigned value) { clear();menu = value >= 0x21 && value <= 0x24 ? value : 0; }
    bool antiAir() const { return menu==0x24; }
    bool bind(const void* context,const unsigned* ids,const unsigned* powers,unsigned size) {
        world=nullptr;count=0;
        if (!antiAir() || !context || !canSelect(ids,powers,size)) return false;
        world=context;count=size;memcpy(units,ids,size*sizeof(unsigned));return true;
    }
    bool matches(const void* context,const unsigned* ids,unsigned size) const {
        return antiAir() && world && world==context && count && count==size && ids &&
            !memcmp(units,ids,count*sizeof(unsigned));
    }
    static bool aiming(unsigned mode,unsigned phase,unsigned arms) { return mode==6 && phase==4 && arms==2; }
    unsigned targetKind(unsigned arms, unsigned kind) const {
        return antiAir() && count && world && arms == 2 && kind == 1 ? 0x81 : kind;
    }
    void clear() { menu=0;world=nullptr;count=0;memset(units,0,sizeof(units)); }
};
/** Native 004c7820 output: active/type, 6x8 effect samples, six radial lengths.
 * Type 5 is an existing renderer style, not a weapon-array subscript. AA range/effect
 * follows the replacement server's <8 map-unit dispersal policy. Author: Peppone Choi. */
struct TacticalAntiAirPreview {
    enum { SIZE=0xdc, STYLE=5 };
    static bool write(unsigned char* record,unsigned power) {
        if (!record) return false;
        memset(record,0,SIZE);
        if (!power || power>65535) return false;
        record[0]=1;record[1]=STYLE;
        const float effect=100.f,range=8.f;
        for (unsigned sector=0;sector<6;++sector) {
            for (unsigned band=0;band<8;++band)memcpy(record+4+(sector*8+band)*4,&effect,4);
            memcpy(record+0xc4+sector*4,&range,4);
        }
        return true;
    }
    static bool owns(const unsigned char* record) {
        if (!record || record[0]!=1 || record[1]!=STYLE || record[2] || record[3]) return false;
        for (unsigned i=0;i<48;++i) {float value;memcpy(&value,record+4+i*4,4);if(value!=100.f)return false;}
        for (unsigned i=0;i<6;++i) {float value;memcpy(&value,record+0xc4+i*4,4);if(value!=8.f)return false;}
        return true;
    }
    static void clear(unsigned char* record) { if(owns(record))memset(record,0,SIZE); }
};
}
