#include <logh7/legacy/image_decode.hpp>

#include <windows.h>
#include <shlwapi.h>
#include <wincodec.h>
#include <wrl/client.h>

#include <cstddef>
#include <cstdint>
#include <expected>
#include <limits>
#include <span>
#include <vector>

namespace {

using logh7::legacy::ImageDecodeError;
using logh7::legacy::ImageDecodeErrorCode;
using Microsoft::WRL::ComPtr;

constexpr std::uint32_t kMaximumDimension = 4096;
constexpr std::uint32_t kBytesPerPixel = 4;

}

namespace logh7::legacy {

std::expected<BgraImage, ImageDecodeError> DecodeWicBmpOrJpeg(
    const std::span<const std::byte> bytes) {
    if (bytes.size() > std::numeric_limits<UINT>::max()) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }

    ComPtr<IStream> stream;
    stream.Attach(SHCreateMemStream(
        reinterpret_cast<const BYTE*>(bytes.data()),
        static_cast<UINT>(bytes.size())));
    if (!stream) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicStreamCreateFailed});
    }

    ComPtr<IWICImagingFactory> factory;
    if (FAILED(CoCreateInstance(
            CLSID_WICImagingFactory,
            nullptr,
            CLSCTX_INPROC_SERVER,
            IID_PPV_ARGS(factory.GetAddressOf())))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicComUnavailable});
    }

    ComPtr<IWICBitmapDecoder> decoder;
    if (FAILED(factory->CreateDecoderFromStream(
            stream.Get(),
            nullptr,
            WICDecodeMetadataCacheOnDemand,
            decoder.GetAddressOf()))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicDecoderCreateFailed});
    }

    UINT frameCount = 0;
    if (FAILED(decoder->GetFrameCount(&frameCount))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameDecodeFailed});
    }
    if (frameCount != 1U) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameCountUnsupported});
    }

    GUID containerFormat{};
    if (FAILED(decoder->GetContainerFormat(&containerFormat))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameDecodeFailed});
    }
    if (!IsEqualGUID(containerFormat, GUID_ContainerFormatBmp) &&
        !IsEqualGUID(containerFormat, GUID_ContainerFormatJpeg)) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicContainerUnsupported});
    }

    ComPtr<IWICBitmapFrameDecode> frame;
    if (FAILED(decoder->GetFrame(0, frame.GetAddressOf()))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameDecodeFailed});
    }

    UINT width = 0;
    UINT height = 0;
    if (FAILED(frame->GetSize(&width, &height))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameDecodeFailed});
    }
    if (width == 0U || height == 0U ||
        width > kMaximumDimension || height > kMaximumDimension) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicDimensionsUnsupported});
    }
    if (width > std::numeric_limits<std::uint32_t>::max() / kBytesPerPixel) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }
    const std::uint32_t stride = width * kBytesPerPixel;
    if (height > std::numeric_limits<std::size_t>::max() / stride) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }
    const std::size_t pixelBytes = static_cast<std::size_t>(height) * stride;
    if (pixelBytes > std::numeric_limits<UINT>::max()) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::ImageSizeOverflow});
    }

    ComPtr<IWICFormatConverter> converter;
    if (FAILED(factory->CreateFormatConverter(converter.GetAddressOf())) ||
        FAILED(converter->Initialize(
            frame.Get(),
            GUID_WICPixelFormat32bppPBGRA,
            WICBitmapDitherTypeNone,
            nullptr,
            0.0,
            WICBitmapPaletteTypeCustom))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicPixelConversionFailed});
    }

    BgraImage image{width, height, stride, std::vector<std::byte>(pixelBytes)};
    if (FAILED(converter->CopyPixels(
            nullptr,
            stride,
            static_cast<UINT>(image.pixels.size()),
            reinterpret_cast<BYTE*>(image.pixels.data())))) {
        return std::unexpected(ImageDecodeError{ImageDecodeErrorCode::WicFrameDecodeFailed});
    }
    return image;
}

}
