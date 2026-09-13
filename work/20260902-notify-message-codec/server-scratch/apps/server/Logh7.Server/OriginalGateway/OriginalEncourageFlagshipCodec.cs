using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

/// <summary>
/// 鼓舞 = 0x0409 CommandEncourageFlagship (the client's own name for the type,
/// from its message-type table at .data 0x00768FD8).
/// </summary>
/// <remarks>
/// ORIGINAL_OBSERVED 2026-09-09, twice, byte for byte apart from the leading
/// word: <c>0409 08D24738 00000002 00000002 00000002</c> and
/// <c>0409 08C1A738 00000002 00000002 00000002</c> - 18 bytes, big-endian.
/// ORIGINAL_STATIC 2026-09-10: the client names the four words itself. Its own
/// logger for this type (0x00494FF0, prefix string <c>_INF:CommandEncourageFlagship#</c>
/// at .data 0x00769DC0) prints <c>time=</c> [+0], <c>wait=</c> [+4],
/// <c>id=</c> [+8] and <c>unit=</c> [+0xC], and the sender 0x004B45D0 confirms
/// the two that matter: it writes the result of 0x004B4A90 (the current
/// character's id) to buffer+8 and its own argument to buffer+0xC before
/// pushing selector 0x58. So <see cref="Id"/> is the acting character and
/// <see cref="Unit"/> is the fleet - which the capture could not tell apart,
/// both being 2 in that world. The sender leaves +0 and +4 alone, which is why
/// <see cref="Time"/> arrives as uninitialised client stack.
/// </remarks>
public readonly record struct OriginalEncourageFlagshipCommand(
    uint Time,
    uint Wait,
    uint Id,
    uint Unit);

public static class OriginalEncourageFlagshipCodec
{
    public const ushort CommandType = 0x0409;
    public const int MessageSize = sizeof(ushort) + sizeof(uint) * 4;

    public static bool TryDecode(ReadOnlySpan<byte> payload,
        out OriginalEncourageFlagshipCommand command)
    {
        command = default;
        if (payload.Length != MessageSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return false;
        }
        command = new OriginalEncourageFlagshipCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]));
        return true;
    }

    public static byte[] Encode(OriginalEncourageFlagshipCommand command)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + MessageSize];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], command.Id);
        BinaryPrimitives.WriteUInt32BigEndian(payload[14..], command.Unit);
        return frame;
    }
}
