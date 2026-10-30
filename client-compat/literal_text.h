// Localize verified on-screen literal pushes. Author: Peppone Choi.
#pragma once
#include "korean_text.h"
#include <cwchar>
static constexpr unsigned koreanLiteralPushes[]={0x4c6e7e,0x4c6ed2,0x4c7207};
static constexpr unsigned koreanSelectionLogPush=0x570d9f;
struct KoreanWideLiteralPush { unsigned address,original; };
static constexpr KoreanWideLiteralPush koreanWideLiteralPushes[]={
    {0x594b79,0x78d958}, // day suffix, wcscat into native lobby buffer
    {0x594b3b,0x78d96c}, // month suffix
    {0x594936,0x78d99c}, // population unit, first faction
    {0x594981,0x78d99c}, // population unit, second faction
    {0x594b0b,0x78d97c}  // elapsed-time prefix, swprintf
};
static bool installKoreanLiteralText() {
    static_assert(sizeof(void*)==4,"CD literal pointer width");
    const BYTE expected[]={0x68,0x4c,0x19,0x77,0x00};
    const char* original=reinterpret_cast<const char*>(0x77194c);
    const char* translated=logh::koreanStaticResource(original);
    if (translated==original) return false;
    const BYTE selectionExpected[]={0x68,0x58,0xbd,0x78,0};
    const char* selectionOriginal=reinterpret_cast<const char*>(0x78bd58);
    const char* selectionTranslated=logh::koreanStaticResource(selectionOriginal);
    if (selectionTranslated==selectionOriginal ||
        memcmp(reinterpret_cast<void*>(koreanSelectionLogPush),selectionExpected,sizeof(selectionExpected))) return false;
    for (unsigned address:koreanLiteralPushes)
        if (memcmp(reinterpret_cast<void*>(address),expected,sizeof(expected))) return false;
    for (const auto& row:koreanWideLiteralPushes) {
        BYTE oldPush[5]={0x68};memcpy(oldPush+1,&row.original,4);
        const auto entry=logh::koreanWideResource(reinterpret_cast<const wchar_t*>(row.original));
        // These are fixed native wchar_t arrays. A replacement must never
        // consume more units than the original literal's allotted suffix.
        if (!entry || std::wcslen(entry->text)>entry->units ||
            memcmp(reinterpret_cast<void*>(row.address),oldPush,sizeof(oldPush))) return false;
    }
    BYTE replacement[5]={0x68};memcpy(replacement+1,&translated,sizeof(translated));
    for (unsigned address:koreanLiteralPushes)
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,sizeof(expected))) return false;
    memcpy(replacement+1,&selectionTranslated,sizeof(selectionTranslated));
    if (!patchBytes(reinterpret_cast<void*>(koreanSelectionLogPush),selectionExpected,replacement,sizeof(selectionExpected))) return false;
    for (const auto& row:koreanWideLiteralPushes) {
        BYTE oldPush[5]={0x68},newPush[5]={0x68};memcpy(oldPush+1,&row.original,4);
        const auto text=logh::koreanWideEntry(row.original)->text;
        memcpy(newPush+1,&text,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),oldPush,newPush,sizeof(oldPush))) return false;
    }
    return true;
}
