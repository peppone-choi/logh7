#include <windows.h>
#include <shellapi.h>

#include <algorithm>
#include <charconv>
#include <cwctype>
#include <filesystem>
#include <limits>
#include <optional>
#include <string>
#include <string_view>

namespace {

constexpr wchar_t kWindowClassName[] = L"LOGH7_GREENFIELD_CLIENT";
constexpr wchar_t kVersion[] = L"Logh7Client 0.1.0\n";
constexpr UINT_PTR kSmokeTimerId = 1;

struct ClientOptions {
    std::wstring profile = L"default";
    std::wstring server = L"http://127.0.0.1:47910";
    std::optional<std::wstring> sessionId;
    std::filesystem::path legacyRoot;
    std::optional<unsigned> smokeExitMs;
    SIZE resolution{1920, 1080};
};

struct ParseResult {
    ClientOptions options;
    bool showVersion = false;
    std::wstring error;
};

void WriteHandle(const DWORD handleId, const std::wstring_view message) {
    const HANDLE handle = GetStdHandle(handleId);
    if (handle == nullptr || handle == INVALID_HANDLE_VALUE) {
        return;
    }

    const int byteCount = WideCharToMultiByte(
        CP_UTF8,
        0,
        message.data(),
        static_cast<int>(message.size()),
        nullptr,
        0,
        nullptr,
        nullptr);
    if (byteCount <= 0) {
        return;
    }

    std::string bytes(static_cast<std::size_t>(byteCount), '\0');
    WideCharToMultiByte(
        CP_UTF8,
        0,
        message.data(),
        static_cast<int>(message.size()),
        bytes.data(),
        byteCount,
        nullptr,
        nullptr);

    DWORD written = 0;
    WriteFile(handle, bytes.data(), static_cast<DWORD>(bytes.size()), &written, nullptr);
}

bool IsUuid(const std::wstring_view value) {
    if (value.size() != 36) {
        return false;
    }

    for (std::size_t index = 0; index < value.size(); ++index) {
        const bool isSeparator = index == 8 || index == 13 || index == 18 || index == 23;
        if (isSeparator) {
            if (value[index] != L'-') {
                return false;
            }
        } else if (std::iswxdigit(value[index]) == 0) {
            return false;
        }
    }
    return true;
}

bool IsHttpUrl(const std::wstring_view value) {
    constexpr std::wstring_view http = L"http://";
    constexpr std::wstring_view https = L"https://";
    return (value.starts_with(http) && value.size() > http.size()) ||
           (value.starts_with(https) && value.size() > https.size());
}

std::optional<unsigned> ParseUnsigned(const std::wstring_view value) {
    if (value.empty()) {
        return std::nullopt;
    }

    std::string narrow;
    narrow.reserve(value.size());
    for (const wchar_t character : value) {
        if (character < L'0' || character > L'9') {
            return std::nullopt;
        }
        narrow.push_back(static_cast<char>(character));
    }

    unsigned parsed = 0;
    const auto [end, error] = std::from_chars(narrow.data(), narrow.data() + narrow.size(), parsed);
    if (error != std::errc{} || end != narrow.data() + narrow.size()) {
        return std::nullopt;
    }
    return parsed;
}

std::optional<SIZE> ParseResolution(const std::wstring_view value) {
    if (value == L"1280x720") {
        return SIZE{1280, 720};
    }
    if (value == L"1920x1080") {
        return SIZE{1920, 1080};
    }
    if (value == L"2560x1440") {
        return SIZE{2560, 1440};
    }
    if (value == L"3840x2160") {
        return SIZE{3840, 2160};
    }
    return std::nullopt;
}

ParseResult ParseCommandLine() {
    ParseResult result;
    int argumentCount = 0;
    LPWSTR* arguments = CommandLineToArgvW(GetCommandLineW(), &argumentCount);
    if (arguments == nullptr) {
        result.error = L"unable to read command line";
        return result;
    }

    const auto releaseArguments = [&arguments]() { LocalFree(arguments); };
    for (int index = 1; index < argumentCount; ++index) {
        const std::wstring_view option = arguments[index];
        if (option == L"--version") {
            result.showVersion = true;
            continue;
        }

        if (index + 1 >= argumentCount) {
            result.error = L"missing value for " + std::wstring(option);
            releaseArguments();
            return result;
        }
        const std::wstring value = arguments[++index];

        if (option == L"--profile") {
            if (value.empty()) {
                result.error = L"--profile requires a non-empty name";
                releaseArguments();
                return result;
            }
            result.options.profile = value;
        } else if (option == L"--server") {
            if (!IsHttpUrl(value)) {
                result.error = L"--server requires an http or https URL";
                releaseArguments();
                return result;
            }
            result.options.server = value;
        } else if (option == L"--session") {
            if (!IsUuid(value)) {
                result.error = L"--session requires a UUID";
                releaseArguments();
                return result;
            }
            result.options.sessionId = value;
        } else if (option == L"--legacy-root") {
            if (value.empty()) {
                result.error = L"--legacy-root requires a path";
                releaseArguments();
                return result;
            }
            result.options.legacyRoot = value;
        } else if (option == L"--resolution") {
            const std::optional<SIZE> resolution = ParseResolution(value);
            if (!resolution.has_value()) {
                result.error = L"--resolution must be 1280x720, 1920x1080, 2560x1440, or 3840x2160";
                releaseArguments();
                return result;
            }
            result.options.resolution = resolution.value();
        } else if (option == L"--smoke-exit-ms") {
            const std::optional<unsigned> timeout = ParseUnsigned(value);
            if (!timeout.has_value() || timeout.value() == 0) {
                result.error = L"--smoke-exit-ms requires a positive integer";
                releaseArguments();
                return result;
            }
            result.options.smokeExitMs = timeout;
        } else {
            result.error = L"unknown option " + std::wstring(option);
            releaseArguments();
            return result;
        }
    }

    releaseArguments();
    return result;
}

void DrawTextLine(
    const HDC deviceContext,
    const std::wstring& text,
    RECT bounds,
    const COLORREF color,
    const UINT format) {
    SetTextColor(deviceContext, color);
    DrawTextW(deviceContext, text.c_str(), static_cast<int>(text.size()), &bounds, format | DT_NOPREFIX);
}

void PaintClient(const HWND window, const HDC deviceContext, const ClientOptions& options) {
    RECT client{};
    GetClientRect(window, &client);
    const int width = client.right - client.left;
    const int height = client.bottom - client.top;
    const UINT dpi = GetDpiForWindow(window);
    const int scale = std::max(1, static_cast<int>(dpi) / 96);
    const int margin = 24 * scale;
    const int headerBottom = 96 * scale;
    const int footerTop = height - (44 * scale);

    const HBRUSH background = CreateSolidBrush(RGB(5, 13, 17));
    FillRect(deviceContext, &client, background);
    DeleteObject(background);

    const HPEN framePen = CreatePen(PS_SOLID, std::max(1, scale), RGB(43, 102, 98));
    const HPEN gridPen = CreatePen(PS_SOLID, 1, RGB(19, 49, 52));
    const HGDIOBJ originalPen = SelectObject(deviceContext, framePen);

    MoveToEx(deviceContext, margin, headerBottom, nullptr);
    LineTo(deviceContext, width - margin, headerBottom);
    MoveToEx(deviceContext, margin, footerTop, nullptr);
    LineTo(deviceContext, width - margin, footerTop);

    SelectObject(deviceContext, gridPen);
    const int gridStep = 64 * scale;
    for (int x = margin; x <= width - margin; x += gridStep) {
        MoveToEx(deviceContext, x, headerBottom, nullptr);
        LineTo(deviceContext, x, footerTop);
    }
    for (int y = headerBottom; y <= footerTop; y += gridStep) {
        MoveToEx(deviceContext, margin, y, nullptr);
        LineTo(deviceContext, width - margin, y);
    }

    SelectObject(deviceContext, originalPen);
    DeleteObject(framePen);
    DeleteObject(gridPen);

    SetBkMode(deviceContext, TRANSPARENT);
    const HFONT headingFont = CreateFontW(
        -MulDiv(18, static_cast<int>(dpi), 96),
        0,
        0,
        0,
        FW_BOLD,
        FALSE,
        FALSE,
        FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        FIXED_PITCH | FF_MODERN,
        L"Consolas");
    const HFONT detailFont = CreateFontW(
        -MulDiv(14, static_cast<int>(dpi), 96),
        0,
        0,
        0,
        FW_NORMAL,
        FALSE,
        FALSE,
        FALSE,
        DEFAULT_CHARSET,
        OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,
        CLEARTYPE_QUALITY,
        FIXED_PITCH | FF_MODERN,
        L"Consolas");

    const HGDIOBJ originalFont = SelectObject(deviceContext, headingFont);
    RECT profileBounds{margin, 16 * scale, width - margin, 46 * scale};
    DrawTextLine(
        deviceContext,
        L"PROFILE  " + options.profile,
        profileBounds,
        RGB(156, 221, 188),
        DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    SelectObject(deviceContext, detailFont);
    RECT serverBounds{margin, 50 * scale, width - margin, 80 * scale};
    DrawTextLine(
        deviceContext,
        L"SERVER   " + options.server,
        serverBounds,
        RGB(102, 183, 173),
        DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    const std::wstring session = options.sessionId.value_or(L"UNASSIGNED");
    RECT sessionBounds{margin, footerTop + (8 * scale), width / 2, height - (8 * scale)};
    DrawTextLine(
        deviceContext,
        L"SESSION  " + session,
        sessionBounds,
        RGB(102, 183, 173),
        DT_LEFT | DT_SINGLELINE | DT_VCENTER);

    RECT diagnosticBounds{width / 2, footerTop + (8 * scale), width - margin, height - (8 * scale)};
    DrawTextLine(
        deviceContext,
        L"DIAGNOSTIC BOOTSTRAP / NO FIDELITY CLAIM",
        diagnosticBounds,
        RGB(115, 133, 126),
        DT_RIGHT | DT_SINGLELINE | DT_VCENTER);

    SelectObject(deviceContext, originalFont);
    DeleteObject(headingFont);
    DeleteObject(detailFont);
}

LRESULT CALLBACK WindowProcedure(const HWND window, const UINT message, const WPARAM wParam, const LPARAM lParam) {
    if (message == WM_NCCREATE) {
        const auto* create = reinterpret_cast<const CREATESTRUCTW*>(lParam);
        SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(create->lpCreateParams));
    }

    const auto* options = reinterpret_cast<const ClientOptions*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
    case WM_GETMINMAXINFO:
        if (options != nullptr) {
            auto* bounds = reinterpret_cast<MINMAXINFO*>(lParam);
            const UINT dpi = std::max<UINT>(96, GetDpiForWindow(window));
            RECT requested{0, 0, options->resolution.cx, options->resolution.cy};
            AdjustWindowRectExForDpi(&requested, WS_OVERLAPPEDWINDOW, FALSE, 0, dpi);
            bounds->ptMaxTrackSize.x = std::max(bounds->ptMaxTrackSize.x, requested.right - requested.left);
            bounds->ptMaxTrackSize.y = std::max(bounds->ptMaxTrackSize.y, requested.bottom - requested.top);
        }
        return 0;
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT: {
        PAINTSTRUCT paint{};
        const HDC deviceContext = BeginPaint(window, &paint);
        if (options != nullptr) {
            PaintClient(window, deviceContext, *options);
        }
        EndPaint(window, &paint);
        return 0;
    }
    case WM_DPICHANGED: {
        const auto* suggested = reinterpret_cast<const RECT*>(lParam);
        SetWindowPos(
            window,
            nullptr,
            suggested->left,
            suggested->top,
            suggested->right - suggested->left,
            suggested->bottom - suggested->top,
            SWP_NOACTIVATE | SWP_NOZORDER);
        return 0;
    }
    case WM_TIMER:
        if (wParam == kSmokeTimerId) {
            KillTimer(window, kSmokeTimerId);
            DestroyWindow(window);
        }
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProcW(window, message, wParam, lParam);
    }
}

}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR commandLine, int showCommand) {
    static_cast<void>(commandLine);

    ParseResult parsed = ParseCommandLine();
    if (!parsed.error.empty()) {
        WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: " + parsed.error + L"\n");
        return 2;
    }
    if (parsed.showVersion) {
        WriteHandle(STD_OUTPUT_HANDLE, kVersion);
        return 0;
    }

    if (!parsed.options.legacyRoot.empty()) {
        std::error_code error;
        if (!std::filesystem::is_directory(parsed.options.legacyRoot, error) || error) {
            WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: --legacy-root must name an existing directory\n");
            return 3;
        }
    }

    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);

    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.style = CS_HREDRAW | CS_VREDRAW;
    windowClass.lpfnWndProc = WindowProcedure;
    windowClass.hInstance = instance;
    windowClass.hCursor = LoadCursorW(nullptr, IDC_CROSS);
    windowClass.hbrBackground = nullptr;
    windowClass.lpszClassName = kWindowClassName;
    if (RegisterClassExW(&windowClass) == 0) {
        WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: unable to register the client window\n");
        return 1;
    }

    const DWORD windowStyle = WS_OVERLAPPEDWINDOW;
    const UINT dpi = GetDpiForSystem();
    RECT windowBounds{0, 0, parsed.options.resolution.cx, parsed.options.resolution.cy};
    AdjustWindowRectExForDpi(&windowBounds, windowStyle, FALSE, 0, dpi);

    const std::wstring title = L"LOGH7 Greenfield - " + parsed.options.profile;
    const HWND window = CreateWindowExW(
        0,
        kWindowClassName,
        title.c_str(),
        windowStyle,
        CW_USEDEFAULT,
        CW_USEDEFAULT,
        windowBounds.right - windowBounds.left,
        windowBounds.bottom - windowBounds.top,
        nullptr,
        nullptr,
        instance,
        &parsed.options);
    if (window == nullptr) {
        WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: unable to create the client window\n");
        return 1;
    }

    if (SetWindowPos(
            window,
            nullptr,
            0,
            0,
            windowBounds.right - windowBounds.left,
            windowBounds.bottom - windowBounds.top,
            SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER) == FALSE) {
        WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: unable to size the client window\n");
        DestroyWindow(window);
        return 1;
    }

    ShowWindow(window, showCommand == 0 ? SW_SHOWNORMAL : showCommand);
    UpdateWindow(window);

    if (parsed.options.smokeExitMs.has_value()) {
        if (SetTimer(window, kSmokeTimerId, parsed.options.smokeExitMs.value(), nullptr) == 0) {
            WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: unable to start the smoke timer\n");
            DestroyWindow(window);
            return 1;
        }
    }

    MSG message{};
    while (true) {
        const BOOL result = GetMessageW(&message, nullptr, 0, 0);
        if (result == 0) {
            return static_cast<int>(message.wParam);
        }
        if (result == -1) {
            WriteHandle(STD_ERROR_HANDLE, L"Logh7Client: window message loop failed\n");
            return 1;
        }
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
}
