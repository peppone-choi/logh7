// Missing tactical command guidance. Author: Peppone Choi.
#pragma once
#include "unicode_text.h"
#include <cstring>

namespace logh {
inline bool missingTacticalText(const char* text) {
    return text && (!strcmp(text, "NO DATA") || !strcmp(text, "NO TABLE"));
}
inline const wchar_t* tacticalRetreatText(unsigned field, const char* original) {
    if (!missingTacticalText(original)) return nullptr;
    if (field == 61) return L"철수 확인";
    if (field == 40) return L"선택한 함선에 철수 명령을 내리겠습니까?";
    return nullptr;
}
struct TacticalNotice { unsigned index; unsigned motion; const char* marker; const wchar_t* text; };
inline const TacticalNotice* tacticalNotices(size_t& count) {
    static const TacticalNotice rows[] = {
        {8, 0, "LOGH7_TACTICAL_SELECT", L"명령할 함선을 선택하세요.\n"},
        {9, 0, "LOGH7_TACTICAL_COMMAND", L"명령 버튼을 선택하세요.\n"},
        {10, 3, "LOGH7_TACTICAL_TURN", L"선회할 방향을 지정하세요.\n"},
        {10, 4, "LOGH7_TACTICAL_PARALLEL", L"평행 이동할 위치를 지정하세요.\n"},
        {10, 0, "LOGH7_TACTICAL_DESTINATION", L"이동할 위치를 지정하세요.\n"},
        {11, 0, "LOGH7_TACTICAL_WAYPOINT", L"경유지를 추가하거나 이동 경로를 확정하세요.\n"},
        {13, 0, "LOGH7_TACTICAL_TARGET", L"공격할 대상을 선택하세요.\n"},
        {14, 0, "LOGH7_TACTICAL_MISSION_SKIRMISH", L"유격 임무: 대상 적 유닛을 선택하세요.\n"},
        {15, 0, "LOGH7_TACTICAL_MISSION_DEFEND", L"방위 임무: 지킬 아군 행성·요새를 선택하세요.\n"},
        {16, 0, "LOGH7_TACTICAL_MISSION_OCCUPY", L"점령 임무: 점령할 적 행성·요새를 선택하세요.\n"},
        {17, 0, "LOGH7_TACTICAL_MISSION_INTERCEPT", L"요격 임무: 격파할 적 유닛을 선택하세요.\n"},
        {18, 0, "LOGH7_TACTICAL_MISSION_RECON", L"정찰 임무: 정찰할 적 유닛을 선택하세요.\n"},
        {19, 0, "LOGH7_TACTICAL_MISSION_RETREAT", L"철수 임무를 지시했습니다.\n"},
        // Not a group-118 index: a non-chief's 0421 is a 具申 (the server keeps it as a proposal).
        {1019, 0, "LOGH7_TACTICAL_MISSION_PROPOSED", L"철수 임무를 구신했습니다.\n"},
        {20, 0, "LOGH7_TACTICAL_FIRE_STOP", L"공격 중지 명령을 요청했습니다.\n"},
        {21, 0, "LOGH7_TACTICAL_ATTACK_REQUEST", L"공격 명령을 요청했습니다.\n"},
        {22, 0, "LOGH7_TACTICAL_ATTACK_AUTO", L"사정 안의 적을 자동으로 공격합니다.\n"},
        {23, 0, "LOGH7_TACTICAL_ATTACK_MODE_C", L"공격 방식을 지정했습니다.\n"},
        {24, 0, "LOGH7_TACTICAL_ATTACK_MODE_B", L"공격 방식을 지정했습니다.\n"},
        {25, 0, "LOGH7_TACTICAL_ATTACK_MODE_A", L"공격 방식을 지정했습니다.\n"},
        {26, 0, "LOGH7_TACTICAL_STANCE_STATION_BASE", L"주류할 아군 거점을 선택하세요.\n"},
        {27, 0, "LOGH7_TACTICAL_STANCE_ANCHOR_BASE", L"정박할 아군 거점을 선택하세요.\n"},
        {28, 0, "LOGH7_TACTICAL_STANCE_REFUSED", L"선택한 유닛은 지금 이 태세로 바꿀 수 없습니다.\n"},
        {12, 0, "LOGH7_TACTICAL_TRANSFER_SELECT", L"소속을 옮길 부대를 선택하세요.\n"},
        {43, 0, "LOGH7_TACTICAL_ENCOURAGE_REQUEST", L"고무 명령을 요청했습니다.\n"},
        {29, 0, "LOGH7_TACTICAL_FLEET_ENCOURAGE", L"함정 고무 명령을 요청했습니다.\n"},
        {39, 0, "LOGH7_TACTICAL_SORTIE_NONE", L"출격할 주류 함정이 없습니다.\n"},
        {32, 0, "LOGH7_TACTICAL_STANCE_STATIONED", L"주류 태세로 전환을 요청했습니다.\n"},
        {33, 0, "LOGH7_TACTICAL_STANCE_SAILING", L"항행 태세로 전환을 요청했습니다.\n"},
        {34, 0, "LOGH7_TACTICAL_STANCE_ANCHORED", L"정박 태세로 전환을 요청했습니다.\n"},
        {35, 0, "LOGH7_TACTICAL_STANCE_BATTLE", L"전투 태세로 전환을 요청했습니다.\n"},
        {41, 0, "LOGH7_TACTICAL_RETREAT_CANCEL", L"철수 명령을 취소했습니다.\n"},
        {42, 0, "LOGH7_TACTICAL_RETREAT_REQUEST", L"철수 명령을 요청했습니다.\n"},
        {2, 0, "LOGH7_TACTICAL_BASE_SUPPLY_SENT", L"보급을 요청했습니다.\n"},
        {3, 0, "LOGH7_TACTICAL_BASE_REPAIR_SENT", L"수리를 요청했습니다.\n"},
        {44, 0, "LOGH7_TACTICAL_FORTRESS_ENCOURAGE", L"요새 고무 명령을 요청했습니다.\n"},
        {49, 0, "LOGH7_TACTICAL_REPAIR_SELECT", L"수리할 함정을 선택하세요.\n"},
        {50, 0, "LOGH7_TACTICAL_SUPPLY_SELECT", L"보급할 함정을 선택하세요.\n"},
        {46, 0, "LOGH7_TACTICAL_BASE_SUPPLY_SELECT", L"보급할 주둔 함정을 목록에서 선택하세요.\n"},
        {47, 0, "LOGH7_TACTICAL_BASE_SUPPORT_SELECT", L"수리할 주둔 함정을 목록에서 선택하세요.\n"},
        {60, 0, "LOGH7_TACTICAL_BASE_SUPPORT_EMPTY", L"이 거점에 주둔한 함정이 없습니다.\n"},
    };
    count = sizeof(rows) / sizeof(rows[0]);
    return rows;
}
inline const char* tacticalMissionNotice(const char* marker, bool chief) {
    return !chief && marker && !strcmp(marker, "LOGH7_TACTICAL_MISSION_RETREAT") ? "LOGH7_TACTICAL_MISSION_PROPOSED" : marker;
}
inline const char* tacticalNoticeFallback(unsigned group, unsigned index, const char* original, unsigned motion = 0) {
    if (group != 118 || !missingTacticalText(original)) return original;
    size_t count; const auto rows = tacticalNotices(count);
    for (size_t i = 0; i < count; ++i)
        if (rows[i].index == index && rows[i].motion && rows[i].motion == motion) return rows[i].marker;
    for (size_t i = 0; i < count; ++i)
        if (rows[i].index == index && !rows[i].motion) return rows[i].marker;
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
