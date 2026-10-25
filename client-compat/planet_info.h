// Restore the CD's empty planet/fortress information menu using its own GUI.
// All quantities come from fresh decoded server responses. Author: 최병호.
#pragma once
#include <stdio.h>
#include <stdlib.h>
#include "planet_info_data.h"

static struct PlanetInformation {
    void* world;
    void* popup;
    unsigned character, grid, ids[4], count, index, page;
    unsigned originalLabels[4];
    int originalBodyHeight;
    bool conditionsReady, warehouseReady, displayed, advancePending;
    ULONGLONG requestedAt, acceptAfter;
    BYTE condition[0x180], warehouse[0x300];
} planetInformation = {};

static void closePlanetInformation() {
    if (planetInformation.popup) {
        const unsigned offsets[] = {0x1c, 0x20, 0x24, 0x28};
        for (unsigned i = 0; i < 4; ++i) {
            auto element = at<void*>(planetInformation.popup, offsets[i]);
            if (!element) continue;
            auto text = static_cast<BYTE*>(element) + 0xb04;
            reinterpret_cast<void(__cdecl*)(void*)>(0x4ea880)(text);
            at<unsigned>(text, 0) = planetInformation.originalLabels[i];
        }
        auto body = at<void*>(planetInformation.popup, 0x20);
        if (body) at<int>(body, 0x30) = planetInformation.originalBodyHeight;
        reinterpret_cast<int(__thiscall*)(void*, int)>(0x570340)(planetInformation.popup, 1);
    }
    planetInformation = {};
}

// Use the original UTF-16 text pool. Its ANSI wrapper forces the Japanese CRT
// locale and would misdecode Korean labels on a Korean Windows host.
static void planetLabel(void* popup, unsigned offset, const wchar_t* label) {
    auto element = at<void*>(popup, offset);
    if (!element || !at<BYTE>(element, 8)) return;
    reinterpret_cast<void(__cdecl*)(void*, const wchar_t*, int)>(0x4ea8b0)
        (static_cast<BYTE*>(element) + 0xb04, label, -1);
}

static void fitPlanetBody() {
    auto popup = planetInformation.popup;
    if (!popup) return;
    // The CD expands its frame but leaves the body clipping rectangle at 96px.
    // Restore it on close because the command dialog shares this native slot.
    auto body = at<void*>(popup, 0x20);
    float measured = at<float>(popup, 0xdd4);
    if (body && _finite(measured) && measured >= 0 && measured <= 400) {
        int height = static_cast<int>(ceilf(measured)) + 16;
        at<int>(body, 0x30) = height > planetInformation.originalBodyHeight ? height : planetInformation.originalBodyHeight;
    }
}

static unsigned planetStockPages() {
    auto& info = planetInformation;
    return info.warehouseReady ? (info.warehouse[12] + info.warehouse[0x260] + 8) / 9 : 0;
}
static void planetText(const wchar_t* text) {
    auto popup = planetInformation.popup;
    planetLabel(popup, 0x1c, L"행성·요새 정보");
    planetLabel(popup, 0x20, text);
    planetLabel(popup, 0x24, planetInformation.page < planetStockPages() ?
        (planetInformation.page ? L"다음 재고" : L"재고 보기") :
        planetInformation.count > 1 ? L"다음 행성" : planetInformation.count ? L"새로고침" : L"확인");
    planetLabel(popup, 0x28, L"닫기");
    reinterpret_cast<void(__thiscall*)(void*)>(0x570740)(popup);
    fitPlanetBody();
}

static void requestPlanetInformation() {
    auto& info = planetInformation;
    info.conditionsReady = info.warehouseReady = info.displayed = false;
    info.page = 0;
    info.requestedAt = GetTickCount64();
    info.acceptAfter = info.requestedAt + 200;
    memset(info.condition, 0, sizeof(info.condition));
    memset(info.warehouse, 0, sizeof(info.warehouse));
    // Native confirm dialogs close after an accepted choice. Reopen the same
    // slot only after release, rather than reusing a still-pressed button.
    reinterpret_cast<int(__thiscall*)(void*, int)>(0x570340)(info.popup, 4);
    at<int>(info.popup, 0xde0) = 1;
    planetText(L"서버에서 정보를 조회하고 있습니다.");
    // Preserve the original network serializer and encryption. No CP command.
    struct Query { BYTE count, padding[3]; unsigned ids[4]; } query = {};
    query.count = 1; query.ids[0] = info.ids[info.index];
    auto send = reinterpret_cast<unsigned(__thiscall*)(void*, char, unsigned, const void*)>(0x4b78a0);
    send(info.world, 0, 0x21, &query); // 031e -> 031f conditions
    unsigned warehouse[] = {query.ids[0], 0};
    send(info.world, 0, 0x17, warehouse); // 0326 -> 0327 warehouse
}

// Both callbacks run in the native world dispatcher, after its validated decode
// and copy, on the game thread. Never reuse an earlier query cache on opening.
static void __cdecl receivedPlanetConditions(void* world) {
    auto& info = planetInformation;
    if (!info.popup || info.world != world || !info.count) return;
    auto record = logh::findPlanetCondition(static_cast<BYTE*>(world) + 0x3facf4, info.ids[info.index], info.grid);
    if (record) { memcpy(info.condition, record, sizeof(info.condition)); info.conditionsReady = true; info.displayed = false; }
}
static unsigned conditionsResume = 0x4bdd33;
static void __declspec(naked) planetConditionsCopied() {
    __asm {
        pushfd
        pushad
        push eax
        call receivedPlanetConditions
        add esp, 4
        popad
        popfd
        jmp dword ptr [conditionsResume]
    }
}
static void __fastcall receivedPlanetWarehouse(void* world, void*, void* message) {
    auto& info = planetInformation;
    if (info.popup && info.world == world && info.count &&
        logh::matchingPlanetWarehouse(static_cast<BYTE*>(message), info.ids[info.index])) {
        memcpy(info.warehouse, message, sizeof(info.warehouse));
        info.warehouseReady = true;
        info.displayed = false;
    }
    reinterpret_cast<void(__thiscall*)(void*, void*)>(0x4bef50)(world, message);
}

static void showPlanetInformation() {
    auto& info = planetInformation;
    unsigned id = info.ids[info.index];
    wchar_t title[13] = {};
    // The 031d catalogue already contains the bounded Unicode name. Avoid the
    // native ANSI name formatter, which introduces another code-page conversion.
    for (unsigned slot = 0; slot < 350; ++slot) {
        if (at<unsigned>(info.world, 0x2eb288 + slot * 4) != id) continue;
        memcpy(title, static_cast<BYTE*>(info.world) + 0x2eb800 + slot * 0x250 + 0x48, 12 * sizeof(wchar_t));
        break;
    }
    const BYTE* condition = info.condition;
    unsigned power = condition[4];
    const wchar_t* powerName = power == 2 ? L"은하제국" : power == 3 ? L"자유행성동맹" : power == 0 ? L"중립" : L"기타 세력";
    wchar_t text[1024] = {};
    if (info.page) {
        _snwprintf_s(text, _countof(text), _TRUNCATE, L"%ls - 함선·인원 재고 (%u/%u)\n", title, info.page, planetStockPages());
        unsigned shipCount = info.warehouse[12], troopCount = info.warehouse[0x260];
        unsigned first = (info.page - 1) * 9, last = first + 9;
        for (unsigned row = first; row < last && row < shipCount + troopCount; ++row) {
            bool ship = row < shipCount;
            const BYTE* record = info.warehouse + (ship ? 14 + row * 6 : 0x262 + (row - shipCount) * 6);
            unsigned kind = logh::infoShort(record, 0), level = record[2], quantity = logh::infoShort(record, 4);
            // Native crew catalogue IDs start at 1000; constmsg 86 uses local IDs.
            if (!ship && kind >= 1000 && kind < 1016) kind -= 1000;
            wchar_t name[64] = {}, line[128];
            if (kind < (ship ? 179u : 11u)) {
                auto label = reinterpret_cast<const char*(__thiscall*)(void*, int, int)>(0x522010)
                    (reinterpret_cast<void*>(0x2217400), ship ? 85 : 86, kind);
                if (label) MultiByteToWideChar(932, 0, label, -1, name, _countof(name));
            }
            if (!name[0]) _snwprintf_s(name, _countof(name), _TRUNCATE, L"미등록 품목 %u", kind);
            const wchar_t* levels[] = {L"그린", L"노멀", L"베테랑", L"엘리트"};
            const wchar_t* rank = level < 4 ? levels[level] : L"?";
            const BYTE* specification = static_cast<const BYTE*>(info.world) + 0x2c1a78 + kind * 0x2a8;
            if (ship && kind < 200 && specification[0] && logh::infoShort(specification, 0x218)) {
                unsigned count = quantity * logh::infoShort(specification, 0x218);
                _snwprintf_s(line, _countof(line), _TRUNCATE, L"함선 %ls: %u 유닛 (%u척)\n", name, quantity, count);
            } else {
                _snwprintf_s(line, _countof(line), _TRUNCATE, L"%ls %ls %ls: %u 유닛\n", ship ? L"함선" : L"인원", name, rank, quantity);
            }
            wcscat_s(text, line);
        }
        planetText(text); info.displayed = true; return;
    }
    _snwprintf_s(text, sizeof(text) / sizeof(text[0]), _TRUNCATE,
        L"(%u/%u) %ls\n소속: %ls\n대기: %ls  거주: %ls\n"
        L"인구: %u  성인: %u\n세율: %u%%  민심: %u  치안: %u\n군수물자: %u\n식량: %u  광물: %u",
        info.index + 1, info.count, title, powerName,
        condition[0x174] ? L"있음" : L"없음", condition[0x175] ? L"가능" : L"불가",
        logh::infoWord(condition, 0x120), logh::infoWord(condition, 0x124), logh::infoShort(condition, 0x128),
        logh::infoShort(condition, 0x150), logh::infoShort(condition, 0x152),
        logh::infoWord(info.warehouse, 0x2f4), logh::infoWord(info.warehouse, 0x2f8), logh::infoWord(info.warehouse, 0x2fc));
    planetText(text);
    info.displayed = true;
}

static int __cdecl planetInfoMenu(void* gui) {
    auto& info = planetInformation;
    void* world = *reinterpret_cast<void**>(0x7ccffc);
    if (info.popup) {
        void* character = world ? at<void*>(world, 8) : nullptr;
        if (world != info.world || !character || !at<BYTE>(character, 0) || !at<BYTE>(world, 0x2a58f8) ||
            at<BYTE>(world, 0x3579cc) || !at<BYTE>(world, 0x2b6a6c) ||
            at<unsigned>(character, 0x24) != info.character || at<unsigned>(world, 0x2b6a70) != info.grid) {
            closePlanetInformation();
        } else {
            reinterpret_cast<void(__thiscall*)(void*)>(0x56f960)(info.popup);
            int selection = at<int>(info.popup, 0xde0);
            if (info.advancePending) {
                // GetKeyState reads this game thread's message queue, never the
                // active desktop's asynchronous global keyboard state.
                if (!(GetKeyState(VK_LBUTTON) & 0x8000) && !(GetKeyState(VK_RETURN) & 0x8000)) {
                    info.advancePending = false;
                    if (info.page < planetStockPages()) {
                        ++info.page;
                        reinterpret_cast<int(__thiscall*)(void*, int)>(0x570340)(info.popup, 4);
                        at<int>(info.popup, 0xde0) = 1;
                        info.acceptAfter = GetTickCount64() + 200;
                        showPlanetInformation();
                    } else {
                        info.index = (info.index + 1) % info.count;
                        requestPlanetInformation();
                    }
                }
            } else if (GetTickCount64() < info.acceptAfter) {
                reinterpret_cast<int(__thiscall*)(void*, int)>(0x570340)(info.popup, 4);
                at<int>(info.popup, 0xde0) = 1;
            } else if (selection == 4 || (selection == 3 && !info.count)) closePlanetInformation();
            else if (selection == 3 && info.count) {
                info.advancePending = true;
            } else if (!info.displayed && info.count) {
                if (info.conditionsReady && info.warehouseReady) showPlanetInformation();
                else if (GetTickCount64() - info.requestedAt >= 5000) {
                    planetText(L"서버 정보를 받지 못했습니다.\n다시 조회하거나 창을 닫아 주세요.");
                    info.displayed = true;
                }
            }
            fitPlanetBody(); // The native animation tick restores its fixed 96px clip every frame.
            return -1;
        }
    }
    int selected = reinterpret_cast<int(__cdecl*)(void*)>(0x54be80)(gui);
    if (selected != 6 || !world || !at<BYTE>(world, 0x2a58f8) || at<BYTE>(world, 0x3579cc) ||
        !at<BYTE>(world, 0x2b6a6c) || *reinterpret_cast<void**>(0xc9e2f8)) return selected;
    void* character = at<void*>(world, 8);
    unsigned grid = at<unsigned>(world, 0x2b6a70);
    if (!character || grid >= 5000) return selected;
    info.world = world; info.character = at<unsigned>(character, 0x24); info.grid = grid;
    BYTE* bases[4] = {};
    for (unsigned slot = 0; slot < 350; ++slot) {
        BYTE* base = static_cast<BYTE*>(world) + 0x2eb800 + slot * 0x250;
        if (!at<BYTE>(base, 0) || at<int>(base, 8) != 2 || at<unsigned short>(base, 0x10) != grid) continue;
        if (info.count == 4) { info = {}; return selected; }
        unsigned index = info.count++;
        while (index && at<float>(bases[index - 1], 0x34) > at<float>(base, 0x34)) {
            bases[index] = bases[index - 1]; info.ids[index] = info.ids[index - 1]; --index;
        }
        bases[index] = base;
        info.ids[index] = at<unsigned>(world, 0x2eb288 + slot * 4);
    }
    // Slot 3 is the original strategy command dialog. Its native modal controls
    // block other commands; do not steal an already visible native dialog.
    info.popup = reinterpret_cast<void*(__thiscall*)(void*, int, int)>(0x4fdde0)
        (reinterpret_cast<void*>(0xc9e638), 3, 4);
    if (!info.popup) { info = {}; return selected; }
    auto body = at<void*>(info.popup, 0x20);
    info.originalBodyHeight = body ? at<int>(body, 0x30) : 96;
    // Borrow widgets, not their original text handles. Allocate separate native
    // pool entries and restore the exact labels before another command uses them.
    const unsigned offsets[] = {0x1c, 0x20, 0x24, 0x28};
    for (unsigned i = 0; i < 4; ++i) {
        auto element = at<void*>(info.popup, offsets[i]);
        if (!element) continue;
        info.originalLabels[i] = at<unsigned>(element, 0xb04);
        at<unsigned>(element, 0xb04) = 0;
    }
    if (info.count) requestPlanetInformation();
    else planetText(L"현재 그리드에는 행성이나 요새가 없습니다.");
    return selected; // Let the original handler close the pull-up and old info windows.
}

static bool installPlanetInformation() {
    const unsigned addresses[] = {0x54bc01, 0x4bae89, 0x4babc2};
    const BYTE expected[][5] = {{0xe8,0x7a,0x02,0,0}, {0xe9,0xa5,0x2e,0,0}, {0xe8,0x89,0x43,0,0}};
    const void* functions[] = {&planetInfoMenu, &planetConditionsCopied, &receivedPlanetWarehouse};
    for (unsigned i = 0; i < 3; ++i)
        if (memcmp(reinterpret_cast<void*>(addresses[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < 3; ++i) {
        auto target = reinterpret_cast<BYTE*>(addresses[i]);
        BYTE replacement[5]; replacement[0] = expected[i][0];
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(static_cast<const BYTE*>(functions[i]) - target - 5);
        if (!patchBytes(target, expected[i], replacement, 5)) return false;
    }
    return true;
}
