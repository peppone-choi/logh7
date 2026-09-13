#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace logh7::legacy {

enum class LegacyResourceId {
    StrategyPanelAtlas,
    StrategyGridGlow,
    StrategyArticleBackgroundProbe,
};

enum class LegacyDataErrorCode {
    RootMissing,
    RootReparsePoint,
    PayloadNotFound,
    PayloadAmbiguous,
    AssetMissing,
    AssetReparsePoint,
    AssetOutsideRoot,
    SizeMismatch,
    HashMismatch,
    ReadFailed,
};

struct LegacyDataError {
    LegacyDataErrorCode code;
    std::string logicalId;
};

struct ValidatedBlob {
    LegacyResourceId id;
    std::string_view logicalId;
    std::filesystem::path manifestRelativePath;
    std::uint64_t byteLength;
    std::array<std::byte, 32> sha256;
    std::vector<std::byte> bytes;
};

struct LegacyManifestEntry {
    LegacyResourceId id;
    std::string_view logicalId;
    std::filesystem::path relativePath;
    std::uint64_t byteLength;
    std::array<std::byte, 32> sha256;
};

std::string_view StableErrorCode(LegacyDataErrorCode code) noexcept;

class LegacyDataProvider {
public:
    static std::expected<LegacyDataProvider, LegacyDataError> OpenStrategyShell(
        const std::filesystem::path& suppliedRoot);

#if defined(LOGH7_TESTING)
    static std::expected<LegacyDataProvider, LegacyDataError> OpenForTest(
        const std::filesystem::path& suppliedRoot,
        std::span<const LegacyManifestEntry> generatedFixtureManifest);
#endif

    std::expected<ValidatedBlob, LegacyDataError> Load(LegacyResourceId id) const;
    const std::filesystem::path& payloadRoot() const noexcept;
    std::string manifestHashHex() const;

private:
    struct StoredBlob {
        LegacyResourceId id;
        std::string logicalId;
        std::filesystem::path manifestRelativePath;
        std::uint64_t byteLength;
        std::array<std::byte, 32> sha256;
        std::vector<std::byte> bytes;
    };

    LegacyDataProvider(
        std::filesystem::path payloadRoot,
        std::vector<StoredBlob> blobs,
        std::array<std::byte, 32> manifestHash);

    static std::expected<LegacyDataProvider, LegacyDataError> Open(
        const std::filesystem::path& suppliedRoot,
        std::span<const LegacyManifestEntry> manifest);

    std::filesystem::path payloadRoot_;
    std::vector<StoredBlob> blobs_;
    std::array<std::byte, 32> manifestHash_{};
};

}
