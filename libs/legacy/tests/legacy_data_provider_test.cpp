#include <logh7/legacy/legacy_data_provider.hpp>

#include <windows.h>

#include <array>
#include <atomic>
#include <cstddef>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

using logh7::legacy::LegacyDataErrorCode;
using logh7::legacy::LegacyDataProvider;
using logh7::legacy::LegacyManifestEntry;
using logh7::legacy::LegacyResourceId;

constexpr std::string_view kAtlasBytes = "atlas-fixture";
constexpr std::string_view kGridBytes = "grid-fixture";
constexpr std::string_view kJpegBytes = "jpeg-fixture";

std::array<std::byte, 32> ParseSha256(const std::string_view hex) {
    std::array<std::byte, 32> result{};
    const auto nibble = [](const char character) -> unsigned char {
        if (character >= '0' && character <= '9') {
            return static_cast<unsigned char>(character - '0');
        }
        return static_cast<unsigned char>(character - 'a' + 10);
    };
    for (std::size_t index = 0; index < result.size(); ++index) {
        const auto value = static_cast<unsigned char>(
            (nibble(hex[index * 2]) << 4U) | nibble(hex[index * 2 + 1]));
        result[index] = static_cast<std::byte>(value);
    }
    return result;
}

std::array<LegacyManifestEntry, 3> FixtureManifest() {
    return {{
        {LegacyResourceId::StrategyPanelAtlas,
         "ui.panel.strategy.atlas",
         L"data/image/senryaku_panel/senryaku_mainpanel.tga",
         kAtlasBytes.size(),
         ParseSha256("9bc946715355ae1fbec3cd3d59ce4be1d02624f66b0ac77838badd4432b5abdf")},
        {LegacyResourceId::StrategyGridGlow,
         "ui.strategy.grid-glow",
         L"data/image/strategy/grid_glow.bmp",
         kGridBytes.size(),
         ParseSha256("df1715a2564c5e2ae01f4159038cf9e0c5ad9fd606df71f66acc2f15bd1ca962")},
        {LegacyResourceId::StrategyArticleBackgroundProbe,
         "ui.strategy.article-background-probe",
         L"data/image/spot/bg005.jpg",
         kJpegBytes.size(),
         ParseSha256("fe7984712ccab67b150e3e8337f9cb104bbf44d7b404fb8286e1ca8eb335eddb")},
    }};
}

class TempTree {
public:
    TempTree() {
        static std::atomic<unsigned long long> sequence{0};
        std::array<wchar_t, MAX_PATH + 1> tempPath{};
        const DWORD length = GetTempPathW(static_cast<DWORD>(tempPath.size()), tempPath.data());
        if (length == 0 || length >= tempPath.size()) {
            throw std::runtime_error("GetTempPathW failed");
        }
        root_ = std::filesystem::path(tempPath.data()) /
            (L"logh7-legacy-test-" + std::to_wstring(GetCurrentProcessId()) + L"-" +
             std::to_wstring(sequence.fetch_add(1)));
        std::filesystem::create_directories(root_);
    }

    TempTree(const TempTree&) = delete;
    TempTree& operator=(const TempTree&) = delete;

    ~TempTree() {
        std::error_code ignored;
        std::filesystem::remove_all(root_, ignored);
    }

    const std::filesystem::path& root() const noexcept {
        return root_;
    }

private:
    std::filesystem::path root_;
};

void WriteBytes(const std::filesystem::path& path, const std::string_view bytes) {
    std::filesystem::create_directories(path.parent_path());
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    if (!output) {
        throw std::runtime_error("fixture write failed");
    }
}

void PopulatePayload(const std::filesystem::path& payloadRoot) {
    const auto manifest = FixtureManifest();
    WriteBytes(payloadRoot / manifest[0].relativePath, kAtlasBytes);
    WriteBytes(payloadRoot / manifest[1].relativePath, kGridBytes);
    WriteBytes(payloadRoot / manifest[2].relativePath, kJpegBytes);
}

bool CreateDirectoryLink(
    const std::filesystem::path& link,
    const std::filesystem::path& target) {
    return CreateSymbolicLinkW(
               link.c_str(),
               target.c_str(),
               SYMBOLIC_LINK_FLAG_DIRECTORY | SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE) != FALSE;
}

struct TestContext {
    int failures = 0;

    void Expect(const bool condition, const std::string_view message) {
        if (!condition) {
            ++failures;
            std::cerr << "FAIL: " << message << '\n';
        }
    }

    void ExpectError(
        const std::expected<LegacyDataProvider, logh7::legacy::LegacyDataError>& result,
        const LegacyDataErrorCode expected,
        const std::string_view message) {
        Expect(!result.has_value(), message);
        if (!result.has_value()) {
            if (result.error().code != expected) {
                std::cerr << "  expected=" << logh7::legacy::StableErrorCode(expected)
                          << " actual=" << logh7::legacy::StableErrorCode(result.error().code)
                          << " resource=" << result.error().logicalId << '\n';
            }
            Expect(result.error().code == expected, message);
        }
    }
};

void TestDirectAndTwoLevelRoots(TestContext& context) {
    const auto manifest = FixtureManifest();

    TempTree direct;
    PopulatePayload(direct.root());
    auto directResult = LegacyDataProvider::OpenForTest(direct.root(), manifest);
    context.Expect(directResult.has_value(), "direct payload root resolves");
    if (!directResult.has_value()) {
        std::cerr << "  direct error=" << logh7::legacy::StableErrorCode(directResult.error().code)
                  << " resource=" << directResult.error().logicalId << '\n';
    }
    if (directResult.has_value()) {
        auto blob = directResult->Load(LegacyResourceId::StrategyPanelAtlas);
        context.Expect(blob.has_value(), "validated atlas loads by stable resource id");
        if (blob.has_value()) {
            context.Expect(blob->logicalId == "ui.panel.strategy.atlas", "logical resource id is preserved");
            context.Expect(blob->bytes.size() == kAtlasBytes.size(), "validated bytes are returned");
        }
        context.Expect(directResult->manifestHashHex().size() == 64, "manifest hash is SHA-256 hex");
    }

    TempTree extraction;
    const auto payload = extraction.root() / L"Disk1" / L"Game";
    PopulatePayload(payload);
    auto extractionResult = LegacyDataProvider::OpenForTest(extraction.root(), manifest);
    context.Expect(extractionResult.has_value(), "two-level extraction root resolves");
    if (!extractionResult.has_value()) {
        std::cerr << "  extraction error=" << logh7::legacy::StableErrorCode(extractionResult.error().code)
                  << " resource=" << extractionResult.error().logicalId << '\n';
    }
    if (directResult.has_value() && extractionResult.has_value()) {
        context.Expect(
            directResult->manifestHashHex() == extractionResult->manifestHashHex(),
            "manifest identity does not depend on supplied root");
    }
}

void TestCandidateCardinality(TestContext& context) {
    const auto manifest = FixtureManifest();

    TempTree empty;
    context.ExpectError(
        LegacyDataProvider::OpenForTest(empty.root(), manifest),
        LegacyDataErrorCode::PayloadNotFound,
        "zero payload candidates fail closed");

    TempTree ambiguous;
    PopulatePayload(ambiguous.root() / L"one" / L"payload");
    PopulatePayload(ambiguous.root() / L"two" / L"payload");
    context.ExpectError(
        LegacyDataProvider::OpenForTest(ambiguous.root(), manifest),
        LegacyDataErrorCode::PayloadAmbiguous,
        "two payload candidates fail closed");
}

void TestCorruptAssets(TestContext& context) {
    const auto manifest = FixtureManifest();

    TempTree missing;
    PopulatePayload(missing.root());
    std::filesystem::remove(missing.root() / manifest[1].relativePath);
    context.ExpectError(
        LegacyDataProvider::OpenForTest(missing.root(), manifest),
        LegacyDataErrorCode::PayloadNotFound,
        "a payload missing a manifest file does not qualify");

    TempTree truncated;
    PopulatePayload(truncated.root());
    WriteBytes(truncated.root() / manifest[0].relativePath, kAtlasBytes.substr(0, kAtlasBytes.size() - 1));
    context.ExpectError(
        LegacyDataProvider::OpenForTest(truncated.root(), manifest),
        LegacyDataErrorCode::SizeMismatch,
        "one-byte truncation is rejected");

    TempTree appended;
    PopulatePayload(appended.root());
    WriteBytes(appended.root() / manifest[0].relativePath, std::string(kAtlasBytes) + "x");
    context.ExpectError(
        LegacyDataProvider::OpenForTest(appended.root(), manifest),
        LegacyDataErrorCode::SizeMismatch,
        "one-byte append is rejected");

    TempTree mutated;
    PopulatePayload(mutated.root());
    std::string sameLength(kAtlasBytes);
    sameLength[0] = 'A';
    WriteBytes(mutated.root() / manifest[0].relativePath, sameLength);
    context.ExpectError(
        LegacyDataProvider::OpenForTest(mutated.root(), manifest),
        LegacyDataErrorCode::HashMismatch,
        "same-length mutation is rejected by hash");
}

void TestReparseRoot(TestContext& context) {
    const auto manifest = FixtureManifest();
    TempTree tree;
    const auto payload = tree.root() / L"payload";
    PopulatePayload(payload);
    const auto link = tree.root() / L"payload-link";
    context.Expect(CreateDirectoryLink(link, payload), "test environment can create a directory reparse point");
    if (std::filesystem::exists(link)) {
        context.ExpectError(
            LegacyDataProvider::OpenForTest(link, manifest),
            LegacyDataErrorCode::RootReparsePoint,
            "reparse supplied root is rejected");
    }
}

void TestNestedReparseCandidatesAreNotTraversed(TestContext& context) {
    const auto manifest = FixtureManifest();
    TempTree external;
    PopulatePayload(external.root());

    TempTree direct;
    const auto directLink = direct.root() / L"payload-link";
    context.Expect(
        CreateDirectoryLink(directLink, external.root()),
        "test environment can create a direct-child directory reparse point");
    context.ExpectError(
        LegacyDataProvider::OpenForTest(direct.root(), manifest),
        LegacyDataErrorCode::PayloadNotFound,
        "direct-child reparse payload candidate is not traversed");

    TempTree grandchild;
    const auto wrapper = grandchild.root() / L"wrapper";
    std::filesystem::create_directories(wrapper);
    const auto grandchildLink = wrapper / L"payload-link";
    context.Expect(
        CreateDirectoryLink(grandchildLink, external.root()),
        "test environment can create a grandchild directory reparse point");
    context.ExpectError(
        LegacyDataProvider::OpenForTest(grandchild.root(), manifest),
        LegacyDataErrorCode::PayloadNotFound,
        "grandchild reparse payload candidate is not traversed");
}

void TestReparseParentCannotEscapePayloadRoot(TestContext& context) {
    const auto manifest = FixtureManifest();
    TempTree external;
    PopulatePayload(external.root());

    TempTree candidate;
    const auto dataLink = candidate.root() / L"data";
    context.Expect(
        CreateDirectoryLink(dataLink, external.root() / L"data"),
        "test environment can create an in-candidate directory reparse point");
    context.ExpectError(
        LegacyDataProvider::OpenForTest(candidate.root(), manifest),
        LegacyDataErrorCode::AssetOutsideRoot,
        "final-handle containment rejects a parent-directory reparse escape");
}

}

int main() {
    TestContext context;
    TestDirectAndTwoLevelRoots(context);
    TestCandidateCardinality(context);
    TestCorruptAssets(context);
    TestReparseRoot(context);
    TestNestedReparseCandidatesAreNotTraversed(context);
    TestReparseParentCannotEscapePayloadRoot(context);

    if (context.failures != 0) {
        std::cerr << context.failures << " LegacyDataProvider assertion(s) failed\n";
        return 1;
    }
    std::cout << "LegacyDataProvider tests passed\n";
    return 0;
}
