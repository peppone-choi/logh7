// Overlay coordinates matching the native strategy HUD (004d5610). Author: Peppone Choi.
#pragma once

// 004d5610 places the 743x201 panel (atlas 0,200) at client (w - 743, h - 201) and passes
// 0x4cb2b0 the render/client ratio from 004ea610. The client rect and render size differ when
// the window is not exactly the configured size, so overlays must use the same pair.
struct HudFrame { float width, height, scale[3]; };
static bool hudFrame(HudFrame& frame) {
    auto display = *reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!display) return false;
    struct Rect { int x, y, width, height; } client = {
        at<int>(display, 0x2a5fc), at<int>(display, 0x2a600), at<int>(display, 0x2a604), at<int>(display, 0x2a608)};
    if (client.width < 743 || client.width > 8192 || client.height < 600 || client.height > 8192) return false;
    Rect mapped{};
    reinterpret_cast<Rect*(__cdecl*)(Rect*, const Rect*)>(0x4ea610)(&mapped, &client);
    float sx = static_cast<float>(mapped.width) / client.width, sy = static_cast<float>(mapped.height) / client.height;
    if (!_finite(sx) || !_finite(sy) || sx <= 0 || sy <= 0) return false;
    frame = {static_cast<float>(client.width), static_cast<float>(client.height), {sx, sy, 1}};
    return true;
}

// Map a client-space HUD point (as 004d5610 lays out the panel) to the render target used by 0x4e8af0 text.
static int hudRenderX(const HudFrame& frame, float x) { return static_cast<int>(x * frame.scale[0]); }
static int hudRenderY(const HudFrame& frame, float y) { return static_cast<int>(y * frame.scale[1]); }
