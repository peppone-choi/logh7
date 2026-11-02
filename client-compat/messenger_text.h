// Only the inspected CD status menu and its selected-state captions. Author: Peppone Choi.
#pragma once
#include <cstdint>
namespace logh {
// Return 3 for any unrelated call/index; those retain native lookup behavior.
inline unsigned messengerStatusKind(uintptr_t returnAddress,unsigned group,unsigned index) {
    if (group!=0x16) return 3;
    if (returnAddress==0x5433a5) {
        if (index==0x77) return 0;
        if (index==0x7b) return 1;
        if (index==0x78) return 2;
    }
    if (returnAddress==0x54501d && index==0x78) return 2;
    if (returnAddress==0x545044 && index==0x66) return 1;
    if (returnAddress==0x54506b && index==0x77) return 0;
    return 3;
}
}
