// Mode 7 is read-only and is consumed before the original outfit cache. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
#include "country_image_data.h"
static SRWLOCK lobbyCountryLock=SRWLOCK_INIT;
static struct LobbyCountryInbox {
    logh::LobbySessionList sessions={};logh::LobbyCountryDraft draft={};logh::LobbyCountryPage published={};
    unsigned sequence=0,nonce=0;bool pending=false;ULONGLONG sentAt=0;
} lobbyCountryInbox;
static struct LobbyCountrySelectionInbox {
    logh::LobbyCountrySelection choice={};unsigned nonce=0;ULONGLONG sentAt=0;
} lobbyCountrySelectionInbox;
static struct LobbyEmblemInbox {
    logh::CountryImageDraft draft;
    logh::CountryImageData rows[logh::lobbyCountryRows];
    ULONGLONG retryAt[logh::lobbyCountryRows]={};
    ULONGLONG deadline=0;
    unsigned nonce=0,slot=0;
    std::uint64_t epoch=1;
} lobbyEmblemInbox;
static constexpr unsigned lobbyEmblemProbe=127*512+logh::lobbyCountryMaximum;
static unsigned lobbyEmblemProbeSession=0;
static bool lobbyEmblemSupported=false;

static void resetLobbyEmblems() {
    auto& images=lobbyEmblemInbox;
    const unsigned nonce=images.nonce;
    const auto epoch=images.epoch+1;
    images={};images.nonce=nonce;images.epoch=epoch;
}

static void requestLobbyEmblem(void* world,unsigned session,unsigned offset,ULONGLONG now) {
    if(!world)return;
    unsigned query[3]={};
    AcquireSRWLockExclusive(&lobbyCountryLock);
    const auto& page=lobbyCountryInbox.published;
    auto& images=lobbyEmblemInbox;
    if(lobbyEmblemSupported && lobbyEmblemProbeSession==session && page.session==session && page.offset==offset &&
        page.generation && now>=images.deadline) {
        for(unsigned i=0;i<page.count;++i) {
            const auto id=page.rows[i].id;
            if(now<images.retryAt[i] || (images.rows[i].id==id && images.rows[i].revision>=page.revision))continue;
            images.nonce=images.nonce%32767+1;
            const unsigned token=images.nonce*65536+session;
            images.draft.begin(id,token,page.revision,18);
            images.slot=i;images.deadline=now+3000;images.retryAt[i]=now+5000;
            query[0]=id;query[1]=token;query[2]=18;
            break;
        }
    }
    ReleaseSRWLockExclusive(&lobbyCountryLock);
    if(query[2])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}
static bool updateLobbyCountryWindow(void* controller);
static unsigned __stdcall decodedLobbyCountrySessions(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x444900)(record,reader);
    logh::LobbySessionList list;
    if(!result && logh::readLobbySessionList(static_cast<const BYTE*>(record),0x5304,list)) {
        resetCountryEndings();
        resetTacticalCamps();
        AcquireSRWLockExclusive(&lobbyCountryLock);
        lobbyCountryInbox.sessions=list;++lobbyCountryInbox.sequence;
        ReleaseSRWLockExclusive(&lobbyCountryLock);
    }
    return result;
}
static bool receiveLobbyCountries(const BYTE* record) {
    if(record[8]==18) {
        AcquireSRWLockExclusive(&lobbyCountryLock);
        auto& images=lobbyEmblemInbox;
        if(images.deadline && images.draft.accept(record,24+10*36)==2) {
            images.rows[images.slot]=images.draft.value;
            images.deadline=0;++lobbyCountryInbox.sequence;
        }
        ReleaseSRWLockExclusive(&lobbyCountryLock);return true;
    }
    if(record[8]>=8 && record[8]<=10) {
        AcquireSRWLockExclusive(&lobbyCountryLock);
        lobbyCountrySelectionInbox.choice.accept(record,24+10*36);
        ReleaseSRWLockExclusive(&lobbyCountryLock);return true;
    }
    if(record[8]!=7)return false;
    AcquireSRWLockExclusive(&lobbyCountryLock);
    auto& inbox=lobbyCountryInbox;
    if(lobbyEmblemProbeSession && logh::paintWord(record)==lobbyEmblemProbeSession && logh::paintWord(record+4)==lobbyEmblemProbe) {
        wchar_t fields[10][13]={};
        if(!logh::paintWord(record+12) && !logh::paintWord(record+16) && logh::lobbyCountryFields(record,24+10*36,fields) &&
            !wcscmp(fields[0],L"NC2") && !wcscmp(fields[2],L"350"))lobbyEmblemSupported=true;
        ReleaseSRWLockExclusive(&lobbyCountryLock);return true;
    }
    if(inbox.pending && inbox.draft.accept(record,24+10*36)==2) {
        const auto& before=inbox.published;
        const auto& after=inbox.draft.page;
        bool sameRows=before.session==after.session && before.offset==after.offset &&
            before.generation==after.generation && before.count==after.count;
        for(unsigned i=0;sameRows && i<after.count;++i)sameRows=before.rows[i].id==after.rows[i].id;
        if(!sameRows)resetLobbyEmblems();
        inbox.published=inbox.draft.page;inbox.pending=false;++inbox.sequence;
    }
    ReleaseSRWLockExclusive(&lobbyCountryLock);return true;
}
static void resetLobbyCountrySelection() {
    AcquireSRWLockExclusive(&lobbyCountryLock);lobbyCountrySelectionInbox.choice={};
    ReleaseSRWLockExclusive(&lobbyCountryLock);
}
static bool requestLobbyCountrySelection(void* world,unsigned session,unsigned country,unsigned mode) {
    if(!world)return false;
    unsigned query[3]={};
    AcquireSRWLockExclusive(&lobbyCountryLock);
    auto& inbox=lobbyCountrySelectionInbox;
    inbox.nonce=inbox.nonce%32767+1;
    if(!inbox.choice.begin(session,country,mode,inbox.nonce)) {ReleaseSRWLockExclusive(&lobbyCountryLock);return false;}
    inbox.sentAt=GetTickCount64();query[0]=country;query[1]=inbox.choice.queryBase();query[2]=mode;
    ReleaseSRWLockExclusive(&lobbyCountryLock);
    return reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query)!=0;
}
static void resetLobbyCountries(bool sessions=false) {
    AcquireSRWLockExclusive(&lobbyCountryLock);
    lobbyCountryInbox.pending=false;lobbyCountryInbox.draft={};lobbyCountryInbox.published={};
    resetLobbyEmblems();
    lobbyEmblemProbeSession=0;lobbyEmblemSupported=false;
    if(sessions)lobbyCountryInbox.sessions={};++lobbyCountryInbox.sequence;
    ReleaseSRWLockExclusive(&lobbyCountryLock);
}
static bool requestLobbyCountries(void* world,unsigned session,unsigned offset) {
    if(!world || !session || session>65535 || offset>350)return false;
    unsigned query[3]={};const auto now=GetTickCount64();
    AcquireSRWLockExclusive(&lobbyCountryLock);
    auto& inbox=lobbyCountryInbox;
    if(inbox.pending && now-inbox.sentAt<5000) {ReleaseSRWLockExclusive(&lobbyCountryLock);return false;}
    inbox.nonce=inbox.nonce%127+1;
    inbox.draft.begin(session,inbox.nonce,offset);inbox.pending=true;inbox.sentAt=now;
    query[0]=session;query[1]=inbox.draft.queryBase();query[2]=7;
    const bool probe=lobbyEmblemProbeSession!=session;
    if(probe) {lobbyEmblemProbeSession=session;lobbyEmblemSupported=false;}
    ReleaseSRWLockExclusive(&lobbyCountryLock);
    const auto send=reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0);
    if(probe) {
        const unsigned capability[3]={session,lobbyEmblemProbe,7};
        send(world,0,0x10,capability);
    }
    return send(world,0,0x10,query)!=0;
}
static bool installLobbyCountryTransport() {
    const unsigned original=0x444900,replacement=reinterpret_cast<unsigned>(&decodedLobbyCountrySessions);
    return patchBytes(reinterpret_cast<void*>(0x66cd78),&original,&replacement,4);
}
