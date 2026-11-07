// Publish a complete result before the native ending notification, and keep it across disconnect. Author: Peppone Choi.
#pragma once
#include "country_ending_data.h"
static SRWLOCK countryEndingLock=SRWLOCK_INIT;
static struct {
    void* world=nullptr;unsigned character=0,nonce=0,sequence=0;bool pending=false,push=false;
    ULONGLONG sentAt=0;logh::CountryEndingDraft draft;
    logh::CountryEndingCatalog published,collecting;
} countryEndingInbox;
static void resetCountryEndings() {
    AcquireSRWLockExclusive(&countryEndingLock);auto& inbox=countryEndingInbox;
    inbox.world=nullptr;inbox.character=0;inbox.pending=inbox.push=false;inbox.draft={};
    inbox.published={};inbox.collecting={};++inbox.sequence;
    ReleaseSRWLockExclusive(&countryEndingLock);
}
static bool receiveCountryEndings(const BYTE* p) {
    if(p[8]!=14)return false;
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);auto own=world ? at<BYTE*>(world,8) : nullptr;
    const unsigned character=own && own[0] ? at<unsigned>(own,0x24) : 0;
    AcquireSRWLockExclusive(&countryEndingLock);auto& inbox=countryEndingInbox;
    const unsigned base=logh::paintWord(p+4),offset=base%512;
    if(base/512==127 && !logh::paintWord(p+12) && !logh::paintWord(p+16) &&
        (!offset || inbox.push && inbox.world==world && inbox.character==character && inbox.collecting.rows.size()==offset)) {
        if(!offset) {inbox.world=world;inbox.character=character;inbox.collecting={};}
        inbox.push=true;inbox.pending=inbox.draft.begin(logh::paintWord(p),127,offset);
    }
    if(inbox.world==world && inbox.character==character && inbox.pending && inbox.draft.accept(p,384)==2) {
        const auto& page=inbox.draft.page;inbox.pending=false;
        if(inbox.push) {
            auto& all=inbox.collecting;
            bool valid=all.rows.empty() ? page.offset==0 : page.offset==all.rows.size() &&
                all.header.session==page.session && all.header.total==page.total && all.header.generation==page.generation &&
                all.header.seconds==page.seconds && all.header.status==page.status && all.header.viewer==page.viewer &&
                (!page.count || all.rows.back().id<page.rows[0].id);
            if(valid) {
                all.header=page;for(unsigned i=0;i<page.count;++i)all.rows.push_back(page.rows[i]);
                if(all.rows.size()==page.total) {inbox.published=all;inbox.push=false;++inbox.sequence;}
            } else {inbox.collecting={};inbox.push=false;}
        } else {
            inbox.published.header=page;inbox.published.rows.assign(page.rows,page.rows+page.count);++inbox.sequence;
        }
    }
    ReleaseSRWLockExclusive(&countryEndingLock);return true;
}
static bool requestCountryEndings(void* world,unsigned session,unsigned offset) {
    if(!world)return false;unsigned query[3]={};auto own=at<BYTE*>(world,8);
    AcquireSRWLockExclusive(&countryEndingLock);auto& inbox=countryEndingInbox;
    if(inbox.pending && GetTickCount64()-inbox.sentAt<5000) {ReleaseSRWLockExclusive(&countryEndingLock);return false;}
    inbox.nonce=inbox.nonce%126+1;
    if(!inbox.draft.begin(session,inbox.nonce,offset)) {ReleaseSRWLockExclusive(&countryEndingLock);return false;}
    inbox.world=world;inbox.character=own && own[0] ? at<unsigned>(own,0x24) : 0;
    inbox.pending=true;inbox.push=false;inbox.sentAt=GetTickCount64();query[0]=session;query[1]=inbox.draft.queryBase();query[2]=14;
    ReleaseSRWLockExclusive(&countryEndingLock);
    return reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query)!=0;
}
