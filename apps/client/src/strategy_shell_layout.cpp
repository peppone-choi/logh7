#include "strategy_shell_layout.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>

namespace logh7::client {
namespace {

constexpr LONG kMinimumRightConsoleWidth = 420;

LONG CheckedLong(const std::int64_t value) {
    if (value < static_cast<std::int64_t>(std::numeric_limits<LONG>::min()) ||
        value > static_cast<std::int64_t>(std::numeric_limits<LONG>::max())) {
        throw StrategyShellLayoutError(StrategyShellLayoutErrorCode::ArithmeticOverflow);
    }
    return static_cast<LONG>(value);
}

LONG ScaleFrom96(const LONG value, const UINT dpi) {
    const std::int64_t numerator =
        static_cast<std::int64_t>(value) * static_cast<std::int64_t>(dpi) + 48;
    return CheckedLong(numerator / 96);
}

LONG RoundedRatio(const LONG value, const LONG numerator, const LONG denominator) {
    const std::int64_t scaled =
        static_cast<std::int64_t>(value) * static_cast<std::int64_t>(numerator) +
        static_cast<std::int64_t>(denominator / 2);
    return CheckedLong(scaled / denominator);
}

LONG CheckedAdd(const LONG first, const LONG second) {
    return CheckedLong(static_cast<std::int64_t>(first) + static_cast<std::int64_t>(second));
}

LONG CheckedSubtract(const LONG first, const LONG second) {
    return CheckedLong(static_cast<std::int64_t>(first) - static_cast<std::int64_t>(second));
}

bool HasPositiveArea(const RECT& rectangle) {
    return rectangle.right > rectangle.left && rectangle.bottom > rectangle.top;
}

bool IsInside(const RECT& rectangle, const SIZE client) {
    return rectangle.left >= 0 && rectangle.top >= 0 &&
           rectangle.right <= client.cx && rectangle.bottom <= client.cy;
}

void RequireValidRegion(const RECT& rectangle, const SIZE client) {
    if (!HasPositiveArea(rectangle) || !IsInside(rectangle, client)) {
        throw StrategyShellLayoutError(StrategyShellLayoutErrorCode::UnsupportedViewport);
    }
}

constexpr std::array<AtlasRegion, 3> kAtlasRegions{
    AtlasRegion{"status_frame", RECT{0, 0, 742, 198}, RECT{24, 24, 24, 24},
                "authored-crop-candidate"},
    AtlasRegion{"command_frame", RECT{0, 230, 742, 398}, RECT{20, 20, 20, 20},
                "authored-crop-candidate"},
    AtlasRegion{"minimap_frame", RECT{0, 402, 339, 598}, RECT{16, 16, 16, 16},
                "authored-crop-candidate"},
};

}

StrategyShellLayoutError::StrategyShellLayoutError(const StrategyShellLayoutErrorCode code) noexcept
    : code_(code) {}

StrategyShellLayoutErrorCode StrategyShellLayoutError::code() const noexcept {
    return code_;
}

const char* StrategyShellLayoutError::what() const noexcept {
    switch (code_) {
    case StrategyShellLayoutErrorCode::UnsupportedViewport:
        return "LAYOUT_UNSUPPORTED_VIEWPORT";
    case StrategyShellLayoutErrorCode::InvalidDpi:
        return "LAYOUT_INVALID_DPI";
    case StrategyShellLayoutErrorCode::ArithmeticOverflow:
        return "LAYOUT_ARITHMETIC_OVERFLOW";
    }
    return "LAYOUT_UNKNOWN_ERROR";
}

StrategyShellLayout ComputeStrategyShellLayout(const SIZE clientPixels, const UINT dpi) {
    if (dpi == 0) {
        throw StrategyShellLayoutError(StrategyShellLayoutErrorCode::InvalidDpi);
    }
    if (clientPixels.cx <= 0 || clientPixels.cy <= 0) {
        throw StrategyShellLayoutError(StrategyShellLayoutErrorCode::UnsupportedViewport);
    }

    const LONG margin = ScaleFrom96(8, dpi);
    const LONG diagnosticHeight = ScaleFrom96(28, dpi);
    const LONG hudHeight = std::clamp(RoundedRatio(clientPixels.cy, 25, 100), 180L, 270L);
    const LONG iconRailHeight = ScaleFrom96(34, dpi);
    const LONG diagnosticTop = CheckedSubtract(clientPixels.cy, diagnosticHeight);
    const LONG hudTop = CheckedSubtract(diagnosticTop, hudHeight);

    const LONG characterWidth = std::clamp(RoundedRatio(clientPixels.cx, 25, 100), 300L, 480L);
    const LONG minimapWidth = std::clamp(RoundedRatio(clientPixels.cx, 31, 100), 360L, 580L);
    const LONG rightConsoleLeft = CheckedAdd(characterWidth, minimapWidth);
    const LONG rightConsoleWidth = CheckedSubtract(clientPixels.cx, rightConsoleLeft);
    if (rightConsoleWidth < kMinimumRightConsoleWidth || hudTop <= 0 ||
        diagnosticTop <= hudTop || iconRailHeight >= hudHeight) {
        throw StrategyShellLayoutError(StrategyShellLayoutErrorCode::UnsupportedViewport);
    }

    const LONG authorityWidth = RoundedRatio(rightConsoleWidth, 58, 100);
    const LONG authorityRight = CheckedAdd(rightConsoleLeft, authorityWidth);
    const LONG iconRailTop = CheckedSubtract(diagnosticTop, iconRailHeight);
    const LONG communicationWidth =
        std::clamp(RoundedRatio(clientPixels.cx, 36, 100), 420L, 620L);
    const LONG communicationHeight =
        std::clamp(RoundedRatio(clientPixels.cy, 19, 100), 144L, 192L);

    StrategyShellLayout layout{
        RECT{0, 0, clientPixels.cx, hudTop},
        RECT{margin, CheckedSubtract(hudTop, communicationHeight),
             CheckedAdd(margin, communicationWidth), hudTop},
        RECT{0, hudTop, characterWidth, diagnosticTop},
        RECT{characterWidth, hudTop, rightConsoleLeft, diagnosticTop},
        RECT{rightConsoleLeft, hudTop, authorityRight, iconRailTop},
        RECT{authorityRight, hudTop, clientPixels.cx, iconRailTop},
        RECT{rightConsoleLeft, iconRailTop, clientPixels.cx, diagnosticTop},
        RECT{0, diagnosticTop, clientPixels.cx, clientPixels.cy},
    };

    const std::array<RECT, 8> rectangles{
        layout.mainView,
        layout.communication,
        layout.characterStatus,
        layout.minimap,
        layout.authorityCard,
        layout.memberList,
        layout.iconRail,
        layout.diagnosticStrip,
    };
    for (const RECT& rectangle : rectangles) {
        RequireValidRegion(rectangle, clientPixels);
    }
    return layout;
}

std::span<const AtlasRegion> StrategyShellAtlasRegions() {
    return kAtlasRegions;
}

}
