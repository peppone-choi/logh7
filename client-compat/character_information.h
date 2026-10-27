// Connect the spot menu to the existing native character information tabs.
// Author: 최병호.
#pragma once
#include "spot_presence.h"
#include "character_cards.h"
#include "character_info_data.h"
static SRWLOCK characterReplyLock=SRWLOCK_INIT;
static struct CharacterInbox {
    logh::OutfitReplyQueue queue;
    unsigned token, ready;
    void* world;
    BYTE record[logh::characterProfileBytes];
} characterInbox={};
static unsigned characterReplyToken=0;
static void* characterInformationManager=nullptr;
static void* characterInformationWorld=nullptr;
static struct SpotInformationWindow {
    void* manager; void* world; void* root;
    unsigned own, target, spot, owner;
    logh::CharacterCards cards;
    bool cardsValid;
    unsigned token;
    ULONGLONG sentAt;
    bool waiting, timeoutShown;
} spotInformationWindow={};
static void characterInformationTitle(void* root,void* character,const wchar_t* suffix=L"") {
    auto title=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2);
    auto text=std::wstring(L"인물 정보: ")+spotCharacterLabel(character)+suffix;
    if (title) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,title,text.c_str());
}
static bool requestCharacterInformation(unsigned id,void* world) {
    auto connection=*reinterpret_cast<void**>(0x7c25f4);
    auto sender=connection ? at<void*>(connection,0x44) : nullptr;
    if (!sender || !at<void*>(sender,0)) return false;
    AcquireSRWLockExclusive(&characterReplyLock);
    if (characterInbox.world!=world) {characterInbox={};characterInbox.world=world;}
    unsigned token=++characterReplyToken;
    if (!token) token=++characterReplyToken;
    bool added=characterInbox.queue.add(id,0,token,false);
    if (added) {characterInbox.token=token;characterInbox.ready=0;spotInformationWindow.token=token;}
    ReleaseSRWLockExclusive(&characterReplyLock);
    if (!added) return false;
    // 004b8611's asynchronous send interface. Generic operation table has no 0354 entry.
    auto send=at<void*>(at<void*>(sender,0),0x18);
    reinterpret_cast<void(__thiscall*)(void*,unsigned,unsigned,const void*)>(send)
        (sender,at<unsigned>(connection,0x40),0x354,&id);
    return true;
}
static unsigned __stdcall decodedCharacterInformation(void* record,void* reader) {
    unsigned result=reinterpret_cast<unsigned(__stdcall*)(void*,void*)>(0x42a420)(record,reader);
    auto p=static_cast<const BYTE*>(record);
    unsigned token=0;
    if (result || !logh::validCharacterProfile(p,logh::characterProfileBytes,logh::infoWord(p,0))) return result;
    AcquireSRWLockExclusive(&characterReplyLock);
    if (characterInbox.queue.take(logh::infoWord(p,0),0,false,token) && token==characterInbox.token) {
        memcpy(characterInbox.record,p,sizeof(characterInbox.record));characterInbox.ready=token;
    }
    ReleaseSRWLockExclusive(&characterReplyLock);
    return result;
}
static void renderCharacterCards(void* manager,const logh::CharacterCards& cards,bool valid,void* character) {
    auto page=at<void*>(manager,4+17*4);
    auto sheet=page ? reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(page,2,0) : nullptr;
    if (!sheet) return;
    std::wstring text;
    if (!valid) text=L"직무 권한 정보를 표시할 수 없습니다.\n";
    else if (!cards.count) text=L"표시할 직무 권한 카드가 없습니다.\n";
    for (unsigned i=0;valid && i<cards.count;++i) {
        const auto& card=cards.rows[i];
        std::wstring line;
        if (card.kind==0) line=L"개인";
        else if (card.kind==59 || card.kind==195) line=L"함장";
        else {
            auto name=reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8cb0)
                (reinterpret_cast<void*>(0x7cd048),card.kind);
            if (!logh::japaneseFragment(name,line) || line.empty()) line=L"이름 미확인";
        }
        if (card.target) {
            wchar_t base[32]={};
            if (logh::characterCardBase(card.kind)) pickerName(card.target,base);
            if (base[0]) line+=std::wstring(L" · ")+base;
            else if ((card.kind==59 || card.kind==195) && card.target==at<unsigned>(character,0x48)) line+=L" · 기함";
            else line+=L" · 대상 정보 미수신";
        }
        text+=line+L"\n";
    }
    reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(page,sheet,text.c_str());
}
static void* currentSpotInformationTarget(void* world,unsigned id) {
    if (!world || !id) return nullptr;
    auto own=at<void*>(world,8);
    if (!own || !at<BYTE>(own,0)) return nullptr;
    for (unsigned i=0;i<600;++i) {
        auto peer=static_cast<BYTE*>(world)+0xc+i*0x370;
        if (at<BYTE>(peer,0) && at<unsigned>(peer,0x24)==id && at<BYTE>(peer,0xa1) &&
            logh::sameSpotOccupant(at<unsigned>(own,0x24),at<unsigned>(own,0x40),at<unsigned>(own,0x44),
                id,at<unsigned>(peer,0x40),at<unsigned>(peer,0x44))) return peer;
    }
    return nullptr;
}
static void updateCharacterInformation(void* manager) {
    characterInformationManager=manager;
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    characterInformationWorld=world;
    if (!world || !at<BYTE>(world,0x2a58f8)) {
        AcquireSRWLockExclusive(&characterReplyLock);characterInbox={};ReleaseSRWLockExclusive(&characterReplyLock);
    }
    auto& state=spotInformationWindow;
    if (!state.root) return;
    // Shared native info slots can be replaced by another information screen.
    // Never dereference a previous world's or replaced window's pointer.
    if (manager!=state.manager || at<void*>(manager,4+13*4)!=state.root) {state={};return;}
    if (!at<BYTE>(state.root,4)) {state={};return;}
    auto own=world ? at<void*>(world,8) : nullptr;
    if (world!=state.world || !own || !at<BYTE>(own,0) || !at<BYTE>(world,0x2a58f8) ||
        at<BYTE>(world,0x3579cc) || at<unsigned>(own,0x24)!=state.own ||
        at<unsigned>(own,0x40)!=state.spot || at<unsigned>(own,0x44)!=state.owner ||
        !currentSpotInformationTarget(world,state.target)) {
        reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(state.root,0);
        *reinterpret_cast<BYTE*>(0x2221dfc)=0;
        state={};return;
    }
    auto peer=currentSpotInformationTarget(world,state.target);
    BYTE profile[logh::characterProfileBytes]={};
    bool received=false;
    AcquireSRWLockExclusive(&characterReplyLock);
    if (state.waiting && characterInbox.ready==state.token) {
        memcpy(profile,characterInbox.record,sizeof(profile));characterInbox.ready=0;received=true;
    }
    ReleaseSRWLockExclusive(&characterReplyLock);
    if (received) {
        state.waiting=false;
        if (!profile[4]) {
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x502ea0)(state.root,0);
            *reinterpret_cast<BYTE*>(0x2221dfc)=0;state={};return;
        }
        BYTE snapshot[0x370];memcpy(snapshot,peer,sizeof(snapshot));
        if (logh::applyCharacterProfile(profile,sizeof(profile),snapshot,sizeof(snapshot))) {
            reinterpret_cast<int(__cdecl*)(void*,int,void*)>(0x54c950)(manager,14,snapshot);
            characterInformationTitle(state.root,snapshot);
            renderCharacterCards(manager,state.cards,state.cardsValid,peer);
        }
    } else if (state.waiting && !state.timeoutShown && GetTickCount64()-state.sentAt>=5000) {
        state.timeoutShown=true;characterInformationTitle(state.root,peer,L" · 조회 응답 지연");
    }
    logh::CharacterCards cards;
    bool valid=logh::CharacterCards::read(peer,0x370,cards);
    if (valid!=state.cardsValid || (valid && !cards.same(state.cards))) {
        renderCharacterCards(manager,cards,valid,peer);
        state.cards=cards;state.cardsValid=valid;
    }
}
static void __fastcall spotCharacterInformation(void* context,void*) {
    auto character=reinterpret_cast<void*(__thiscall*)(void*)>(0x546810)(context);
    auto world=*reinterpret_cast<void**>(0x7ccffc);
    auto manager=characterInformationManager;
    character=character ? currentSpotInformationTarget(world,at<unsigned>(character,0x24)) : nullptr;
    if (character && world && world==characterInformationWorld && manager && at<BYTE>(world,0x2a58f8) &&
        !at<BYTE>(world,0x3579cc) && !*reinterpret_cast<void**>(0xc9e2f8)) {
        reinterpret_cast<void(__cdecl*)(void*,int)>(0x54c4b0)(manager,0);
        reinterpret_cast<int(__cdecl*)(void*,int,void*)>(0x54c950)(manager,14,character);
        auto root=at<void*>(manager,4+13*4);
        if (root) {
            characterInformationTitle(root,character,L" · 조회 중");
            reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(root);
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
            reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(manager,root);
            *reinterpret_cast<BYTE*>(0x2221dfc)=1;
            auto own=at<void*>(world,8);
            spotInformationWindow={manager,world,root,at<unsigned>(own,0x24),at<unsigned>(character,0x24),
                at<unsigned>(own,0x40),at<unsigned>(own,0x44),{},false,0,GetTickCount64(),false,false};
            auto& state=spotInformationWindow;
            state.cardsValid=logh::CharacterCards::read(character,0x370,state.cards);
            renderCharacterCards(manager,state.cards,state.cardsValid,character);
            state.waiting=requestCharacterInformation(state.target,world);
            if (!state.waiting) characterInformationTitle(root,character,L" · 조회 요청 실패");
        }
    }
    reinterpret_cast<void(__thiscall*)(void*)>(0x546850)(context);
}
static bool installCharacterInformation() {
    const unsigned decoder=0x42a420;
    if (memcmp(reinterpret_cast<void*>(0x66c324),&decoder,4)) return false;
    const BYTE expected[]={0xe8,0xf7,0xd0,0xff,0xff};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&spotCharacterInformation)-reinterpret_cast<BYTE*>(0x549754)-5);
    if (!patchBytes(reinterpret_cast<void*>(0x549754),expected,replacement,5)) return false;
    const auto hook=reinterpret_cast<unsigned>(&decodedCharacterInformation);
    return patchBytes(reinterpret_cast<void*>(0x66c324),&decoder,&hook,4);
}
