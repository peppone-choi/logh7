// Dedicated 0355 decoded record (0042a420), distinct from the world cache. Author: Peppone Choi.
#pragma once
#include "outfit_info_data.h"
namespace logh {
constexpr unsigned characterProfileBytes=0x1ac;
inline bool profileString(const unsigned char* p,unsigned offset) {
    unsigned count=p[offset];
    if (count>13) return false;
    for (unsigned i=0;i<count;++i) {
        unsigned value=infoShort(p,offset+2+i*2);
        if (i+1==count ? value!=0 : value==0 || (value>=0xd800 && value<=0xdfff)) return false;
    }
    return true;
}
inline bool validCharacterProfile(const unsigned char* p,unsigned size,unsigned id) {
    if (!p || size<characterProfileBytes || !id || infoWord(p,0)!=id ||
        !profileString(p,4) || !profileString(p,0x30) || !profileString(p,0x6c) ||
        p[0x90]>16 || p[0xb2]>1 || p[0xe0]>5) return false;
    for (unsigned i=0;i<p[0x90];++i) if (infoShort(p,0x92+i*2)>260) return false;
    if (p[0xb2] && (!infoWord(p,0xb4) || p[0xb9]>3 || p[0xba]>1 ||
        (p[0xba] && (!infoWord(p,0xbc) || !profileString(p,0xc4))))) return false;
    for (unsigned i=0;i<p[0xe0];++i) {
        auto row=p+0xe4+i*0x28;
        if (infoShort(row,0)>260 || !infoWord(row,4) || !profileString(row,0xc)) return false;
    }
    // Name count zero is the server's unavailable/denied response, not a zero-filled person.
    return !p[4] || ((p[0x20]==2 || p[0x20]==3) && infoShort(p,0x6a)<=255);
}
inline bool applyCharacterProfile(const unsigned char* p,unsigned size,void* record,unsigned bytes) {
    if (!record || bytes<0x370) return false;
    auto dst=static_cast<unsigned char*>(record);
    if (!validCharacterProfile(p,size,infoWord(dst,0x24)) || !p[4]) return false;
    memcpy(dst+0x28,p+0x20,4); memcpy(dst+0x2c,p+0x24,4); memcpy(dst+0x30,p+0x28,2);
    auto string=[&](unsigned dest,unsigned source) {
        dst[dest]=p[source];memset(dst+dest+2,0,26);
        memcpy(dst+dest+2,p+source+2,p[source]*2);
    };
    memcpy(dst+0x48,p+0x2c,4);string(0x4c,0x30);
    memcpy(dst+0x40,p+0x54,8); memcpy(dst+0x7c,p+0x5c,4);
    string(0xdc,4);
    memcpy(dst+0xfa,p+0x6a,2);string(0xfc,0x6c);
    memcpy(dst+0x118,p+0x88,4);
    for (unsigned i=0;i<8;++i) { unsigned short ability=p[0x60+i]; memcpy(dst+0x1ac+i*4,&ability,2); }
    dst[0x1cc]=p[0x68]; dst[0x1cd]=p[0x69];
    // 0355 contains neither ability XP nor all card target pairs. Preserve those cache fields.
    return true;
}
}
