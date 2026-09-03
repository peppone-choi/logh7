using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalCreateCharacterCommand(
    byte RequestCategory,
    uint CharacterId,
    byte Power,
    byte Blood,
    byte Sex,
    string LastName,
    string FirstName,
    uint Age,
    byte BirthMonth,
    byte BirthDay,
    uint Face,
    byte[] AbilityValues,
    byte BonusPoint,
    byte Title,
    byte Rank,
    byte FlagshipClass,
    byte FlagshipModel,
    ushort FlagshipId,
    string FlagshipName,
    byte Check,
    byte[] RawPayload);

public readonly record struct OriginalCreateCharacterParseResult(
    bool Success,
    OriginalCreateCharacterCommand? Command,
    string? ErrorCode);

public static class OriginalCharacterCodec
{
    public const ushort CreateType = 0x1008;
    public const int MaximumNameElements = 13;
    private const int MinimumPayloadSize = sizeof(ushort) + 37;

    public static OriginalCreateCharacterParseResult DecodeCreate(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < MinimumPayloadSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CreateType)
        {
            return Invalid("original.character.create.length-or-type");
        }

        var cursor = sizeof(ushort);
        var requestCategory = payload[cursor++];
        var characterId = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        var power = payload[cursor++];
        var blood = payload[cursor++];
        var sex = payload[cursor++];
        if (!TryReadPstr16(payload, ref cursor, allowEmpty: false, out var lastName) ||
            !TryReadPstr16(payload, ref cursor, allowEmpty: false, out var firstName) ||
            payload.Length - cursor < 27)
        {
            return Invalid("original.character.create.name-or-tail");
        }

        var age = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        var birthMonth = payload[cursor++];
        var birthDay = payload[cursor++];
        var face = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        var abilities = payload.Slice(cursor, 8).ToArray();
        cursor += abilities.Length;
        var bonusPoint = payload[cursor++];
        var title = payload[cursor++];
        var rank = payload[cursor++];
        var flagshipClass = payload[cursor++];
        var flagshipModel = payload[cursor++];
        var flagshipId = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        if (!TryReadPstr16(payload, ref cursor, allowEmpty: true, out var flagshipName) ||
            cursor >= payload.Length)
        {
            return Invalid("original.character.create.flagship");
        }

        var check = payload[cursor++];
        if (cursor != payload.Length)
        {
            return Invalid("original.character.create.trailing-bytes");
        }

        return new OriginalCreateCharacterParseResult(
            true,
            new OriginalCreateCharacterCommand(
                requestCategory,
                characterId,
                power,
                blood,
                sex,
                lastName,
                firstName,
                age,
                birthMonth,
                birthDay,
                face,
                abilities,
                bonusPoint,
                title,
                rank,
                flagshipClass,
                flagshipModel,
                flagshipId,
                flagshipName,
                check,
                payload.ToArray()),
            null);
    }

    public static byte[] EncodeAccepted(OriginalCreateCharacterCommand command)
    {
        var validated = DecodeCreate(command.RawPayload);
        if (!validated.Success)
        {
            throw new ArgumentException("ORIGINAL_CHARACTER_SNAPSHOT_INVALID", nameof(command));
        }

        var validatedPayload = validated.Command!.Value.RawPayload;
        var response = new byte[OriginalLoginCodec.MessageCodeSize + validatedPayload.Length];
        validatedPayload.CopyTo(response, OriginalLoginCodec.MessageCodeSize);
        return response;
    }

    private static bool TryReadPstr16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        bool allowEmpty,
        out string value)
    {
        value = string.Empty;
        if (cursor >= payload.Length)
        {
            return false;
        }

        var count = payload[cursor++];
        if (count > MaximumNameElements || (!allowEmpty && count < 2))
        {
            return false;
        }

        if (count == 0)
        {
            return allowEmpty;
        }

        var byteCount = count * sizeof(ushort);
        if (payload.Length - cursor < byteCount)
        {
            return false;
        }

        var characters = new char[count];
        for (var index = 0; index < count; index++)
        {
            characters[index] = (char)BinaryPrimitives.ReadUInt16BigEndian(
                payload[(cursor + index * sizeof(ushort))..]);
        }

        cursor += byteCount;
        if (characters[^1] != '\0' || characters[..^1].Contains('\0') ||
            characters[..^1].Any(char.IsSurrogate))
        {
            return false;
        }

        value = new string(characters, 0, characters.Length - 1);
        return allowEmpty || value.Length != 0;
    }

    private static OriginalCreateCharacterParseResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
