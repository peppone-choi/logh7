// Signed radar coordinates must never index outside the ten-digit atlas.
// Author: 최병호
#pragma once
#include <intrin.h>

static thread_local struct RadarDigitState {
    bool active;
    struct Row { float y, left; bool negative; void* texture; float color[4], depth; } rows[4];
    unsigned count;
} radarDigits = {};

// These four callers use the same native stack frame: the digit table starts
// 0x104 bytes above this call's return address. A negative C remainder selected
// stack memory before that table and turned IDs/stack values into texture UVs.
static __declspec(noinline) unsigned __cdecl radarCoordinateDigit(void* texture, const float* point,
    const float* rectangle, const float* color, float, float) {
    const auto base = reinterpret_cast<LONG_PTR>(_AddressOfReturnAddress()) + 0x104;
    const auto offset = reinterpret_cast<LONG_PTR>(rectangle) - base;
    if (offset % 16 || offset < -9 * 16 || offset > 9 * 16) return 0;
    int digit = static_cast<int>(offset / 16);
    if (radarDigits.active && point) {
        unsigned row = 0;
        while (row < radarDigits.count && radarDigits.rows[row].y != point[1]) ++row;
        if (row == radarDigits.count && row < _countof(radarDigits.rows)) {
            auto& added = radarDigits.rows[radarDigits.count++];
            added = {}; added.y = point[1]; added.left = point[0];
            added.texture = texture; added.depth = point[2];
            memcpy(added.color, color, sizeof(added.color));
        }
        if (row < radarDigits.count) {
            if (point[0] < radarDigits.rows[row].left) radarDigits.rows[row].left = point[0];
            radarDigits.rows[row].negative |= digit < 0;
        }
    }
    if (digit < 0) digit = -digit;
    const float uv[] = {digit ? 237.0f + digit * 10.0f : 337.0f, 235.0f, 9.0f, 8.0f};
    return reinterpret_cast<unsigned(__cdecl*)(void*, const float*, const float*, const float*, float, float)>(0x4ddc00)
        (texture, point, uv, color, 9.0f, 8.0f);
}

static void __fastcall drawSignedRadar(void* manager, void*, int x, int y) {
    radarDigits = {}; radarDigits.active = true;
    reinterpret_cast<void(__thiscall*)(void*, int, int)>(0x4ec700)(manager, x, y);
    radarDigits.active = false;
    for (unsigned i = 0; i < radarDigits.count; ++i) {
        const auto& row = radarDigits.rows[i];
        if (!row.negative) continue;
        // Reuse the middle stroke of the native digit 8, on the same atlas
        // and render layer as its digits; the text queue is behind this HUD.
        const float point[] = {row.left - 8, row.y + 3, row.depth};
        const float uv[] = {319, 238, 5, 2};
        reinterpret_cast<unsigned(__cdecl*)(void*, const float*, const float*, const float*, float, float)>(0x4ddc00)
            (row.texture, point, uv, row.color, 5.0f, 2.0f);
    }
}

static bool installTacticalRadar() {
    const unsigned calls[] = {0x50d1d2, 0x4ed23b, 0x4ed3f2, 0x4ed63e, 0x4ed7f1};
    const BYTE expected[][5] = {{0xe8,0x29,0xf5,0xfd,0xff}, {0xe8,0xc0,9,0xff,0xff},
        {0xe8,9,8,0xff,0xff}, {0xe8,0xbd,5,0xff,0xff}, {0xe8,0x0a,4,0xff,0xff}};
    for (unsigned i = 0; i < _countof(calls); ++i)
        if (memcmp(reinterpret_cast<void*>(calls[i]), expected[i], 5)) return false;
    for (unsigned i = 0; i < _countof(calls); ++i) {
        BYTE replacement[5] = {0xe8};
        const BYTE* target = i ? reinterpret_cast<const BYTE*>(&radarCoordinateDigit) : reinterpret_cast<const BYTE*>(&drawSignedRadar);
        *reinterpret_cast<LONG*>(replacement + 1) = static_cast<LONG>(target - reinterpret_cast<const BYTE*>(calls[i]) - 5);
        if (!patchBytes(reinterpret_cast<void*>(calls[i]), expected[i], replacement, 5)) return false;
    }
    return true;
}
