// Localize verified on-screen literal pushes. Author: 최병호.
#pragma once
#include "korean_text.h"
static constexpr unsigned koreanLiteralPushes[]={0x4c6e7e,0x4c6ed2,0x4c7207};
static constexpr unsigned koreanSelectionLogPush=0x570d9f;
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
    BYTE replacement[5]={0x68};memcpy(replacement+1,&translated,sizeof(translated));
    for (unsigned address:koreanLiteralPushes)
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,sizeof(expected))) return false;
    memcpy(replacement+1,&selectionTranslated,sizeof(selectionTranslated));
    return patchBytes(reinterpret_cast<void*>(koreanSelectionLogPush),selectionExpected,replacement,sizeof(selectionExpected));
}
