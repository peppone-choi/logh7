using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMessengerMessageCommand(
    uint SourceCharacterId,
    string Message);

public static class OriginalMessengerMessageCodec
{
    public const ushort RequestType = 0x0f0f;
    private const int MaximumNameCharacters = 13;
    private const int MaximumFlagshipCharacters = 16;
    private const int MaximumMessageUnits = 512;

    public static bool TryDecode(
        ReadOnlySpan<byte> payload,
        out OriginalMessengerMessageCommand command)
    {
        command = default;
        var cursor = 0;
        if (!TryReadUInt16(payload, ref cursor, out var type) ||
            type != RequestType ||
            !TryReadUInt32(payload, ref cursor, out var header) ||
            header != 0 ||
            !TryReadRecord(payload, ref cursor, out var sourceCharacterId) ||
            !TryReadPstr16(payload, ref cursor, out var message) ||
            cursor != payload.Length ||
            sourceCharacterId == 0 ||
            string.IsNullOrWhiteSpace(message))
        {
            return false;
        }

        command = new OriginalMessengerMessageCommand(sourceCharacterId, message);
        return true;
    }

    public static byte[] EncodeAccepted(ReadOnlySpan<byte> command)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + command.Length];
        command.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return response;
    }

    private static bool TryReadRecord(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out uint characterId)
    {
        characterId = 0;
        if (!TryReadUInt32(payload, ref cursor, out characterId) ||
            !TrySkipByteLengthPstr16(payload, ref cursor, MaximumNameCharacters) ||
            !TryAdvance(payload, ref cursor, sizeof(ushort) * 2) ||
            !TrySkipByteLengthPstr16(payload, ref cursor, MaximumFlagshipCharacters) ||
            !TryReadByte(payload, ref cursor, out var connectionGroupCount) ||
            connectionGroupCount > 1)
        {
            return false;
        }

        for (var group = 0; group < connectionGroupCount; group++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var groupCharacterId) ||
                groupCharacterId != characterId ||
                !TryAdvance(payload, ref cursor, 2) ||
                !TryReadByte(payload, ref cursor, out var nestedCount) ||
                nestedCount > 1)
            {
                return false;
            }

            for (var nested = 0; nested < nestedCount; nested++)
            {
                if (!TryReadUInt32(payload, ref cursor, out var nestedCharacterId) ||
                    nestedCharacterId != characterId ||
                    !TryAdvance(payload, ref cursor, sizeof(ushort) * 2) ||
                    !TrySkipByteLengthPstr16(payload, ref cursor, MaximumNameCharacters))
                {
                    return false;
                }
            }
        }

        if (!TryReadByte(payload, ref cursor, out var statusCount) || statusCount > 4)
        {
            return false;
        }

        for (var status = 0; status < statusCount; status++)
        {
            if (!TryAdvance(payload, ref cursor, sizeof(ushort)) ||
                !TryReadUInt32(payload, ref cursor, out var statusCharacterId) ||
                statusCharacterId != characterId ||
                !TryAdvance(payload, ref cursor, sizeof(ushort) * 2) ||
                !TrySkipByteLengthPstr16(payload, ref cursor, MaximumNameCharacters))
            {
                return false;
            }
        }

        return TryReadUInt32(payload, ref cursor, out var trailingCharacterId) &&
               trailingCharacterId == characterId &&
               TryReadUInt32(payload, ref cursor, out var trailingUnknown0) &&
               trailingUnknown0 == 0 &&
               TryReadUInt32(payload, ref cursor, out var trailingUnknown1) &&
               trailingUnknown1 == 0;
    }

    private static bool TryReadPstr16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out string value)
    {
        value = string.Empty;
        if (!TryReadUInt16(payload, ref cursor, out var units) ||
            units is 0 or > MaximumMessageUnits ||
            !TryAdvance(payload, ref cursor, units * sizeof(char)))
        {
            return false;
        }

        var start = cursor - units * sizeof(char);
        var characters = new char[units];
        for (var index = 0; index < characters.Length; index++)
        {
            characters[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(
                payload[(start + index * sizeof(char))..]);
        }

        if (characters[^1] != '\0' || characters[..^1].Contains('\0'))
        {
            return false;
        }

        value = new string(characters, 0, characters.Length - 1);
        return true;
    }

    private static bool TrySkipByteLengthPstr16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        int maximumCharacters)
    {
        return TryReadByte(payload, ref cursor, out var characterCount) &&
               characterCount <= maximumCharacters &&
               TryAdvance(payload, ref cursor, characterCount * sizeof(ushort));
    }

    private static bool TryReadByte(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out byte value)
    {
        value = 0;
        if (!TryAdvance(payload, ref cursor, 1))
        {
            return false;
        }

        value = payload[cursor - 1];
        return true;
    }

    private static bool TryReadUInt16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out ushort value)
    {
        value = 0;
        if (!TryAdvance(payload, ref cursor, sizeof(ushort)))
        {
            return false;
        }

        value = BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor - sizeof(ushort))..]);
        return true;
    }

    private static bool TryReadUInt32(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out uint value)
    {
        value = 0;
        if (!TryAdvance(payload, ref cursor, sizeof(uint)))
        {
            return false;
        }

        value = BinaryPrimitives.ReadUInt32BigEndian(payload[(cursor - sizeof(uint))..]);
        return true;
    }

    private static bool TryAdvance(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        int count)
    {
        if (count < 0 || cursor < 0 || cursor > payload.Length - count)
        {
            return false;
        }

        cursor += count;
        return true;
    }
}
