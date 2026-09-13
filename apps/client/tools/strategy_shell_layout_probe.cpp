#include "strategy_shell_layout.hpp"

#include <charconv>
#include <cstdlib>
#include <iostream>
#include <string_view>

namespace {

bool ParseLong(const std::string_view text, LONG& value) {
    const char* begin = text.data();
    const char* end = text.data() + text.size();
    const auto result = std::from_chars(begin, end, value);
    return result.ec == std::errc{} && result.ptr == end;
}

bool ParseUint(const std::string_view text, UINT& value) {
    const char* begin = text.data();
    const char* end = text.data() + text.size();
    const auto result = std::from_chars(begin, end, value);
    return result.ec == std::errc{} && result.ptr == end;
}

void WriteRect(const std::string_view name, const RECT& rectangle, const bool trailingComma) {
    std::cout << "\"" << name << "\":{";
    std::cout << "\"left\":" << rectangle.left << ',';
    std::cout << "\"top\":" << rectangle.top << ',';
    std::cout << "\"right\":" << rectangle.right << ',';
    std::cout << "\"bottom\":" << rectangle.bottom << '}';
    if (trailingComma) {
        std::cout << ',';
    }
}

void WriteLayout(const SIZE client, const UINT dpi, const logh7::client::StrategyShellLayout& layout) {
    std::cout << "{\"valid\":true,\"manualRegionCount\":7,";
    std::cout << "\"clientWidth\":" << client.cx << ',';
    std::cout << "\"clientHeight\":" << client.cy << ',';
    std::cout << "\"dpi\":" << dpi << ',';
    WriteRect("mainView", layout.mainView, true);
    WriteRect("communication", layout.communication, true);
    WriteRect("characterStatus", layout.characterStatus, true);
    WriteRect("minimap", layout.minimap, true);
    WriteRect("authorityCard", layout.authorityCard, true);
    WriteRect("memberList", layout.memberList, true);
    WriteRect("iconRail", layout.iconRail, true);
    WriteRect("diagnosticStrip", layout.diagnosticStrip, false);
    std::cout << "}\n";
}

int ExitCodeFor(const logh7::client::StrategyShellLayoutErrorCode code) {
    switch (code) {
    case logh7::client::StrategyShellLayoutErrorCode::UnsupportedViewport:
        return 2;
    case logh7::client::StrategyShellLayoutErrorCode::InvalidDpi:
        return 3;
    case logh7::client::StrategyShellLayoutErrorCode::ArithmeticOverflow:
        return 4;
    }
    return 5;
}

}

int main(const int argumentCount, char* arguments[]) {
    if (argumentCount != 4) {
        std::cerr << "usage: StrategyShellLayoutProbe.exe <width> <height> <dpi>\n";
        return 1;
    }

    SIZE client{};
    UINT dpi = 0;
    if (!ParseLong(arguments[1], client.cx) || !ParseLong(arguments[2], client.cy) ||
        !ParseUint(arguments[3], dpi)) {
        std::cerr << "{\"valid\":false,\"error\":\"LAYOUT_INVALID_ARGUMENT\"}\n";
        return 1;
    }

    try {
        const logh7::client::StrategyShellLayout layout =
            logh7::client::ComputeStrategyShellLayout(client, dpi);
        WriteLayout(client, dpi, layout);
        return EXIT_SUCCESS;
    } catch (const logh7::client::StrategyShellLayoutError& error) {
        std::cerr << "{\"valid\":false,\"error\":\"" << error.what() << "\"}\n";
        return ExitCodeFor(error.code());
    }
}
