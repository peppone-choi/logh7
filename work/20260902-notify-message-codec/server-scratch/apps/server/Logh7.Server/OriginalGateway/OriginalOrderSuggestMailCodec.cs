using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public enum OriginalOrderSuggestType : byte
{
    Suggestion = 0,
    Order = 1
}

public readonly record struct OriginalOrderSuggestMailWireRecord(
    uint MailId,
    uint ReferId,
    byte Status,
    OriginalMailListCharacter Sender,
    OriginalMailListCharacter Recipient,
    uint Time,
    ushort Command,
    OriginalOrderSuggestType OrderSuggestType,
    byte Influence,
    uint UnknownTrailing0,
    uint UnknownTrailing1);

public static class OriginalOrderSuggestMailCodec
{
    public const ushort OrderType = 0x0f13;
    public const ushort NotifyType = 0x0f15;
    public const int MinimumPayloadSize = 73;

    private const int MaximumNameCharacters = 13;

    public static byte[] EncodeOrder(OriginalOrderSuggestMailWireRecord record)
    {
        if (record.MailId == 0 ||
            record.Sender.CharacterId == 0 ||
            record.Recipient.CharacterId == 0 ||
            record.OrderSuggestType is not (
                OriginalOrderSuggestType.Order or OriginalOrderSuggestType.Suggestion))
        {
            throw new ArgumentOutOfRangeException(nameof(record));
        }

        var writer = new WireWriter();
        writer.WriteUInt32(record.MailId);
        writer.WriteUInt32(record.ReferId);
        writer.WriteByte(record.Status);
        WriteCharacter(writer, record.Sender);
        WriteCharacter(writer, record.Recipient);
        writer.WriteUInt32(record.Time);
        writer.WriteUInt16(record.Command);
        writer.WriteByte((byte)record.OrderSuggestType);
        writer.WriteByte(record.Influence);
        writer.WriteUInt32(record.UnknownTrailing0);
        writer.WriteUInt32(record.UnknownTrailing1);

        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + writer.Count];
        BinaryPrimitives.WriteUInt16BigEndian(
            response.AsSpan(OriginalLoginCodec.MessageCodeSize),
            OrderType);
        writer.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort)));
        return response;
    }

    public static byte[] EncodeNotify(uint mailId, uint referId, byte status)
    {
        if (mailId == 0)
        {
            throw new ArgumentOutOfRangeException(nameof(mailId));
        }

        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 9];
        var payload = response.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, NotifyType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[sizeof(ushort)..], mailId);
        BinaryPrimitives.WriteUInt32BigEndian(
            payload[(sizeof(ushort) + sizeof(uint))..], referId);
        payload[sizeof(ushort) + (2 * sizeof(uint))] = status;
        return response;
    }

    private static void WriteCharacter(WireWriter writer, OriginalMailListCharacter character)
    {
        writer.WriteUInt32(character.CharacterId);
        writer.WritePstr16(character.DisplayName, MaximumNameCharacters);
        writer.WriteUInt16(0);
        writer.WriteUInt16(0);
        writer.WriteByte(0);
        writer.WriteByte(0);
        writer.WriteByte(0);
        writer.WriteUInt32(0);
        writer.WriteUInt32(0);
        writer.WriteUInt32(0);
    }

    private sealed class WireWriter
    {
        private readonly List<byte> _bytes = [];

        public int Count => _bytes.Count;

        public void WriteByte(byte value) => _bytes.Add(value);

        public void WriteUInt16(ushort value)
        {
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WriteUInt32(uint value)
        {
            _bytes.Add((byte)(value >> 24));
            _bytes.Add((byte)(value >> 16));
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WritePstr16(string value, int maximumCharacters)
        {
            ArgumentNullException.ThrowIfNull(value);
            if (value.Length > maximumCharacters)
            {
                throw new ArgumentOutOfRangeException(nameof(value));
            }

            WriteByte(checked((byte)value.Length));
            foreach (var character in value)
            {
                WriteUInt16(character);
            }
        }

        public void CopyTo(Span<byte> destination) =>
            _bytes.ToArray().CopyTo(destination);
    }
}
