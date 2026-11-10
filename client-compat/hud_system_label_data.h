// HUD location line (slot 13) refresh after the canonical strategy grid changes. Author: Peppone Choi.
#pragma once
#include <wchar.h>
#include <stdlib.h>

namespace logh {
// The CD writes slot 13 only while building the HUD: 0058d739 ("%s 성계", 0078d4c8) or
// 0058d759 ("%d, %d 그리드", 0078d4b8) feed the same 0058d790 setter call. 0058ee70 never
// rewrites it, so a location change on the same HUD (fortress return 3757 -> 2991) keeps the
// old system title.
constexpr unsigned hudSystemLabelSlot=13;
constexpr unsigned hudSystemLabelWidget=4+hudSystemLabelSlot*4; // 0x58d840 table at 0x22225c8
constexpr unsigned hudSystemCatalogueCell=0x4c8b70;  // stdcall (x,y) -> grid cell
constexpr unsigned hudSystemCatalogueName=0x4c8c90;  // stdcall (cell[0]) -> catalogue name
constexpr unsigned hudSystemSetterCall=0x58d790;     // native ANSI setter 0x5034d0 for slot 13

// Exact CD formats; grids count from 1 like the native HUD (2016 -> "17, 21").
// A system without a decoded name returns false so the native label stays.
inline bool hudSystemLabel(unsigned grid,bool system,const wchar_t* name,wchar_t* out,size_t size) {
    if (!out || !size || grid>=5000) return false;
    int written=-1;
    if (system) {
        if (name && name[0]) written=_snwprintf_s(out,size,_TRUNCATE,L"%ls 성계",name);
    } else written=_snwprintf_s(out,size,_TRUNCATE,L"%u, %u 그리드",grid%100+1,grid/100+1);
    return written>0;
}
// Rewrite once per (HUD root, grid). Missing data leaves the pair unapplied so the native
// label stays and the next drawn frame retries.
struct HudSystemLabelRefresh {
    void* root=nullptr;
    unsigned grid=~0u;
    bool due(void* liveRoot,unsigned liveGrid)const {
        return liveRoot && liveGrid<5000 && (liveRoot!=root || liveGrid!=grid);
    }
    void applied(void* liveRoot,unsigned liveGrid) {root=liveRoot;grid=liveGrid;}
};
}
