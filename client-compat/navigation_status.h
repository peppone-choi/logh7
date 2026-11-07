// Display the server's accepted docking deadline; never predict arrival locally.
// Author: Peppone Choi.
#pragma once
#include "navigation_hud_data.h"
#include "country_metadata.h"

static struct DockingStatus {
    void* world;
    unsigned character, target;
    unsigned long long deadline;
} dockingStatus = {};
// A reserved warp (design 2026-10-30): the empty native ACK handler 004bea90 receives
// the server's wait; 004bee20 (0b07) ends it with the actual arrival or the origin.
static struct WarpStatus {
    void* world;
    unsigned character, grid;
    unsigned long long deadline;
} warpStatus = {};

static void refreshNavigationHud(BYTE* world,BYTE* own) {
    unsigned character=at<unsigned>(own,0x24);
    if(at<BYTE>(world,0x3579cc) ||
        !((dockingStatus.world==world && dockingStatus.character==character) ||
          (warpStatus.world==world && warpStatus.character==character)))return;
    // The command wait stops 0058ee70; the existing HUD and its bars still draw.
    auto hud=reinterpret_cast<BYTE*>(0x22225c8);
    auto root=at<void*>(hud,0);
    if(!root || !at<BYTE>(root,4))return;
    unsigned now=reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4c53b0)(world);
    unsigned pcp=at<unsigned>(own,0x74),mcp=at<unsigned>(own,0x78);
    wchar_t cp[32],date[96];
    unsigned country=runtimeOwnCountry(world,character,at<BYTE>(own,0x28));
    if(!runtimeNavigationHud(now,pcp,mcp,country,cp,date))return;
    static void* previousRoot=nullptr;
    static unsigned previousMinute=~0u,previousPcp=~0u,previousMcp=~0u,previousCountry=~0u;
    if(root==previousRoot && now/60==previousMinute && pcp==previousPcp && mcp==previousMcp && country==previousCountry)return;
    auto text=reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560);
    text(root,at<void*>(hud,4+8*4),cp);
    text(root,at<void*>(hud,4+11*4),date);
    previousRoot=root;previousMinute=now/60;previousPcp=pcp;previousMcp=mcp;previousCountry=country;
}

static void __fastcall setHudCalendarText(void* root,void*,void* widget,const char* original) {
    reinterpret_cast<void(__thiscall*)(void*,void*,const char*)>(0x5034d0)(root,widget,original);
    auto world=*reinterpret_cast<BYTE**>(0x7ccffc);
    auto own=world ? at<BYTE*>(world,8) : nullptr;
    if(!own || !at<BYTE>(own,0))return;
    wchar_t cp[32],date[96];
    unsigned now=reinterpret_cast<unsigned(__thiscall*)(void*)>(0x4c53b0)(world);
    if(runtimeNavigationHud(now,at<unsigned>(own,0x74),at<unsigned>(own,0x78),runtimeOwnCountry(world,at<unsigned>(own,0x24),at<BYTE>(own,0x28)),cp,date))
        reinterpret_cast<void(__thiscall*)(void*,void*,const wchar_t*)>(0x503560)(root,widget,date);
}

static void __fastcall acceptedWarp(void* world, void*, void* message, int result) {
    if (!result && world && message) {
        unsigned wait = at<unsigned>(message, 4), grid = at<unsigned short>(message, 0x18);
        unsigned short card = at<unsigned short>(message, 12);
        if ((card == 59 || card == 195) && at<unsigned>(message, 8) && wait && grid < 5000)
            warpStatus = {world, at<unsigned>(message, 8), grid,
                static_cast<unsigned long long>(at<unsigned>(message, 0)) + wait};
    }
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x4bea90)(world, message, result);
}
static void __fastcall movedGrid(void* world, void*, void* message) {
    if (warpStatus.world == world && at<unsigned>(message, 4) == warpStatus.character)
        warpStatus = {};
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x4bee20)(world, message);
}

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
static void drawWarpStatus(BYTE* world, BYTE* own) {
    if (!warpStatus.grid && !warpStatus.character) return;
    if (warpStatus.world != world || !own || !at<BYTE>(own, 0) || warpStatus.character != at<unsigned>(own, 0x24)) {
        warpStatus = {};
        return;
    }
    if (at<BYTE>(world, 0x3579cc)) return;
    auto display = *reinterpret_cast<BYTE**>(0x7c1b4c);
    auto clock = *reinterpret_cast<BYTE**>(0x7cd028);
    if (!display || !clock || !at<unsigned>(display, 0x2a418)) return;
    unsigned now = at<unsigned>(clock, 0x10);
    auto remaining = warpStatus.deadline > now ? warpStatus.deadline - now : 0;
    wchar_t title[64], time[96];
    // The native HUD counts grids from 1 (2016 -> "17, 21").
    _snwprintf_s(title, _countof(title), _TRUNCATE, L"워프 대기: %u, %u 그리드", warpStatus.grid % 100 + 1, warpStatus.grid / 100 + 1);
    if (remaining) _snwprintf_s(time, _countof(time), _TRUNCATE,
        L"남은 게임 시간: %02llu:%02llu:%02llu", remaining / 3600, remaining / 60 % 60, remaining % 60);
    else wcscpy_s(time, L"워프 실행 확인 중");
    HudFrame frame; // Client-space panel anchor, mapped to the text render target like the native panel.
    if (!hudFrame(frame)) return;
    const int x = hudRenderX(frame, frame.width - 205);
    auto y = [&](int fromBottom) { return hudRenderY(frame, frame.height - fromBottom); };
    reinterpret_cast<void(__thiscall*)(void*, float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48), 1.0f);
    auto draw = reinterpret_cast<void(__cdecl*)(void*, void*, const wchar_t*, int, int, unsigned, int)>(0x4e8af0);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), title, x, y(112), 0xffffdf70, 6);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), time, x, y(96), 0xffffdf70, 6);
}
static unsigned unreadPersonalMail(BYTE* world, unsigned owner);
// "새 편지 N통" above the HUD mailbox button while the personal inbox holds unread rows (mail_input.h).
static void drawMailNotice(BYTE* world, BYTE* own) {
    if (!own || !at<BYTE>(own, 0) || at<BYTE>(world, 0x3579cc)) return;
    unsigned unread = unreadPersonalMail(world, at<unsigned>(own, 0x24));
    auto display = *reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!unread || !display || !at<unsigned>(display, 0x2a418)) return;
    HudFrame frame; // Client-space panel anchor, mapped to the text render target like the native panel.
    if (!hudFrame(frame)) return;
    const int x = hudRenderX(frame, frame.width - 205);
    auto y = [&](int fromBottom) { return hudRenderY(frame, frame.height - fromBottom); };
    wchar_t text[32];
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"새 편지 %u통", unread);
    reinterpret_cast<void(__thiscall*)(void*, float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48), 1.0f);
    reinterpret_cast<void(__cdecl*)(void*, void*, const wchar_t*, int, int, unsigned, int)>(0x4e8af0)(
        at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), text, x, y(80), 0xffffdf70, 6);
}
static void drawDockingStatus() {
    auto world = *reinterpret_cast<BYTE**>(0x7ccffc);
    auto own = world ? at<BYTE*>(world, 8) : nullptr;
    if (!world || !at<BYTE>(world, 0x2a58f8)) return;
    if(own && at<BYTE>(own,0))refreshNavigationHud(world,own);
    drawMailNotice(world, own);
    drawWarpStatus(world, own); // A warp reservation and a dock reservation never coexist.
    if (dockingStatus.world != world) {
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
    HudFrame frame; // Client-space panel anchor, mapped to the text render target like the native panel.
    if (!hudFrame(frame)) return;
    const int x = hudRenderX(frame, frame.width - 205);
    auto y = [&](int fromBottom) { return hudRenderY(frame, frame.height - fromBottom); };
    // Use the same UTF-16 glyph queue as native HUD text, below the location line.
    reinterpret_cast<void(__thiscall*)(void*, float)>(0x4b0780)(reinterpret_cast<void*>(0xbffa48), 1.0f);
    auto draw = reinterpret_cast<void(__cdecl*)(void*, void*, const wchar_t*, int, int, unsigned, int)>(0x4e8af0);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), title, x, y(112), 0xffffdf70, 6);
    draw(at<void*>(display, 0x2a418), reinterpret_cast<void*>(0xbffa48), time, x, y(96), 0xffffdf70, 6);
}
static bool installNavigationStatus() {
    const unsigned calls[] = {0x4bcbcc, 0x4bcfad, 0x4bcc1d, 0x4bcf79,0x58f0c6};
    const BYTE expected[][5] = {{0xe8,0xaf,0x8b,0,0}, {0xe8,0xae,0x1e,0,0}, {0xe8,0x6e,0x1e,0,0}, {0xe8,0xa2,0x1e,0,0},{0xe8,0x05,0x44,0xf7,0xff}};
    const void* functions[] = {&acceptedDock, &arrivedDock, &acceptedWarp, &movedGrid,&setHudCalendarText};
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
