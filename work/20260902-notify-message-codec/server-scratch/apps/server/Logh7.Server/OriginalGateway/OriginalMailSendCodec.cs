using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Logh7.Server.OriginalGateway;

public sealed record OriginalMailSendCommand(
    uint SenderCharacterId,
    uint RecipientCharacterId,
    string Title,
    string Body,
    uint[] HeaderWords,
    string RequestFingerprint);

public readonly record struct OriginalMailSendDecodeResult(
    bool Success,
    OriginalMailSendCommand? Command,
    string? ErrorCode);

public static class OriginalMailSendCodec
{
    public const ushort RequestType = 0x0f10;
    public const ushort ResponseType = 0x0f11;
    public const int HeaderWordCount = 15;
    private const int HeaderSize = HeaderWordCount * sizeof(uint);
    private const int SenderCharacterWordIndex = 3;
    private const int RecipientCharacterWordIndex = 9;
    private const int MaximumTitleUnits = 64;
    private const int MaximumBodyUnits = 2048;

    public static OriginalMailSendDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < sizeof(ushort) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return Invalid("original.mail.send.type");
        }

        if (payload.Length < sizeof(ushort) + HeaderSize)
        {
            return Invalid("original.mail.send.header.truncated");
        }

        var headerWords = new uint[HeaderWordCount];
        var cursor = sizeof(ushort);
        for (var index = 0; index < headerWords.Length; index++)
        {
            headerWords[index] = BinaryPrimitives.ReadUInt32LittleEndian(
                payload[cursor..]);
            cursor += sizeof(uint);
        }

        if (!TryReadTerminatedPstr16(
                payload,
                ref cursor,
                MaximumTitleUnits,
                "title",
                out var title,
                out var errorCode) ||
            !TryReadTerminatedPstr16(
                payload,
                ref cursor,
                MaximumBodyUnits,
                "body",
                out var body,
                out errorCode))
        {
            return Invalid(errorCode!);
        }

        if (cursor != payload.Length)
        {
            return Invalid("original.mail.send.trailing-bytes");
        }

        var senderCharacterId = headerWords[SenderCharacterWordIndex];
        var recipientCharacterId = headerWords[RecipientCharacterWordIndex];
        if (senderCharacterId == 0 || recipientCharacterId == 0)
        {
            return Invalid("original.mail.send.character-id");
        }

        if (string.IsNullOrWhiteSpace(title) || string.IsNullOrWhiteSpace(body))
        {
            return Invalid("original.mail.send.content-empty");
        }

        return new OriginalMailSendDecodeResult(
            true,
            new OriginalMailSendCommand(
                senderCharacterId,
                recipientCharacterId,
                title,
                body,
                headerWords,
                Convert.ToHexStringLower(SHA256.HashData(payload))),
            null);
    }

    private static bool TryReadTerminatedPstr16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        int maximumUnits,
        string field,
        out string value,
        out string? errorCode)
    {
        value = string.Empty;
        errorCode = null;
        if (payload.Length - cursor < sizeof(ushort))
        {
            errorCode = $"original.mail.send.{field}.truncated";
            return false;
        }

        var units = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        if (units is 0 || units > maximumUnits)
        {
            errorCode = $"original.mail.send.{field}.length";
            return false;
        }

        var byteCount = checked(units * sizeof(char));
        if (payload.Length - cursor < byteCount)
        {
            errorCode = $"original.mail.send.{field}.truncated";
            return false;
        }

        var characters = new char[units];
        for (var index = 0; index < characters.Length; index++)
        {
            characters[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(
                payload[(cursor + index * sizeof(char))..]);
        }
        cursor += byteCount;
        if (characters[^1] != '\0' || characters[..^1].Contains('\0'))
        {
            errorCode = $"original.mail.send.{field}.terminator";
            return false;
        }

        value = new string(characters, 0, characters.Length - 1);
        return true;
    }

    private static OriginalMailSendDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
