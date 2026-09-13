#include <logh7/legacy/legacy_data_provider.hpp>

#include <windows.h>
#include <bcrypt.h>

#include <algorithm>
#include <array>
#include <limits>
#include <span>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using logh7::legacy::LegacyDataError;
using logh7::legacy::LegacyDataErrorCode;
using logh7::legacy::LegacyManifestEntry;
using logh7::legacy::LegacyResourceId;

class UniqueHandle {
public:
    UniqueHandle() = default;
    explicit UniqueHandle(const HANDLE handle) : handle_(handle) {}
    UniqueHandle(const UniqueHandle&) = delete;
    UniqueHandle& operator=(const UniqueHandle&) = delete;

    UniqueHandle(UniqueHandle&& other) noexcept : handle_(std::exchange(other.handle_, INVALID_HANDLE_VALUE)) {}

    UniqueHandle& operator=(UniqueHandle&& other) noexcept {
        if (this != &other) {
            Reset();
            handle_ = std::exchange(other.handle_, INVALID_HANDLE_VALUE);
        }
        return *this;
    }

    ~UniqueHandle() {
        Reset();
    }

    HANDLE get() const noexcept {
        return handle_;
    }

    explicit operator bool() const noexcept {
        return handle_ != nullptr && handle_ != INVALID_HANDLE_VALUE;
    }

private:
    void Reset() noexcept {
        if (*this) {
            CloseHandle(handle_);
        }
        handle_ = INVALID_HANDLE_VALUE;
    }

    HANDLE handle_ = INVALID_HANDLE_VALUE;
};

class AlgorithmHandle {
public:
    ~AlgorithmHandle() {
        if (handle_ != nullptr) {
            BCryptCloseAlgorithmProvider(handle_, 0);
        }
    }

    BCRYPT_ALG_HANDLE* address() noexcept {
        return &handle_;
    }

    BCRYPT_ALG_HANDLE get() const noexcept {
        return handle_;
    }

private:
    BCRYPT_ALG_HANDLE handle_ = nullptr;
};

class HashHandle {
public:
    ~HashHandle() {
        if (handle_ != nullptr) {
            BCryptDestroyHash(handle_);
        }
    }

    BCRYPT_HASH_HANDLE* address() noexcept {
        return &handle_;
    }

    BCRYPT_HASH_HANDLE get() const noexcept {
        return handle_;
    }

private:
    BCRYPT_HASH_HANDLE handle_ = nullptr;
};

bool Failed(const NTSTATUS status) noexcept {
    return status < 0;
}

std::array<std::byte, 32> ParseSha256(const std::string_view hex) {
    std::array<std::byte, 32> result{};
    const auto nibble = [](const char character) -> unsigned char {
        if (character >= '0' && character <= '9') {
            return static_cast<unsigned char>(character - '0');
        }
        return static_cast<unsigned char>(character - 'a' + 10);
    };
    for (std::size_t index = 0; index < result.size(); ++index) {
        result[index] = static_cast<std::byte>(
            (nibble(hex[index * 2]) << 4U) | nibble(hex[index * 2 + 1]));
    }
    return result;
}

std::span<const LegacyManifestEntry> StrategyShellManifest() {
    static const std::array<LegacyManifestEntry, 3> manifest{{
        {LegacyResourceId::StrategyPanelAtlas,
         "ui.panel.strategy.atlas",
         L"data/image/senryaku_panel/senryaku_mainpanel.tga",
         4'194'322,
         ParseSha256("864a9de33880cadecb9560d78813839228d5fbef0a865ad730df60c8c2e4791c")},
        {LegacyResourceId::StrategyGridGlow,
         "ui.strategy.grid-glow",
         L"data/image/strategy/grid_glow.bmp",
         786'488,
         ParseSha256("3916b0caf78790988b4de633d337e44d8956ab293441260e0553cb3f41881982")},
        {LegacyResourceId::StrategyArticleBackgroundProbe,
         "ui.strategy.article-background-probe",
         L"data/image/spot/bg005.jpg",
         367'094,
         ParseSha256("4e639db66a901e77e8600c0456b521f8ec057b0cf398377ec185f9472a79d099")},
    }};
    return manifest;
}

std::expected<std::array<std::byte, 32>, LegacyDataError> Sha256(
    const std::span<const std::byte> bytes,
    const std::string_view logicalId) {
    AlgorithmHandle algorithm;
    if (Failed(BCryptOpenAlgorithmProvider(
            algorithm.address(), BCRYPT_SHA256_ALGORITHM, nullptr, 0))) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, std::string(logicalId)});
    }

    DWORD objectLength = 0;
    DWORD hashLength = 0;
    DWORD copied = 0;
    if (Failed(BCryptGetProperty(
            algorithm.get(),
            BCRYPT_OBJECT_LENGTH,
            reinterpret_cast<PUCHAR>(&objectLength),
            sizeof(objectLength),
            &copied,
            0)) ||
        Failed(BCryptGetProperty(
            algorithm.get(),
            BCRYPT_HASH_LENGTH,
            reinterpret_cast<PUCHAR>(&hashLength),
            sizeof(hashLength),
            &copied,
            0)) ||
        hashLength != 32) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, std::string(logicalId)});
    }

    std::vector<UCHAR> hashObject(objectLength);
    HashHandle hash;
    if (Failed(BCryptCreateHash(
            algorithm.get(),
            hash.address(),
            hashObject.data(),
            static_cast<ULONG>(hashObject.size()),
            nullptr,
            0,
            0))) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, std::string(logicalId)});
    }

    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const auto chunkLength = static_cast<ULONG>(std::min<std::size_t>(
            bytes.size() - offset, std::numeric_limits<ULONG>::max()));
        auto* chunk = reinterpret_cast<PUCHAR>(
            const_cast<std::byte*>(bytes.data() + offset));
        if (Failed(BCryptHashData(hash.get(), chunk, chunkLength, 0))) {
            return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, std::string(logicalId)});
        }
        offset += chunkLength;
    }

    std::array<std::byte, 32> result{};
    if (Failed(BCryptFinishHash(
            hash.get(), reinterpret_cast<PUCHAR>(result.data()), static_cast<ULONG>(result.size()), 0))) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, std::string(logicalId)});
    }
    return result;
}

bool ConstantTimeEqual(
    const std::array<std::byte, 32>& left,
    const std::array<std::byte, 32>& right) noexcept {
    unsigned char difference = 0;
    for (std::size_t index = 0; index < left.size(); ++index) {
        difference = static_cast<unsigned char>(
            difference | std::to_integer<unsigned char>(left[index] ^ right[index]));
    }
    return difference == 0;
}

std::string Hex(const std::array<std::byte, 32>& bytes) {
    constexpr char digits[] = "0123456789abcdef";
    std::string result;
    result.resize(bytes.size() * 2);
    for (std::size_t index = 0; index < bytes.size(); ++index) {
        const auto value = std::to_integer<unsigned char>(bytes[index]);
        result[index * 2] = digits[value >> 4U];
        result[index * 2 + 1] = digits[value & 0x0fU];
    }
    return result;
}

std::expected<std::array<std::byte, 32>, LegacyDataError> HashManifest(
    const std::span<const LegacyManifestEntry> manifest) {
    std::vector<std::byte> serialized;
    for (const LegacyManifestEntry& entry : manifest) {
        const std::string path = entry.relativePath.generic_string();
        const std::string size = std::to_string(entry.byteLength);
        const auto append = [&serialized](const std::string_view value) {
            const auto* begin = reinterpret_cast<const std::byte*>(value.data());
            serialized.insert(serialized.end(), begin, begin + value.size());
            serialized.push_back(std::byte{0});
        };
        append(entry.logicalId);
        append(path);
        append(size);
        serialized.insert(serialized.end(), entry.sha256.begin(), entry.sha256.end());
    }
    return Sha256(serialized, "manifest");
}

std::expected<std::vector<std::filesystem::path>, LegacyDataError> DirectDirectories(
    const std::filesystem::path& root) {
    std::vector<std::filesystem::path> result;
    const std::filesystem::path pattern = root / L"*";
    WIN32_FIND_DATAW data{};
    const HANDLE findHandle = FindFirstFileW(pattern.c_str(), &data);
    if (findHandle == INVALID_HANDLE_VALUE) {
        const DWORD error = GetLastError();
        if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
            return result;
        }
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, {}});
    }

    do {
        const std::wstring_view name(data.cFileName);
        if (name == L"." || name == L"..") {
            continue;
        }
        const bool isDirectory = (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        const bool isReparse = (data.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        if (isDirectory && !isReparse) {
            result.push_back(root / data.cFileName);
        }
    } while (FindNextFileW(findHandle, &data) != FALSE);

    const DWORD finalError = GetLastError();
    FindClose(findHandle);
    if (finalError != ERROR_NO_MORE_FILES) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, {}});
    }
    return result;
}

bool Qualifies(
    const std::filesystem::path& candidate,
    const std::span<const LegacyManifestEntry> manifest) {
    return std::ranges::all_of(manifest, [&candidate](const LegacyManifestEntry& entry) {
        const DWORD attributes = GetFileAttributesW((candidate / entry.relativePath).c_str());
        return attributes != INVALID_FILE_ATTRIBUTES &&
            (attributes & FILE_ATTRIBUTE_DIRECTORY) == 0;
    });
}

std::expected<std::filesystem::path, LegacyDataError> FinalPath(
    const HANDLE handle,
    const LegacyDataErrorCode errorCode,
    const std::string_view logicalId) {
    const DWORD required = GetFinalPathNameByHandleW(
        handle, nullptr, 0, FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (required == 0) {
        return std::unexpected(LegacyDataError{errorCode, std::string(logicalId)});
    }
    std::vector<wchar_t> buffer(static_cast<std::size_t>(required) + 1);
    const DWORD written = GetFinalPathNameByHandleW(
        handle,
        buffer.data(),
        static_cast<DWORD>(buffer.size()),
        FILE_NAME_NORMALIZED | VOLUME_NAME_DOS);
    if (written == 0 || written >= buffer.size()) {
        return std::unexpected(LegacyDataError{errorCode, std::string(logicalId)});
    }
    return std::filesystem::path(std::wstring(buffer.data(), written));
}

std::wstring ComparablePath(const std::filesystem::path& path) {
    std::wstring value = path.native();
    constexpr std::wstring_view uncPrefix = L"\\\\?\\UNC\\";
    constexpr std::wstring_view devicePrefix = L"\\\\?\\";
    if (value.starts_with(uncPrefix)) {
        value = L"\\\\" + value.substr(uncPrefix.size());
    } else if (value.starts_with(devicePrefix)) {
        value.erase(0, devicePrefix.size());
    }
    std::ranges::replace(value, L'/', L'\\');
    while (value.size() > 3 && value.back() == L'\\') {
        value.pop_back();
    }
    return value;
}

bool IsWithinRoot(
    const std::filesystem::path& root,
    const std::filesystem::path& asset) {
    const std::wstring rootText = ComparablePath(root);
    const std::wstring assetText = ComparablePath(asset);
    if (assetText.size() < rootText.size()) {
        return false;
    }
    if (CompareStringOrdinal(
            rootText.c_str(),
            static_cast<int>(rootText.size()),
            assetText.c_str(),
            static_cast<int>(rootText.size()),
            TRUE) != CSTR_EQUAL) {
        return false;
    }
    return assetText.size() == rootText.size() || assetText[rootText.size()] == L'\\';
}

std::expected<std::filesystem::path, LegacyDataError> CanonicalPayloadRoot(
    const std::filesystem::path& candidate) {
    UniqueHandle handle(CreateFileW(
        candidate.c_str(),
        FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr,
        OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
        nullptr));
    if (!handle) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, {}});
    }

    FILE_ATTRIBUTE_TAG_INFO attributes{};
    if (GetFileInformationByHandleEx(
            handle.get(), FileAttributeTagInfo, &attributes, sizeof(attributes)) == FALSE) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::ReadFailed, {}});
    }
    if ((attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::RootReparsePoint, {}});
    }
    return FinalPath(handle.get(), LegacyDataErrorCode::ReadFailed, {});
}

std::expected<std::vector<std::byte>, LegacyDataError> ReadAndValidateAsset(
    const std::filesystem::path& candidateRoot,
    const std::filesystem::path& canonicalPayloadRoot,
    const LegacyManifestEntry& entry) {
    const std::filesystem::path assetPath = candidateRoot / entry.relativePath;
    UniqueHandle handle(CreateFileW(
        assetPath.c_str(),
        GENERIC_READ | FILE_READ_ATTRIBUTES,
        FILE_SHARE_READ,
        nullptr,
        OPEN_EXISTING,
        FILE_FLAG_SEQUENTIAL_SCAN | FILE_FLAG_OPEN_REPARSE_POINT,
        nullptr));
    if (!handle) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::AssetMissing, std::string(entry.logicalId)});
    }

    FILE_ATTRIBUTE_TAG_INFO attributes{};
    if (GetFileInformationByHandleEx(
            handle.get(), FileAttributeTagInfo, &attributes, sizeof(attributes)) == FALSE) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::ReadFailed, std::string(entry.logicalId)});
    }
    if ((attributes.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::AssetReparsePoint, std::string(entry.logicalId)});
    }

    auto finalAssetPath = FinalPath(
        handle.get(), LegacyDataErrorCode::ReadFailed, entry.logicalId);
    if (!finalAssetPath.has_value()) {
        return std::unexpected(finalAssetPath.error());
    }
    if (!IsWithinRoot(canonicalPayloadRoot, *finalAssetPath)) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::AssetOutsideRoot, std::string(entry.logicalId)});
    }

    LARGE_INTEGER size{};
    if (GetFileSizeEx(handle.get(), &size) == FALSE || size.QuadPart < 0) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::ReadFailed, std::string(entry.logicalId)});
    }
    if (static_cast<std::uint64_t>(size.QuadPart) != entry.byteLength ||
        entry.byteLength > std::numeric_limits<std::size_t>::max()) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::SizeMismatch, std::string(entry.logicalId)});
    }

    std::vector<std::byte> bytes(static_cast<std::size_t>(entry.byteLength));
    std::size_t offset = 0;
    while (offset < bytes.size()) {
        const DWORD requested = static_cast<DWORD>(std::min<std::size_t>(
            bytes.size() - offset, std::numeric_limits<DWORD>::max()));
        DWORD received = 0;
        if (ReadFile(handle.get(), bytes.data() + offset, requested, &received, nullptr) == FALSE ||
            received == 0) {
            return std::unexpected(LegacyDataError{
                LegacyDataErrorCode::ReadFailed, std::string(entry.logicalId)});
        }
        offset += received;
    }

    std::byte trailing{};
    DWORD trailingLength = 0;
    if (ReadFile(handle.get(), &trailing, 1, &trailingLength, nullptr) == FALSE) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::ReadFailed, std::string(entry.logicalId)});
    }
    if (trailingLength != 0) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::SizeMismatch, std::string(entry.logicalId)});
    }

    auto actualHash = Sha256(bytes, entry.logicalId);
    if (!actualHash.has_value()) {
        return std::unexpected(actualHash.error());
    }
    if (!ConstantTimeEqual(*actualHash, entry.sha256)) {
        return std::unexpected(LegacyDataError{
            LegacyDataErrorCode::HashMismatch, std::string(entry.logicalId)});
    }
    return bytes;
}

}

namespace logh7::legacy {

std::string_view StableErrorCode(const LegacyDataErrorCode code) noexcept {
    switch (code) {
    case LegacyDataErrorCode::RootMissing:
        return "LEGACY_ROOT_MISSING";
    case LegacyDataErrorCode::RootReparsePoint:
        return "LEGACY_ROOT_REPARSE_POINT";
    case LegacyDataErrorCode::PayloadNotFound:
        return "LEGACY_PAYLOAD_NOT_FOUND";
    case LegacyDataErrorCode::PayloadAmbiguous:
        return "LEGACY_PAYLOAD_AMBIGUOUS";
    case LegacyDataErrorCode::AssetMissing:
        return "LEGACY_ASSET_MISSING";
    case LegacyDataErrorCode::AssetReparsePoint:
        return "LEGACY_ASSET_REPARSE_POINT";
    case LegacyDataErrorCode::AssetOutsideRoot:
        return "LEGACY_ASSET_OUTSIDE_ROOT";
    case LegacyDataErrorCode::SizeMismatch:
        return "LEGACY_ASSET_SIZE_MISMATCH";
    case LegacyDataErrorCode::HashMismatch:
        return "LEGACY_ASSET_HASH_MISMATCH";
    case LegacyDataErrorCode::ReadFailed:
        return "LEGACY_READ_FAILED";
    }
    return "LEGACY_READ_FAILED";
}

LegacyDataProvider::LegacyDataProvider(
    std::filesystem::path payloadRoot,
    std::vector<StoredBlob> blobs,
    const std::array<std::byte, 32> manifestHash)
    : payloadRoot_(std::move(payloadRoot)),
      blobs_(std::move(blobs)),
      manifestHash_(manifestHash) {}

std::expected<LegacyDataProvider, LegacyDataError> LegacyDataProvider::OpenStrategyShell(
    const std::filesystem::path& suppliedRoot) {
    return Open(suppliedRoot, StrategyShellManifest());
}

#if defined(LOGH7_TESTING)
std::expected<LegacyDataProvider, LegacyDataError> LegacyDataProvider::OpenForTest(
    const std::filesystem::path& suppliedRoot,
    const std::span<const LegacyManifestEntry> generatedFixtureManifest) {
    return Open(suppliedRoot, generatedFixtureManifest);
}
#endif

std::expected<LegacyDataProvider, LegacyDataError> LegacyDataProvider::Open(
    const std::filesystem::path& suppliedRoot,
    const std::span<const LegacyManifestEntry> manifest) {
    const DWORD suppliedAttributes = GetFileAttributesW(suppliedRoot.c_str());
    if (suppliedAttributes == INVALID_FILE_ATTRIBUTES ||
        (suppliedAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::RootMissing, {}});
    }
    if ((suppliedAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::RootReparsePoint, {}});
    }

    std::vector<std::filesystem::path> candidates;
    if (Qualifies(suppliedRoot, manifest)) {
        candidates.push_back(suppliedRoot);
    }

    auto children = DirectDirectories(suppliedRoot);
    if (!children.has_value()) {
        return std::unexpected(children.error());
    }
    for (const std::filesystem::path& child : *children) {
        if (Qualifies(child, manifest)) {
            candidates.push_back(child);
        }
        auto grandchildren = DirectDirectories(child);
        if (!grandchildren.has_value()) {
            return std::unexpected(grandchildren.error());
        }
        for (const std::filesystem::path& grandchild : *grandchildren) {
            if (Qualifies(grandchild, manifest)) {
                candidates.push_back(grandchild);
            }
        }
    }

    if (candidates.empty()) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::PayloadNotFound, {}});
    }
    if (candidates.size() != 1) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::PayloadAmbiguous, {}});
    }

    auto payloadRoot = CanonicalPayloadRoot(candidates.front());
    if (!payloadRoot.has_value()) {
        return std::unexpected(payloadRoot.error());
    }

    std::vector<StoredBlob> blobs;
    blobs.reserve(manifest.size());
    for (const LegacyManifestEntry& entry : manifest) {
        auto bytes = ReadAndValidateAsset(candidates.front(), *payloadRoot, entry);
        if (!bytes.has_value()) {
            return std::unexpected(bytes.error());
        }
        blobs.push_back(StoredBlob{
            entry.id,
            std::string(entry.logicalId),
            entry.relativePath,
            entry.byteLength,
            entry.sha256,
            std::move(*bytes),
        });
    }

    auto manifestHash = HashManifest(manifest);
    if (!manifestHash.has_value()) {
        return std::unexpected(manifestHash.error());
    }
    return LegacyDataProvider(std::move(*payloadRoot), std::move(blobs), *manifestHash);
}

std::expected<ValidatedBlob, LegacyDataError> LegacyDataProvider::Load(
    const LegacyResourceId id) const {
    const auto found = std::ranges::find(blobs_, id, &StoredBlob::id);
    if (found == blobs_.end()) {
        return std::unexpected(LegacyDataError{LegacyDataErrorCode::AssetMissing, {}});
    }
    return ValidatedBlob{
        found->id,
        found->logicalId,
        found->manifestRelativePath,
        found->byteLength,
        found->sha256,
        found->bytes,
    };
}

const std::filesystem::path& LegacyDataProvider::payloadRoot() const noexcept {
    return payloadRoot_;
}

std::string LegacyDataProvider::manifestHashHex() const {
    return Hex(manifestHash_);
}

}
