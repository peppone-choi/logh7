using System.Buffers.Binary;
using System.Text;

namespace Logh7.Server.OriginalGateway;

// ORIGINAL_STATIC wire shapes recovered from G7MTClient (see
// docs/handoffs/2026-09-02-notify-invalid-message-wire-static.md):
//   0x0500 NotifyInvalidMessage: u16 error, u8 reserved, u8 msgSize(<=128), msgSize x u16 BE (UTF-16BE)
//   0x0501 NotifyError:          u8 msgSize(<=128), msgSize x u16 BE (UTF-16BE)
// The 2-byte application type precedes the body; the caller wraps the frame with the
// gateway message-code envelope exactly like OriginalMoveGridCodec.EncodeNotification.
public static class OriginalNotifyMessageCodec
{
    public const ushort InvalidMessageType = 0x0500;
    public const ushort ErrorType = 0x0501;
    public const int MaximumMessageUnits = 128;

    public static byte[] EncodeInvalidMessage(ushort error, string text, byte reserved = 0)
    {
        var units = Units(text);
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 4 + units.Length * 2];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, InvalidMessageType);
        var body = payload[sizeof(ushort)..];
        BinaryPrimitives.WriteUInt16BigEndian(body, error);
        body[2] = reserved;
        body[3] = checked((byte)units.Length);
        WriteUnits(body[4..], units);
        return frame;
    }

    public static byte[] EncodeError(string text)
    {
        var units = Units(text);
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 2 + units.Length * 2];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, ErrorType);
        var body = payload[sizeof(ushort)..];
        body[0] = checked((byte)units.Length);
        body[1] = 0;
        WriteUnits(body[2..], units);
        return frame;
    }

    private static char[] Units(string text)
    {
        ArgumentNullException.ThrowIfNull(text);
        var units = text.ToCharArray();
        if (units.Length > MaximumMessageUnits)
        {
            throw new ArgumentOutOfRangeException(nameof(text), "ORIGINAL_NOTIFY_MESSAGE_TOO_LONG");
        }
        return units;
    }

    private static void WriteUnits(Span<byte> destination, char[] units)
    {
        for (var i = 0; i < units.Length; i++)
        {
            BinaryPrimitives.WriteUInt16BigEndian(destination[(i * 2)..], units[i]);
        }
    }
}
