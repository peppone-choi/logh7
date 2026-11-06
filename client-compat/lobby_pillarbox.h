// Lobby at its native 1024x768 render, shown 4:3 in the configured screen size. Author: Peppone Choi.
#pragma once
#include <commctrl.h>
#include <stdio.h>
#include <stdlib.h>
#pragma comment(lib, "comctl32.lib")

// 0051a370 switches the lobby to a fixed 1024x768 with 00401760 (window + render size)
// followed by 004e7570 (popup style + ChangeDisplaySettings to the render size), and the
// game screen to GraphicConfig.txt's ScreenWidth/ScreenHeight (0xc6ebb0 items 5 and 6).
// With a larger configured size the lobby keeps the configured window and display mode,
// renders 1024x768, and the view window (the D3D device window) is centred at 4:3 inside the
// frame. All mouse and native-control conversions are relative to the view client rect and
// use the render/client ratio from 004ea460, so they need no offset.
struct LobbyBox { bool active = false, subclassed = false; int screenWidth = 0, screenHeight = 0, width = 0, height = 0; };
static LobbyBox lobbyBox;

static HWND lobbyFrameWindow() { auto frame = *reinterpret_cast<BYTE**>(0x7c1b50); return frame ? at<HWND>(frame, 0x1c) : nullptr; }
static HWND lobbyViewWindow() { auto view = *reinterpret_cast<BYTE**>(0x7c1b4c); return view ? at<HWND>(view, 0x1c) : nullptr; }
// Optional trace for native checks: LOGH7_LOBBY_BOX_LOG names a file to append to.
static void lobbyBoxTrace(const char* step) {
    char path[MAX_PATH];
    DWORD length = GetEnvironmentVariableA("LOGH7_LOBBY_BOX_LOG", path, MAX_PATH);
    if (!length || length >= MAX_PATH) return;
    HANDLE file = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_ALWAYS, 0, nullptr);
    if (file == INVALID_HANDLE_VALUE) return;
    RECT frameWindow{}, frameClient{}, viewWindow{}, viewClient{};
    HWND frame = lobbyFrameWindow(), view = lobbyViewWindow();
    if (frame) { GetWindowRect(frame, &frameWindow); GetClientRect(frame, &frameClient); }
    if (view) { GetWindowRect(view, &viewWindow); GetClientRect(view, &viewClient); }
    auto app = *reinterpret_cast<BYTE**>(0x7c1b4c);
    char line[512];
    int size = _snprintf_s(line, _TRUNCATE,
        "%s active=%d box=%dx%d screen=%dx%d memory=%dx%d render=%dx%d frame=[%ld,%ld,%ld,%ld]/%ldx%ld view=[%ld,%ld,%ld,%ld]/%ldx%ld style=%08lx\r\n",
        step, lobbyBox.active ? 1 : 0, lobbyBox.width, lobbyBox.height, lobbyBox.screenWidth, lobbyBox.screenHeight,
        *reinterpret_cast<int*>(0xc6ebb0 + 5 * 4), *reinterpret_cast<int*>(0xc6ebb0 + 6 * 4),
        app ? at<int>(app, 0x2a3d8) : 0, app ? at<int>(app, 0x2a3dc) : 0,
        frameWindow.left, frameWindow.top, frameWindow.right, frameWindow.bottom, frameClient.right, frameClient.bottom,
        viewWindow.left, viewWindow.top, viewWindow.right, viewWindow.bottom, viewClient.right, viewClient.bottom,
        frame ? static_cast<unsigned long>(GetWindowLongA(frame, GWL_STYLE)) : 0ul);
    DWORD written = 0;
    if (size > 0) WriteFile(file, line, static_cast<DWORD>(size), &written, nullptr);
    CloseHandle(file);
}
// ScreenWidth/ScreenHeight from the client root's GraphicConfig.txt (the EXE reads
// "%s/../GraphicConfig.txt"). Read the file itself: whether the native settings array at 0xc6ebb0
// is filled before the lobby is unverified, and a native lobby check boxed the view in 3144x1084.
static bool configuredScreen(int& width, int& height) {
    static int cachedWidth = -1, cachedHeight = -1;
    if (cachedWidth < 0) {
        cachedWidth = cachedHeight = 0;
        char path[MAX_PATH];
        DWORD length = GetModuleFileNameA(nullptr, path, MAX_PATH);
        char* slash = length && length < MAX_PATH ? strrchr(path, '\\') : nullptr;
        if (slash && strcpy_s(slash + 1, MAX_PATH - (slash + 1 - path), "..\\GraphicConfig.txt") == 0) {
            HANDLE file = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, 0, nullptr);
            if (file != INVALID_HANDLE_VALUE) {
                char text[4096] = {};
                DWORD read = 0;
                ReadFile(file, text, sizeof(text) - 1, &read, nullptr);
                CloseHandle(file);
                text[read] = 0;
                auto value = [&](const char* key) -> int {
                    for (char* line = text; line && *line;) {
                        char* next = strchr(line, '\n');
                        size_t keyLength = strlen(key);
                        if (!strncmp(line, key, keyLength) && (line[keyLength] == '\r' || line[keyLength] == '\n'))
                            return next ? atoi(next + 1) : 0;
                        line = next ? next + 1 : nullptr;
                    }
                    return 0;
                };
                cachedWidth = value("ScreenWidth"); cachedHeight = value("ScreenHeight");
            }
        }
    }
    width = cachedWidth; height = cachedHeight;
    return width >= 800 && width <= 7680 && height >= 600 && height <= 4320; // launcher range
}
static void nativeScreenSize(void* app, int width, int height) {
    reinterpret_cast<void(__thiscall*)(void*, int, int)>(0x401760)(app, width, height);
}
static void layoutLobbyBox() {
    HWND frame = lobbyFrameWindow(), view = lobbyViewWindow();
    auto app = *reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!lobbyBox.active || !frame || !view || !app || IsIconic(frame)) return;
    RECT client{}, window{}, inner{};
    GetClientRect(frame, &client); GetWindowRect(view, &window); GetClientRect(view, &inner);
    const int edgeWidth = (window.right - window.left) - inner.right, edgeHeight = (window.bottom - window.top) - inner.bottom;
    const int availableWidth = client.right - edgeWidth, availableHeight = client.bottom - edgeHeight;
    if (availableWidth <= 0 || availableHeight <= 0) return;
    int width = availableWidth, height = MulDiv(availableWidth, lobbyBox.height, lobbyBox.width);
    if (height > availableHeight) { height = availableHeight; width = MulDiv(availableHeight, lobbyBox.width, lobbyBox.height); }
    SetWindowPos(view, nullptr, (client.right - width - edgeWidth) / 2, (client.bottom - height - edgeHeight) / 2,
        width + edgeWidth, height + edgeHeight, SWP_NOZORDER | SWP_NOACTIVATE);
    reinterpret_cast<void(__cdecl*)(void*)>(0x4ea460)(app); // client rect and render/client ratio
    InvalidateRect(frame, nullptr, TRUE);
}
static void leaveLobbyBox() {
    if (!lobbyBox.active) return;
    lobbyBox.active = false;
    HWND frame = lobbyFrameWindow(), view = lobbyViewWindow();
    if (!frame || !view) return;
    // 00401760 measures the frame border from the view client size, so refill the frame first.
    RECT client{};
    GetClientRect(frame, &client);
    SetWindowPos(view, nullptr, 0, 0, client.right, client.bottom, SWP_NOZORDER | SWP_NOACTIVATE);
    InvalidateRect(frame, nullptr, TRUE);
}
static LRESULT CALLBACK lobbyFrameProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam, UINT_PTR, DWORD_PTR) {
    if (message == WM_ERASEBKGND && lobbyBox.active) {
        auto dc = reinterpret_cast<HDC>(wParam);
        RECT client{};
        GetClientRect(window, &client);
        if (HWND view = lobbyViewWindow()) {
            RECT box{};
            GetWindowRect(view, &box);
            MapWindowPoints(nullptr, window, reinterpret_cast<POINT*>(&box), 2);
            ExcludeClipRect(dc, box.left, box.top, box.right, box.bottom);
        }
        FillRect(dc, &client, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
        return 1;
    }
    // In popup (full screen) mode the frame is fitted to the monitor plus the border measured
    // against the view client. With the view boxed that border grows on every fit (1280 check:
    // 2222 -> 2664 -> 3144 wide), so keep the frame at the unboxed result: the monitor with the
    // view's client edge just outside it.
    if (message == WM_WINDOWPOSCHANGING && lobbyBox.active && (GetWindowLongA(window, GWL_STYLE) & WS_POPUP)) {
        auto position = reinterpret_cast<WINDOWPOS*>(lParam);
        HWND view = lobbyViewWindow();
        MONITORINFO monitor{sizeof(monitor)};
        if (!(position->flags & SWP_NOSIZE) && view &&
            GetMonitorInfoA(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) {
            RECT viewWindow{}, viewClient{};
            GetWindowRect(view, &viewWindow); GetClientRect(view, &viewClient);
            const int edgeWidth = (viewWindow.right - viewWindow.left) - viewClient.right;
            const int edgeHeight = (viewWindow.bottom - viewWindow.top) - viewClient.bottom;
            position->x = monitor.rcMonitor.left - edgeWidth / 2;
            position->y = monitor.rcMonitor.top - edgeHeight / 2;
            position->cx = monitor.rcMonitor.right - monitor.rcMonitor.left + edgeWidth;
            position->cy = monitor.rcMonitor.bottom - monitor.rcMonitor.top + edgeHeight;
            position->flags &= ~SWP_NOMOVE;
        }
    }
    LRESULT result = DefSubclassProc(window, message, wParam, lParam);
    // The MFC frame refills its view on WM_SIZE (restore, display change); centre it again.
    if ((message == WM_SIZE && wParam != SIZE_MINIMIZED) || message == WM_DISPLAYCHANGE) { layoutLobbyBox(); lobbyBoxTrace(message == WM_SIZE ? "frame-size" : "frame-display"); }
    return result;
}
// After 00401760 the frame drops its caption and the popup frame is fitted to the monitor,
// resizing the view without 004ea460. The stored client rect and render/client ratio then
// describe the old view (native A/B check, game at 1280x720: view 1920x1080, stored 1280x740,
// HUD clicks missed with and without the lobby box).
// Only recomputing the ratio is not enough: the native game HUD scales its sprites by the ratio
// but draws text, portraits and native panels at fixed render positions, so they separated
// (native check 05, render 1280x720 in a 1920x1080 view). The game screen is consistent only
// when render size == view client size (1920 run). So in the game screen the render size
// follows the view; the boxed lobby keeps its native 1024x768 render with the ratio.
#include "ui_scale.h" // whole-screen UI scale on the game screen
static bool renderFollowsView = false;
static bool viewResetting = false; // our own Reset/refill resizes the view; do not follow it
static constexpr UINT viewRenderMessage = WM_APP + 0x4c7;
static LRESULT CALLBACK viewSizeProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam, UINT_PTR, DWORD_PTR) {
    auto app = *reinterpret_cast<BYTE**>(0x7c1b4c);
    if (message == viewRenderMessage) {
        // A device Reset makes the windowed D3D layer resize the view to the backbuffer size
        // (+20 high), centred in the frame (native 18: 1280x720 -> view 1280x740, then 1038x620 ...).
        // Following that view shrinks the render on every step, so the target comes from the
        // frame client instead and the view is refilled after our own Reset.
        HWND frame = lobbyFrameWindow();
        RECT frameClient{}, viewWindow{}, viewClient{};
        if (renderFollowsView && app && frame && at<HWND>(app, 0x1c) == window && GetClientRect(frame, &frameClient) &&
            GetWindowRect(window, &viewWindow) && GetClientRect(window, &viewClient)) {
            const int viewWidth = frameClient.right - ((viewWindow.right - viewWindow.left) - viewClient.right);
            const int viewHeight = frameClient.bottom - ((viewWindow.bottom - viewWindow.top) - viewClient.bottom);
            if (viewWidth >= 640 && viewHeight >= 480 && viewWidth <= 7680 && viewHeight <= 4320) {
                int width = viewWidth, height = viewHeight;
                const bool scaled = uiScaleRender(viewWidth, viewHeight, width, height);
                viewResetting = true;
                // Same pair 00401760 uses: render size + device Reset, then client rect and ratio.
                if (at<int>(app, 0x2a3d8) != width || at<int>(app, 0x2a3dc) != height)
                    reinterpret_cast<void(__thiscall*)(void*, int, int)>(0x401830)(app, width, height);
                SetWindowPos(window, nullptr, 0, 0, frameClient.right, frameClient.bottom, SWP_NOZORDER | SWP_NOACTIVATE);
                viewResetting = false;
                reinterpret_cast<void(__cdecl*)(void*)>(0x4ea460)(app);
                uiScaleApply(app, viewWidth, viewHeight, scaled);
                lobbyBoxTrace("view-render");
            }
        }
        return 0;
    }
    LRESULT result = DefSubclassProc(window, message, wParam, uiScaleMouse(message, lParam));
    if (message == WM_SIZE && wParam != SIZE_MINIMIZED && LOWORD(lParam) && HIWORD(lParam) &&
        app && at<HWND>(app, 0x1c) == window) {
        if (renderFollowsView && !viewResetting) PostMessageA(window, viewRenderMessage, 0, 0); // Reset outside the resize
        else if (lobbyBox.active) reinterpret_cast<void(__cdecl*)(void*)>(0x4ea460)(app);
        lobbyBoxTrace("view-size");
    }
    return result;
}
static void watchViewSize() {
    static HWND watched = nullptr;
    HWND view = lobbyViewWindow();
    if (view && view != watched && SetWindowSubclass(view, viewSizeProc, 0x4c4f4256, 0)) watched = view;
}
// Replaces the two fixed lobby calls 00401760(1024, 768).
static void __fastcall lobbyScreenSize(void* app, void*, int width, int height) {
    int screenWidth = 0, screenHeight = 0;
    HWND frame = lobbyFrameWindow(), view = lobbyViewWindow();
    lobbyBoxTrace("lobby-enter");
    watchViewSize();
    renderFollowsView = false;
    uiScaleReset();
    leaveLobbyBox();
    // Native A/B checks: LOGH7_LOBBY_BOX=0 keeps the original lobby; the trace still records it.
    char boxSwitch[4] = {};
    if (frame && !lobbyBox.subclassed && GetEnvironmentVariableA("LOGH7_LOBBY_BOX_LOG", nullptr, 0))
        lobbyBox.subclassed = SetWindowSubclass(frame, lobbyFrameProc, 0x4c4f4248, 0) != FALSE;
    if ((GetEnvironmentVariableA("LOGH7_LOBBY_BOX", boxSwitch, sizeof(boxSwitch)) && !strcmp(boxSwitch, "0")) ||
        !configuredScreen(screenWidth, screenHeight) || (screenWidth == width && screenHeight == height) ||
        !frame || !view || at<HWND>(app, 0x2a40c) != view) { // the D3D device window must be the view
        nativeScreenSize(app, width, height);
        return;
    }
    nativeScreenSize(app, screenWidth, screenHeight);
    lobbyBoxTrace("lobby-native");
    reinterpret_cast<void(__thiscall*)(void*, int, int)>(0x401830)(app, width, height); // render size + Reset
    if (!lobbyBox.subclassed) lobbyBox.subclassed = SetWindowSubclass(frame, lobbyFrameProc, 0x4c4f4248, 0) != FALSE;
    lobbyBox = {true, lobbyBox.subclassed, screenWidth, screenHeight, width, height};
    layoutLobbyBox();
    lobbyBoxTrace("lobby-boxed");
}
// Replaces the login and game calls of 00401760: the frame is refilled before the native resize.
static void __fastcall gameScreenSize(void* app, void*, int width, int height) {
    lobbyBoxTrace("game-enter");
    watchViewSize();
    leaveLobbyBox();
    // The login screen is a fixed 640x480 window; only the game screen follows the view.
    renderFollowsView = !(width == 640 && height == 480);
    uiScaleReset(); // native resize measures the real view; the posted render step reapplies the scale
    nativeScreenSize(app, width, height);
    if (renderFollowsView) if (HWND view = lobbyViewWindow()) PostMessageA(view, viewRenderMessage, 0, 0);
    lobbyBoxTrace("game-native");
}
// Replaces the two lobby calls 004e7570(1, frame, 1024, 768), which read the render size for
// ChangeDisplaySettings. Keep the configured display mode while the lobby is boxed.
static void __cdecl lobbyScreenMode(int mode, void* frame, int width, int height) {
    auto screenMode = reinterpret_cast<void(__cdecl*)(int, void*, int, int)>(0x4e7570);
    auto app = *reinterpret_cast<BYTE**>(0x7c1b4c);
    if (!lobbyBox.active || !app) { screenMode(mode, frame, width, height); return; }
    int& renderWidth = at<int>(app, 0x2a3d8);
    int& renderHeight = at<int>(app, 0x2a3dc);
    const int savedWidth = renderWidth, savedHeight = renderHeight;
    renderWidth = lobbyBox.screenWidth; renderHeight = lobbyBox.screenHeight;
    screenMode(mode, frame, lobbyBox.screenWidth, lobbyBox.screenHeight);
    renderWidth = savedWidth; renderHeight = savedHeight;
    layoutLobbyBox();
    lobbyBoxTrace("lobby-mode");
}
struct LobbyBoxCall { unsigned address; BYTE expected[5]; void* function; };
static bool installLobbyPillarbox() {
    const LobbyBoxCall calls[] = {
        {0x51a521, {0xe8, 0x3a, 0x72, 0xee, 0xff}, reinterpret_cast<void*>(&gameScreenSize)},  // login 640x480
        {0x51a745, {0xe8, 0x16, 0x70, 0xee, 0xff}, reinterpret_cast<void*>(&lobbyScreenSize)}, // lobby 1024x768
        {0x51a8f4, {0xe8, 0x67, 0x6e, 0xee, 0xff}, reinterpret_cast<void*>(&lobbyScreenSize)}, // lobby 1024x768
        {0x51b77e, {0xe8, 0xdd, 0x5f, 0xee, 0xff}, reinterpret_cast<void*>(&gameScreenSize)},  // configured game size
        {0x51a75d, {0xe8, 0x0e, 0xce, 0xfc, 0xff}, reinterpret_cast<void*>(&lobbyScreenMode)}, // lobby display mode
        {0x51a90c, {0xe8, 0x5f, 0xcc, 0xfc, 0xff}, reinterpret_cast<void*>(&lobbyScreenMode)}};
    for (const auto& row : calls)
        if (memcmp(reinterpret_cast<void*>(row.address), row.expected, sizeof(row.expected))) return false;
    for (const auto& row : calls) {
        BYTE call[5] = {0xe8};
        *reinterpret_cast<LONG*>(call + 1) = static_cast<LONG>(static_cast<BYTE*>(row.function) - reinterpret_cast<BYTE*>(row.address) - 5);
        if (!patchBytes(reinterpret_cast<void*>(row.address), row.expected, call, sizeof(call))) return false;
    }
    return true;
}
