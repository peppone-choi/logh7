// 040c flagship energy reply vs the own corps copy. 작성: Peppone Choi (peppone-choi).
#pragma once
#include <cstring>
namespace logh {
// 004c1700 copies the 0x20-byte 040c record to corps+0x2f8 (004c32a0 seeds it from 033f).
// +0x12..+0x1c are beam, gun, six shields, engine, warp and sensor; the 0050d230
// slider refresh reads exactly corps+0x30a..+0x314.
constexpr unsigned tacticalEnergyRecord=0x2f8,tacticalEnergyRecordBytes=0x20;
constexpr unsigned tacticalEnergyField=0x12,tacticalEnergyCount=11;
// The server answers a refused 040c with its stored allocation (gateway 0x040c).
// `after` must hold this very reply, so another flagship's record never matches;
// an own reply that leaves the previous allocation unchanged was not applied.
inline bool tacticalEnergyKept(const unsigned char* before,const unsigned char* after,const unsigned char* reply) {
    if (!before || !after || !reply) return false;
    if (std::memcmp(after,reply,tacticalEnergyField+tacticalEnergyCount)) return false;
    return !std::memcmp(before+tacticalEnergyField,reply+tacticalEnergyField,tacticalEnergyCount);
}
// 0050d230 applies the 0x16 refresh only while the slider hold (0x22166e8, 0x78
// after a change) is below 1. A queued change (0x22166ec) is a newer request.
inline bool releaseTacticalEnergyHold(int& hold,unsigned char changed) {
    if (changed) return false;
    hold=0;
    return true;
}
}
