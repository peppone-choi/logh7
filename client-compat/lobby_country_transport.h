// Mode 7 is read-only and is consumed before the original outfit cache. Author: Peppone Choi.
#pragma once
#include "lobby_country_data.h"
static SRWLOCK lobbyCountryLock=SRWLOCK_INIT;
static struct LobbyCountryInbox {
    logh::LobbySessionList sessions={};logh::LobbyCountryDraft draft={};logh::LobbyCountryPage published={};
    unsigned sequence=0,nonce=0;bool pending=false;ULONGLONG sentAt=0;
} lobbyCountryInbox;
static struct LobbyCountrySelectionInbox {
    logh::LobbyCountrySelection choice={};unsigned nonce=0;ULONGLONG sentAt=0;
} lobbyCountrySelectionInbox;
static bool updateLobbyCountryWindow(void* controller);
static unsigned __stdcall decodedLobbyCountrySessions(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x444900)(record,reader);
    logh::LobbySessionList list;
    if(!result && logh::readLobbySessionList(static_cast<const BYTE*>(record),0x5304,list)) {
        AcquireSRWLockExclusive(&lobbyCountryLock);
        lobbyCountryInbox.sessions=list;++lobbyCountryInbox.sequence;
        ReleaseSRWLockExclusive(&lobbyCountryLock);
    }
    return result;
}
static bool receiveLobbyCountries(const BYTE* record) {
    if(record[8]>=8 && record[8]<=10) {
        AcquireSRWLockExclusive(&lobbyCountryLock);
        lobbyCountrySelectionInbox.choice.accept(record,24+10*36);
        ReleaseSRWLockExclusive(&lobbyCountryLock);return true;
    }
    if(record[8]!=7)return false;
    AcquireSRWLockExclusive(&lobbyCountryLock);
    auto& inbox=lobbyCountryInbox;
    if(inbox.pending && inbox.draft.accept(record,24+10*36)==2) {
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
    ReleaseSRWLockExclusive(&lobbyCountryLock);
    return reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query)!=0;
}
static bool installLobbyCountryTransport() {
    const unsigned original=0x444900,replacement=reinterpret_cast<unsigned>(&decodedLobbyCountrySessions);
    return patchBytes(reinterpret_cast<void*>(0x66cd78),&original,&replacement,4);
}
