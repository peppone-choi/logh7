#include <logh7/legacy/image_decode.hpp>
#include <logh7/legacy/legacy_data_provider.hpp>

#include <windows.h>
#include <objbase.h>

#include <algorithm>
#include <cstddef>
#include <filesystem>
#include <iostream>
#include <optional>
#include <string_view>

namespace {

using logh7::legacy::LegacyResourceId;

struct ProbeResource {
    LegacyResourceId id;
    std::string_view decoder;
    bool usesWic;
};

std::optional<ProbeResource> ParseResource(const std::wstring_view value) {
    if (value == L"ui.panel.strategy.atlas") {
        return ProbeResource{
            LegacyResourceId::StrategyPanelAtlas,
            "tga.type2.bgra32.bottom-origin",
            false,
        };
    }
    if (value == L"ui.strategy.grid-glow") {
        return ProbeResource{LegacyResourceId::StrategyGridGlow, "wic.bmp", true};
    }
    if (value == L"ui.strategy.article-background-probe") {
        return ProbeResource{LegacyResourceId::StrategyArticleBackgroundProbe, "wic.jpeg", true};
    }
    return std::nullopt;
}

void PrintUsage() {
    std::cerr << "usage: LegacyImageProbe --legacy-root <path> --resource <logical-id>\n";
}

}

int wmain(const int argumentCount, wchar_t** arguments) {
    std::filesystem::path root;
    std::optional<ProbeResource> resource;
    for (int index = 1; index < argumentCount; ++index) {
        const std::wstring_view argument(arguments[index]);
        if (argument == L"--legacy-root" && index + 1 < argumentCount) {
            root = arguments[++index];
        } else if (argument == L"--resource" && index + 1 < argumentCount) {
            resource = ParseResource(arguments[++index]);
        } else {
            PrintUsage();
            return 2;
        }
    }
    if (root.empty() || !resource.has_value()) {
        PrintUsage();
        return 2;
    }

    auto provider = logh7::legacy::LegacyDataProvider::OpenStrategyShell(root);
    if (!provider.has_value()) {
        std::cerr << logh7::legacy::StableErrorCode(provider.error().code);
        if (!provider.error().logicalId.empty()) {
            std::cerr << ' ' << provider.error().logicalId;
        }
        std::cerr << '\n';
        return 3;
    }
    auto blob = provider->Load(resource->id);
    if (!blob.has_value()) {
        std::cerr << logh7::legacy::StableErrorCode(blob.error().code) << '\n';
        return 3;
    }

    HRESULT comResult = S_OK;
    if (resource->usesWic) {
        comResult = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (FAILED(comResult)) {
            std::cerr << "WIC_COM_UNAVAILABLE\n";
            return 6;
        }
    }

    auto image = resource->usesWic
        ? logh7::legacy::DecodeWicBmpOrJpeg(blob->bytes)
        : logh7::legacy::DecodeStrategyPanelTga(blob->bytes);
    if (resource->usesWic) {
        CoUninitialize();
    }
    if (!image.has_value()) {
        std::cerr << logh7::legacy::StableErrorCode(image.error().code) << '\n';
        return 6;
    }

    std::size_t nontransparentPixels = 0;
    for (std::size_t alphaIndex = 3; alphaIndex < image->pixels.size(); alphaIndex += 4) {
        if (image->pixels[alphaIndex] != std::byte{0}) {
            ++nontransparentPixels;
        }
    }
    std::cout << "{\"logicalId\":\"" << blob->logicalId
              << "\",\"decoder\":\"" << resource->decoder
              << "\",\"width\":" << image->width
              << ",\"height\":" << image->height
              << ",\"stride\":" << image->stride
              << ",\"outputFormat\":\"PBGRA32_TOP_DOWN\""
              << ",\"nontransparentPixelCount\":" << nontransparentPixels
              << "}\n";
    return 0;
}
