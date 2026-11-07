// Resolve stable base IDs before rendering country labels or map colors. Author: Peppone Choi.
#pragma once
#include "base_affiliation_data.h"
static SRWLOCK baseAffiliationLock=SRWLOCK_INIT;
static struct BaseAffiliationCache {void* world=nullptr;logh::BaseAffiliation value={};} baseAffiliationCache;
static void resetBaseAffiliations() {
    AcquireSRWLockExclusive(&baseAffiliationLock);baseAffiliationCache={};ReleaseSRWLockExclusive(&baseAffiliationLock);
}
static void publishBaseAffiliations(void* world,const logh::BaseAffiliation& value) {
    AcquireSRWLockExclusive(&baseAffiliationLock);
    bool changed=baseAffiliationCache.world!=world || baseAffiliationCache.value.count!=value.count;
    for(unsigned i=0;!changed && i<value.count;++i) {
        const auto& old=baseAffiliationCache.value.rows[i];const auto& row=value.rows[i];
        changed=old.id!=row.id || old.country!=row.country || old.power!=row.power || old.camp!=row.camp;
    }
    baseAffiliationCache={world,value};ReleaseSRWLockExclusive(&baseAffiliationLock);
    if(changed)++countryNameSequence;
}
static bool runtimeBaseOwner(void* world,unsigned id,logh::BaseAffiliation::Owner& owner) {
    AcquireSRWLockShared(&baseAffiliationLock);
    const auto row=baseAffiliationCache.world==world ? baseAffiliationCache.value.find(id) : nullptr;
    if(row)owner=*row;ReleaseSRWLockShared(&baseAffiliationLock);return row!=nullptr;
}
static std::wstring runtimeBaseAffiliation(void* world,unsigned id,unsigned columns=80) {
    logh::BaseAffiliation::Owner owner;
    if(!runtimeBaseOwner(world,id,owner))return L"소속 조회 중";
    if(!owner.country)return logh::fitCountryLabel(logh::planetAffiliation(owner.power,owner.camp),columns);
    return runtimeCountryAffiliation(owner.country,owner.camp,columns);
}
