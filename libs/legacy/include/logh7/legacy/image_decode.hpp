#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string_view>
#include <vector>

namespace logh7::legacy {

struct BgraImage {
    std::uint32_t width;
    std::uint32_t height;
    std::uint32_t stride;
    std::vector<std::byte> pixels;
};

enum class ImageDecodeErrorCode {
    TgaHeaderTruncated,
    TgaIdLengthUnsupported,
    TgaColorMapUnsupported,
    TgaImageTypeUnsupported,
    TgaRleUnsupported,
    TgaDimensionsUnsupported,
    TgaPixelDepthUnsupported,
    TgaDescriptorUnsupported,
    TgaPayloadLengthMismatch,
    WicComUnavailable,
    WicStreamCreateFailed,
    WicDecoderCreateFailed,
    WicFrameCountUnsupported,
    WicContainerUnsupported,
    WicFrameDecodeFailed,
    WicDimensionsUnsupported,
    WicPixelConversionFailed,
    ImageSizeOverflow,
};

struct ImageDecodeError {
    ImageDecodeErrorCode code;
};

std::string_view StableErrorCode(ImageDecodeErrorCode code) noexcept;

std::expected<BgraImage, ImageDecodeError> DecodeStrategyPanelTga(
    std::span<const std::byte> bytes);

std::expected<BgraImage, ImageDecodeError> DecodeWicBmpOrJpeg(
    std::span<const std::byte> bytes);

}
