// Hooks only the four verified tactical guidance lookups. Author: 최병호.
#pragma once
#include "tactical_notice_data.h"
static const unsigned tacticalNoticeCalls[] = {0x50e0cc, 0x50e0fb, 0x50eaf1, 0x50eb08};
static const char* __fastcall tacticalNoticeLookup(void* table, void*, unsigned group, unsigned index) {
    const auto original = reinterpret_cast<const char*(__thiscall*)(void*, unsigned, unsigned)>(0x522010)
        (table, group, index);
    return logh::tacticalNoticeFallback(group, index, original);
}
static void tacticalNoticeCall(unsigned address, BYTE (&bytes)[5], const void* function) {
    bytes[0] = 0xe8;
    const LONG offset = static_cast<LONG>(reinterpret_cast<const BYTE*>(function) - reinterpret_cast<const BYTE*>(address) - 5);
    memcpy(bytes + 1, &offset, sizeof(offset));
}
static bool verifyTacticalNotices() {
    for (unsigned address : tacticalNoticeCalls) {
        BYTE expected[5];
        tacticalNoticeCall(address, expected, reinterpret_cast<const void*>(0x522010));
        if (memcmp(reinterpret_cast<const void*>(address), expected, sizeof(expected))) return false;
    }
    return true;
}
static bool installTacticalNotices() {
    for (unsigned address : tacticalNoticeCalls) {
        BYTE expected[5], replacement[5];
        tacticalNoticeCall(address, expected, reinterpret_cast<const void*>(0x522010));
        tacticalNoticeCall(address, replacement, reinterpret_cast<const void*>(&tacticalNoticeLookup));
        if (!patchBytes(reinterpret_cast<void*>(address), expected, replacement, sizeof(expected))) return false;
    }
    return true;
}
