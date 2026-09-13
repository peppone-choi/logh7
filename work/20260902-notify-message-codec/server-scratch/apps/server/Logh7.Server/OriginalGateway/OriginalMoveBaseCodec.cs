using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMoveBaseRequest(uint Time, uint Wait, uint ActorId,
    ushort Card, uint Pcp, uint Mcp, uint BaseId, ushort Mode);

public static class OriginalMoveBaseCodec
{
    public const ushort RequestType = 0x0b00;

    // Reader00449380 consumes this same command on the server->client route;
    // dispatcher004BA2B0 copies its32-byte expanded struct and calls004C5780.
    // It is separate from0B0B, which changes player location in004BEE60.
    // Caller supplies authoritative balances/deadline fields, never blind echo.
    public static byte[] EncodeResponse(OriginalMoveBaseRequest response)
    {
        var frame = new byte[34];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, RequestType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], response.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], response.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], response.ActorId);
        BinaryPrimitives.WriteUInt16BigEndian(payload[14..], response.Card);
        BinaryPrimitives.WriteUInt32BigEndian(payload[16..], response.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[20..], response.Mcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[24..], response.BaseId);
        BinaryPrimitives.WriteUInt16BigEndian(payload[28..], response.Mode);
        return frame;
    }

    // Original reader00449380/logger00449550: packed body28, expanded32.
    // Raw client cost/mode values are decoded, not trusted as authority state.
    public static bool TryDecodeRequest(ReadOnlySpan<byte> payload, out OriginalMoveBaseRequest request)
    {
        request = default;
        if (payload.Length != 30 || BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
            return false;
        request = new(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[14..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[16..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[20..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[24..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[28..]));
        return true;
    }
}
