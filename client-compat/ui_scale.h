// Whole-screen UI scale for the game screen (user decision 2026-11-07, option B). Author: Peppone Choi.
#pragma once

// The native game HUD is consistent only when render size == stored client size (ratio 1):
// sprites follow the ratio but text, portraits and native panels use fixed render positions.
// So instead of scaling the HUD, the whole frame is rendered at view / scale and Present
// stretches it to the view (the path the 4:3 lobby already uses). The stored client rect and
// ratio are kept at the render size, and every client-coordinate exchange between the game and
// the view window is converted here: mouse messages, ScreenToClient/ClientToScreen, and the
// placement of native child windows (edit/IME controls).
// LOGH7_UI_SCALE: unset or "auto" = view height / 1080 when the view is taller than 1080;
// a number (1 .. 4) fixes it; "1" turns scaling off.
struct UiScale { bool active = false; float x = 1, y = 1; };
static UiScale uiScale;

static float uiScaleSetting(int viewHeight) {
    char text[16] = {};
    DWORD length = GetEnvironmentVariableA("LOGH7_UI_SCALE", text, sizeof(text));
    if (length && length < sizeof(text) && _stricmp(text, "auto")) {
        float value = static_cast<float>(atof(text));
        return value >= 1 && value <= 4 ? value : 1;
    }
    return viewHeight > 1080 ? viewHeight / 1080.0f : 1;
}
// Render size for a game-screen view; returns false when no scaling applies.
static bool uiScaleRender(int viewWidth, int viewHeight, int& width, int& height) {
    const float scale = uiScaleSetting(viewHeight);
    if (scale <= 1.001f) return false;
    width = static_cast<int>(viewWidth / scale + 0.5f);
    height = static_cast<int>(viewHeight / scale + 0.5f);
    return width >= 640 && height >= 480;
}
// After 004ea460 stored the view client: keep the render size and ratio 1, remember the scale.
static void uiScaleApply(void* app, int viewWidth, int viewHeight, bool scaled) {
    const int width = at<int>(app, 0x2a3d8), height = at<int>(app, 0x2a3dc);
    uiScale.active = scaled && width > 0 && height > 0 && (width != viewWidth || height != viewHeight);
    if (!uiScale.active) { uiScale.x = uiScale.y = 1; return; }
    uiScale.x = static_cast<float>(viewWidth) / width;
    uiScale.y = static_cast<float>(viewHeight) / height;
    at<int>(app, 0x2a5fc) = 0; at<int>(app, 0x2a600) = 0;
    at<int>(app, 0x2a604) = width; at<int>(app, 0x2a608) = height;
    *reinterpret_cast<float*>(0x772e2c) = 1.0f;
    *reinterpret_cast<float*>(0x772e30) = 1.0f;
}
static void uiScaleReset() { uiScale = {}; }
// Mouse messages carry view client coordinates; the game expects render coordinates.
static LPARAM uiScaleMouse(UINT message, LPARAM lParam) {
    if (!uiScale.active || message < WM_MOUSEFIRST || message > WM_MOUSELAST || message == WM_MOUSEWHEEL ||
        message == WM_MOUSEHWHEEL) return lParam;
    const int x = static_cast<short>(LOWORD(lParam)), y = static_cast<short>(HIWORD(lParam));
    return MAKELPARAM(static_cast<short>(x / uiScale.x), static_cast<short>(y / uiScale.y));
}

// 00500b70 converts the raw GUI cursor with ScreenToClient(app+0x2a5ec), not necessarily the view handle.
static bool uiScaledView(HWND window) {
    auto app = *reinterpret_cast<BYTE**>(0x7c1b4c);
    return uiScale.active && window && (window == lobbyViewWindow() || (app && window == at<HWND>(app, 0x2a5ec)));
}
static bool uiScaledChild(HWND window) {
    return uiScale.active && window && (GetWindowLongA(window, GWL_STYLE) & WS_CHILD) && GetParent(window) == lobbyViewWindow();
}
static int uiScaleX(int value) { return static_cast<int>(value * uiScale.x + (value >= 0 ? 0.5f : -0.5f)); }
static int uiScaleY(int value) { return static_cast<int>(value * uiScale.y + (value >= 0 ? 0.5f : -0.5f)); }

static decltype(&ScreenToClient) realScreenToClient = nullptr;
static decltype(&ClientToScreen) realClientToScreen = nullptr;
static decltype(&MoveWindow) realMoveWindow = nullptr;
static decltype(&SetWindowPos) realSetWindowPos = nullptr;
static decltype(&DeferWindowPos) realDeferWindowPos = nullptr;
static decltype(&CreateWindowExA) realCreateWindowExA = nullptr;

static BOOL WINAPI scaledScreenToClient(HWND window, LPPOINT point) {
    BOOL result = realScreenToClient(window, point);
    if (result && point && uiScaledView(window)) {
        point->x = static_cast<LONG>(point->x / uiScale.x);
        point->y = static_cast<LONG>(point->y / uiScale.y);
    }
    return result;
}
static BOOL WINAPI scaledClientToScreen(HWND window, LPPOINT point) {
    if (point && uiScaledView(window)) { point->x = uiScaleX(point->x); point->y = uiScaleY(point->y); }
    return realClientToScreen(window, point);
}
static BOOL WINAPI scaledMoveWindow(HWND window, int x, int y, int width, int height, BOOL repaint) {
    if (uiScaledChild(window)) { x = uiScaleX(x); y = uiScaleY(y); width = uiScaleX(width); height = uiScaleY(height); }
    return realMoveWindow(window, x, y, width, height, repaint);
}
static BOOL WINAPI scaledSetWindowPos(HWND window, HWND after, int x, int y, int width, int height, UINT flags) {
    if (uiScaledChild(window)) {
        if (!(flags & SWP_NOMOVE)) { x = uiScaleX(x); y = uiScaleY(y); }
        if (!(flags & SWP_NOSIZE)) { width = uiScaleX(width); height = uiScaleY(height); }
    }
    return realSetWindowPos(window, after, x, y, width, height, flags);
}
static HDWP WINAPI scaledDeferWindowPos(HDWP info, HWND window, HWND after, int x, int y, int width, int height, UINT flags) {
    if (uiScaledChild(window)) {
        if (!(flags & SWP_NOMOVE)) { x = uiScaleX(x); y = uiScaleY(y); }
        if (!(flags & SWP_NOSIZE)) { width = uiScaleX(width); height = uiScaleY(height); }
    }
    return realDeferWindowPos(info, window, after, x, y, width, height, flags);
}
static HWND WINAPI scaledCreateWindowExA(DWORD exStyle, LPCSTR className, LPCSTR name, DWORD style, int x, int y,
    int width, int height, HWND parent, HMENU menu, HINSTANCE instance, LPVOID parameter) {
    if (uiScale.active && (style & WS_CHILD) && parent && parent == lobbyViewWindow()) {
        if (x != CW_USEDEFAULT) { x = uiScaleX(x); y = uiScaleY(y); }
        if (width != CW_USEDEFAULT) { width = uiScaleX(width); height = uiScaleY(height); }
    }
    return realCreateWindowExA(exStyle, className, name, style, x, y, width, height, parent, menu, instance, parameter);
}

// Redirect the EXE's own USER32 imports; other modules and this DLL keep the real functions.
static bool patchUser32Import(const char* name, void* replacement, void** original) {
    auto base = reinterpret_cast<BYTE*>(0x400000);
    auto dos = reinterpret_cast<IMAGE_DOS_HEADER*>(base);
    auto nt = reinterpret_cast<IMAGE_NT_HEADERS32*>(base + dos->e_lfanew);
    const auto& directory = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (!directory.VirtualAddress) return false;
    for (auto entry = reinterpret_cast<IMAGE_IMPORT_DESCRIPTOR*>(base + directory.VirtualAddress); entry->Name; ++entry) {
        if (_stricmp(reinterpret_cast<const char*>(base + entry->Name), "USER32.dll") || !entry->OriginalFirstThunk) continue;
        auto names = reinterpret_cast<IMAGE_THUNK_DATA32*>(base + entry->OriginalFirstThunk);
        auto slots = reinterpret_cast<IMAGE_THUNK_DATA32*>(base + entry->FirstThunk);
        for (; names->u1.AddressOfData; ++names, ++slots) {
            if (IMAGE_SNAP_BY_ORDINAL32(names->u1.Ordinal)) continue;
            auto import = reinterpret_cast<IMAGE_IMPORT_BY_NAME*>(base + names->u1.AddressOfData);
            if (strcmp(reinterpret_cast<const char*>(import->Name), name)) continue;
            DWORD previous = 0;
            if (!VirtualProtect(&slots->u1.Function, sizeof(DWORD), PAGE_READWRITE, &previous)) return false;
            *original = reinterpret_cast<void*>(slots->u1.Function);
            slots->u1.Function = reinterpret_cast<DWORD>(replacement);
            DWORD ignored = 0; VirtualProtect(&slots->u1.Function, sizeof(DWORD), previous, &ignored);
            return true;
        }
    }
    return false;
}
static bool installUiScale() {
    return patchUser32Import("ScreenToClient", reinterpret_cast<void*>(&scaledScreenToClient), reinterpret_cast<void**>(&realScreenToClient)) &&
        patchUser32Import("ClientToScreen", reinterpret_cast<void*>(&scaledClientToScreen), reinterpret_cast<void**>(&realClientToScreen)) &&
        patchUser32Import("MoveWindow", reinterpret_cast<void*>(&scaledMoveWindow), reinterpret_cast<void**>(&realMoveWindow)) &&
        patchUser32Import("SetWindowPos", reinterpret_cast<void*>(&scaledSetWindowPos), reinterpret_cast<void**>(&realSetWindowPos)) &&
        patchUser32Import("DeferWindowPos", reinterpret_cast<void*>(&scaledDeferWindowPos), reinterpret_cast<void**>(&realDeferWindowPos)) &&
        patchUser32Import("CreateWindowExA", reinterpret_cast<void*>(&scaledCreateWindowExA), reinterpret_cast<void**>(&realCreateWindowExA));
}
