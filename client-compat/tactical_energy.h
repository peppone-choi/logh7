// Show the server's 040c allocation after a refused energy request. 작성: Peppone Choi (peppone-choi).
#pragma once
#include "tactical_energy_data.h"
// The own corps (world+8) keeps 004c1700's copy of the last authoritative 040c record.
static BYTE* tacticalEnergyOwnRecord(void* world) {
    if (!world || !at<BYTE>(world,0x126718)) return nullptr;
    auto own=at<BYTE*>(world,8);
    return own && at<BYTE>(own,0) ? own+logh::tacticalEnergyRecord : nullptr;
}
static bool tacticalEnergySnapshot(void* world,BYTE* before) {
    auto record=tacticalEnergyOwnRecord(world);
    if (!record) return false;
    memcpy(before,record,logh::tacticalEnergyRecordBytes);
    return true;
}
// Accepted replies keep the native slider hold. A refused reply already updated the
// corps copy and posted 0x16; release the hold so 0050d230 shows that allocation.
static void receivedTacticalEnergy(void* world,const BYTE* before,const BYTE* reply) {
    auto record=tacticalEnergyOwnRecord(world);
    if (!record || !logh::tacticalEnergyKept(before,record,reply)) return;
    logh::releaseTacticalEnergyHold(*reinterpret_cast<int*>(0x22166e8),*reinterpret_cast<BYTE*>(0x22166ec));
}
