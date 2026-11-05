// Decoded 033f percentages and the CD flagship's live fields. 작성: Peppone Choi (peppone-choi).
#pragma once
#include <cstdint>
#include <cstring>
namespace logh {
inline bool applyTacticalFlagshipCharge(unsigned char* unit,const unsigned char* row) {
    if (!unit || !row || !unit[0] || unit[8]!=1 || unit[9]!=0) return false;
    std::uint32_t character=0,source=0;
    std::uint16_t beam=0,gun=0;
    std::memcpy(&character,unit+0x984,4);std::memcpy(&source,row,4);
    std::memcpy(&beam,row+30,2);std::memcpy(&gun,row+32,2);
    if (!character || character!=source || beam>100 || gun>100) return false;
    // The native importer copies these only when constructing the live unit.
    std::memcpy(unit+0x9ce,row+30,4);
    return true;
}
}
