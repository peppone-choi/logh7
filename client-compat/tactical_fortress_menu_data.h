// Fortress command UI follows the server-issued office and its exact base seat.
// Ground garrison cards do not grant command of the fortress cannon. Author: 최병호.
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
}
