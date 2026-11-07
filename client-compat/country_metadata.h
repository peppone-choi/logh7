// Runtime calendar metadata is copied under a lock before formatting the HUD. Author: Peppone Choi.
#pragma once
#include "country_metadata_data.h"
#include "country_name_data.h"
#include "navigation_hud_data.h"
#include "country_identity_data.h"
#include <atomic>
static SRWLOCK countryMetadataLock=SRWLOCK_INIT;
static std::atomic<unsigned> countryNameSequence{0};
static struct CountryMetadataCache {unsigned count;logh::CountryMetadata rows[350];} countryMetadataCache={};
static struct OwnCountryIdentity {void* world=nullptr;logh::CountryIdentity value={};} ownCountryIdentity;
static void resetCountryMetadata() {
    AcquireSRWLockExclusive(&countryMetadataLock);countryMetadataCache={};ownCountryIdentity={};++countryNameSequence;ReleaseSRWLockExclusive(&countryMetadataLock);
}
static void publishOwnCountryIdentity(void* world,const logh::CountryIdentity& value) {
    AcquireSRWLockExclusive(&countryMetadataLock);ownCountryIdentity={world,value};ReleaseSRWLockExclusive(&countryMetadataLock);
}
static unsigned runtimeOwnCountry(void* world,unsigned character,unsigned power) {
    AcquireSRWLockShared(&countryMetadataLock);const auto& own=ownCountryIdentity;
    const unsigned result=own.world==world && own.value.character==character && own.value.power==power ? own.value.country.id : power;
    ReleaseSRWLockShared(&countryMetadataLock);return result;
}
static void publishCountryMetadata(const logh::CountryMetadata& value) {
    AcquireSRWLockExclusive(&countryMetadataLock);auto& cache=countryMetadataCache;
    unsigned i=0;for(;i<cache.count && cache.rows[i].id!=value.id;++i){}
    if(i<350 && (i==cache.count || cache.rows[i].revision<=value.revision)) {
        if(i==cache.count || wcscmp(cache.rows[i].name,value.name))++countryNameSequence;
        cache.rows[i]=value;if(i==cache.count)++cache.count;
    }
    ReleaseSRWLockExclusive(&countryMetadataLock);
}
static std::wstring runtimeCountryAffiliation(unsigned id,unsigned camp=0,unsigned columns=80) {
    logh::CountryMetadata metadata;bool found=false;
    AcquireSRWLockShared(&countryMetadataLock);
    for(unsigned i=0;i<countryMetadataCache.count;++i)if(countryMetadataCache.rows[i].id==id) {metadata=countryMetadataCache.rows[i];found=true;break;}
    ReleaseSRWLockShared(&countryMetadataLock);
    return logh::countryAffiliationLabel(id,camp,found ? &metadata : nullptr,columns);
}
static bool runtimeNavigationHud(unsigned seconds,unsigned pcp,unsigned mcp,unsigned country,wchar_t (&cp)[32],wchar_t (&date)[96]) {
    logh::CountryMetadata metadata;bool found=false;
    AcquireSRWLockShared(&countryMetadataLock);
    for(unsigned i=0;i<countryMetadataCache.count;++i)if(countryMetadataCache.rows[i].id==country) {metadata=countryMetadataCache.rows[i];found=true;break;}
    ReleaseSRWLockShared(&countryMetadataLock);
    if(found) {const logh::CountryCalendar calendar={L"",metadata.calendar,metadata.yearOffset};return logh::formatNavigationHud(seconds,pcp,mcp,calendar,cp,date);}
    return logh::navigationHudText(seconds,pcp,mcp,country,cp,date);
}
