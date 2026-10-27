// Connect the spot menu to the existing native character information tabs.
// Author: 최병호.
#pragma once
#include "spot_presence.h"
#include "character_cards.h"
static void* characterInformationManager=nullptr;
static void* characterInformationWorld=nullptr;
static struct SpotInformationWindow {
    void* manager; void* world; void* root;
    unsigned own, target, spot, owner;
    logh::CharacterCards cards;
    bool cardsValid;
} spotInformationWindow={};
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
            auto title=reinterpret_cast<void*(__thiscall*)(void*,int,int)>(0x502780)(root,1,2);
            auto name=std::wstring(L"인물 정보: ")+spotCharacterLabel(character);
            if (title) reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,title,name.c_str());
            reinterpret_cast<void(__thiscall*)(void*)>(0x503910)(root);
            reinterpret_cast<void(__thiscall*)(void*,int)>(0x506280)(root,12);
            reinterpret_cast<void(__thiscall*)(void*,void*)>(0x50beb0)(manager,root);
            *reinterpret_cast<BYTE*>(0x2221dfc)=1;
            auto own=at<void*>(world,8);
            spotInformationWindow={manager,world,root,at<unsigned>(own,0x24),at<unsigned>(character,0x24),
                at<unsigned>(own,0x40),at<unsigned>(own,0x44),{},false};
            auto& state=spotInformationWindow;
            state.cardsValid=logh::CharacterCards::read(character,0x370,state.cards);
            renderCharacterCards(manager,state.cards,state.cardsValid,character);
        }
    }
    reinterpret_cast<void(__thiscall*)(void*)>(0x546850)(context);
}
static bool installCharacterInformation() {
    const BYTE expected[]={0xe8,0xf7,0xd0,0xff,0xff};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&spotCharacterInformation)-reinterpret_cast<BYTE*>(0x549754)-5);
    return patchBytes(reinterpret_cast<void*>(0x549754),expected,replacement,5);
}
