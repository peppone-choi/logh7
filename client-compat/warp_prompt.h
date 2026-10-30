// Non-ASCII map-selection prompt through the native name-label renderer. Author: 최병호.
#pragma once
#include <windows.h>

// 004d8f1f/004d8f36 draw the selection prompt ([0x9d2a30+0x38], e.g. 0x78bca4)
// with 004ce410. Its 004af380 texture font skips every byte >= 0x80, so our
// private Hangul glyphs printed only their trail bytes. 004ce350 has the same
// cdecl (x, y, text, color, scale) contract and lays out CP932/private glyphs
// for the star-system names on the same map, so only such text is redirected.
static constexpr unsigned warpPromptCalls[]={0x4d8f1f,0x4d8f36};
static constexpr unsigned warpPromptAsciiDraw=0x4ce410, warpPromptGlyphDraw=0x4ce350;

static bool warpPromptNeedsGlyphs(const char* text) {
    if (!text) return false;
    for (auto p=reinterpret_cast<const unsigned char*>(text);*p;++p) if (*p>=0x80) return true;
    return false;
}
static void __cdecl warpPromptDraw(int x,int y,const char* text,unsigned color,float scale) {
    // The ASCII font's 3.0 scale is its own cell size; the name renderer uses 1.0.
    if (warpPromptNeedsGlyphs(text))
        reinterpret_cast<void(__cdecl*)(int,int,const char*,unsigned,float)>(warpPromptGlyphDraw)(x,y,text,color,1.0f);
    else reinterpret_cast<void(__cdecl*)(int,int,const char*,unsigned,float)>(warpPromptAsciiDraw)(x,y,text,color,scale);
}
static bool installWarpPrompt() {
    BYTE expected[2][5];
    for (unsigned i=0;i<2;++i) {
        expected[i][0]=0xe8;
        *reinterpret_cast<LONG*>(expected[i]+1)=static_cast<LONG>(warpPromptAsciiDraw-(warpPromptCalls[i]+5));
        if (memcmp(reinterpret_cast<void*>(warpPromptCalls[i]),expected[i],5)) return false;
    }
    for (unsigned i=0;i<2;++i) {
        BYTE replacement[5]={0xe8};
        *reinterpret_cast<LONG*>(replacement+1)=static_cast<LONG>(reinterpret_cast<const BYTE*>(&warpPromptDraw)-
            reinterpret_cast<const BYTE*>(warpPromptCalls[i])-5);
        if (!patchBytes(reinterpret_cast<void*>(warpPromptCalls[i]),expected[i],replacement,5)) return false;
    }
    return true;
}
