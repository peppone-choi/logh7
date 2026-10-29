// Resource-only Korean text. ANSI input and UTF-16 network strings stay separate.
// Author: 최병호.
#pragma once
#include "korean_catalog_data.h"
#include <cstring>
#include <string>

namespace logh {
inline const KoreanResource* koreanResourceEntry(uint64_t key) {
    size_t left=0,right=sizeof(koreanResources)/sizeof(koreanResources[0]);
    while (left<right) {
        const size_t middle=left+(right-left)/2;
        if (koreanResources[middle].key<key) left=middle+1;
        else right=middle;
    }
    return left<sizeof(koreanResources)/sizeof(koreanResources[0]) && koreanResources[left].key==key
        ? &koreanResources[left] : nullptr;
}
inline const char* koreanResource(unsigned table, unsigned group, unsigned index, const char* original) {
    if (!original || group > 0xffff || index > 0xffff) return original;
    const uint64_t key = (static_cast<uint64_t>(table) << 32) | (group << 16) | index;
    uint64_t hash = 14695981039346656037ULL;
    size_t length = 0;
    for (auto p = reinterpret_cast<const unsigned char*>(original); *p; ++p) {
        hash = (hash ^ *p) * 1099511628211ULL;
        ++length;
    }
    const auto row=koreanResourceEntry(key);
    if (row && row->hash==hash && row->length==length) return row->text;
    return original;
}
inline const char* koreanResource(unsigned group, unsigned index, const char* original) {
    return koreanResource(0,group,index,original);
}
inline const char* koreanStaticResource(const char* original) {
    // Only an exact original-image address and matching bytes can select this
    // table. Identical text in player input or a received heap buffer is kept.
    const auto address=reinterpret_cast<uintptr_t>(original);
    if (address>0xffffffffu) return original;
    return koreanResource(65535,static_cast<unsigned>(address>>16),static_cast<unsigned>(address&0xffff),original);
}
inline void restoreKoreanGlyphs(wchar_t* text) {
    if (!text) return;
    for (; *text; ++text) {
        const unsigned index = static_cast<unsigned>(*text)-0xe000u;
        if (index < koreanGlyphCount) *text = koreanGlyphs()[index];
    }
}
inline void restoreKoreanGlyphs(std::wstring& text) {
    if (!text.empty()) restoreKoreanGlyphs(&text[0]);
}
}
