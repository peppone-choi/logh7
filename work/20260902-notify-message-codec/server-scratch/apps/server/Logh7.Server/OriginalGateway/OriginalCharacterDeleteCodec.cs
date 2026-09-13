using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalCharacterDeleteDecodeResult(
    bool Success,
    uint SessionId,
    string? ErrorCode);

public static class OriginalCharacterDeleteCodec
{
    public const ushort RequestType = 0x2008;
    public const int RequestMessageSize = sizeof(ushort) + sizeof(uint);

    public static OriginalCharacterDeleteDecodeResult DecodeRequest(
        ReadOnlySpan<byte> payload)
    {
        if (payload.Length != RequestMessageSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return new OriginalCharacterDeleteDecodeResult(
                false, 0, "original.character-delete.length-or-type");
        }

        return new OriginalCharacterDeleteDecodeResult(
            true,
            BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]),
            null);
    }
}
