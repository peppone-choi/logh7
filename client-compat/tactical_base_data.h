// Decoded base notifications; no process addresses in these state helpers. Author: 최병호.
#pragma once
#include <stdint.h>
#include <string.h>
namespace logh {
inline unsigned baseMessageWord(const unsigned char* bytes,unsigned offset) {
    uint32_t value;memcpy(&value,bytes+offset,4);return value;
}
inline unsigned baseMessageShort(const unsigned char* bytes,unsigned offset) {
    uint16_t value;memcpy(&value,bytes+offset,2);return value;
}
struct TacticalBaseDamage {
    unsigned normal=0,alive=0,morale=0,face=255;
    float shield=0;
    bool read(const unsigned char* message,unsigned maximum,unsigned capacity) {
        if (!message || !maximum || maximum>300 || capacity>65535 || message[8]!=0 || message[9]!=0x81 ||
            !baseMessageWord(message,4) || baseMessageWord(message,4)>65535 || !baseMessageWord(message,12)) return false;
        unsigned unavailable=baseMessageShort(message,16),destroyed=baseMessageShort(message,18);
        unsigned direction=message[20],lost=baseMessageShort(message,22);
        if (unavailable>maximum || destroyed>unavailable || lost>capacity || message[24]>100 ||
            (direction>5 && direction!=255) || (direction==255 && lost)) return false;
        normal=maximum-unavailable;alive=maximum-destroyed;morale=message[24];face=direction;
        shield=static_cast<float>(capacity-lost);return true;
    }
};
struct TacticalEmergencySupply {
    unsigned base=0,target=0,available=0,supplies=0;
    bool read(const unsigned char* message) {
        if (!message) return false;
        unsigned b=baseMessageWord(message,0),t=baseMessageWord(message,4);
        unsigned a=baseMessageWord(message,8),s=baseMessageWord(message,12);
        if (!b || b>65535 || t>0x7fffffffu || a>0x7fffffffu || s>0x7fffffffu || (!t && s)) return false;
        base=b;target=t;available=a;supplies=s;return true;
    }
};
}
