// Only known game dialog literals and selected exact copies. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"
#include "input_text.h"

namespace logh {
static constexpr unsigned dialogBodyCopies[]={
    0x77194c,0x77a648,0x7869ec,0x786a10,0x786a30,0x786a7c,
    0x787ac4,0x788038,0x78805c,0x788084,0x7880a8,
    0x78bbd0,0x78bcbc,0x78be60
};
static constexpr unsigned dialogCaptionCopies[]={0x786a28,0x786a74,0x786ab4,0x7881ec,0x7881f4};
static constexpr unsigned dialogMessageBoxCalls[]={0x5a9051,0x5dcf12,0x5dcf81,0x652380};
inline bool dialogMessageBoxCaller(uintptr_t address) {
    for (unsigned call:dialogMessageBoxCalls) if (address==call+6u) return true;
    return false;
}
inline bool translatedDialogText(const char* text,bool caption,std::wstring& result) {
    if (!text) return false;
    // Resource lookup can already have returned our private CP932 bytes.
    // Recognize their identity, not arbitrary matching player content.
    for (const auto& row:koreanResources)
        if (text==row.text) return japaneseFragment(text,result);
    const char* translated=koreanStaticResource(text);
    if (translated==text) {
        // Copies match a reviewed whole literal, never arbitrary substrings.
        // Keep normal heap/player strings out of resource translation.
        const unsigned* addresses=caption ? dialogCaptionCopies : dialogBodyCopies;
        const size_t count=caption ? sizeof(dialogCaptionCopies)/sizeof(unsigned) : sizeof(dialogBodyCopies)/sizeof(unsigned);
        for (size_t i=0;i<count && translated==text;++i) {
            auto row=koreanResourceEntry((static_cast<uint64_t>(65535)<<32)|addresses[i]);
            if (row && !strcmp(text,row->text)) translated=row->text;
            else translated=koreanResource(65535,addresses[i]>>16,addresses[i]&0xffff,text);
        }
    }
    return translated!=text && japaneseFragment(translated,result);
}
inline bool ansiDialogText(const char* text,std::wstring& result,UINT codepage=CP_ACP) {
    if (!text) {result.clear();return true;}
    return inputFragment(text,result,codepage);
}
}
