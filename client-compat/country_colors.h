// Poll display metadata through an explicit outfit-query extension. Author: Peppone Choi (peppone-choi).
#pragma once
#include "country_colors_data.h"
#include "country_catalog_data.h"
static SRWLOCK countryPaintLock=SRWLOCK_INIT;
static struct CountryPaintInbox {
    void* world;unsigned pending,nonce,cursor,count;ULONGLONG sentAt,pollAt;
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
    if(record[8]!=4)return false;
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;logh::CountryPaint paint;
    if(inbox.pending && logh::readCountryPaint(record,24+6*36,inbox.pending,inbox.nonce,paint)) {
        unsigned index=0;for(;index<inbox.count;++index)if(inbox.rows[index].id==paint.id)break;
        if(index<350){inbox.rows[index]=paint;if(index==inbox.count)++inbox.count;}
        inbox.pending=0;
    }
    ReleaseSRWLockExclusive(&countryPaintLock);return true;
}
static void pollCountryPaint(void* world) {
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2a58f8))return;
    unsigned query[3]={};auto now=GetTickCount64();
    AcquireSRWLockExclusive(&countryPaintLock);
    auto& inbox=countryPaintInbox;
    if(inbox.world!=world){inbox={};inbox.world=world;}
    if(inbox.pending && now-inbox.sentAt>=5000)inbox.pending=0;
    if(!inbox.pending && now>=inbox.pollAt) {
        // shortcut: known current IDs come from the shared catalogue; extend the runtime catalogue before enabling new countries.
        const unsigned count=static_cast<unsigned>(sizeof(logh::countryDisplays)/sizeof(logh::countryDisplays[0]));
        if(count) {
            inbox.pending=logh::countryDisplays[inbox.cursor++%count].id;
            inbox.nonce=(inbox.nonce%65535)+1;inbox.sentAt=now;inbox.pollAt=now+1000;
            query[0]=inbox.pending;query[1]=inbox.nonce;query[2]=4;
        }
    }
    ReleaseSRWLockExclusive(&countryPaintLock);
    if(query[0])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
