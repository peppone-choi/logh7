// Lobby at its native 1024x768 render, shown 4:3 in the configured screen size. Author: Peppone Choi.
#pragma once
#include <commctrl.h>
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
static bool configuredScreen(int& width, int& height) {
    width = *reinterpret_cast<int*>(0xc6ebb0 + 5 * 4);
    height = *reinterpret_cast<int*>(0xc6ebb0 + 6 * 4);
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
    LRESULT result = DefSubclassProc(window, message, wParam, lParam);
    // The MFC frame refills its view on WM_SIZE (restore, display change); centre it again.
    if ((message == WM_SIZE && wParam != SIZE_MINIMIZED) || message == WM_DISPLAYCHANGE) layoutLobbyBox();
    return result;
}
// Replaces the two fixed lobby calls 00401760(1024, 768).
static void __fastcall lobbyScreenSize(void* app, void*, int width, int height) {
    int screenWidth = 0, screenHeight = 0;
    HWND frame = lobbyFrameWindow(), view = lobbyViewWindow();
    leaveLobbyBox();
    if (!configuredScreen(screenWidth, screenHeight) || (screenWidth == width && screenHeight == height) ||
        !frame || !view || at<HWND>(app, 0x2a40c) != view) { // the D3D device window must be the view
        nativeScreenSize(app, width, height);
        return;
    }
    nativeScreenSize(app, screenWidth, screenHeight);
    reinterpret_cast<void(__thiscall*)(void*, int, int)>(0x401830)(app, width, height); // render size + Reset
    if (!lobbyBox.subclassed) lobbyBox.subclassed = SetWindowSubclass(frame, lobbyFrameProc, 0x4c4f4248, 0) != FALSE;
    lobbyBox = {true, lobbyBox.subclassed, screenWidth, screenHeight, width, height};
    layoutLobbyBox();
}
// Replaces the login and game calls of 00401760: the frame is refilled before the native resize.
static void __fastcall gameScreenSize(void* app, void*, int width, int height) {
    leaveLobbyBox();
    nativeScreenSize(app, width, height);
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
