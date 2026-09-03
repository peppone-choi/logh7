using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalCommandSearchWireRecord(
    uint Time,
    uint Wait,
    uint Unknown0,
    uint Unknown1,
    uint Unknown2);

public static class OriginalCommandSearchCodec
{
    public const ushort Type = 0x0b03;
    public const int PayloadSize = 20;

    public static byte[] Encode(OriginalCommandSearchWireRecord record)
    {
        var response = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + PayloadSize];
        var payload = response.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, Type);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], record.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], record.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], record.Unknown0);
        BinaryPrimitives.WriteUInt32BigEndian(payload[14..], record.Unknown1);
        BinaryPrimitives.WriteUInt32BigEndian(payload[18..], record.Unknown2);
        return response;
    }
}
