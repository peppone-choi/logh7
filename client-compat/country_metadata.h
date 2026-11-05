// Runtime calendar metadata is copied under a lock before formatting the HUD. Author: Peppone Choi.
#pragma once
#include "country_metadata_data.h"
#include "navigation_hud_data.h"
static SRWLOCK countryMetadataLock=SRWLOCK_INIT;
static struct CountryMetadataCache {unsigned count;logh::CountryMetadata rows[350];} countryMetadataCache={};
static void resetCountryMetadata() {
    AcquireSRWLockExclusive(&countryMetadataLock);countryMetadataCache={};ReleaseSRWLockExclusive(&countryMetadataLock);
}
static void publishCountryMetadata(const logh::CountryMetadata& value) {
    AcquireSRWLockExclusive(&countryMetadataLock);auto& cache=countryMetadataCache;
    unsigned i=0;for(;i<cache.count && cache.rows[i].id!=value.id;++i){}
    if(i<350 && (i==cache.count || cache.rows[i].revision<=value.revision)) {cache.rows[i]=value;if(i==cache.count)++cache.count;}
    ReleaseSRWLockExclusive(&countryMetadataLock);
}
static bool runtimeNavigationHud(unsigned seconds,unsigned pcp,unsigned mcp,unsigned country,wchar_t (&cp)[32],wchar_t (&date)[96]) {
    logh::CountryMetadata metadata;bool found=false;
    AcquireSRWLockShared(&countryMetadataLock);
    for(unsigned i=0;i<countryMetadataCache.count;++i)if(countryMetadataCache.rows[i].id==country) {metadata=countryMetadataCache.rows[i];found=true;break;}
    ReleaseSRWLockShared(&countryMetadataLock);
    if(found) {const logh::CountryCalendar calendar={L"",metadata.calendar,metadata.yearOffset};return logh::formatNavigationHud(seconds,pcp,mcp,calendar,cp,date);}
    return logh::navigationHudText(seconds,pcp,mcp,country,cp,date);
}
