// Two native charged-character cards, keyed by both session and character. Author: Peppone Choi.
#pragma once
static constexpr unsigned lobbyPortraitMode=21;
struct LobbyPortraitRow {
    unsigned session=0,character=0,serial=0;
    bool supported=false,present=false;
    ULONGLONG dueAt=0;
    BYTE rgba[4096]={};
};
static struct LobbyPortraitInbox {
    void* world=nullptr;
    LobbyPortraitRow rows[2];
    logh::CountryImageDraft draft;
    unsigned pending=0,slot=0,nonce=0,serial=0;
    ULONGLONG deadline=0,sendAt=0;
} lobbyPortraitInbox;

// The decoded 2004 rows are documented in session_ending_data.h.
static void pollLobbyPortraits(void* world,bool visible) {
    unsigned query[3]={};
    auto now=GetTickCount64();
    AcquireSRWLockExclusive(&lobbyCountryLock);
    auto& inbox=lobbyPortraitInbox;
    if(!visible || !world || inbox.world!=world) {
        inbox.world=visible ? world : nullptr;
        inbox.rows[0]={};inbox.rows[1]={};inbox.pending=0;
    }
    if(visible && world) {
        const auto list=static_cast<const BYTE*>(world)+0x35975c;
        const unsigned count=list[0]<=2 ? list[0] : 0;
        for(unsigned i=0;i<2;++i) {
            auto p=list+i*0x36c;
            const bool charged=i<count && p[0x2a0]==2 && p[0x2a1]==1;
            const unsigned session=charged ? logh::infoShort(p,4) : 0;
            const unsigned character=charged ? logh::infoWord(p,0x2a4) : 0;
            auto& row=inbox.rows[i];
            if(row.session!=session || row.character!=character) {
                row={};row.session=session;row.character=character;
                if(inbox.pending && inbox.slot==i)inbox.pending=0;
            }
        }
        if(inbox.pending && now>=inbox.deadline)inbox.pending=0;
        if(!inbox.pending && now>=inbox.sendAt)for(unsigned i=0;i<2;++i) {
            auto& row=inbox.rows[i];
            if(!row.session || !row.character || row.character>=10000 || now<row.dueAt)continue;
            inbox.slot=i;inbox.deadline=now+5000;inbox.sendAt=now+1100;row.dueAt=now+15000;
            if(row.supported) {
                inbox.nonce=inbox.nonce%32767+1;
                const unsigned token=inbox.nonce*65536+row.session;
                inbox.draft.begin(row.character,token,0,lobbyPortraitMode);
                query[0]=row.character;query[1]=token;query[2]=lobbyPortraitMode;
            } else {
                query[0]=row.session;query[1]=lobbyEmblemProbe;query[2]=7;
            }
            inbox.pending=query[2];
            break;
        }
    }
    ReleaseSRWLockExclusive(&lobbyCountryLock);
    if(query[2])reinterpret_cast<unsigned(__thiscall*)(void*,char,unsigned,const void*)>(0x4b78a0)(world,0,0x10,query);
}

// Called with lobbyCountryLock held.
static bool receiveLobbyPortrait(const BYTE* record) {
    auto& inbox=lobbyPortraitInbox;
    if(record[8]==lobbyPortraitMode) {
        if(inbox.pending==lobbyPortraitMode && inbox.draft.accept(record,24+10*36)==2) {
            auto& row=inbox.rows[inbox.slot];inbox.pending=0;
            const auto pixels=inbox.draft.value.pixels(false);
            if(pixels && (!row.present || memcmp(row.rgba,pixels,sizeof(row.rgba)))) {
                memcpy(row.rgba,pixels,sizeof(row.rgba));row.serial=++inbox.serial;
            }
            row.present=pixels!=nullptr;
        }
        return true;
    }
    if(record[8]==7 && inbox.pending==7 && logh::paintWord(record)==inbox.rows[inbox.slot].session &&
        logh::paintWord(record+4)==lobbyEmblemProbe && !logh::paintWord(record+12) && !logh::paintWord(record+16)) {
        wchar_t fields[10][13]={};
        if(logh::lobbyCountryFields(record,24+10*36,fields) && !wcscmp(fields[0],L"NC2") && !wcscmp(fields[3],L"1")) {
            auto& row=inbox.rows[inbox.slot];row.supported=true;row.dueAt=0;
        }
        inbox.pending=0;
    }
    return false;
}
