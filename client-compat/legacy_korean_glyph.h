// Private resource glyphs in the older ANSI outline renderer. Author: 최병호.
#pragma once
#include <windows.h>
#include "korean_text.h"

namespace logh {
inline bool legacyKoreanGlyph(UINT code,wchar_t& glyph) {
    // The native renderer packs the first byte in BH and the second in BL.
    // CP932 EUDC is used only for our resource transport, never player input.
    if (code<0xf040 || code>0xf9fc) return false;
    const char bytes[]={static_cast<char>(code>>8),static_cast<char>(code&255)};
    wchar_t decoded=0;
    if (MultiByteToWideChar(932,MB_ERR_INVALID_CHARS,bytes,2,&decoded,1)!=1) return false;
    const unsigned index=static_cast<unsigned>(decoded)-0xe000u;
    if (index>=koreanGlyphCount) return false;
    glyph=koreanGlyphs()[index];
    return true;
}
inline DWORD legacyKoreanOutline(HDC dc,UINT character,UINT format,LPGLYPHMETRICS metrics,
                                 DWORD capacity,LPVOID buffer,const MAT2* matrix) {
    wchar_t glyph=0;
    if ((format&GGO_GLYPH_INDEX) || !legacyKoreanGlyph(character,glyph))
        return GetGlyphOutlineA(dc,character,format,metrics,capacity,buffer,matrix);
    // Preserve the native logical size/weight and restore its selected font
    // after both sizing and bitmap calls. No host settings or visible DC.
    LOGFONTW description={};
    const auto old=GetCurrentObject(dc,OBJ_FONT);
    if (!old || GetObjectW(old,sizeof(description),&description)!=sizeof(description)) return GDI_ERROR;
    description.lfCharSet=HANGUL_CHARSET;
    lstrcpynW(description.lfFaceName,L"Malgun Gothic",LF_FACESIZE);
    const auto font=CreateFontIndirectW(&description);
    if (!font) return GDI_ERROR;
    const auto previous=SelectObject(dc,font);
    DWORD result=GDI_ERROR;
    if (previous && previous!=HGDI_ERROR) {
        result=GetGlyphOutlineW(dc,static_cast<UINT>(glyph),format,metrics,capacity,buffer,matrix);
        SelectObject(dc,previous);
    }
    DeleteObject(font);
    return result;
}
}
