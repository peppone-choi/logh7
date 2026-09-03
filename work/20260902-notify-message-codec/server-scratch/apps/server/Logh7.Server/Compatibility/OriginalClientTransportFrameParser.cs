using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

public enum OriginalClientFrameParseStatus
{
    NeedMoreData,
    Frame,
    Invalid
}

public readonly record struct OriginalClientTransportFrame(
    int TotalLength,
    ushort BodyLength,
    ushort OuterControl,
    int PayloadOffset,
    int PayloadLength);

public readonly record struct OriginalClientFrameParseResult(
    OriginalClientFrameParseStatus Status,
    OriginalClientTransportFrame Frame,
    string? ErrorCode);

public static class OriginalClientTransportFrameParser
{
    public const int ConfirmedStaticMaximumBodyLength = 0xf000;

    private const int LengthPrefixSize = sizeof(ushort);
    private const int OuterControlSize = sizeof(ushort);

    public static OriginalClientFrameParseResult Parse(
        ReadOnlySpan<byte> bytes,
        int maximumBodyLength = ConfirmedStaticMaximumBodyLength)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumBodyLength, OuterControlSize);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(maximumBodyLength, ushort.MaxValue);

        if (bytes.Length < LengthPrefixSize)
        {
            return NeedMoreData();
        }

        var bodyLength = BinaryPrimitives.ReadUInt16BigEndian(bytes);
        if (bodyLength < OuterControlSize)
        {
            return Invalid("original.transport.body-too-short");
        }

        if (bodyLength > maximumBodyLength)
        {
            return Invalid("original.transport.body-too-large");
        }

        var totalLength = LengthPrefixSize + bodyLength;
        if (bytes.Length < totalLength)
        {
            return NeedMoreData();
        }

        var outerControl = BinaryPrimitives.ReadUInt16BigEndian(bytes[LengthPrefixSize..]);
        var frame = new OriginalClientTransportFrame(
            TotalLength: totalLength,
            BodyLength: bodyLength,
            OuterControl: outerControl,
            PayloadOffset: LengthPrefixSize + OuterControlSize,
            PayloadLength: bodyLength - OuterControlSize);

        return new OriginalClientFrameParseResult(OriginalClientFrameParseStatus.Frame, frame, null);
    }

    private static OriginalClientFrameParseResult NeedMoreData() =>
        new(OriginalClientFrameParseStatus.NeedMoreData, default, null);

    private static OriginalClientFrameParseResult Invalid(string errorCode) =>
        new(OriginalClientFrameParseStatus.Invalid, default, errorCode);
}

