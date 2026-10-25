// Display the server's accepted docking deadline; never predict arrival locally.
// Author: 최병호.
#pragma once

static struct DockingStatus {
    void* world;
    unsigned character, target;
    unsigned long long deadline;
} dockingStatus = {};

static void __fastcall acceptedDock(void* world, void*, void* message, int result) {
    // Bootstrap can deliver the ACK before the local character pointer is bound.
    // Retain the decoded record and validate ownership when the HUD becomes ready.
    if (!result && world && message && !at<BYTE>(world, 0x3579cc)) {
        unsigned target = at<unsigned>(message, 24), wait = at<unsigned>(message, 4);
        unsigned short card = at<unsigned short>(message, 12);
        if ((card == 59 || card == 195) && at<unsigned>(message, 8) && pickerBase(target) && wait &&
            at<unsigned short>(message, 28) == 4) {
            dockingStatus = {world, at<unsigned>(message, 8), target,
                static_cast<unsigned long long>(at<unsigned>(message, 0)) + wait};
        }
    }
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x4c5780)(world, message, result);
}
static void __fastcall arrivedDock(void* world, void*, void* message) {
    if (dockingStatus.world == world && at<unsigned>(message, 4) == dockingStatus.character)
        dockingStatus = {};
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x4bee60)(world, message);
}
static void drawDockingStatus() {
    auto world = *reinterpret_cast<BYTE**>(0x7ccffc);
    auto own = world ? at<BYTE*>(world, 8) : nullptr;
    if (!world || dockingStatus.world != world) {
        dockingStatus = {};
        return;
    }
    if (!at<BYTE>(world, 0x2a58f8)) return;
    if (!own || !at<BYTE>(own, 0) || dockingStatus.character != at<unsigned>(own, 0x24)) {
        dockingStatus = {};
        return;
    }
    if (!dockingStatus.target || at<BYTE>(world, 0x3579cc)) return;
    auto display = *reinterpret_cast<BYTE**>(0x7c1b4c);
    auto clock = *reinterpret_cast<BYTE**>(0x7cd028);
    auto base = pickerBase(dockingStatus.target);
    if (!display || !clock || !base || !at<unsigned>(display, 0x2a418) ||
        logh::infoShort(base, 0x10) != at<unsigned>(own, 0x320)) return;
    unsigned now = at<unsigned>(clock, 0x10);
    auto remaining = dockingStatus.deadline > now ? dockingStatus.deadline - now : 0;
    wchar_t name[32] = {}, title[64], time[96];
    pickerName(dockingStatus.target, name);
    _snwprintf_s(title, _countof(title), _TRUNCATE, L"기항: %ls", name);
    if (remaining) _snwprintf_s(time, _countof(time), _TRUNCATE,
        L"남은 게임 시간: %02llu:%02llu:%02llu",
        remaining / 3600, remaining / 60 % 60, remaining % 60);
    else wcscpy_s(time, L"도착 확인 중");
    int width = at<int>(display, 0x2a604), height = at<int>(display, 0x2a608);
    if (width < 743 || width > 4096 || height < 600 || height > 4096) return;
    // Use the same UTF-16 glyph queue as native HUD text, below the location line.
    reinterpret_cast<void(__thiscall*)(void*, float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48), 1.0f);
    auto draw = reinterpret_cast<void(__cdecl*)(void*, void*, const wchar_t*, int, int, unsigned, int)>(0x4e8af0);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), title, width - 205, height - 112, 0xffffdf70, 6);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), time, width - 205, height - 96, 0xffffdf70, 6);
}
static bool installNavigationStatus() {
    const unsigned calls[] = {0x4bcbcc, 0x4bcfad};
    const BYTE expected[][5] = {{0xe8,0xaf,0x8b,0,0}, {0xe8,0xae,0x1e,0,0}};
    const void* functions[] = {&acceptedDock, &arrivedDock};
    for (unsigned i = 0; i < _countof(calls); ++i)
        if (memcmp(reinterpret_cast<void*>(calls[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < _countof(calls); ++i) {
        BYTE replacement[5] = {0xe8};
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(reinterpret_cast<const BYTE*>(functions[i]) -
            reinterpret_cast<const BYTE*>(calls[i]) - 5);
        if (!patchBytes(reinterpret_cast<void*>(calls[i]), expected[i], replacement, 5)) return false;
    }
    return true;
}
