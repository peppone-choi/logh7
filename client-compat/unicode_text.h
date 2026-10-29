// Native text controls already contain UTF-16. Decode only the new ANSI fragment.
// Author: 최병호.
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
}
