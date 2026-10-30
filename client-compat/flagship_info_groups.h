// Flagship information: posture and weapon names from the groups this constmsg has.
// Author: Peppone Choi.
#pragma once
#include <windows.h>

// 0054d4e0 builds the flagship sheet. It reads posture names from group 0x78 (120) with
// the character byte +0x31e, and the four weapon names from group 0x7b (123) with the
// flagship record bytes +0x6e/+0x73/+0x77/+0x7e. The held constmsg.dat has 119 groups:
// the same tables are group 113 (posture: 格納/駐留/航行/戦闘...) and 116 (weapons: 光子砲...),
// seven lower, so 00522010 answered NO TABLE. Only these nine pushes are redirected.
static constexpr unsigned flagshipGroupPushes[]={0x54d8e8,0x54db2c,0x54db46,0x54db6a,0x54db89,0x54e000,0x54e0b9,0x54e16b,0x54e217};

static bool installFlagshipInfoGroups() {
    for (unsigned address : flagshipGroupPushes) {
        auto p=reinterpret_cast<const BYTE*>(address);
        if (p[0]!=0x6a || (p[1]!=0x78 && p[1]!=0x7b)) return false;
    }
    for (unsigned address : flagshipGroupPushes) {
        auto p=reinterpret_cast<const BYTE*>(address);
        const BYTE expected[2]={0x6a,p[1]};
        const BYTE replacement[2]={0x6a,static_cast<BYTE>(p[1]==0x78 ? 0x71 : 0x74)};
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,2)) return false;
    }
    return true;
}
