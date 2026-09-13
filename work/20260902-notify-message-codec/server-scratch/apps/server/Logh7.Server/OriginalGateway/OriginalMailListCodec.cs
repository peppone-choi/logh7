using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMailListCharacter(
    uint CharacterId,
    string DisplayName);

public readonly record struct OriginalMailListRequest(
    uint CharacterId,
    byte Box,
    bool UnreadOnly,
    byte[] Payload);

public readonly record struct OriginalMailListWireRecord(
    uint MailId,
    uint ReferId,
    byte Status,
    OriginalMailListCharacter Sender,
    OriginalMailListCharacter Recipient,
    uint Metadata,
    string Subject,
    string Body);

public readonly record struct OriginalMailListDecodeResult(
    bool Success,
    OriginalMailListRequest? Request,
    string? ErrorCode);

public static class OriginalMailListCodec
{
    public const ushort RequestType = 0x0f08;
    public const ushort BeginType = 0x0f08;
    public const ushort EndType = 0x0f09;
    public const ushort RecordType = 0x0f0a;
    public const int RequestMessageSize = 0x1c;

    private const int MaximumNameCharacters = 13;
    private const int MaximumSubjectCharacters = 128;
    private const int MaximumBodyCharacters = 512;

    public static OriginalMailListDecodeResult DecodeRequest(ReadOnlySpan<byte> payload)
    {
        if (payload.Length != RequestMessageSize)
        {
            return Invalid("original.mail.list.request-length");
        }

        if (BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return Invalid("original.mail.list.type");
        }

        var cursor = sizeof(ushort);
        var characterId = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        var nameLength = payload[cursor++];
        var nameBytes = checked(nameLength * sizeof(char));
        if (payload.Length - cursor < nameBytes + 19)
        {
            return Invalid("original.mail.list.character-shape");
        }

        cursor += nameBytes;
        cursor += sizeof(ushort) * 2;
        cursor += 3;
        cursor += sizeof(uint) * 3;
        if (cursor + 2 != payload.Length)
        {
            return Invalid("original.mail.list.character-shape");
        }

        var box = payload[cursor++];
        var unread = payload[cursor++];
        if (characterId == 0 || box > 1 || unread > 1)
        {
            return Invalid("original.mail.list.filter");
        }

        return new OriginalMailListDecodeResult(
            true,
            new OriginalMailListRequest(characterId, box, unread != 0, payload.ToArray()),
            null);
    }

    public static byte[] EncodeBegin(OriginalMailListRequest request)
    {
        if (request.Payload.Length != RequestMessageSize ||
            BinaryPrimitives.ReadUInt16BigEndian(request.Payload) != RequestType)
        {
            throw new ArgumentException("ORIGINAL_MAIL_LIST_REQUEST", nameof(request));
        }

        var response = new byte[OriginalLoginCodec.MessageCodeSize + request.Payload.Length];
        request.Payload.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return response;
    }

    public static byte[] EncodeRecord(OriginalMailListWireRecord record)
    {
        if (record.MailId == 0 || record.Sender.CharacterId == 0 ||
            record.Recipient.CharacterId == 0)
        {
            throw new ArgumentOutOfRangeException(nameof(record));
        }

        var writer = new WireWriter();
        writer.WriteUInt32(record.MailId);
        writer.WriteUInt32(record.ReferId);
        writer.WriteByte(record.Status);
        WriteCharacter(writer, record.Sender);
        WriteCharacter(writer, record.Recipient);
        writer.WriteUInt32(record.Metadata);
        writer.WritePstr16(record.Subject, MaximumSubjectCharacters);
        writer.WriteLongPstr16(record.Body, MaximumBodyCharacters);
        return Wrap(RecordType, writer);
    }

    public static byte[] EncodeEnd() => OriginalWorldBootstrapCodec.EncodeStatus(EndType);

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

    private static byte[] Wrap(ushort type, WireWriter writer)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + writer.Count];
        BinaryPrimitives.WriteUInt16BigEndian(
            response.AsSpan(OriginalLoginCodec.MessageCodeSize), type);
        writer.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort)));
        return response;
    }

    private static OriginalMailListDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);

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
            var length = Math.Min(value.Length, maximumCharacters);
            WriteByte(checked((byte)length));
            WriteCharacters(value.AsSpan(0, length));
        }

        public void WriteLongPstr16(string value, int maximumCharacters)
        {
            ArgumentNullException.ThrowIfNull(value);
            var length = Math.Min(value.Length, maximumCharacters);
            WriteUInt16(checked((ushort)length));
            WriteCharacters(value.AsSpan(0, length));
        }

        public void CopyTo(Span<byte> destination) =>
            _bytes.ToArray().CopyTo(destination);

        private void WriteCharacters(ReadOnlySpan<char> value)
        {
            foreach (var character in value)
            {
                WriteUInt16(character);
            }
        }
    }
}
