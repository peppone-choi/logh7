// Fortress command UI follows the server-issued office and its exact base seat.
// Ground garrison cards do not grant command of the fortress cannon. Author: Peppone Choi.
#pragma once
#include "character_cards.h"
namespace logh {
inline bool commandsFortress(const void* own,size_t bytes,unsigned base) {
    if(!base || base>65535)return false;
    CharacterCards cards;
    if(!CharacterCards::read(own,bytes,cards))return false;
    for(unsigned i=0;i<cards.count;++i)if((cards.rows[i].kind==51 || cards.rows[i].kind==187) && cards.rows[i].target==base)return true;
    return false;
}
// 00422d80 decodes 033f into u16 count + pad, then 60-byte corps rows:
// +0 character, +0x10 supreme-commander flag (server CommandCorps.chief).
// Returns -1 when this character's row is absent, so partial updates keep the old state.
inline int corpsChief(const BYTE* decoded,unsigned character) {
    if(!decoded || !character)return -1;
    unsigned short count=*reinterpret_cast<const unsigned short*>(decoded);
    if(count>600)return -1;
    for(unsigned i=0;i<count;++i) {
        const BYTE* row=decoded+4+i*0x3c;
        if(*reinterpret_cast<const unsigned*>(row)==character)return row[0x10]?1:0;
    }
    return -1;
}
// update04 added the supreme commander's mission (index 20) and emergency supply (23)
// to the commander tab, whose CD table at 00785bc8 is all zero.
inline void commanderMenu(bool chief,BYTE enabled[24]) {
    for(unsigned i=0;i<24;++i)enabled[i]=0;
    if(chief)enabled[20]=enabled[23]=1;
}
}
