// Own residence spot check for the HUD spot label fallback. Author: Peppone Choi.
#pragma once
#include <cstring>
namespace logh {
// 004c9170 facility record: kind u16 +0, id u32 +4, spot count +8, spot rows +0xc
// (stride 0xc: kind u16 +0, id u32 +4), at most 20 rows. evidence:client
constexpr unsigned residenceFacilityKind=16, residenceSpotKind=12, facilitySpotLimit=20;
inline bool ownResidenceSpot(unsigned self,unsigned owner,unsigned spot,const unsigned char* facility) {
    if(!self || owner!=self || !spot || !facility)return false;
    unsigned short kind=0;memcpy(&kind,facility,2);
    const unsigned count=facility[8];
    if(kind!=residenceFacilityKind || count>facilitySpotLimit)return false;
    for(unsigned i=0;i<count;++i) {
        const unsigned char* row=facility+0xc+i*0xc;
        unsigned id=0;memcpy(&id,row+4,4);
        if(id!=spot)continue;
        unsigned short rowKind=0;memcpy(&rowKind,row,2);
        return rowKind==residenceSpotKind;
    }
    return false;
}
}
