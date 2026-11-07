// Network replies only stage data; the UI thread owns native relation writes. Author: Peppone Choi.
#pragma once
#include "tactical_camp_data.h"
static SRWLOCK tacticalCampLock=SRWLOCK_INIT;
static struct TacticalCampCache {
    void* world=nullptr;unsigned session=0,character=0,grid=5000,nonce=0,cursor=0;
    bool pending=false;ULONGLONG sentAt=0,pollAt=0,validUntil=0;
    logh::TacticalCampDraft draft;
    logh::TacticalCampRoster collecting,published;
} tacticalCampCache;
static void resetTacticalCamps() {
    AcquireSRWLockExclusive(&tacticalCampLock);
    const auto nonce=tacticalCampCache.nonce;
    tacticalCampCache={};tacticalCampCache.nonce=nonce;
    ReleaseSRWLockExclusive(&tacticalCampLock);
}
static unsigned tacticalCampGrid(void* world) {
    const auto own=world ? at<BYTE*>(world,8) : nullptr;
    return own && own[0] && at<BYTE>(world,0x126718) && !at<BYTE>(world,0x3579cc) ? at<unsigned short>(own,0x320) : 5000;
}
static bool runtimeTacticalSide(void* world,unsigned id,logh::TacticalSide& side,logh::TacticalSide* viewer=nullptr) {
    const auto grid=tacticalCampGrid(world);bool found=false;
    const auto own=grid<5000 ? at<BYTE*>(world,8) : nullptr;
    AcquireSRWLockShared(&tacticalCampLock);const auto& cache=tacticalCampCache;
    if(own && cache.world==world && cache.grid==grid && cache.character==at<unsigned>(own,0x24) &&
        cache.session==(*reinterpret_cast<unsigned*>(0x2216c3c)&65535) && GetTickCount64()<cache.validUntil) {
        const auto row=cache.published.find(id);
        if(row) {side=*row;if(viewer)*viewer=cache.published.viewer;found=true;}
    }
    ReleaseSRWLockShared(&tacticalCampLock);return found;
}
static bool receiveTacticalCamps(const BYTE* record) {
    if(record[8]!=15)return false;
    AcquireSRWLockExclusive(&tacticalCampLock);auto& cache=tacticalCampCache;
    if(cache.pending && cache.draft.accept(record,24+10*36)==2) {
        cache.pending=false;const auto& page=cache.draft.page;
        if(!cache.collecting.append(page)) {
            cache.collecting={};cache.published={};cache.cursor=0;cache.validUntil=0;cache.pollAt=0;
        } else if(page.more) {cache.cursor=page.next;cache.pollAt=0;}
        else {
            cache.published=cache.collecting;cache.collecting={};cache.cursor=0;
            cache.pollAt=GetTickCount64()+1000;cache.validUntil=GetTickCount64()+10000;
        }
    }
    ReleaseSRWLockExclusive(&tacticalCampLock);return true;
}
static void pollTacticalCamps(void* world) {
    unsigned query[3]={};const auto grid=tacticalCampGrid(world);const auto now=GetTickCount64();
    const auto own=grid<5000 ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own ? at<unsigned>(own,0x24) : 0;
    const unsigned session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
    AcquireSRWLockExclusive(&tacticalCampLock);auto& cache=tacticalCampCache;
    if(grid>=5000 || !character || cache.world!=world || cache.grid!=grid || cache.character!=character || cache.session!=session) {
        const auto nonce=cache.nonce;cache={};cache.nonce=nonce;
        if(grid<5000 && character) {cache.world=world;cache.grid=grid;cache.character=character;cache.session=session;}
    }
    if(cache.pending && now-cache.sentAt>=5000) {cache.pending=false;cache.cursor=0;cache.collecting={};}
    if(grid<5000 && character && !cache.pending && now>=cache.pollAt) {
        cache.nonce=cache.nonce%65535+1;cache.draft.begin(cache.nonce,cache.cursor,grid);cache.pending=true;cache.sentAt=now;
        query[0]=cache.nonce;query[1]=cache.cursor;query[2]=15;
    }
    ReleaseSRWLockExclusive(&tacticalCampLock);
    if(query[0])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
static unsigned tacticalUnitRelation(void* world,unsigned id,unsigned nativeRelation) {
    logh::TacticalSide target,viewer;
    if(!runtimeTacticalSide(world,id,target,&viewer))return nativeRelation;
    const auto own=at<BYTE*>(world,8);
    return logh::tacticalRelation(id,at<unsigned>(own,0x48),viewer,target);
}
static BYTE tacticalRelationTrampoline[10];
using TacticalRelationLookup=BYTE*(__cdecl*)(unsigned);
static TacticalRelationLookup originalTacticalRelation=reinterpret_cast<TacticalRelationLookup>(&tacticalRelationTrampoline[0]);
static BYTE* __cdecl actualTacticalRelation(unsigned id) {
    auto record=originalTacticalRelation(id);
    if(record) {
        auto world=*reinterpret_cast<void**>(0x7ccffc);auto& flags=at<unsigned>(record,0x8f4);
        flags=(flags&~0x3c00u)|tacticalUnitRelation(world,id,flags&0x3c00);
    }
    return record;
}
static void refreshTacticalRelations(void* world) {
    if(tacticalCampGrid(world)>=5000)return;
    // Restore the native fallback when metadata expires, also for previously cached icons.
    auto table=static_cast<BYTE*>(world)+0x126718;
    const auto own=at<BYTE*>(world,8);
    auto ownRecord=reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,int)>(0x4c7fc0)(table,at<unsigned>(own,0x24),1);
    if(!ownRecord)return;
    const auto ownId=at<unsigned>(own,0x48);
    auto relation=[&](unsigned id,unsigned fallback) {
        auto unit=reinterpret_cast<BYTE*(__cdecl*)(void*,unsigned,int,int,int,int)>(0x4c7cd0)(table,id,1,255,255,255);
        const auto native=unit ? id==ownId ? 0x400u : ownRecord[10]==unit[10] ? 0x800u : 0x1000u : fallback;
        return tacticalUnitRelation(world,id,native);
    };
    for(unsigned i=0;i<600;++i) {
        auto record=reinterpret_cast<BYTE*>(0x7db3c8)+i*0xb4c;
        if(record[0]) {auto& flags=at<unsigned>(record,0x8f4);flags=(flags&~0x3c00u)|relation(at<unsigned>(record,4),flags&0x3c00);}
    }
    const auto sentinel=reinterpret_cast<BYTE*>(0xc515f8);
    auto node=*reinterpret_cast<BYTE**>(0xc5162c);
    for(unsigned i=0;node && node!=sentinel && i<1200;++i) {
        auto& flags=at<unsigned>(node,0x24);
        if(flags&1)flags=(flags&~0x3c00u)|relation(at<unsigned>(node,0x20),flags&0x3c00);
        node=at<BYTE*>(node,0x34);
    }
}
static bool installTacticalRelations() {
    const BYTE expected[]={0x83,0xec,0x08,0x53,0x55};auto entry=reinterpret_cast<BYTE*>(0x4c96c0);
    if(memcmp(entry,expected,5))return false;
    DWORD previous=0;if(!VirtualProtect(tacticalRelationTrampoline,sizeof(tacticalRelationTrampoline),PAGE_EXECUTE_READWRITE,&previous))return false;
    memcpy(tacticalRelationTrampoline,expected,5);tacticalRelationTrampoline[5]=0xe9;
    *reinterpret_cast<LONG*>(tacticalRelationTrampoline+6)=static_cast<LONG>(entry+5-tacticalRelationTrampoline-10);
    FlushInstructionCache(GetCurrentProcess(),tacticalRelationTrampoline,sizeof(tacticalRelationTrampoline));
    BYTE jump[]={0xe9,0,0,0,0};*reinterpret_cast<LONG*>(jump+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&actualTacticalRelation)-entry-5);
    return patchBytes(entry,expected,jump,5);
}
