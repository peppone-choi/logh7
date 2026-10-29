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
inline const KoreanWideResource* koreanWideEntry(uintptr_t address) {
    size_t left=0,right=sizeof(koreanWideResources)/sizeof(koreanWideResources[0])-1;
    while (left<right) {
        const size_t middle=left+(right-left)/2;
        if (koreanWideResources[middle].address<address) left=middle+1;
        else right=middle;
    }
    const auto& row=koreanWideResources[left];
    return row.address && row.address==address ? &row : nullptr;
}
inline const KoreanWideResource* koreanWideResourceAt(uintptr_t address,const wchar_t* original) {
    const auto row=koreanWideEntry(address);
    if (!row || !original) return nullptr;
    uint64_t hash=14695981039346656037ULL;
    for (unsigned i=0;i<row->units;++i) {
        const unsigned unit=static_cast<unsigned>(original[i]);
        if (!unit || unit>0xffff) return nullptr;
        hash=((hash^(unit&255))*1099511628211ULL);
        hash=((hash^(unit>>8))*1099511628211ULL);
    }
    return !original[row->units] && hash==row->hash ? row : nullptr;
}
inline const KoreanWideResource* koreanWideResource(const wchar_t* original) {
    return koreanWideResourceAt(reinterpret_cast<uintptr_t>(original),original);
}
inline const wchar_t* koreanWideText(const wchar_t* original) {
    const auto row=koreanWideResource(original);
    return row ? row->text : original;
}
// These five original formats contain only narrow string arguments. Never
// walk arbitrary printf arguments (integers/doubles/wide strings differ).
struct KoreanStringFormat { uintptr_t address; uint64_t hash; unsigned bytes,arguments; };
static constexpr KoreanStringFormat koreanStringFormats[]={
    {0x76ccf4,0x11aa7f4d5bedd4d5ULL,4,2},
    {0x771e08,0x07b53d07b4815e69ULL,2,1},
    {0x788a24,0xd8588373263ac73bULL,5,2},
    {0x78c40c,0x7f64b1e64970ca8aULL,28,3},
    {0x7887d0,0x98e0ee7f12a6a7fcULL,24,3}
};
inline unsigned koreanFormatStringArguments(uintptr_t address,const char* format) {
    if (!format) return 0;
    for (const auto& row:koreanStringFormats) {
        if (row.address!=address) continue;
        if (strlen(format)!=row.bytes) return 0;
        uint64_t hash=14695981039346656037ULL;
        for (const auto* p=reinterpret_cast<const unsigned char*>(format);*p;++p)
            hash=(hash^*p)*1099511628211ULL;
        return hash==row.hash ? row.arguments : 0;
    }
    return 0;
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
