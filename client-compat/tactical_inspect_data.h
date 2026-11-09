// Independent ship lookup driven by native tactical state 2. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <float.h>
#include <cstring>
#include "tactical_detection_data.h"
namespace logh {
// 0050d230 state 2 clears 02216680/02216674, then picks ships with 0x3d81 and bases with 0x3c04.
// Both globals are reused elsewhere (state 9 reads 02216674), so they mean a lookup only in state 2.
// 007ca550 is success-only and may be stale, and 02215128 holds the allied command selection.
constexpr unsigned tacticalInspectState=2;
inline unsigned tacticalInspectShip(unsigned state,unsigned ship) {return state==tacticalInspectState ? ship : 0;}
// Keeps a closed panel closed until the native pick changes. No pick re-arms it.
struct TacticalInspectLatch {
    unsigned dismissed=0;
    unsigned next(unsigned picked) {
        if(picked!=dismissed)dismissed=0;
        return picked==dismissed ? 0 : picked;
    }
    void dismiss(unsigned id) {dismissed=id;}
};
enum class TacticalInspectView {hidden,basic,full};
// Approved in design-ship-squadron-info.md: other camps show basic rows, only while detected.
inline TacticalInspectView tacticalInspectView(unsigned relation,bool undetected) {
    if(relation==0x400 || relation==0x800)return TacticalInspectView::full;
    return undetected ? TacticalInspectView::hidden : TacticalInspectView::basic;
}
// Server search 0 and the native distance result (level 4) both mean out of detection.
inline bool tacticalInspectUndetected(const BYTE* unit,unsigned ownId) {
    return tacticalUnitHidden(unit,ownId) || unit[tacticalUnitLevel]==tacticalUndetected;
}
// Pages a fixed table from the row after start, wrapping to start last. rows means nothing was accepted.
template<class Accept> inline unsigned nextInspectRow(unsigned rows,unsigned start,bool forward,Accept accept) {
    for(unsigned step=1;step<=rows;++step) {
        const unsigned row=(start+(forward ? step : rows-step))%rows;
        if(accept(row))return row;
    }
    return rows;
}
// spec = world+0x2c1a78+kind*0x2a8. The rated float is spec+0x258, the effective integer unit+0x958.
inline bool tacticalInspectRated(const BYTE* spec,float& rated) {
    std::memcpy(&rated,spec+0x258,sizeof(rated));
    return _finite(rated)!=0;
}
}
