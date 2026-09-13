using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalOrderSuggestReplyCommand(
    uint ActorCharacterId,
    uint TargetCharacterId,
    string ActorDisplayName,
    string TargetDisplayName,
    byte ReplyValue,
    byte[] Payload);

public readonly record struct OriginalOrderSuggestReplyDecodeResult(
    bool Success,
    OriginalOrderSuggestReplyCommand? Command,
    string? ErrorCode);

public static class OriginalOrderSuggestReplyCodec
{
    public const ushort RequestType = 0x0f14;

    private const int MaximumNameCharacters = 13;

    public static OriginalOrderSuggestReplyDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        const int fixedBytesOutsideCharacters =
            sizeof(ushort) + sizeof(uint) * 2 + sizeof(byte) + sizeof(uint) +
            sizeof(uint) * 4 + sizeof(byte) + sizeof(uint) * 4 + sizeof(ushort) +
            sizeof(byte);
        const int minimumCompactCharacterBytes =
            sizeof(byte) + sizeof(ushort) * 2 + sizeof(byte) * 3;
        if (payload.Length < fixedBytesOutsideCharacters + minimumCompactCharacterBytes * 2)
        {
            return Invalid("original.order-suggest-reply.command-length");
        }

        if (BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return Invalid("original.order-suggest-reply.type");
        }

        var cursor = sizeof(ushort);
        var unknown0 = ReadUInt32(payload, ref cursor);
        var actorCharacterId = ReadUInt32(payload, ref cursor);
        var packedPrefix = payload[cursor++];
        var targetCharacterId = ReadUInt32(payload, ref cursor);

        if (!TryReadCompactCharacter(payload, ref cursor, out var actorDisplayName) ||
            !TryReadOuterCharacterReference(
                payload,
                ref cursor,
                targetCharacterId) ||
            !TryReadCompactCharacter(payload, ref cursor, out var targetDisplayName))
        {
            return Invalid("original.order-suggest-reply.character-shape");
        }

        if (cursor >= payload.Length)
        {
            return Invalid("original.order-suggest-reply.command-length");
        }

        var trailingCollectionCount = payload[cursor++];
        if (!TryReadZeroUInt32s(payload, ref cursor, 4) ||
            cursor + sizeof(ushort) + sizeof(byte) > payload.Length)
        {
            return Invalid("original.order-suggest-reply.command-length");
        }

        var trailingSelector = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        var replyValue = payload[cursor++];
        if (cursor != payload.Length)
        {
            return Invalid("original.order-suggest-reply.command-length");
        }

        if (unknown0 != 0 ||
            actorCharacterId == 0 ||
            targetCharacterId == 0 ||
            packedPrefix != 0 ||
            trailingCollectionCount != 0 ||
            trailingSelector != 0 ||
            replyValue > 2)
        {
            return Invalid("original.order-suggest-reply.command-shape");
        }

        return new OriginalOrderSuggestReplyDecodeResult(
            true,
            new OriginalOrderSuggestReplyCommand(
                actorCharacterId,
                targetCharacterId,
                actorDisplayName,
                targetDisplayName,
                replyValue,
                payload.ToArray()),
            null);
    }

    public static byte[] EncodeAccepted(OriginalOrderSuggestReplyCommand command)
    {
        if (command.Payload.Length < sizeof(ushort) ||
            BinaryPrimitives.ReadUInt16BigEndian(command.Payload) != RequestType)
        {
            throw new ArgumentException(
                "ORIGINAL_ORDER_SUGGEST_REPLY_COMMAND",
                nameof(command));
        }

        var response = new byte[OriginalLoginCodec.MessageCodeSize + command.Payload.Length];
        command.Payload.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return response;
    }

    public static byte[] Encode(
        uint actorCharacterId,
        uint targetCharacterId,
        string actorDisplayName,
        string targetDisplayName,
        byte replyValue)
    {
        if (actorCharacterId == 0 ||
            targetCharacterId == 0 ||
            actorDisplayName.Length > MaximumNameCharacters ||
            targetDisplayName.Length > MaximumNameCharacters ||
            replyValue > 2)
        {
            throw new ArgumentOutOfRangeException(nameof(replyValue));
        }

        var payload = new List<byte>();
        WriteUInt16(payload, RequestType);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, actorCharacterId);
        payload.Add(0);
        WriteUInt32(payload, targetCharacterId);
        WriteCompactCharacter(payload, actorDisplayName);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, targetCharacterId);
        WriteCompactCharacter(payload, targetDisplayName);
        payload.Add(0);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, 0);
        WriteUInt32(payload, 0);
        WriteUInt16(payload, 0);
        payload.Add(replyValue);

        var response = new byte[OriginalLoginCodec.MessageCodeSize + payload.Count];
        payload.CopyTo(response, OriginalLoginCodec.MessageCodeSize);
        return response;
    }

    private static bool TryReadCompactCharacter(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out string displayName)
    {
        displayName = string.Empty;
        if (cursor >= payload.Length)
        {
            return false;
        }

        var nameLength = payload[cursor++];
        var characterBytes = checked(nameLength * sizeof(char));
        var fixedTailBytes = sizeof(ushort) * 2 + sizeof(byte) * 3;
        if (nameLength > MaximumNameCharacters ||
            cursor + characterBytes + fixedTailBytes > payload.Length)
        {
            return false;
        }

        var characters = new char[nameLength];
        for (var index = 0; index < characters.Length; index++)
        {
            characters[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(
                payload[(cursor + index * sizeof(char))..]);
        }
        cursor += characterBytes;

        var unknown0 = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        var unknown1 = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        var secondaryNameLength = payload[cursor++];
        var outfitCount = payload[cursor++];
        var baseCount = payload[cursor++];
        if (unknown0 != 0 ||
            unknown1 != 0 ||
            secondaryNameLength != 0 ||
            outfitCount != 0 ||
            baseCount != 0)
        {
            return false;
        }

        displayName = new string(characters);
        return true;
    }

    private static void WriteCompactCharacter(List<byte> payload, string displayName)
    {
        payload.Add(checked((byte)displayName.Length));
        foreach (var character in displayName)
        {
            WriteUInt16(payload, character);
        }
        WriteUInt16(payload, 0);
        WriteUInt16(payload, 0);
        payload.Add(0);
        payload.Add(0);
        payload.Add(0);
    }

    private static void WriteUInt16(List<byte> payload, ushort value)
    {
        payload.Add((byte)(value >> 8));
        payload.Add((byte)value);
    }

    private static void WriteUInt32(List<byte> payload, uint value)
    {
        payload.Add((byte)(value >> 24));
        payload.Add((byte)(value >> 16));
        payload.Add((byte)(value >> 8));
        payload.Add((byte)value);
    }

    private static uint ReadUInt32(ReadOnlySpan<byte> payload, ref int cursor)
    {
        var value = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        return value;
    }

    private static bool TryReadZeroUInt32s(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        int count)
    {
        if (cursor + count * sizeof(uint) > payload.Length)
        {
            return false;
        }

        for (var index = 0; index < count; index++)
        {
            if (ReadUInt32(payload, ref cursor) != 0)
            {
                return false;
            }
        }

        return true;
    }

    private static bool TryReadOuterCharacterReference(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        uint expectedCharacterId)
    {
        if (cursor + sizeof(uint) * 4 > payload.Length)
        {
            return false;
        }

        return ReadUInt32(payload, ref cursor) == 0 &&
            ReadUInt32(payload, ref cursor) == 0 &&
            ReadUInt32(payload, ref cursor) == 0 &&
            ReadUInt32(payload, ref cursor) == expectedCharacterId;
    }

    private static OriginalOrderSuggestReplyDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
