#include <logh7/legacy/legacy_data_provider.hpp>

#include <windows.h>

#include <array>
#include <cstddef>
#include <filesystem>
#include <iostream>
#include <optional>
#include <string>
#include <string_view>

namespace {

using logh7::legacy::LegacyResourceId;

std::string Hex(const std::array<std::byte, 32>& bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result(bytes.size() * 2, '\0');
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        const auto value = std::to_integer<unsigned char>(bytes[index]);
        result[index * 2] = digits[value >> 4U];
        result[index * 2 + 1] = digits[value & 0x0fU];
    }
    return result;
}

std::optional<LegacyResourceId> ParseResource(const std::wstring_view value) {
    if (value == L"ui.panel.strategy.atlas") {
        return LegacyResourceId::StrategyPanelAtlas;
    }
    if (value == L"ui.strategy.grid-glow") {
        return LegacyResourceId::StrategyGridGlow;
    }
    if (value == L"ui.strategy.article-background-probe") {
        return LegacyResourceId::StrategyArticleBackgroundProbe;
    }
    return std::nullopt;
}

}

int wmain(const int argumentCount, wchar_t** arguments) {
    std::filesystem::path root;
    std::optional<LegacyResourceId> resource;
    for (int index = 1; index < argumentCount; ++index) {
        const std::wstring_view argument(arguments[index]);
        if (argument == L"--legacy-root" && index + 1 < argumentCount) {
            root = arguments[++index];
        } else if (argument == L"--resource" && index + 1 < argumentCount) {
            resource = ParseResource(arguments[++index]);
        } else {
            std::cerr << "usage: LegacyDataProviderProbe --legacy-root <path> --resource <logical-id>\n";
            return 2;
        }
    }
    if (root.empty() || !resource.has_value()) {
        std::cerr << "usage: LegacyDataProviderProbe --legacy-root <path> --resource <logical-id>\n";
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

    auto blob = provider->Load(*resource);
    if (!blob.has_value()) {
        std::cerr << logh7::legacy::StableErrorCode(blob.error().code) << '\n';
        return 3;
    }

    std::cout << "{\"logicalId\":\"" << blob->logicalId
              << "\",\"manifestRelativePath\":\"" << blob->manifestRelativePath.generic_string()
              << "\",\"byteLength\":" << blob->byteLength
              << ",\"sha256\":\"" << Hex(blob->sha256)
              << "\",\"manifestHash\":\"" << provider->manifestHashHex()
              << "\"}\n";
    return 0;
}
