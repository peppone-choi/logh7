using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

public static class OriginalClientTransportFrameWriter
{
    private const int LengthPrefixSize = sizeof(ushort);
    private const int OuterControlSize = sizeof(ushort);

    public static byte[] Encode(ushort outerControl, ReadOnlySpan<byte> payload) =>
        Encode([], outerControl, payload);

    public static byte[] EncodeBatch(params byte[][] frames)
    {
        ArgumentNullException.ThrowIfNull(frames);
        var length = checked(frames.Sum(frame =>
        {
            ArgumentNullException.ThrowIfNull(frame);
            return frame.Length;
        }));
        var batch = new byte[length];
        var offset = 0;
        foreach (var frame in frames)
        {
            frame.CopyTo(batch, offset);
            offset += frame.Length;
        }

        return batch;
    }

    public static byte[] Encode(
        ReadOnlySpan<byte> bodyPrefix,
        ushort outerControl,
        ReadOnlySpan<byte> payload)
    {
        ArgumentOutOfRangeException.ThrowIfGreaterThan(
            bodyPrefix.Length + payload.Length,
            ushort.MaxValue - OuterControlSize);

        var bodyLength = bodyPrefix.Length + OuterControlSize + payload.Length;
        var frame = new byte[LengthPrefixSize + bodyLength];
        BinaryPrimitives.WriteUInt16BigEndian(frame, (ushort)bodyLength);
        bodyPrefix.CopyTo(frame.AsSpan(LengthPrefixSize));
        var outerControlOffset = LengthPrefixSize + bodyPrefix.Length;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(outerControlOffset), outerControl);
        payload.CopyTo(frame.AsSpan(outerControlOffset + OuterControlSize));
        return frame;
    }
}
