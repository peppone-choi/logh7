#pragma once

#include <windows.h>

#include <exception>
#include <span>
#include <string_view>

namespace logh7::client {

struct StrategyShellLayout {
    RECT mainView;
    RECT communication;
    RECT characterStatus;
    RECT minimap;
    RECT authorityCard;
    RECT memberList;
    RECT iconRail;
    RECT diagnosticStrip;
};

struct AtlasRegion {
    std::string_view logicalId;
    RECT source;
    RECT capInsets;
    std::string_view provenance;
};

enum class StrategyShellLayoutErrorCode {
    UnsupportedViewport,
    InvalidDpi,
    ArithmeticOverflow,
};

class StrategyShellLayoutError final : public std::exception {
public:
    explicit StrategyShellLayoutError(StrategyShellLayoutErrorCode code) noexcept;
    StrategyShellLayoutErrorCode code() const noexcept;
    const char* what() const noexcept override;

private:
    StrategyShellLayoutErrorCode code_;
};

StrategyShellLayout ComputeStrategyShellLayout(SIZE clientPixels, UINT dpi);
std::span<const AtlasRegion> StrategyShellAtlasRegions();

}
