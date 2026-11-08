// Server search status over native tactical units and markers. Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <stdlib.h>
#include <cstdint>
#include <cstring>
namespace logh {
// 00421f80 decodes 033b as u16 count, rows at +4, stride 0x34, id +0 and search +0x30.
// 004c32a0 copies each row to unit+0x97c, so the imported search byte is unit+0x9ac.
constexpr unsigned tacticalStateRows=4,tacticalStateStride=0x34,tacticalStateSearch=0x30,tacticalStateLimit=600;
constexpr unsigned tacticalUnitStride=0x9ec,tacticalUnitSearch=0x97c+tacticalStateSearch;
// 004b2c80 resets enemies to contact 0 and level 4 before its own distance-only check.
constexpr unsigned tacticalUnitContact=0x95c,tacticalUnitLevel=0x95e;
constexpr BYTE tacticalUndetected=4;
// 004ec700 walks the pool (+0x44 count, rows at +0x48) and 004effe0 walks the list (+0x40, +0x38).
// The pool ends at the renderer's +0xa458 field, which leaves exactly 700 rows of 0x3c bytes.
constexpr unsigned tacticalMarkerPool=0x48,tacticalMarkerStride=0x3c,tacticalMarkerLimit=700;
constexpr unsigned tacticalMarkerFlags=0x24,tacticalMarkerUnit=0x28,tacticalMarkerLevel=0x2e;

inline unsigned tacticalWord(const BYTE* p) {unsigned value;memcpy(&value,p,4);return value;}

// Rows keep only the latest wire status per ID until the UI thread applies them.
struct TacticalDetectionInbox {
    struct Row {unsigned id;BYTE search;};
    unsigned count=0;
    Row rows[tacticalStateLimit*2]={};
    void clear() {count=0;}
    bool merge(const BYTE* decoded) {
        unsigned short total;memcpy(&total,decoded,2);
        if(total>tacticalStateLimit)return false;
        bool complete=true;
        for(unsigned i=0;i<total;++i) {
            const auto row=decoded+tacticalStateRows+i*tacticalStateStride;
            const auto id=tacticalWord(row);
            if(!id)continue;
            unsigned slot=0;
            while(slot<count && rows[slot].id!=id)++slot;
            if(slot==count) {
                if(count==_countof(rows)) {complete=false;continue;}
                ++count;
            }
            rows[slot]={id,row[tacticalStateSearch]};
        }
        return complete;
    }
};
inline void applyTacticalDetection(BYTE* units,const TacticalDetectionInbox& inbox) {
    for(unsigned i=0;i<inbox.count;++i)for(unsigned j=0;j<tacticalStateLimit;++j) {
        const auto unit=units+j*tacticalUnitStride;
        if(!unit[0] || unit[8]!=1 || tacticalWord(unit+4)!=inbox.rows[i].id)continue;
        unit[tacticalUnitSearch]=inbox.rows[i].search;
        break;
    }
}
inline bool tacticalUnitHidden(const BYTE* unit,unsigned ownId) {
    return unit[0] && unit[8]==1 && !unit[tacticalUnitSearch] && tacticalWord(unit+4)!=ownId;
}
inline void forceTacticalUndetected(BYTE* unit) {
    unit[tacticalUnitContact]=0;unit[tacticalUnitContact+1]=0;unit[tacticalUnitLevel]=tacticalUndetected;
}
inline void forceTacticalUndetected(BYTE* units,unsigned ownId) {
    for(unsigned i=0;i<tacticalStateLimit;++i) {
        const auto unit=units+i*tacticalUnitStride;
        if(tacticalUnitHidden(unit,ownId))forceTacticalUndetected(unit);
    }
}
inline bool tacticalMarkerHidden(const BYTE* node,const BYTE* units,unsigned ownId) {
    if(!units || !(tacticalWord(node+tacticalMarkerFlags)&1))return false;
    const auto unit=static_cast<std::uintptr_t>(tacticalWord(node+tacticalMarkerUnit));
    const auto base=reinterpret_cast<std::uintptr_t>(units);
    if(unit<base || unit>=base+tacticalStateLimit*tacticalUnitStride || (unit-base)%tacticalUnitStride)return false;
    return tacticalUnitHidden(reinterpret_cast<const BYTE*>(unit),ownId);
}
// The two draw loops skip level 4 themselves. The level is restored before pick or later frames read it.
class TacticalHiddenMarkers {
    BYTE* nodes[tacticalMarkerLimit];
    BYTE levels[tacticalMarkerLimit];
    unsigned count=0;
    void hide(BYTE* node,const BYTE* units,unsigned ownId) {
        if(!tacticalMarkerHidden(node,units,ownId))return;
        nodes[count]=node;levels[count++]=node[tacticalMarkerLevel];node[tacticalMarkerLevel]=tacticalUndetected;
    }
public:
    TacticalHiddenMarkers(BYTE* renderer,const BYTE* units,unsigned ownId,bool pool) {
        if(!renderer || !units)return;
        if(pool) {
            unsigned total=tacticalWord(renderer+0x44);
            if(total>tacticalMarkerLimit)total=tacticalMarkerLimit;
            for(unsigned i=0;i<total;++i)hide(renderer+tacticalMarkerPool+i*tacticalMarkerStride,units,ownId);
            return;
        }
        const auto head=renderer+8;
        auto node=reinterpret_cast<BYTE*>(static_cast<std::uintptr_t>(tacticalWord(renderer+0x40)));
        for(unsigned i=0;node && node!=head && i<tacticalMarkerLimit;++i) {
            hide(node,units,ownId);
            node=reinterpret_cast<BYTE*>(static_cast<std::uintptr_t>(tacticalWord(node+0x38)));
        }
    }
    ~TacticalHiddenMarkers() {while(count) {--count;nodes[count][tacticalMarkerLevel]=levels[count];}}
    TacticalHiddenMarkers(const TacticalHiddenMarkers&)=delete;
    TacticalHiddenMarkers& operator=(const TacticalHiddenMarkers&)=delete;
};
// Finds rel32 calls to one native target inside a function range taken from the decompiled callers.
inline unsigned findTacticalCalls(BYTE* start,unsigned size,const void* target,BYTE** sites,unsigned limit) {
    unsigned found=0;
    for(unsigned offset=0;offset+5<=size;++offset) {
        if(start[offset]!=0xe8)continue;
        const auto next=reinterpret_cast<std::uintptr_t>(start+offset+5);
        if(next+tacticalWord(start+offset+1)!=reinterpret_cast<std::uintptr_t>(target))continue;
        if(found<limit)sites[found]=start+offset;
        ++found;
    }
    return found;
}
}
