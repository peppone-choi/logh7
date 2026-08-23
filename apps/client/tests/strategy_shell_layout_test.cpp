#include "strategy_shell_layout.hpp"
#include "strategy_shell_text.hpp"

#include <array>
#include <cstdlib>
#include <exception>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>

namespace {

using logh7::client::AtlasRegion;
using logh7::client::StrategyShellLayout;
using logh7::client::StrategyShellLayoutError;
using logh7::client::StrategyShellLayoutErrorCode;
using logh7::client::StrategyShellText;

void Require(const bool condition, const std::string_view message) {
    if (!condition) {
        throw std::runtime_error(std::string(message));
    }
}

bool EqualRect(const RECT& actual, const RECT& expected) {
    return actual.left == expected.left && actual.top == expected.top &&
           actual.right == expected.right && actual.bottom == expected.bottom;
}

bool HasPositiveArea(const RECT& rectangle) {
    return rectangle.right > rectangle.left && rectangle.bottom > rectangle.top;
}

bool IsInside(const RECT& rectangle, const SIZE client) {
    return rectangle.left >= 0 && rectangle.top >= 0 &&
           rectangle.right <= client.cx && rectangle.bottom <= client.cy;
}

bool Overlaps(const RECT& first, const RECT& second) {
    return first.left < second.right && first.right > second.left &&
           first.top < second.bottom && first.bottom > second.top;
}

std::array<RECT, 8> AllRectangles(const StrategyShellLayout& layout) {
    return {
        layout.mainView,
        layout.communication,
        layout.characterStatus,
        layout.minimap,
        layout.authorityCard,
        layout.memberList,
        layout.iconRail,
        layout.diagnosticStrip,
    };
}

void CheckRequiredRegions(const SIZE client, const StrategyShellLayout& layout) {
    const auto rectangles = AllRectangles(layout);
    for (const RECT& rectangle : rectangles) {
        Require(HasPositiveArea(rectangle), "required rectangle has no area");
        Require(IsInside(rectangle, client), "required rectangle is out of bounds");
    }

    for (std::size_t first = 0; first < rectangles.size(); ++first) {
        for (std::size_t second = first + 1; second < rectangles.size(); ++second) {
            if (!Overlaps(rectangles[first], rectangles[second])) {
                continue;
            }
            Require(first == 0 && second == 1, "a region other than communication overlaps main view");
        }
    }

    Require(Overlaps(layout.mainView, layout.communication), "communication must overlay main view");
    Require(layout.communication.bottom == layout.mainView.bottom, "communication must end at HUD top");
    Require(layout.diagnosticStrip.left == 0 && layout.diagnosticStrip.right == client.cx,
            "diagnostic strip must span the full client width");
}

void CheckExactLayout1280() {
    constexpr SIZE client{1280, 720};
    const StrategyShellLayout layout = logh7::client::ComputeStrategyShellLayout(client, 96);
    CheckRequiredRegions(client, layout);

    Require(EqualRect(layout.mainView, RECT{0, 0, 1280, 512}), "1280 main view changed");
    Require(EqualRect(layout.communication, RECT{8, 368, 469, 512}), "1280 communication changed");
    Require(EqualRect(layout.characterStatus, RECT{0, 512, 320, 692}), "1280 character panel changed");
    Require(EqualRect(layout.minimap, RECT{320, 512, 717, 692}), "1280 minimap changed");
    Require(EqualRect(layout.authorityCard, RECT{717, 512, 1044, 658}), "1280 authority panel changed");
    Require(EqualRect(layout.memberList, RECT{1044, 512, 1280, 658}), "1280 member panel changed");
    Require(EqualRect(layout.iconRail, RECT{717, 658, 1280, 692}), "1280 icon rail changed");
    Require(EqualRect(layout.diagnosticStrip, RECT{0, 692, 1280, 720}), "1280 diagnostics changed");
}

void CheckExactLayout1920() {
    constexpr SIZE client{1920, 1080};
    const StrategyShellLayout layout = logh7::client::ComputeStrategyShellLayout(client, 96);
    CheckRequiredRegions(client, layout);

    Require(EqualRect(layout.mainView, RECT{0, 0, 1920, 782}), "1920 main view changed");
    Require(EqualRect(layout.communication, RECT{8, 590, 628, 782}), "1920 communication changed");
    Require(EqualRect(layout.characterStatus, RECT{0, 782, 480, 1052}), "1920 character panel changed");
    Require(EqualRect(layout.minimap, RECT{480, 782, 1060, 1052}), "1920 minimap changed");
    Require(EqualRect(layout.authorityCard, RECT{1060, 782, 1559, 1018}), "1920 authority panel changed");
    Require(EqualRect(layout.memberList, RECT{1559, 782, 1920, 1018}), "1920 member panel changed");
    Require(EqualRect(layout.iconRail, RECT{1060, 1018, 1920, 1052}), "1920 icon rail changed");
    Require(EqualRect(layout.diagnosticStrip, RECT{0, 1052, 1920, 1080}), "1920 diagnostics changed");
}

void CheckDpiScaling() {
    constexpr SIZE client{1280, 720};
    const StrategyShellLayout layout = logh7::client::ComputeStrategyShellLayout(client, 144);
    CheckRequiredRegions(client, layout);
    Require(layout.communication.left == 12, "safe margin did not scale at 150 percent DPI");
    Require(layout.diagnosticStrip.top == 678, "diagnostic strip did not scale at 150 percent DPI");
    Require(layout.iconRail.top == 627, "icon rail did not scale at 150 percent DPI");
}

void CheckRejectedLayouts() {
    try {
        static_cast<void>(logh7::client::ComputeStrategyShellLayout(SIZE{1024, 600}, 96));
        Require(false, "undersized viewport was accepted");
    } catch (const StrategyShellLayoutError& error) {
        Require(error.code() == StrategyShellLayoutErrorCode::UnsupportedViewport,
                "undersized viewport returned the wrong error");
    }

    try {
        static_cast<void>(logh7::client::ComputeStrategyShellLayout(SIZE{1280, 720}, 0));
        Require(false, "zero DPI was accepted");
    } catch (const StrategyShellLayoutError& error) {
        Require(error.code() == StrategyShellLayoutErrorCode::InvalidDpi,
                "zero DPI returned the wrong error");
    }
}

void CheckAtlasRegions() {
    const std::span<const AtlasRegion> regions = logh7::client::StrategyShellAtlasRegions();
    Require(regions.size() == 3, "atlas registry must contain exactly three crop candidates");

    constexpr std::array<std::string_view, 3> logicalIds{
        "status_frame",
        "command_frame",
        "minimap_frame",
    };
    constexpr std::array<RECT, 3> sources{
        RECT{0, 0, 742, 198},
        RECT{0, 230, 742, 398},
        RECT{0, 402, 339, 598},
    };

    for (std::size_t index = 0; index < regions.size(); ++index) {
        const AtlasRegion& region = regions[index];
        Require(region.logicalId == logicalIds[index], "atlas logical ID changed");
        Require(EqualRect(region.source, sources[index]), "atlas source rectangle changed");
        Require(HasPositiveArea(region.source), "atlas crop has no area");
        Require(IsInside(region.source, SIZE{1024, 1024}), "atlas crop is outside the atlas");
        Require(!EqualRect(region.source, RECT{0, 0, 1024, 1024}), "full atlas was registered as a crop");
        Require(region.provenance == "authored-crop-candidate", "atlas provenance changed");
        Require(region.capInsets.left > 0 && region.capInsets.top > 0 &&
                    region.capInsets.right > 0 && region.capInsets.bottom > 0,
                "atlas cap insets must be explicit positive data");
    }
}

std::array<std::wstring_view, 7> RegionLabels(const StrategyShellText& text) {
    return {
        text.mainViewLabel,
        text.communicationLabel,
        text.characterStatusLabel,
        text.minimapLabel,
        text.authorityCardLabel,
        text.memberListLabel,
        text.iconRailLabel,
    };
}

void RequireCompleteText(const StrategyShellText& text) {
    for (const std::wstring_view label : RegionLabels(text)) {
        Require(!label.empty(), "a manual region label was dropped");
    }
    Require(!text.sessionUnassigned.empty(), "session empty state was dropped");
    Require(!text.authorityUnavailable.empty(), "authority empty state was dropped");
    Require(!text.memberListUnavailable.empty(), "member-list empty state was dropped");
    Require(!text.galaxyModelUnparsed.empty(), "galaxy-model empty state was dropped");
    Require(!text.diagnosticStatus.empty(), "diagnostic status was dropped");
}

void CheckTextModels() {
    const StrategyShellText korean = logh7::client::StrategyShellTextFor(L"ko-KR");
    RequireCompleteText(korean);
    Require(korean.locale == L"ko-KR", "Korean locale changed");
    Require(korean.fontFamily == L"Malgun Gothic", "Korean font changed");
    Require(korean.sessionUnassigned == L"세션 미지정", "typed session state changed");
    Require(korean.authorityUnavailable == L"권한 정보 미수신", "typed authority state changed");
    Require(korean.memberListUnavailable == L"멤버 목록 미수신", "typed member state changed");
    Require(korean.galaxyModelUnparsed == L"은하 지도 모델 미해석", "typed galaxy state changed");

    const StrategyShellText defaulted = logh7::client::StrategyShellTextFor(L"en-US");
    Require(defaulted.locale == L"ko-KR", "unknown locale did not default to Korean");
    Require(defaulted.sessionUnassigned == korean.sessionUnassigned, "default Korean state differs");

    const StrategyShellText japanese = logh7::client::StrategyShellTextFor(L"ja-JP");
    RequireCompleteText(japanese);
    Require(japanese.locale == L"ja-JP", "Japanese locale changed");
    Require(japanese.fontFamily == L"Yu Gothic UI", "Japanese font changed");
    Require(japanese.sessionUnassigned != korean.sessionUnassigned, "Japanese state reused Korean text");
}

}

int main() {
    try {
        CheckExactLayout1280();
        CheckExactLayout1920();
        CheckDpiScaling();
        CheckRejectedLayouts();
        CheckAtlasRegions();
        CheckTextModels();
        std::cout << "StrategyShellLayout tests passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception& error) {
        std::cerr << "StrategyShellLayout test failure: " << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
