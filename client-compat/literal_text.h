// Localize only the three on-screen format pushes, not the log stream. Author: 최병호.
#pragma once
#include "korean_text.h"
static constexpr unsigned koreanLiteralPushes[]={0x4c6e7e,0x4c6ed2,0x4c7207};
static bool installKoreanLiteralText() {
    static_assert(sizeof(void*)==4,"CD literal pointer width");
    const BYTE expected[]={0x68,0x4c,0x19,0x77,0x00};
    const char* original=reinterpret_cast<const char*>(0x77194c);
    const char* translated=logh::koreanStaticResource(original);
    if (translated==original) return false;
    for (unsigned address:koreanLiteralPushes)
        if (memcmp(reinterpret_cast<void*>(address),expected,sizeof(expected))) return false;
    BYTE replacement[5]={0x68};memcpy(replacement+1,&translated,sizeof(translated));
    for (unsigned address:koreanLiteralPushes)
        if (!patchBytes(reinterpret_cast<void*>(address),expected,replacement,sizeof(expected))) return false;
    return true;
}
