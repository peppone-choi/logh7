// Keep visible outfit countries bound to the current world, character and grid. Author: Peppone Choi.
#pragma once
#include "outfit_affiliation_data.h"
static SRWLOCK outfitAffiliationLock=SRWLOCK_INIT;
static struct OutfitAffiliationCache {
    void* world=nullptr;unsigned session=0,character=0,country=0,grid=5000,nonce=0,cursor=0;
    std::uint64_t generation=0;bool pending=false;ULONGLONG sentAt=0,pollAt=0;
    logh::OutfitAffiliationDraft draft;
    std::vector<logh::BaseAffiliation::Owner> rows,collecting;
} outfitAffiliationCache;
static unsigned outfitAffiliationGrid(void* world) {
    if(!world || at<BYTE>(world,0x3579cc))return 5000;
    const auto own=at<BYTE*>(world,8);if(!own || !own[0])return 5000;
    if(at<BYTE>(world,0x126718))return at<unsigned short>(own,0x320);
    return at<BYTE>(world,0x2a58f8) && at<BYTE>(world,0x2b6a6c) ? at<unsigned short>(world,0x2b6a70) : 5000;
}
static bool runtimeOutfitOwner(void* world,unsigned id,logh::BaseAffiliation::Owner& result) {
    const auto grid=outfitAffiliationGrid(world);bool found=false;
    AcquireSRWLockShared(&outfitAffiliationLock);const auto& cache=outfitAffiliationCache;
    if(cache.world==world && cache.grid==grid)for(const auto& row:cache.rows)if(row.id==id) {result=row;found=true;break;}
    ReleaseSRWLockShared(&outfitAffiliationLock);return found;
}
static std::wstring runtimeOutfitAffiliation(void* world,unsigned id,unsigned columns=80) {
    logh::BaseAffiliation::Owner row;
    if(!runtimeOutfitOwner(world,id,row))return L"소속 조회 중";
    if(!row.country && row.power==4 && !row.camp)return logh::fitCountryLabel(L"해적",columns);
    return row.country ? runtimeCountryAffiliation(row.country,row.camp,columns) : logh::fitCountryLabel(logh::planetAffiliation(row.power,row.camp),columns);
}
static bool runtimeFriendlyOutfit(void* world,unsigned id) {
    const auto own=world ? at<BYTE*>(world,8) : nullptr;logh::BaseAffiliation::Owner row;
    if(!own || !own[0] || !runtimeOutfitOwner(world,id,row) || !row.country)return false;
    return row.country==runtimeOwnCountry(world,at<unsigned>(own,0x24),own[0x28]);
}
static bool receiveOutfitAffiliations(const BYTE* record) {
    if(record[8]!=13)return false;
    AcquireSRWLockExclusive(&outfitAffiliationLock);auto& cache=outfitAffiliationCache;
    if(cache.pending && cache.draft.accept(record,24+10*36)==2) {
        const auto& page=cache.draft.page;cache.pending=false;
        if((cache.generation && cache.generation!=page.generation) || cache.collecting.size()+page.count>65535) {
            cache.cursor=0;cache.generation=0;cache.collecting.clear();cache.rows.clear();cache.pollAt=0;++countryNameSequence;
        } else {
            cache.generation=page.generation;
            for(unsigned i=0;i<page.count;++i)cache.collecting.push_back(page.rows[i]);
            if(page.more) {cache.cursor=page.next;cache.pollAt=0;}
            else {
                bool changed=cache.rows.size()!=cache.collecting.size();
                for(std::size_t i=0;!changed && i<cache.rows.size();++i) {
                    const auto& a=cache.rows[i];const auto& b=cache.collecting[i];
                    changed=a.id!=b.id || a.country!=b.country || a.power!=b.power || a.camp!=b.camp;
                }
                cache.rows.swap(cache.collecting);cache.collecting.clear();cache.cursor=0;cache.pollAt=GetTickCount64()+5000;
                if(changed)++countryNameSequence;
            }
        }
    }
    ReleaseSRWLockExclusive(&outfitAffiliationLock);return true;
}
static void pollOutfitAffiliations(void* world) {
    unsigned query[3]={};const auto grid=outfitAffiliationGrid(world);const auto now=GetTickCount64();
    const auto own=grid<5000 ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own ? at<unsigned>(own,0x24) : 0;
    const unsigned country=own ? runtimeOwnCountry(world,character,own[0x28]) : 0;
    const unsigned session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
    AcquireSRWLockExclusive(&outfitAffiliationLock);auto& cache=outfitAffiliationCache;
    if(grid>=5000 || !character || cache.world!=world || cache.grid!=grid || cache.character!=character || cache.country!=country || cache.session!=session) {
        const bool changed=cache.world!=nullptr || grid<5000 && character;
        const auto nonce=cache.nonce;cache={};cache.nonce=nonce;
        if(grid<5000 && character) {cache.world=world;cache.grid=grid;cache.character=character;cache.country=country;cache.session=session;}
        if(changed)++countryNameSequence;
    }
    if(cache.pending && now-cache.sentAt>=5000) {cache.pending=false;cache.cursor=0;cache.collecting.clear();}
    if(grid<5000 && character && !cache.pending && now>=cache.pollAt) {
        cache.nonce=cache.nonce%65535+1;cache.draft.begin(cache.nonce,cache.cursor,grid);cache.pending=true;cache.sentAt=now;
        query[0]=cache.nonce;query[1]=cache.cursor;query[2]=13;
    }
    ReleaseSRWLockExclusive(&outfitAffiliationLock);
    if(query[0])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
