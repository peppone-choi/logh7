// Native text controls already contain UTF-16. Decode only the new ANSI fragment.
// Author: Peppone Choi.
#pragma once
#include <windows.h>
#include <string>
#include "korean_text.h"
#include "display_text.h"

namespace logh {
inline bool japaneseFragment(const char* fragment, std::wstring& decoded) {
    if (!fragment) return false;
    fragment = koreanStaticResource(fragment);
    int count = MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, fragment, -1, nullptr, 0);
    if (!count) return false;
    std::wstring converted(static_cast<size_t>(count), L'\0');
    if (MultiByteToWideChar(932, MB_ERR_INVALID_CHARS, fragment, -1, &converted[0], count) != count) return false;
    converted.resize(static_cast<size_t>(count - 1));
    restoreKoreanGlyphs(converted);
    restoreDisplayNames(converted);
    decoded.swap(converted);
    return true;
}
// Catalogue lookups through the hooked 00522010 return Korean in private CP932
// glyphs; decode them with restoration instead of a plain CP932 conversion.
template <size_t N> inline void catalogueText(const char* original, wchar_t (&out)[N]) {
    out[0] = 0;
    std::wstring decoded;
    if (japaneseFragment(original, decoded)) wcsncpy_s(out, decoded.c_str(), _TRUNCATE);
}
}
