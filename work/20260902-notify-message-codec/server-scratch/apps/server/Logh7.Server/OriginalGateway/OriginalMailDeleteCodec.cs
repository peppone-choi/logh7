using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMailDeleteCommand(
    uint CharacterId,
    string DisplayName,
    byte Box,
    uint MailId,
    byte[] Payload);

public readonly record struct OriginalMailDeleteDecodeResult(
    bool Success,
    OriginalMailDeleteCommand? Command,
    string? ErrorCode);

public static class OriginalMailDeleteCodec
{
    public const ushort RequestType = 0x0f12;
    private const int CompactCharacterFixedBytes =
        sizeof(uint) + sizeof(byte) + sizeof(ushort) * 2 + 3 + sizeof(uint) * 3;
    private const int TailBytes = sizeof(byte) + sizeof(uint);
    private const int MaximumNameCharacters = 13;

    public static OriginalMailDeleteDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < sizeof(ushort) + CompactCharacterFixedBytes + TailBytes)
        {
            return Invalid("original.mail.delete.command-length");
        }

        if (BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return Invalid("original.mail.delete.type");
        }

        var cursor = sizeof(ushort);
        var characterId = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        var nameLength = payload[cursor++];
        if (nameLength > MaximumNameCharacters)
        {
            return Invalid("original.mail.delete.character-name-length");
        }

        var expectedLength = checked(
            sizeof(ushort) + CompactCharacterFixedBytes +
            nameLength * sizeof(char) + TailBytes);
        if (payload.Length != expectedLength)
        {
            return Invalid("original.mail.delete.command-length");
        }

        var nameCharacters = new char[nameLength];
        for (var index = 0; index < nameCharacters.Length; index++)
        {
            nameCharacters[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(
                payload[(cursor + index * sizeof(char))..]);
        }
        cursor += nameCharacters.Length * sizeof(char);
        cursor += sizeof(ushort) * 2 + 3 + sizeof(uint) * 3;
        var box = payload[cursor++];
        var mailId = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        if (characterId == 0 || mailId == 0 || box > 1)
        {
            return Invalid("original.mail.delete.identifier");
        }

        return new OriginalMailDeleteDecodeResult(
            true,
            new OriginalMailDeleteCommand(
                characterId,
                new string(nameCharacters),
                box,
                mailId,
                payload.ToArray()),
            null);
    }

    public static byte[] EncodeAccepted(OriginalMailDeleteCommand command)
    {
        if (command.Payload.Length == 0 ||
            BinaryPrimitives.ReadUInt16BigEndian(command.Payload) != RequestType)
        {
            throw new ArgumentException("ORIGINAL_MAIL_DELETE_COMMAND", nameof(command));
        }

        var response = new byte[OriginalLoginCodec.MessageCodeSize + command.Payload.Length];
        command.Payload.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return response;
    }

    private static OriginalMailDeleteDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
