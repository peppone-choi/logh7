// Keep the native office information renderer, but bind the selected card.
// Author: 최병호.
#pragma once
#include "appointment_ui_data.h"
#include "unicode_text.h"

struct AppointmentChoice { unsigned card=0, base=0, outfit=0, character=0; const BYTE* person=nullptr; const BYTE* seat=nullptr; };
static bool appointmentChoice(AppointmentChoice& choice) {
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    if (!command || (authority!=54 && authority!=2 && authority!=17 && authority!=134 && authority!=161)) return false;
    auto steps=at<BYTE**>(command,0x10), end=at<BYTE**>(command,0x14);
    unsigned active=at<unsigned>(command,4);
    if (!steps || end<steps || end-steps>64 || active>=static_cast<unsigned>(end-steps)) return false;
    auto confirmation=steps[active];
    if (!confirmation || at<unsigned>(confirmation,0)!=0x675780 || at<unsigned>(confirmation,0x48)!=5) return false;
    for (unsigned i=0;i<active;++i) {
        auto step=steps[i];
        if (!step || !at<BYTE>(step,0x18)) continue;
        auto vars=at<BYTE**>(step,0xc), varsEnd=at<BYTE**>(step,0x10);
        if (!vars || varsEnd<vars || varsEnd-vars>16) return false;
        for (auto var=vars;var!=varsEnd;++var) {
            if (!*var) continue;
            auto name=at<const char*>(*var,8);
            if (!name) continue;
            unsigned* selected=nullptr;
            if (!strcmp(name,"TARGET_SELECT_S_CARD")) selected=&choice.card;
            else if (!strcmp(name,"TARGET_SELECT_S_CARD_BASE")) selected=&choice.base;
            else if (!strcmp(name,"TARGET_SELECT_S_CARD_OUTFIT")) selected=&choice.outfit;
            else if (!strcmp(name,"TARGET_SELECT_S_CHARACTER")) selected=&choice.character;
            if (!selected) continue;
            if (*selected || at<unsigned>(*var,0x14)!=1) return false;
            auto head=at<BYTE*>(*var,0x10);
            auto node=head ? at<BYTE*>(head,0) : nullptr;
            if (!node || node==head) return false;
            *selected=at<unsigned>(node,12);
            if (selected==&choice.character) choice.person=at<BYTE*>(node,20);
            if (selected==&choice.outfit) {
                // Outfit selectors keep the row index as value; the actual ID is in the selected record.
                choice.seat=at<BYTE*>(node,20);
                if (!choice.seat) return false;
                choice.outfit=logh::infoWord(choice.seat,4);
            }
        }
    }
    if (!choice.base && (choice.card==56 || choice.card==57 || choice.card==192 || choice.card==193)) {
        auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
        if (!world) return false;
        unsigned power=choice.card==56 || choice.card==57 ? 2 : 3;
        for (unsigned slot=0;slot<350;++slot) {
            auto base=world+0x2eb800+slot*0x250;
            if (!base[0] || logh::infoWord(base,8)!=2 || logh::planetCapitalPower(base)!=power) continue;
            unsigned id=logh::infoWord(world,0x2eb288+slot*4);
            if (choice.base || !id || id>65535) return false;
            choice.base=id;
        }
    }
    bool validSeat=choice.card==39 ? choice.outfit>0 && choice.seat && logh::infoWord(choice.seat,4)==choice.outfit :
        choice.outfit==0 && choice.base>0 && choice.base<=65535;
    return choice.card>0 && choice.card<=260 && validSeat &&
        choice.character>0 && choice.person && logh::infoWord(choice.person,0)==choice.character;
}

static void __fastcall appointmentConfirmation(void* dialog, void*, const char* original) {
    reinterpret_cast<void(__thiscall*)(void*,const char*)>(0x5706e0)(dialog,original);
    AppointmentChoice choice;
    std::wstring body;
    if (!logh::japaneseFragment(original,body)) return;
    auto newline=body.find(L'\n');
    if (newline==std::wstring::npos) return;
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    if (command && at<unsigned>(command,0x28)==7) {
        auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
        auto own=world ? at<BYTE*>(world,8) : nullptr;
        unsigned card=at<unsigned>(command,0x20);
        if (!own || at<void*>(command,0x1c)!=own || own[0x270]>16 || card>260 || card==0 || card==59 || card==195) return;
        bool held=false;
        for (unsigned i=0;i<own[0x270];++i) if (logh::infoShort(own,0x274+i*8)==card) held=true;
        if (!held) return;
        auto name=reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8cb0)
            (reinterpret_cast<void*>(0x7cd048),card);
        std::wstring office;
        if (!logh::japaneseFragment(name,office) || office.empty()) return;
        body=office+L" 직무를 사임합니다."+body.substr(newline);
    } else {
        if (!appointmentChoice(choice)) return;
        unsigned length=choice.person[4];
        if (length<2 || length>13) return;
        wchar_t person[13]={}, base[32]={};
        memcpy(person,choice.person+6,length*sizeof(wchar_t));
        if (person[length-1]!=0 || wcslen(person)!=length-1) return;
        std::wstring seat;
        if (choice.outfit) {
            auto name=reinterpret_cast<const char*(__thiscall*)(void*,const void*)>(0x4c90c0)
                (reinterpret_cast<void*>(0x7cd048),choice.seat+4);
            if (!logh::japaneseFragment(name,seat)) return;
        } else {
            pickerName(choice.base,base);
            seat=base;
        }
        auto role=reinterpret_cast<const char*(__thiscall*)(void*,unsigned)>(0x4c8cb0)
            (reinterpret_cast<void*>(0x7cd048),choice.card);
        std::wstring office;
        if (seat.empty() || !logh::japaneseFragment(role,office) || office.empty()) return;
        // Preserve the original cost, wait, duration and confirmation question.
        body=std::wstring(person)+L" → "+seat+L" · "+office+body.substr(newline);
    }
    // 005706d0 returns the control directly; 005706c0 returns the GUI manager.
    auto element=reinterpret_cast<void*(__thiscall*)(void*,unsigned)>(0x5706d0)(dialog,5);
    auto manager=reinterpret_cast<void*(__thiscall*)(void*)>(0x5706c0)(dialog);
    if (manager && element && at<BYTE>(element,8))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(manager,element,body.c_str());
}

static unsigned __fastcall appointmentInformation(void* dialog, void*, void* row) {
    unsigned mode=at<unsigned>(dialog,0x234);
    unsigned card=row ? logh::appointmentInformationCard(mode,at<unsigned>(row,8),
        at<unsigned short>(dialog,0x15fc)) : 0;
    // 00577e70 clears the secondary query; 004be750 does not populate it for
    // office selectors. 0057a080 nevertheless indexes the card table with it.
    // The primary query holds the requested card for outfit/base seat lists.
    auto previous=at<unsigned short>(dialog,0x1a00);
    if (card) at<unsigned short>(dialog,0x1a00)=static_cast<unsigned short>(card);
    unsigned result=reinterpret_cast<unsigned(__thiscall*)(void*,void*)>(0x57a080)(dialog,row);
    at<unsigned short>(dialog,0x1a00)=previous;
    return result;
}
static bool installAppointmentInformation() {
    auto target=reinterpret_cast<BYTE*>(0x579ecb);
    const BYTE expected[]={0xe8,0xb0,0x01,0,0};
    BYTE replacement[5]={0xe8};
    *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&appointmentInformation)-target-5);
    auto confirmation=reinterpret_cast<BYTE*>(0x572656);
    const BYTE confirmationExpected[]={0xe8,0x85,0xe0,0xff,0xff};
    BYTE confirmationReplacement[5]={0xe8};
    *reinterpret_cast<LONG*>(confirmationReplacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&appointmentConfirmation)-confirmation-5);
    if (memcmp(target,expected,5) || memcmp(confirmation,confirmationExpected,5)) return false;
    return patchBytes(target,expected,replacement,5) &&
        patchBytes(confirmation,confirmationExpected,confirmationReplacement,5);
}
