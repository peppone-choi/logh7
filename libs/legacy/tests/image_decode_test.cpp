#include <logh7/legacy/image_decode.hpp>

#include <windows.h>
#include <wincodec.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <iostream>
#include <limits>
#include <span>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using logh7::legacy::BgraImage;
using logh7::legacy::ImageDecodeError;
using logh7::legacy::ImageDecodeErrorCode;

constexpr std::uint32_t kTgaWidth = 1024;
constexpr std::uint32_t kTgaHeight = 1024;
constexpr std::size_t kTgaHeaderSize = 18;
constexpr std::size_t kTgaPixelBytes =
    static_cast<std::size_t>(kTgaWidth) * kTgaHeight * 4;

template <typename Interface>
class ComPtr {
public:
    ComPtr() = default;
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;

    ComPtr(ComPtr&& other) noexcept : value_(std::exchange(other.value_, nullptr)) {}

    ComPtr& operator=(ComPtr&& other) noexcept {
        if (this != &other) {
            Reset();
            value_ = std::exchange(other.value_, nullptr);
        }
        return *this;
    }

    ~ComPtr() {
        Reset();
    }

    Interface* get() const noexcept {
        return value_;
    }

    Interface** put() noexcept {
        Reset();
        return &value_;
    }

    Interface* operator->() const noexcept {
        return value_;
    }

    explicit operator bool() const noexcept {
        return value_ != nullptr;
    }

private:
    void Reset() noexcept {
        if (value_ != nullptr) {
            value_->Release();
            value_ = nullptr;
        }
    }

    Interface* value_ = nullptr;
};

class ComApartment {
public:
    ComApartment() : result_(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ComApartment(const ComApartment&) = delete;
    ComApartment& operator=(const ComApartment&) = delete;

    ~ComApartment() {
        if (SUCCEEDED(result_)) {
            CoUninitialize();
        }
    }

    bool available() const noexcept {
        return SUCCEEDED(result_);
    }

private:
    HRESULT result_;
};

struct TestContext {
    int failures = 0;

    void Expect(const bool condition, const std::string_view message) {
        if (!condition) {
            ++failures;
            std::cerr << "FAIL: " << message << '\n';
        }
    }

    void ExpectError(
        const std::expected<BgraImage, ImageDecodeError>& result,
        const ImageDecodeErrorCode expected,
        const std::string_view message) {
        Expect(!result.has_value(), message);
        if (!result.has_value()) {
            if (result.error().code != expected) {
                std::cerr << "  expected=" << logh7::legacy::StableErrorCode(expected)
                          << " actual=" << logh7::legacy::StableErrorCode(result.error().code)
                          << '\n';
            }
            Expect(result.error().code == expected, message);
        }
    }
};

std::vector<std::byte> MakeValidTga() {
    std::vector<std::byte> bytes(kTgaHeaderSize + kTgaPixelBytes);
    bytes[2] = std::byte{2};
    bytes[12] = std::byte{0};
    bytes[13] = std::byte{4};
    bytes[14] = std::byte{0};
    bytes[15] = std::byte{4};
    bytes[16] = std::byte{32};
    bytes[17] = std::byte{0};
    return bytes;
}

void SetStoredPixel(
    std::vector<std::byte>& bytes,
    const std::uint32_t storedRow,
    const std::array<unsigned char, 4>& bgra) {
    const std::size_t offset = kTgaHeaderSize +
        static_cast<std::size_t>(storedRow) * kTgaWidth * 4;
    for (std::size_t index = 0; index < bgra.size(); ++index) {
        bytes[offset + index] = static_cast<std::byte>(bgra[index]);
    }
}

std::array<unsigned char, 4> PixelAt(
    const BgraImage& image,
    const std::uint32_t x,
    const std::uint32_t y) {
    const std::size_t offset = static_cast<std::size_t>(y) * image.stride +
        static_cast<std::size_t>(x) * 4;
    return {
        std::to_integer<unsigned char>(image.pixels[offset]),
        std::to_integer<unsigned char>(image.pixels[offset + 1]),
        std::to_integer<unsigned char>(image.pixels[offset + 2]),
        std::to_integer<unsigned char>(image.pixels[offset + 3]),
    };
}

std::vector<std::byte> EncodeWicFixture(
    const GUID& containerFormat,
    const std::uint32_t width,
    const std::uint32_t height,
    const std::uint32_t frameCount = 1) {
    ComPtr<IWICImagingFactory> factory;
    if (FAILED(CoCreateInstance(
            CLSID_WICImagingFactory,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(factory.put())))) {
        throw std::runtime_error("fixture WIC factory creation failed");
    }

    ComPtr<IStream> stream;
    if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, stream.put()))) {
        throw std::runtime_error("fixture stream creation failed");
    }

    ComPtr<IWICBitmapEncoder> encoder;
    if (FAILED(factory->CreateEncoder(containerFormat, nullptr, encoder.put())) ||
        FAILED(encoder->Initialize(stream.get(), WICBitmapEncoderNoCache))) {
        throw std::runtime_error("fixture encoder initialization failed");
    }

    if (width > std::numeric_limits<std::uint32_t>::max() / 3U) {
        throw std::runtime_error("fixture width overflow");
    }
    const std::uint32_t stride = width * 3U;
    const std::size_t pixelBytes = static_cast<std::size_t>(stride) * height;
    std::vector<BYTE> pixels(pixelBytes);
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::size_t offset = static_cast<std::size_t>(y) * stride +
                static_cast<std::size_t>(x) * 3U;
            pixels[offset] = static_cast<BYTE>(10U + x);
            pixels[offset + 1] = static_cast<BYTE>(20U + y);
            pixels[offset + 2] = static_cast<BYTE>(30U + x + y);
        }
    }

    for (std::uint32_t frameIndex = 0; frameIndex < frameCount; ++frameIndex) {
        ComPtr<IWICBitmapFrameEncode> frame;
        ComPtr<IPropertyBag2> properties;
        if (FAILED(encoder->CreateNewFrame(frame.put(), properties.put())) ||
            FAILED(frame->Initialize(properties.get())) ||
            FAILED(frame->SetSize(width, height))) {
            throw std::runtime_error("fixture frame initialization failed");
        }
        WICPixelFormatGUID pixelFormat = GUID_WICPixelFormat24bppBGR;
        if (FAILED(frame->SetPixelFormat(&pixelFormat)) ||
            !IsEqualGUID(pixelFormat, GUID_WICPixelFormat24bppBGR) ||
            pixelBytes > std::numeric_limits<UINT>::max() ||
            FAILED(frame->WritePixels(
                height,
                stride,
                static_cast<UINT>(pixelBytes),
                pixels.data())) ||
            FAILED(frame->Commit())) {
            throw std::runtime_error("fixture frame write failed");
        }
    }
    if (FAILED(encoder->Commit())) {
        throw std::runtime_error("fixture encoder commit failed");
    }

    STATSTG statistics{};
    HGLOBAL global = nullptr;
    if (FAILED(stream->Stat(&statistics, STATFLAG_NONAME)) ||
        statistics.cbSize.HighPart != 0 ||
        FAILED(GetHGlobalFromStream(stream.get(), &global))) {
        throw std::runtime_error("fixture stream extraction failed");
    }
    const SIZE_T byteLength = static_cast<SIZE_T>(statistics.cbSize.LowPart);
    const void* locked = GlobalLock(global);
    if (locked == nullptr && byteLength != 0) {
        throw std::runtime_error("fixture stream lock failed");
    }
    std::vector<std::byte> bytes(byteLength);
    if (byteLength != 0) {
        std::copy_n(static_cast<const std::byte*>(locked), byteLength, bytes.begin());
        GlobalUnlock(global);
    }
    return bytes;
}

void TestTgaBottomOriginAndPremultiplication(TestContext& context) {
    auto bytes = MakeValidTga();
    SetStoredPixel(bytes, kTgaHeight - 1, {128, 64, 32, 128});
    SetStoredPixel(bytes, 0, {40, 20, 10, 255});

    const auto decoded = logh7::legacy::DecodeStrategyPanelTga(bytes);
    context.Expect(decoded.has_value(), "observed TGA subset decodes");
    if (!decoded.has_value()) {
        return;
    }
    context.Expect(decoded->width == kTgaWidth, "TGA width is preserved");
    context.Expect(decoded->height == kTgaHeight, "TGA height is preserved");
    context.Expect(decoded->stride == kTgaWidth * 4, "TGA stride is tightly packed PBGRA");
    context.Expect(
        decoded->pixels.size() == kTgaPixelBytes,
        "TGA output allocation has the exact checked size");
    context.Expect(
        PixelAt(*decoded, 0, 0) == std::array<unsigned char, 4>{64, 32, 16, 128},
        "stored last scanline becomes top output and straight BGRA is premultiplied");
    context.Expect(
        PixelAt(*decoded, 0, kTgaHeight - 1) == std::array<unsigned char, 4>{40, 20, 10, 255},
        "stored first scanline becomes bottom output scanline");
}

void TestMalformedTgaHeaders(TestContext& context) {
    auto valid = MakeValidTga();

    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(std::span(valid).first(17)),
        ImageDecodeErrorCode::TgaHeaderTruncated,
        "truncated TGA header is rejected");

    auto withId = valid;
    withId[0] = std::byte{1};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(withId),
        ImageDecodeErrorCode::TgaIdLengthUnsupported,
        "nonzero TGA ID length is rejected distinctly");

    auto colorMapped = valid;
    colorMapped[1] = std::byte{1};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(colorMapped),
        ImageDecodeErrorCode::TgaColorMapUnsupported,
        "color-mapped TGA is rejected distinctly");

    auto unsupportedType = valid;
    unsupportedType[2] = std::byte{3};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(unsupportedType),
        ImageDecodeErrorCode::TgaImageTypeUnsupported,
        "unsupported TGA image type is rejected distinctly");

    auto rle = valid;
    rle[2] = std::byte{10};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(rle),
        ImageDecodeErrorCode::TgaRleUnsupported,
        "RLE TGA is rejected distinctly");

    auto wrongDimensions = valid;
    wrongDimensions[12] = std::byte{0xff};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(wrongDimensions),
        ImageDecodeErrorCode::TgaDimensionsUnsupported,
        "non-atlas TGA dimensions are rejected distinctly");

    auto twentyFourBit = valid;
    twentyFourBit[16] = std::byte{24};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(twentyFourBit),
        ImageDecodeErrorCode::TgaPixelDepthUnsupported,
        "24-bit TGA is rejected distinctly");

    auto topOrigin = valid;
    topOrigin[17] = std::byte{0x20};
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(topOrigin),
        ImageDecodeErrorCode::TgaDescriptorUnsupported,
        "top-origin TGA descriptor is rejected distinctly");

    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(std::span(valid).first(valid.size() - 1)),
        ImageDecodeErrorCode::TgaPayloadLengthMismatch,
        "truncated TGA payload is rejected before output allocation");

    valid.push_back(std::byte{0});
    context.ExpectError(
        logh7::legacy::DecodeStrategyPanelTga(valid),
        ImageDecodeErrorCode::TgaPayloadLengthMismatch,
        "trailing TGA bytes violate the exact payload layout");
}

void TestWicBmpAndJpegStreams(TestContext& context) {
    const auto bmpBytes = EncodeWicFixture(GUID_ContainerFormatBmp, 2, 2);
    const auto bmp = logh7::legacy::DecodeWicBmpOrJpeg(bmpBytes);
    context.Expect(bmp.has_value(), "generated in-memory BMP stream decodes");
    if (bmp.has_value()) {
        context.Expect(bmp->width == 2 && bmp->height == 2, "BMP dimensions are preserved");
        context.Expect(bmp->stride == 8, "BMP is converted to tightly packed PBGRA");
        context.Expect(
            PixelAt(*bmp, 0, 0) == std::array<unsigned char, 4>{10, 20, 30, 255},
            "BMP output pixels are top-down PBGRA");
        context.Expect(
            PixelAt(*bmp, 1, 1) == std::array<unsigned char, 4>{11, 21, 32, 255},
            "BMP output retains the generated bottom-row pixel");
    }

    const auto jpegBytes = EncodeWicFixture(GUID_ContainerFormatJpeg, 8, 8);
    const auto jpeg = logh7::legacy::DecodeWicBmpOrJpeg(jpegBytes);
    context.Expect(jpeg.has_value(), "generated in-memory JPEG stream decodes");
    if (jpeg.has_value()) {
        context.Expect(jpeg->width == 8 && jpeg->height == 8, "JPEG dimensions are preserved");
        context.Expect(jpeg->pixels.size() == 8U * 8U * 4U, "JPEG output is bounded PBGRA");
    }
}

void TestMalformedWicStreams(TestContext& context) {
    const auto pngBytes = EncodeWicFixture(GUID_ContainerFormatPng, 2, 2);
    context.ExpectError(
        logh7::legacy::DecodeWicBmpOrJpeg(pngBytes),
        ImageDecodeErrorCode::WicContainerUnsupported,
        "single-frame PNG is rejected by the BMP/JPEG whitelist");

    const auto multiFrameTiff = EncodeWicFixture(GUID_ContainerFormatTiff, 2, 2, 2);
    context.ExpectError(
        logh7::legacy::DecodeWicBmpOrJpeg(multiFrameTiff),
        ImageDecodeErrorCode::WicFrameCountUnsupported,
        "multi-frame WIC stream is rejected before container conversion");

    const auto oversizedBmp = EncodeWicFixture(GUID_ContainerFormatBmp, 4097, 1);
    context.ExpectError(
        logh7::legacy::DecodeWicBmpOrJpeg(oversizedBmp),
        ImageDecodeErrorCode::WicDimensionsUnsupported,
        "WIC dimensions beyond 4096 are rejected before output allocation");

    auto truncatedBmp = EncodeWicFixture(GUID_ContainerFormatBmp, 2, 2);
    truncatedBmp.resize(truncatedBmp.size() - 4);
    context.ExpectError(
        logh7::legacy::DecodeWicBmpOrJpeg(truncatedBmp),
        ImageDecodeErrorCode::WicDecoderCreateFailed,
        "truncated WIC pixels are rejected during decoder creation without output allocation");
}

}

int main(int argumentCount, char** arguments) {
    const bool malformedOnly =
        argumentCount == 2 && std::string_view(arguments[1]) == "--malformed-only";

    ComApartment apartment;
    if (!apartment.available()) {
        std::cerr << "COM initialization failed\n";
        return 1;
    }

    TestContext context;
    if (!malformedOnly) {
        TestTgaBottomOriginAndPremultiplication(context);
        TestWicBmpAndJpegStreams(context);
    }
    TestMalformedTgaHeaders(context);
    TestMalformedWicStreams(context);

    if (context.failures != 0) {
        std::cerr << context.failures << " LegacyImageDecode assertion(s) failed\n";
        return 1;
    }
    std::cout << "LegacyImageDecode tests passed\n";
    return 0;
}
