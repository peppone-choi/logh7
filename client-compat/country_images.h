// Network publication never creates or changes graphics resources. Author: Peppone Choi.
#pragma once
#include "country_image_data.h"
static SRWLOCK countryImagesLock=SRWLOCK_INIT;
static struct {unsigned count=0;std::uint64_t epoch=1;logh::CountryImageData rows[350];} countryImagesCache;
static void resetCountryImages() {
    AcquireSRWLockExclusive(&countryImagesLock);countryImagesCache.count=0;++countryImagesCache.epoch;ReleaseSRWLockExclusive(&countryImagesLock);
}
static bool countryImagesNeeded(unsigned country,std::uint64_t revision) {
    bool needed=true;AcquireSRWLockShared(&countryImagesLock);
    for(unsigned i=0;i<countryImagesCache.count;++i)if(countryImagesCache.rows[i].id==country) {needed=countryImagesCache.rows[i].revision<revision;break;}
    ReleaseSRWLockShared(&countryImagesLock);return needed;
}
static void publishCountryImages(const logh::CountryImageData& value) {
    AcquireSRWLockExclusive(&countryImagesLock);auto& cache=countryImagesCache;
    unsigned i=0;for(;i<cache.count && cache.rows[i].id!=value.id;++i){}
    if(i<350 && (i==cache.count || cache.rows[i].revision<=value.revision)) {cache.rows[i]=value;if(i==cache.count)++cache.count;}
    ReleaseSRWLockExclusive(&countryImagesLock);
}
