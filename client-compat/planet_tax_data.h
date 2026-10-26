// Decoded own-character cards and location. Author: 최병호.
#pragma once
#include "planet_info_data.h"
namespace logh {
inline bool civilTaxCard(unsigned card) { return card==54 || card==190 || card==192; }
inline unsigned heldLocalTaxBase(const unsigned char* own,unsigned card) {
    if (!own || !civilTaxCard(card) || own[0x270]>16) return 0;
    // Native location getter 004b5bf0; zero means no current planetary base.
    unsigned base=infoWord(own,0x358);
    if (!base || base>65535) return 0;
    for (unsigned i=0;i<own[0x270];++i)
        if (infoShort(own,0x274+i*8)==card && infoWord(own,0x278+i*8)==base) return base;
    return 0;
}
}
