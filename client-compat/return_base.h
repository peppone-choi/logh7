// Restore the manual's game-settings entry on the earlier CD client.
// Native widgets, serializer and authoritative replies. Author: Peppone Choi.
#pragma once

static struct ReturnBaseSettings {
    void* manager;
    void* root;
    void* world;
    unsigned character, selected, requested, previous;
    bool visible, pending;
    ULONGLONG sentAt;
    const wchar_t* message;
} returnBaseSettings = {};
static constexpr int returnSettingsId = 0x72;

static void* returnWidget(void* root, int category, int index) {
    return reinterpret_cast<void*(__thiscall*)(void*, int, int)>(0x502780)(root, category, index);
}
static void returnText(int index, const wchar_t* text) {
    auto root = returnBaseSettings.root;
    auto widget = root ? returnWidget(root, 1, index) : nullptr;
    if (widget) reinterpret_cast<void(__thiscall*)(void*, void*, const wchar_t*)>(0x503560)(root, widget, text);
}
static void returnControl(void* root, int index, int x, int y, int width, int height,
    const wchar_t* label, bool button) {
    // Four native states of the existing game-menu button, followed by terminators.
    unsigned sprites[40] = {};
    const unsigned ys[] = {0x209, 0x23c, 0x2a2, 0x26f};
    for (unsigned i = 0; i < 4; ++i) {
        sprites[i * 5] = 0x786abc; sprites[i * 5 + 2] = ys[i];
        sprites[i * 5 + 3] = 209; sprites[i * 5 + 4] = 50;
    }
    auto widget = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)
        (root, 1, index, button ? sprites : nullptr);
    int rect[] = {x, y, 0, width, height};
    reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(root, widget, rect);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5024e0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502eb0)(root, widget, 1);
    reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x5029e0)(root, widget, button ? 2 : 3);
    reinterpret_cast<void(__thiscall*)(void*, void*, const wchar_t*)>(0x503560)(root, widget, label);
    if (button) {
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b00)(root, widget, 1);
        reinterpret_cast<void(__thiscall*)(void*, void*, int)>(0x502b30)(root, widget, 1);
    }
}
static void __cdecl buildReturnSettings(void* manager) {
    // This ID is unused by the supported CD. Never replace an allocated native GUI.
    if (at<void*>(manager, 4 + returnSettingsId * 4)) {
        returnBaseSettings = {};
        reinterpret_cast<void(__cdecl*)(void*)>(0x550b80)(manager);
        return;
    }
    auto root = reinterpret_cast<void*(__thiscall*)(void*, int)>(0x50bb40)(manager, returnSettingsId);
    returnBaseSettings = {}; returnBaseSettings.manager = manager; returnBaseSettings.root = root;
    reinterpret_cast<void(__thiscall*)(void*, int)>(0x502ea0)(root, 0);
    auto container = reinterpret_cast<void*(__thiscall*)(void*, int, int, const void*)>(0x503a10)(root, 0, 0, nullptr);
    int rect[] = {0, 20, 0, 454, 310};
    reinterpret_cast<void(__thiscall*)(void*, void*, const int*)>(0x502940)(root, container, rect);
    returnControl(root, 0, 24, 38, 410, 25, L"귀환 행성", false);
    returnControl(root, 1, 24, 68, 410, 25, L"", false);
    returnControl(root, 2, 24, 103, 410, 25, L"", false);
    returnControl(root, 3, 14, 137, 209, 50, L"이전 행성", true);
    returnControl(root, 4, 231, 137, 209, 50, L"다음 행성", true);
    returnControl(root, 5, 14, 197, 209, 50, L"귀환 행성으로 설정", true);
    returnControl(root, 6, 231, 197, 209, 50, L"설정 해제", true);
    const wchar_t* labels[] = {L"게임 종료", L"사운드 설정", L"게임 설정"};
    int ids[] = {4, 7, returnSettingsId};
    reinterpret_cast<void(__cdecl*)(void*, int, int, int, const wchar_t*, int, void*, int,
        const wchar_t**, int, int, const int*, int)>(0x5148b0)
        (manager, 8, 454, 310, L"시스템 설정", -1, reinterpret_cast<void*>(0x22166fc),
            static_cast<int>(0xffffa03c), labels, 3, -1, ids, 0);
}
static void __cdecl returnSettingsReply(void* record) {
    auto& state = returnBaseSettings;
    if (!state.pending || !record || at<unsigned>(record, 4) != state.character) return;
    state.pending = false;
    if (at<unsigned>(record, 8) != state.requested)
        state.message = L"설정 거부: 아군의 거주 가능한 행성을 선택하세요.";
    else state.message = state.previous == state.requested ? L"현재 귀환 설정을 유지합니다." : L"귀환 설정을 저장했습니다.";
}
static unsigned returnReplyResume = 0x4bc063;
static void __declspec(naked) copiedReturnSettings() {
    __asm {
        pushfd
        pushad
        push ebx
        call returnSettingsReply
        add esp, 4
        popad
        popfd
        jmp dword ptr [returnReplyResume]
    }
}
static void __cdecl updateReturnSettings(void* manager) {
    reinterpret_cast<void(__cdecl*)(void*)>(0x54ef40)(manager);
    auto& state = returnBaseSettings;
    if (state.manager != manager || !state.root) return;
    auto frame = reinterpret_cast<void*(__thiscall*)(void*, int)>(0x50cf40)(manager, 8);
    bool visible = frame && at<BYTE>(frame, 4) && at<BYTE>(state.root, 4);
    if (!visible) { state.visible = false; return; }
    auto world = *reinterpret_cast<void**>(0x7ccffc);
    auto own = world ? at<void*>(world, 8) : nullptr;
    if (!own || !at<BYTE>(own, 0) || !at<BYTE>(world, 0x2a58f8) || at<BYTE>(world, 0x3579cc)) return;
    if (!state.visible || state.world != world || state.character != at<unsigned>(own, 0x24)) {
        if (state.world != world || state.character != at<unsigned>(own, 0x24)) state.pending = false;
        state.world = world; state.character = at<unsigned>(own, 0x24); state.visible = true;
        state.selected = at<unsigned>(own, 0x3c);
        if (!pickerBase(state.selected)) {
            unsigned first = 0;
            for (unsigned slot = 0; slot < 350; ++slot) {
                unsigned id = at<unsigned>(world, 0x2eb288 + slot * 4);
                auto base = pickerBase(id);
                if (!base) continue;
                if (!first) first = id;
                if (logh::infoShort(base, 0x10) == at<unsigned>(own, 0x320)) { state.selected = id; break; }
            }
            if (!pickerBase(state.selected)) state.selected = first;
        }
        state.message = L"아군 거주 행성만 설정할 수 있습니다.";
    }
    BYTE event[52] = {};
    for (int index = 3; index <= 6; ++index) {
        auto widget = returnWidget(state.root, 1, index);
        if (!reinterpret_cast<BYTE(__thiscall*)(void*, int, void*, void*, int)>(0x5015f0)
            (state.root, 2, widget, event, 0) || state.pending) continue;
        if (index <= 4) {
            unsigned slots[350] = {}, count = 0, current = 0;
            for (unsigned slot = 0; slot < 350; ++slot) {
                unsigned id = at<unsigned>(world, 0x2eb288 + slot * 4);
                if (!pickerBase(id)) continue;
                if (id == state.selected) current = count;
                slots[count++] = id;
            }
            if (count) state.selected = slots[(current + (index == 3 ? count - 1 : 1)) % count];
        } else {
            auto clock = *reinterpret_cast<void**>(0x7cd028);
            if (!clock) continue;
            state.requested = index == 6 ? 0 : state.selected;
            state.previous = at<unsigned>(own, 0x3c);
            unsigned record[] = {at<unsigned>(clock, 0x10), state.character, state.requested};
            state.pending = true; state.sentAt = GetTickCount64(); state.message = L"서버에 저장을 요청했습니다.";
            reinterpret_cast<unsigned(__thiscall*)(void*, char, unsigned, const void*)>(0x4b78a0)
                (world, 0, 0x77, record);
        }
    }
    if (state.pending && GetTickCount64() - state.sentAt >= 5000) {
        state.pending = false; state.message = L"응답을 확인하지 못했습니다. 현재 설정을 확인하세요.";
    }
    wchar_t name[32] = {}, text[96];
    unsigned saved = at<unsigned>(own, 0x3c);
    pickerName(saved, name);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"현재: %ls", saved ? (name[0] ? name : L"자료가 없는 행성") : L"미설정 (출신 행성)");
    returnText(0, text);
    pickerName(state.selected, name);
    _snwprintf_s(text, _countof(text), _TRUNCATE, L"선택: %ls (%u)", name, state.selected);
    returnText(1, text);
    returnText(2, state.message ? state.message : L"");
}
static bool installReturnSettings() {
    const unsigned calls[] = {0x54eacc, 0x54ef07, 0x4bd538};
    const BYTE expected[][5] = {{0xe8,0xaf,0x20,0,0}, {0xe8,0x34,0,0,0}, {0xe9,0x26,0xeb,0xff,0xff}};
    const void* targets[] = {&buildReturnSettings, &updateReturnSettings, &copiedReturnSettings};
    const unsigned groups[] = {0x54f64d, 0x54f846, 0x54f925, 0x54f9c7, 0x54fa69,
        0x54fb18, 0x54fbba, 0x54fc58, 0x54ffa4, 0x5501e5};
    const BYTE oldGroup = 106, newGroup = 101;
    for (unsigned address : groups)
        if (*reinterpret_cast<BYTE*>(address) != oldGroup) return false;
    for (unsigned i = 0; i < _countof(calls); ++i)
        if (memcmp(reinterpret_cast<void*>(calls[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < _countof(calls); ++i) {
        BYTE replacement[5]; replacement[0] = expected[i][0];
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(static_cast<const BYTE*>(targets[i]) -
            reinterpret_cast<const BYTE*>(calls[i]) - 5);
        if (!patchBytes(reinterpret_cast<void*>(calls[i]), expected[i], replacement, 5)) return false;
    }
    for (unsigned address : groups)
        if (!patchBytes(reinterpret_cast<void*>(address), &oldGroup, &newGroup, 1)) return false;
    return true;
}
