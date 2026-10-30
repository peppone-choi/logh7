// Hooks only the verified tactical guidance lookups. Author: 최병호.
#pragma once
#include "tactical_notice_data.h"
static const unsigned tacticalNoticeCalls[] = {0x50e0cc, 0x50e0fb, 0x50eaf1, 0x50eb08, 0x50f6b3, 0x50f426,
    0x50ffa5, 0x50ffe3, 0x510363, 0x5104b2};
static const char* __fastcall tacticalNoticeLookup(void* table, void*, unsigned group, unsigned index) {
    const auto original = reinterpret_cast<const char*(__thiscall*)(void*, unsigned, unsigned)>(0x522010)
        (table, group, index);
    return logh::tacticalNoticeFallback(group, index, original, *reinterpret_cast<const BYTE*>(0x221669c));
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
