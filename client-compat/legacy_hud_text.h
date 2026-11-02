// Fixed CD debug HUD formats and its private ANSI glyph consumer. Author: Peppone Choi.
#pragma once
#include "legacy_korean_glyph.h"

struct KoreanLegacyHudPush { unsigned address,original; };
static constexpr KoreanLegacyHudPush koreanLegacyHudPushes[]={
    {0x5a1816,0x78de40}, // camera speed x100
    {0x5a1837,0x78de28}, // zoom value x100
    {0x5a1883,0x78ddf8}, // zoom level x100
    {0x5a191c,0x78ddb4}, // mouse x/y
    {0x5a19d2,0x78dd44}  // frame number
};
static DWORD WINAPI koreanLegacyOutline(HDC dc,UINT character,UINT format,LPGLYPHMETRICS metrics,
                                       DWORD capacity,LPVOID buffer,const MAT2* matrix) {
    return logh::legacyKoreanOutline(dc,character,format,metrics,capacity,buffer,matrix);
}
static bool installKoreanLegacyHud() {
    const unsigned sites[]={0x52478b,0x5247b9};
    const BYTE outlineExpected[]={0xff,0x15,0xc0,0xb0,0x66,0};
    // Validate every format and call before changing any of these sites.
    for (const auto& row:koreanLegacyHudPushes) {
        BYTE expected[5]={0x68};memcpy(expected+1,&row.original,4);
        const auto original=reinterpret_cast<const char*>(row.original);
        const auto text=logh::koreanStaticResource(original);
        // Each original stack buffer has at least 256 bytes. These five
        // formats contain only bounded 32-bit integers, never string varargs.
        if (text==original || strlen(text)>64 ||
            memcmp(reinterpret_cast<void*>(row.address),expected,sizeof(expected))) return false;
    }
    for (auto site:sites)
        if (memcmp(reinterpret_cast<void*>(site),outlineExpected,sizeof(outlineExpected))) return false;
    for (const auto& row:koreanLegacyHudPushes) {
        BYTE expected[5]={0x68},replacement[5]={0x68};memcpy(expected+1,&row.original,4);
        const auto text=logh::koreanStaticResource(reinterpret_cast<const char*>(row.original));
        memcpy(replacement+1,&text,4);
        if (!patchBytes(reinterpret_cast<void*>(row.address),expected,replacement,sizeof(expected))) return false;
    }
    for (auto site:sites) {
        BYTE replacement[6]={0xe8,0,0,0,0,0x90};
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<BYTE*>(&koreanLegacyOutline)-
            reinterpret_cast<BYTE*>(site)-5);
        if (!patchBytes(reinterpret_cast<void*>(site),outlineExpected,replacement,sizeof(replacement))) return false;
    }
    return true;
}
