#include <logh7/legacy/image_decode.hpp>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>
#include <string_view>
#include <vector>

namespace {

using logh7::legacy::ImageDecodeError;
using logh7::legacy::ImageDecodeErrorCode;

constexpr std::size_t kTgaHeaderSize = 18;
constexpr std::uint32_t kAtlasWidth = 1024;
constexpr std::uint32_t kAtlasHeight = 1024;
constexpr std::uint32_t kBytesPerPixel = 4;

std::uint16_t ReadLittleEndian16(
    const std::span<const std::byte> bytes,
    const std::size_t offset) noexcept {
    return static_cast<std::uint16_t>(
        std::to_integer<unsigned char>(bytes[offset]) |
        (static_cast<std::uint16_t>(std::to_integer<unsigned char>(bytes[offset + 1])) << 8U));
}

bool AllZero(
    const std::span<const std::byte> bytes,
    const std::size_t begin,
    const std::size_t end) noexcept {
    return std::ranges::all_of(
        bytes.subspan(begin, end - begin),
        [](const std::byte value) { return value == std::byte{0}; });
}

std::byte Premultiply(const std::byte channel, const std::byte alpha) noexcept {
    const auto channelValue = std::to_integer<std::uint32_t>(channel);
    const auto alphaValue = std::to_integer<std::uint32_t>(alpha);
    return static_cast<std::byte>((channelValue * alphaValue + 127U) / 255U);
}

}

namespace logh7::legacy {

std::string_view StableErrorCode(const ImageDecodeErrorCode code) noexcept {
    switch (code) {
    case ImageDecodeErrorCode::TgaHeaderTruncated:
        return "TGA_HEADER_TRUNCATED";
    case ImageDecodeErrorCode::TgaIdLengthUnsupported:
        return "TGA_ID_LENGTH_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaColorMapUnsupported:
        return "TGA_COLOR_MAP_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaImageTypeUnsupported:
        return "TGA_IMAGE_TYPE_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaRleUnsupported:
        return "TGA_RLE_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaDimensionsUnsupported:
        return "TGA_DIMENSIONS_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaPixelDepthUnsupported:
        return "TGA_PIXEL_DEPTH_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaDescriptorUnsupported:
        return "TGA_DESCRIPTOR_UNSUPPORTED";
    case ImageDecodeErrorCode::TgaPayloadLengthMismatch:
        return "TGA_PAYLOAD_LENGTH_MISMATCH";
    case ImageDecodeErrorCode::WicComUnavailable:
        return "WIC_COM_UNAVAILABLE";
    case ImageDecodeErrorCode::WicStreamCreateFailed:
        return "WIC_STREAM_CREATE_FAILED";
    case ImageDecodeErrorCode::WicDecoderCreateFailed:
        return "WIC_DECODER_CREATE_FAILED";
    case ImageDecodeErrorCode::WicFrameCountUnsupported:
        return "WIC_FRAME_COUNT_UNSUPPORTED";
    case ImageDecodeErrorCode::WicContainerUnsupported:
        return "WIC_CONTAINER_UNSUPPORTED";
    case ImageDecodeErrorCode::WicFrameDecodeFailed:
        return "WIC_FRAME_DECODE_FAILED";
    case ImageDecodeErrorCode::WicDimensionsUnsupported:
        return "WIC_DIMENSIONS_UNSUPPORTED";
    case ImageDecodeErrorCode::WicPixelConversionFailed:
        return "WIC_PIXEL_CONVERSION_FAILED";
    case ImageDecodeErrorCode::ImageSizeOverflow:
        return "IMAGE_SIZE_OVERFLOW";
    }
    return "IMAGE_DECODE_FAILED";
}

std::expected<BgraImage, ImageDecodeError> DecodeStrategyPanelTga(
    const std::span<const std::byte> bytes) {
    if (bytes.size() < kTgaHeaderSize) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaHeaderTruncated});
    }
    if (bytes[0] != std::byte{0}) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaIdLengthUnsupported});
    }
    if (bytes[1] != std::byte{0} || !AllZero(bytes, 3, 8)) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaColorMapUnsupported});
    }

    const auto imageType = std::to_integer<unsigned char>(bytes[2]);
    if (imageType == 10U) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaRleUnsupported});
    }
    if (imageType != 2U) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaImageTypeUnsupported});
    }
    if (!AllZero(bytes, 8, 12)) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaDescriptorUnsupported});
    }

    const std::uint32_t width = ReadLittleEndian16(bytes, 12);
    const std::uint32_t height = ReadLittleEndian16(bytes, 14);
    if (width != kAtlasWidth || height != kAtlasHeight) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaDimensionsUnsupported});
    }
    if (bytes[16] != std::byte{32}) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaPixelDepthUnsupported});
    }
    if (bytes[17] != std::byte{0}) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaDescriptorUnsupported});
    }

    if (width > std::numeric_limits<std::uint32_t>::max() / kBytesPerPixel) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }
    const std::uint32_t stride = width * kBytesPerPixel;
    if (height > std::numeric_limits<std::size_t>::max() / stride) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }
    const std::size_t pixelBytes = static_cast<std::size_t>(height) * stride;
    if (pixelBytes > std::numeric_limits<std::size_t>::max() - kTgaHeaderSize) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }
    if (bytes.size() != kTgaHeaderSize + pixelBytes) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::TgaPayloadLengthMismatch});
    }

    BgraImage image{width, height, stride, std::vector<std::byte>(pixelBytes)};
    for (std::uint32_t outputRow = 0; outputRow < height; ++outputRow) {
        const std::uint32_t storedRow = height - 1U - outputRow;
        const std::size_t sourceOffset =
            kTgaHeaderSize + static_cast<std::size_t>(storedRow) * stride;
        const std::size_t outputOffset = static_cast<std::size_t>(outputRow) * stride;
        for (std::uint32_t column = 0; column < width; ++column) {
            const std::size_t sourcePixel = sourceOffset + static_cast<std::size_t>(column) * 4;
            const std::size_t outputPixel = outputOffset + static_cast<std::size_t>(column) * 4;
            const std::byte alpha = bytes[sourcePixel + 3];
            image.pixels[outputPixel] = Premultiply(bytes[sourcePixel], alpha);
            image.pixels[outputPixel + 1] = Premultiply(bytes[sourcePixel + 1], alpha);
            image.pixels[outputPixel + 2] = Premultiply(bytes[sourcePixel + 2], alpha);
            image.pixels[outputPixel + 3] = alpha;
        }
    }
    return image;
}

}
