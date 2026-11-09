// Hooks only the verified tactical guidance lookups. Author: Peppone Choi.
#pragma once
#include "tactical_notice_data.h"
#include "korean_text.h"
#include <intrin.h>
static const unsigned tacticalNoticeCalls[] = {0x50e0cc, 0x50e0fb, 0x50eaf1, 0x50eb08, 0x50f6b3, 0x50f426,
    0x50ffa5, 0x50ffe3, 0x510363, 0x5104b2,
    // update04 mission prompts (state 0x1a): group 118 indices 14..19 are absent from the CD.
    0x50f096, 0x50f0b0, 0x50f0e2, 0x50f111, 0x50f128, 0x50f157,
    // Attack order sent (0050d230 state 7): 118/21 and 118/22, then the attack mode 118/25, 24 or 23.
    0x50f812, 0x50f83c, 0x50f87f, 0x50f896, 0x50f8ad,
    // Stance (004b4490): pick the base for 4 STATIONED 118/26 or 5 ANCHORED 118/27, then the request 118/32..35.
    0x50f9cd, 0x50fa06, 0x50fc01, 0x50fc1c, 0x50fc37, 0x50fc52,
    // 7 BATTLE refused for a selected unit the native unit table does not hold (118/28).
    0x50fa98,
    // 所属変更 target prompt (118/12) and the 鼓舞 request (118/43).
    0x50f2b2, 0x5100f4,
    // Ship-group tab: 鼓舞 request for fleet units (118/29) and 出撃 refused without stationed units (118/39).
    0x51008a, 0x50fcbb,
    // Fortress tab support sent (state 0x13 supply 118/2, state 0x12 repair 118/3; "%s\n%s" after the unit name)
    // and the ship tab's repair/supply target prompts (state 0xe 118/49, state 0xf 118/50).
    0x5105ce, 0x5105eb, 0x51072a, 0x510753,
    // Repair and supply responses (118/53, 54), then their completion records (118/55, 56).
    0x4c1416, 0x4c1516, 0x4c15c1, 0x4c169b,
    // Fortress tab 鼓舞 sent (state 0x14 after 004b4710): 118/44, the fortress counterpart of 118/43.
    0x51012c,
    // Fortress tab 補給 target prompt (state 0x13, 118/46), the supply twin of the repair prompt 0x510363 (118/47).
    0x51033a,
    // 0425 NotifyWarpedShip (004c1990): a departing unit matched a grid player's flagship (118/57, CD group has 0..6).
    0x4c19e0};
// 0x5104b2 (118/60) fills the base-support window's empty-list text, not the chat log, so the chat marker would show raw
// (seen 2026-11-03, work/fortress-tab-20261103): that one caller gets the catalogue's private CP932 form instead.
static const char* tacticalWindowNotice(unsigned index, const char* marker) {
    static std::string encoded;
    size_t count; const auto rows = logh::tacticalNotices(count);
    for (size_t i = 0; i < count; ++i) if (rows[i].index == index && rows[i].marker == marker) {
        std::wstring text = rows[i].text;
        while (!text.empty() && text.back() == L'\n') text.pop_back();
        return logh::encodeKoreanResource(text.c_str(), encoded) ? encoded.c_str() : marker;
    }
    return marker;
}
// Own supreme-commander flag from 033f (set in tactical_fortress_menu.h).
static volatile LONG ownSupremeCommander=0;
static const char* __fastcall tacticalNoticeLookup(void* table, void*, unsigned group, unsigned index) {
    const auto original = reinterpret_cast<const char*(__thiscall*)(void*, unsigned, unsigned)>(0x522010)
        (table, group, index);
    const auto text = logh::tacticalNoticeFallback(group, index, original, *reinterpret_cast<const BYTE*>(0x221669c));
    if (text != original && _ReturnAddress() == reinterpret_cast<void*>(0x5104b2 + 5)) return tacticalWindowNotice(index, text);
    return logh::tacticalMissionNotice(text, InterlockedCompareExchange(&ownSupremeCommander, 0, 0) != 0);
}
// Preserve the native dialog state machine and its confirm/cancel result.
// Only the withdrawal call is redirected; ordinary dialogs retain their text.
static int __fastcall tacticalRetreatConfirmation(void* manager, void*, unsigned kind, const char* title, const char* body) {
    const int result = reinterpret_cast<int(__thiscall*)(void*, unsigned, const char*, const char*)>(0x54ed00)
        (manager, kind, title, body);
    if (kind != 4 || result || !at<BYTE>(manager, 0x2c)) return result;
    const auto titleText = logh::tacticalRetreatText(61, title);
    const auto bodyText = logh::tacticalRetreatText(40, body);
    if (!titleText && !bodyText) return result;
    auto dialog = at<void*>(manager, 0x28);
    if (!dialog || !reinterpret_cast<BYTE(__thiscall*)(void*)>(0x56ebc0)(dialog)) return result;
    auto root = at<void*>(dialog, 8);
    const unsigned fields[] = {4, 5};
    const wchar_t* texts[] = {titleText, bodyText};
    if (root) for (unsigned i = 0; i < 2; ++i) {
        auto widget = at<void*>(dialog, 0xc + fields[i] * 4);
        if (widget && texts[i])
            reinterpret_cast<void(__thiscall*)(void*, void*, const wchar_t*)>(0x503560)(root, widget, texts[i]);
    }
    return result;
}
static void tacticalNoticeCall(unsigned address, BYTE (&bytes)[5], const void* function) {
    bytes[0] = 0xe8;
    const LONG offset = static_cast<LONG>(reinterpret_cast<const BYTE*>(function) - reinterpret_cast<const BYTE*>(address) - 5);
    memcpy(bytes + 1, &offset, sizeof(offset));
}
static bool verifyTacticalNotices() {
    BYTE expectedDialog[5];
    tacticalNoticeCall(0x50ff8a, expectedDialog, reinterpret_cast<const void*>(0x54ed00));
    if (memcmp(reinterpret_cast<const void*>(0x50ff8a), expectedDialog, sizeof(expectedDialog))) return false;
    for (unsigned address : tacticalNoticeCalls) {
        BYTE expected[5];
        tacticalNoticeCall(address, expected, reinterpret_cast<const void*>(0x522010));
        if (memcmp(reinterpret_cast<const void*>(address), expected, sizeof(expected))) return false;
    }
    return true;
}
static bool installTacticalNotices() {
    BYTE expectedDialog[5], dialogCall[5];
    tacticalNoticeCall(0x50ff8a, expectedDialog, reinterpret_cast<const void*>(0x54ed00));
    tacticalNoticeCall(0x50ff8a, dialogCall, reinterpret_cast<const void*>(&tacticalRetreatConfirmation));
    if (!patchBytes(reinterpret_cast<void*>(0x50ff8a), expectedDialog, dialogCall, sizeof(expectedDialog))) return false;
    for (unsigned address : tacticalNoticeCalls) {
        BYTE expected[5], replacement[5];
        tacticalNoticeCall(address, expected, reinterpret_cast<const void*>(0x522010));
        tacticalNoticeCall(address, replacement, reinterpret_cast<const void*>(&tacticalNoticeLookup));
        if (!patchBytes(reinterpret_cast<void*>(address), expected, replacement, sizeof(expected))) return false;
    }
    return true;
}
