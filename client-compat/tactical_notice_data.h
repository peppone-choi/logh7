// Missing tactical command guidance. Author: 최병호.
#pragma once
#include "unicode_text.h"
#include <cstring>

namespace logh {
struct TacticalNotice { unsigned index; const char* marker; const wchar_t* text; };
inline const TacticalNotice* tacticalNotices(size_t& count) {
    static const TacticalNotice rows[] = {
        {8, "LOGH7_TACTICAL_SELECT", L"명령할 함선을 선택하세요.\n"},
        {9, "LOGH7_TACTICAL_COMMAND", L"명령 버튼을 선택하세요.\n"},
        {10, "LOGH7_TACTICAL_DESTINATION", L"이동할 위치를 지정하세요.\n"},
        {11, "LOGH7_TACTICAL_WAYPOINT", L"경유지를 추가하거나 이동 경로를 확정하세요.\n"},
    };
    count = sizeof(rows) / sizeof(rows[0]);
    return rows;
}
inline const char* tacticalNoticeFallback(unsigned group, unsigned index, const char* original) {
    if (group != 118 || !original || (strcmp(original, "NO DATA") && strcmp(original, "NO TABLE"))) return original;
    size_t count; const auto rows = tacticalNotices(count);
    for (size_t i = 0; i < count; ++i) if (rows[i].index == index) return rows[i].marker;
    return original;
}
// Native CString formatting may add colour tokens, names or punctuation before
// appending the fragment. Decode those as CP932 and substitute only our markers.
inline bool tacticalNoticeFragment(const char* fragment, std::wstring& decoded) {
    if (!fragment) return false;
    size_t count; const auto rows = tacticalNotices(count);
    std::wstring result;
    const char* cursor = fragment;
    bool found = false;
    while (*cursor) {
        const char* next = nullptr;
        const TacticalNotice* selected = nullptr;
        for (size_t i = 0; i < count; ++i) {
            const auto candidate = strstr(cursor, rows[i].marker);
            if (candidate && (!next || candidate < next)) { next = candidate; selected = &rows[i]; }
        }
        if (!next) break;
        std::wstring prefix;
        const std::string narrow(cursor, static_cast<size_t>(next - cursor));
        if (!japaneseFragment(narrow.c_str(), prefix)) return false;
        result += prefix;
        result += selected->text;
        cursor = next + strlen(selected->marker);
        found = true;
    }
    if (!found) return false;
    std::wstring suffix;
    if (!japaneseFragment(cursor, suffix)) return false;
    result += suffix;
    decoded.swap(result);
    return true;
}
}
