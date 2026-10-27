// Native supply record offsets verified against 120b and the selected variable. Author: 최병호.
#pragma once
#include <string>
static bool supplyCommandContext() {
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    unsigned authority=*reinterpret_cast<unsigned*>(0xc9eac0);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    return command && own && own[0] && at<unsigned>(command,0x28)==62 &&
        (authority==44 || authority==180) && at<void*>(command,0x1c)==own;
}
static std::wstring supplyOutfitName(const BYTE* record) {
    if (!record || !logh::infoWord(record,8) || record[13]>3) return L"";
    const wchar_t* kinds[]={L"함대",L"수송부대",L"순찰대",L"지상부대"};
    return std::wstring(L"제")+std::to_wstring(record[12])+kinds[record[13]];
}
static const BYTE* selectedSupplyOutfit() {
    if (!supplyCommandContext()) return nullptr;
    auto command=*reinterpret_cast<BYTE**>(0xc9e2f8);
    auto steps=at<BYTE**>(command,0x10), end=at<BYTE**>(command,0x14);
    unsigned active=at<unsigned>(command,4);
    if (!steps || end<steps || end-steps>64 || active>=static_cast<unsigned>(end-steps)) return nullptr;
    if (!steps[active] || at<unsigned>(steps[active],0)!=0x675780 || at<unsigned>(steps[active],0x48)!=62) return nullptr;
    for (unsigned i=0;i<active;++i) {
        auto step=steps[i];
        if (!step || !at<BYTE>(step,0x18)) continue;
        auto vars=at<BYTE**>(step,0xc), varsEnd=at<BYTE**>(step,0x10);
        if (!vars || varsEnd<vars || varsEnd-vars>16) return nullptr;
        for (auto var=vars;var!=varsEnd;++var) {
            auto name=*var ? at<const char*>(*var,8) : nullptr;
            if (!name || strcmp(name,"TARGET_SELECT_S_SUPPLY_OUTFIT") || at<unsigned>(*var,0x14)!=1) continue;
            auto head=at<BYTE*>(*var,0x10);
            auto node=head ? at<BYTE*>(head,0) : nullptr;
            if (!node || node==head) return nullptr;
            return at<const BYTE*>(node,20);
        }
    }
    return nullptr;
}
