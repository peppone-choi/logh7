// Poll display metadata through an explicit outfit-query extension. Author: Peppone Choi (peppone-choi).
#pragma once
#include "country_colors_data.h"
#include "country_catalog_data.h"
#include "country_metadata.h"
static SRWLOCK countryPaintLock=SRWLOCK_INIT;
static struct CountryPaintInbox {
    void* world;unsigned pending,mode,nonce,cursor,count,session,character,power,ownCountry;bool ownRegistered;
    ULONGLONG sentAt,pollAt,identityAt;
    logh::CountryPaint rows[350];
} countryPaintInbox={};

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
    if(record[8]!=4 && record[8]!=5 && record[8]!=11)return false;
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;logh::CountryPaint paint;
    logh::CountryIdentity identity;
    if(inbox.pending && inbox.mode==11 && logh::readCountryIdentity(record,24+10*36,inbox.character,inbox.nonce,identity) && identity.power==inbox.power) {
        publishCountryMetadata(identity.country);publishOwnCountryIdentity(inbox.world,identity);
        inbox.ownCountry=identity.country.id;inbox.ownRegistered=identity.registered;inbox.pending=0;inbox.pollAt=0;
    }
    if(inbox.pending && inbox.mode==4 && logh::readCountryPaint(record,24+6*36,inbox.pending,inbox.nonce,paint)) {
        unsigned index=0;for(;index<inbox.count;++index)if(inbox.rows[index].id==paint.id)break;
        if(index<350){inbox.rows[index]=paint;if(index==inbox.count)++inbox.count;}
        inbox.pending=0;
    }
    logh::CountryMetadata metadata;
    if(inbox.pending && inbox.mode==5 && logh::readCountryMetadata(record,24+6*36,inbox.pending,inbox.nonce,metadata)) {
        publishCountryMetadata(metadata);inbox.pending=0;
    }
    ReleaseSRWLockExclusive(&countryPaintLock);return true;
}
static void pollCountryPaint(void* world) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2a58f8)) {
        AcquireSRWLockExclusive(&countryPaintLock);
        if(countryPaintInbox.world) {countryPaintInbox.world=nullptr;countryPaintInbox.pending=0;resetCountryMetadata();}
        ReleaseSRWLockExclusive(&countryPaintLock);return;
    }
    unsigned query[3]={};auto now=GetTickCount64();
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;
    const unsigned character=at<unsigned>(own,0x24),power=at<BYTE>(own,0x28),session=*reinterpret_cast<unsigned*>(0x2216c3c)&65535;
    if(!character || (power!=2 && power!=3)) {ReleaseSRWLockExclusive(&countryPaintLock);return;}
    if(inbox.world!=world || inbox.session!=session || inbox.character!=character || inbox.power!=power) {
        const unsigned nonce=inbox.nonce;inbox={};inbox.world=world;inbox.session=session;inbox.character=character;inbox.power=power;inbox.nonce=nonce;
        resetCountryMetadata();
    }
    if(inbox.pending && now-inbox.sentAt>=5000)inbox.pending=0;
    if(!inbox.pending && now>=inbox.pollAt) {
        if(now>=inbox.identityAt) {inbox.pending=character;inbox.mode=11;inbox.identityAt=now+5000;}
        else if(inbox.ownRegistered) {
            // shortcut: territory display IDs still come from the shared catalogue; replace with runtime object IDs before admitting new countries.
            unsigned ids[351]={inbox.ownCountry},count=1;
            for(const auto& country:logh::countryDisplays)if(country.id!=inbox.ownCountry)ids[count++]=country.id;
            inbox.pending=ids[(inbox.cursor/2)%count];inbox.mode=4+inbox.cursor++%2;
        }
        if(inbox.pending) {
            inbox.nonce=(inbox.nonce%65535)+1;inbox.sentAt=now;inbox.pollAt=now+1000;
            query[0]=inbox.pending;query[1]=inbox.nonce;query[2]=inbox.mode;
        }
    }
    ReleaseSRWLockExclusive(&countryPaintLock);
    if(query[0])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
