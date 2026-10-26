// Original grid/party serializers and decoders; read-only standing-unit view.
// Author: 최병호.
#pragma once
#include "planet_unit_data.h"
static SRWLOCK planetUnitLock=SRWLOCK_INIT;
static struct PlanetUnitQuery {
    void* world;
    unsigned base,grid,power,token,pendingToken,pendingId,pending,phase,revision;
    bool active,start;
    ULONGLONG sentAt;
    logh::PlanetUnitRows data;
} planetUnitQuery={};
static void stopPlanetUnits() {
    AcquireSRWLockExclusive(&planetUnitLock);
    planetUnitQuery.active=false;++planetUnitQuery.token;
    planetUnitQuery.phase=0;planetUnitQuery.data={};++planetUnitQuery.revision;
    ReleaseSRWLockExclusive(&planetUnitLock);
}
static void resetPlanetUnitConnection() {
    AcquireSRWLockExclusive(&planetUnitLock);
    auto& q=planetUnitQuery;
    if (q.active || q.pending) {
        unsigned revision=q.revision+1,token=q.token+1;
        q={};q.revision=revision;q.token=token;
    }
    ReleaseSRWLockExclusive(&planetUnitLock);
}
static void planetUnitsParty(const BYTE* p) {
    AcquireSRWLockExclusive(&planetUnitLock);
    auto& q=planetUnitQuery;
    if (q.pending==2 && q.pendingId==logh::infoWord(p,0)) {
        q.pending=0;
        if (q.active && q.pendingToken==q.token && q.phase==2) {
            if (!q.data.party(p,q.pendingId,q.base,q.power)) q.phase=4;
            ++q.revision;
        }
    }
    ReleaseSRWLockExclusive(&planetUnitLock);
}
static unsigned __stdcall decodedPlanetUnitGrid(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x41c5f0)(record,reader);
    AcquireSRWLockExclusive(&planetUnitLock);
    auto& q=planetUnitQuery;
    if (q.pending==1) {
        q.pending=0;
        if (q.active && q.pendingToken==q.token && q.phase==1) {
            q.phase=!result && q.data.grid(static_cast<const BYTE*>(record),q.power) ? 2 : 4;
            ++q.revision;
        }
    }
    ReleaseSRWLockExclusive(&planetUnitLock);return result;
}
static bool updatePlanetUnits(void* world,unsigned base,unsigned grid,unsigned power) {
    unsigned selector=0,query[3]={};
    bool changed=false;
    AcquireSRWLockExclusive(&planetUnitLock);
    auto& q=planetUnitQuery;
    if (!q.active || q.world!=world || q.base!=base || q.grid!=grid || q.power!=power) {
        // Retain an in-flight request until its reply is consumed. A reply has
        // no echoed grid: resetting it here could accept an old selection.
        q.active=true;q.world=world;q.base=base;q.grid=grid;q.power=power;
        ++q.token;q.start=true;q.phase=0;q.data={};++q.revision;changed=true;
    }
    if (q.pending && GetTickCount64()-q.sentAt>=5000 && q.phase!=4) {q.phase=4;++q.revision;changed=true;}
    if (!q.pending && q.start) {
        q.start=false;q.phase=1;q.pending=1;q.pendingToken=q.token;q.sentAt=GetTickCount64();
        // Native switch subtracts one: internal 0x0f sends 032c.
        selector=0x0f;query[0]=grid;reinterpret_cast<BYTE*>(query+1)[0]=static_cast<BYTE>(power);
        reinterpret_cast<BYTE*>(query+1)[1]=0;
    } else if (!q.pending && q.phase==2) {
        if (q.data.next<q.data.candidates) {
            q.pending=2;q.pendingId=q.data.ids[q.data.next++];q.pendingToken=q.token;q.sentAt=GetTickCount64();
            selector=0x10;query[0]=q.pendingId; // base=0, mode=0: friendly public party.
        } else {q.phase=3;++q.revision;changed=true;}
    }
    ReleaseSRWLockExclusive(&planetUnitLock);
    if (selector) reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,selector,query);
    return changed;
}
static unsigned planetUnitsRevision() {
    AcquireSRWLockShared(&planetUnitLock);unsigned value=planetUnitQuery.revision;
    ReleaseSRWLockShared(&planetUnitLock);return value;
}
static unsigned planetUnitPages() {
    AcquireSRWLockShared(&planetUnitLock);
    unsigned pages=planetUnitQuery.phase==3 ? (planetUnitQuery.data.count+7)/8 : 0;
    ReleaseSRWLockShared(&planetUnitLock);return pages;
}
