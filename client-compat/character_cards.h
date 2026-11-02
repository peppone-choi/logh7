// Cards in the native world character, not a wire or query record. Author: Peppone Choi.
#pragma once
#include <stddef.h>
#include <stdint.h>
#include <string.h>
namespace logh {
inline bool characterCardBase(unsigned kind) {
    // Base-targeted offices currently emitted by BootstrapMessages.OfficeCard.
    switch(kind) {
    case 2:case 17:case 24:case 25:case 39:case 44:case 47:case 50:
    case 52:case 54:case 55:case 56:case 57:case 66:case 130:case 134:case 135:case 150:case 161:
    case 175:case 180:case 183:case 186:case 188:case 190:case 191:case 192:case 193:return true;
    default:return false;
    }
}
struct CharacterCards {
    struct Card { unsigned kind, target; } rows[16]={};
    unsigned count=0;
    bool same(const CharacterCards& other) const {
        if (count!=other.count) return false;
        for (unsigned i=0;i<count;++i)
            if (rows[i].kind!=other.rows[i].kind || rows[i].target!=other.rows[i].target) return false;
        return true;
    }
    static bool read(const void* record,size_t bytes,CharacterCards& result) {
        if (!record || bytes<0x274) return false;
        auto data=static_cast<const unsigned char*>(record);
        CharacterCards next;
        next.count=data[0x270];
        if (next.count>16 || bytes<0x274+next.count*8) return false;
        for (unsigned i=0;i<next.count;++i) {
            uint16_t kind;uint32_t target;
            memcpy(&kind,data+0x274+i*8,2);memcpy(&target,data+0x278+i*8,4);
            if (kind>260) return false;
            for (unsigned j=0;j<i;++j) if (next.rows[j].kind==kind && next.rows[j].target==target) return false;
            next.rows[i]={kind,target};
        }
        result=next;return true;
    }
};
}
