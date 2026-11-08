// Poll display metadata through an explicit outfit-query extension. Author: Peppone Choi (peppone-choi).
#pragma once
#include "country_colors_data.h"
#include "country_catalog_data.h"
#include "country_metadata.h"
#include "base_affiliation.h"
#include "outfit_affiliation.h"
#include "tactical_camp.h"
static constexpr unsigned fuelStatusMode=19;
static constexpr unsigned ownPortraitMode=20;
static constexpr unsigned ownCountryExtensionOffset=0x4598; // First native cargo word, unused in mode 11.
struct FuelProgress {unsigned startsAt=0,deadline=0;};
static bool readFuelProgress(const BYTE* record,unsigned character,unsigned nonce,FuelProgress& output) {
    if(record[8]!=fuelStatusMode || record[9]!=2 || record[20]!=3 || logh::paintWord(record)!=character ||
        logh::paintWord(record+4)!=nonce || logh::paintWord(record+12) || logh::paintWord(record+16))return false;
    wchar_t fields[3][13]={};
    for(unsigned i=0;i<3;++i) {
        const auto member=record+24+i*36;
        if(logh::paintWord(member)!=i+1 || member[4]!=i || member[5] || !logh::countryMetadataText(member,fields[i]))return false;
    }
    if(wcscmp(fields[0],L"FS1"))return false;
    std::uint64_t start=0,deadline=0;
    if(fields[2][0] && (!logh::lobbyCountryNumber(fields[2],0xffffffffu,deadline) || !deadline))return false;
    if(fields[1][0] && (!deadline || !logh::lobbyCountryNumber(fields[1],deadline,start)))return false;
    output={static_cast<unsigned>(start),static_cast<unsigned>(deadline)};
    return true;
}
static SRWLOCK countryPaintLock=SRWLOCK_INIT;
static struct CountryPaintInbox {
    void* world;unsigned pending,mode,nonce,cursor,count,session,character,power,ownCountry;bool ownRegistered;
    ULONGLONG sentAt,pollAt,identityAt,basesAt;
    bool identityReceived,fuelSupported,productionQuotesSupported,repairQuotesSupported,supplyQuotesSupported,portraitSupported,spotPortraitSupported;
    ULONGLONG fuelAt,portraitAt;
    FuelProgress fuel;
    unsigned imageCountry;std::uint64_t imageRevision;
    logh::CountryImageDraft image;
    logh::BaseAffiliationDraft bases;
    logh::CountryPaint rows[350];
} countryPaintInbox={};
// Guarded by countryPaintLock. The render thread consumes only completed transfers.
static unsigned ownPortraitSerial=0;
static bool ownPortraitPresent=false;
static unsigned char ownPortraitPixels[4096];

static void clearOwnPortrait() {
    if(ownPortraitPresent){ownPortraitPresent=false;++ownPortraitSerial;}
}

static unsigned countryPaintRgb(unsigned country,unsigned field,unsigned fallback) {
    if(field>3)return fallback;
    unsigned result=fallback;
    AcquireSRWLockShared(&countryPaintLock);
    for(unsigned i=0;i<countryPaintInbox.count;++i)if(countryPaintInbox.rows[i].id==country) {
        const auto& row=countryPaintInbox.rows[i];if(row.mask&(1u<<field))result=row.rgb[field];break;
    }
    ReleaseSRWLockShared(&countryPaintLock);return result;
}
static bool receiveCountryPaint(const BYTE* record) {
    if(record[8]!=4 && record[8]!=5 && record[8]!=11 && record[8]!=12 && record[8]!=16 && record[8]!=17 &&
        record[8]!=fuelStatusMode && record[8]!=ownPortraitMode)return false;
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;logh::CountryPaint paint;
    if(inbox.pending && inbox.mode==12 && inbox.bases.accept(record,24+10*36)==2) {
        publishBaseAffiliations(inbox.world,inbox.bases.value);inbox.pending=0;
    }
    logh::CountryIdentity identity;
    if(inbox.pending && inbox.mode==11 && logh::readCountryIdentity(record,24+10*36,inbox.character,inbox.nonce,identity) && identity.power==inbox.power) {
        publishCountryMetadata(identity.country);publishOwnCountryIdentity(inbox.world,identity);
        inbox.ownCountry=identity.country.id;inbox.ownRegistered=identity.registered;inbox.pending=0;inbox.pollAt=0;
        inbox.identityReceived=true;
        inbox.fuelSupported=logh::paintWord(record+ownCountryExtensionOffset)==1;
        inbox.productionQuotesSupported=logh::paintWord(record+ownCountryExtensionOffset+4)==1;
        inbox.repairQuotesSupported=(record[10]&1)!=0;
        inbox.supplyQuotesSupported=(record[10]&2)!=0;
        inbox.portraitSupported=(record[10]&4)!=0;
        inbox.spotPortraitSupported=(record[10]&8)!=0;
        if(!inbox.portraitSupported)clearOwnPortrait();
    }
    if(inbox.pending && inbox.mode==fuelStatusMode && readFuelProgress(record,inbox.character,inbox.nonce,inbox.fuel)) {
        inbox.pending=0;
        // No idle polling after the initial status. A fresh submission can already have requested another read.
        if(!inbox.fuel.deadline && inbox.fuelAt)inbox.fuelAt=~ULONGLONG{0};
    }
    if(inbox.pending && inbox.mode==4 && logh::readCountryPaint(record,24+6*36,inbox.pending,inbox.nonce,paint)) {
        unsigned index=0;for(;index<inbox.count;++index)if(inbox.rows[index].id==paint.id)break;
        if(index<350){inbox.rows[index]=paint;if(index==inbox.count)++inbox.count;}
        inbox.pending=0;
    }
    logh::CountryMetadata metadata;
    if(inbox.pending && (inbox.mode==5 || inbox.mode==16) && inbox.mode==record[8] &&
        logh::readCountryMetadata(record,24+10*36,inbox.pending,inbox.nonce,metadata)) {
        publishCountryMetadata(metadata);inbox.pending=0;
        if(inbox.mode==16 && countryImagesNeeded(metadata.id,metadata.revision)) {inbox.imageCountry=metadata.id;inbox.imageRevision=metadata.revision;}
    }
    if(inbox.pending && inbox.mode==17 && inbox.image.accept(record,24+10*36)==2) {
        publishCountryImages(inbox.image.value);inbox.pending=0;inbox.imageCountry=0;
    }
    if(inbox.pending && inbox.mode==ownPortraitMode && inbox.image.accept(record,24+10*36)==2) {
        if(const auto pixels=inbox.image.value.pixels(false)) {
            if(!ownPortraitPresent || memcmp(ownPortraitPixels,pixels,sizeof(ownPortraitPixels))) {
                memcpy(ownPortraitPixels,pixels,sizeof(ownPortraitPixels));ownPortraitPresent=true;++ownPortraitSerial;
            }
        } else clearOwnPortrait();
        inbox.pending=0;
    }
    ReleaseSRWLockExclusive(&countryPaintLock);return true;
}
static void pollCountryPaint(void* world) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || (!at<BYTE>(world,0x2a58f8) && !at<BYTE>(world,0x126718))) {
        AcquireSRWLockExclusive(&countryPaintLock);
        if(countryPaintInbox.world) {countryPaintInbox.world=nullptr;countryPaintInbox.pending=0;clearOwnPortrait();resetCountryMetadata();resetBaseAffiliations();}
        ReleaseSRWLockExclusive(&countryPaintLock);return;
    }
    unsigned query[3]={};auto now=GetTickCount64();
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;
    const unsigned character=at<unsigned>(own,0x24),power=at<BYTE>(own,0x28),session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
    if(!character || (power!=2 && power!=3)) {ReleaseSRWLockExclusive(&countryPaintLock);return;}
    if(inbox.world!=world || inbox.session!=session || inbox.character!=character || inbox.power!=power) {
        const unsigned nonce=inbox.nonce;inbox={};inbox.world=world;inbox.session=session;inbox.character=character;inbox.power=power;inbox.nonce=nonce;
        clearOwnPortrait();resetCountryMetadata();resetBaseAffiliations();
    }
    if(inbox.pending && now-inbox.sentAt>=5000) {if(inbox.mode==17)inbox.imageCountry=0;inbox.pending=0;}
    if(!inbox.pending && now>=inbox.pollAt) {
        if(inbox.fuelSupported && now>=inbox.fuelAt) {
            inbox.pending=character;inbox.mode=fuelStatusMode;inbox.fuelAt=now+5000;
        } else if(inbox.imageCountry) {inbox.pending=inbox.imageCountry;inbox.mode=17;}
        else if(now>=inbox.identityAt) {inbox.pending=character;inbox.mode=11;inbox.identityAt=now+5000;}
        else if(inbox.portraitSupported && now>=inbox.portraitAt) {
            inbox.pending=character;inbox.mode=ownPortraitMode;inbox.portraitAt=now+15000;
        }
        else if(now>=inbox.basesAt) {inbox.pending=1;inbox.mode=12;inbox.basesAt=now+5000;}
        else if(inbox.ownRegistered) {
            unsigned ids[353]={inbox.ownCountry},count=1;
            auto add=[&](unsigned id) {if(!id || count>=_countof(ids))return;for(unsigned i=0;i<count;++i)if(ids[i]==id)return;ids[count++]=id;};
            AcquireSRWLockShared(&baseAffiliationLock);
            if(baseAffiliationCache.world==world)for(unsigned i=0;i<baseAffiliationCache.value.count;++i)add(baseAffiliationCache.value.rows[i].country);
            ReleaseSRWLockShared(&baseAffiliationLock);
            for(const auto& country:logh::countryDisplays)add(country.id);
            AcquireSRWLockShared(&outfitAffiliationLock);
            if(outfitAffiliationCache.world==world)for(const auto& row:outfitAffiliationCache.rows) {
                if(count>=353)break;add(row.country);
            }
            ReleaseSRWLockShared(&outfitAffiliationLock);
            AcquireSRWLockShared(&tacticalCampLock);
            if(tacticalCampCache.world==world)for(unsigned i=0;i<tacticalCampCache.published.count;++i) {
                if(count>=353)break;
                const auto& side=tacticalCampCache.published.rows[i].side;if(!side.kind)add(side.owner);
            }
            ReleaseSRWLockShared(&tacticalCampLock);
            inbox.pending=ids[(inbox.cursor/2)%count];
            inbox.mode=inbox.cursor++%2 ? 16u : 4u;
        }
        if(inbox.pending) {
            inbox.nonce=(inbox.nonce%65535)+1;inbox.sentAt=now;inbox.pollAt=now+1000;
            query[0]=inbox.pending;query[1]=inbox.nonce;query[2]=inbox.mode;
            if(inbox.mode==12) {query[0]=0;inbox.bases.begin(inbox.nonce);}
            if(inbox.mode==17)inbox.image.begin(inbox.pending,inbox.nonce,inbox.imageRevision);
            if(inbox.mode==ownPortraitMode)inbox.image.begin(character,inbox.nonce,0,ownPortraitMode);
        }
    }
    ReleaseSRWLockExclusive(&countryPaintLock);
    if(query[2])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
static FuelProgress currentFuelProgress(void* world,unsigned character) {
    AcquireSRWLockShared(&countryPaintLock);
    const auto& inbox=countryPaintInbox;
    const auto fuel=inbox.world==world && inbox.character==character && inbox.fuelSupported ? inbox.fuel : FuelProgress{};
    ReleaseSRWLockShared(&countryPaintLock);
    return fuel;
}
static int productionQuoteCapability(void* world,unsigned character) {
    AcquireSRWLockShared(&countryPaintLock);
    const auto& inbox=countryPaintInbox;
    const int supported=inbox.world==world && inbox.character==character && inbox.identityReceived ?
        static_cast<int>(inbox.productionQuotesSupported) : -1;
    ReleaseSRWLockShared(&countryPaintLock);
    return supported;
}
static int repairQuoteCapability(void* world,unsigned character) {
    AcquireSRWLockShared(&countryPaintLock);
    const auto& inbox=countryPaintInbox;
    const int supported=inbox.world==world && inbox.character==character && inbox.identityReceived ?
        static_cast<int>(inbox.repairQuotesSupported) : -1;
    ReleaseSRWLockShared(&countryPaintLock);
    return supported;
}
static bool supplyQuoteCapability(void* world,unsigned character) {
    AcquireSRWLockShared(&countryPaintLock);
    const auto& inbox=countryPaintInbox;
    const bool supported=inbox.world==world && inbox.character==character && inbox.supplyQuotesSupported;
    ReleaseSRWLockShared(&countryPaintLock);return supported;
}
static void refreshFuelProgress(void* world,unsigned character,bool completed=false) {
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;
    if(inbox.world==world && inbox.character==character) {
        inbox.fuelAt=0;
        if(completed) {
            inbox.fuel={};
            if(inbox.mode==fuelStatusMode)inbox.pending=0;
        }
    }
    ReleaseSRWLockExclusive(&countryPaintLock);
}
