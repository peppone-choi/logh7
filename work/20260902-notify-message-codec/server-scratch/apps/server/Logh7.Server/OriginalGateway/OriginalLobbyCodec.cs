using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalLobbyLoginMessage(byte[] Code, ushort[] AccountElements);

public readonly record struct OriginalLobbySessionRecord(
    ushort SessionId,
    byte Status,
    string Name,
    string BeginDay,
    uint Term);

public sealed record OriginalLobbyCharacterRecord(
    long CharacterId,
    short Faction,
    short Blood,
    short Sex,
    string LastName,
    string FirstName,
    string FlagshipName,
    int Face,
    short[] AbilityValues);

public readonly record struct OriginalLobbySessionSelectionParseResult(
    bool Success,
    ushort SessionId,
    string? ErrorCode);

public readonly record struct OriginalLobbyLoginParseResult(
    bool Success,
    OriginalLobbyLoginMessage? Message,
    string? ErrorCode);

public static class OriginalLobbyCodec
{
    public const ushort LoginRequestType = 0x2000;
    public const ushort LoginAcceptedType = 0x2001;
    public const ushort CharacterListRequestType = 0x2003;
    public const ushort CharacterListResponseType = 0x2004;
    public const ushort SessionListRequestType = 0x2005;
    public const ushort SessionListResponseType = 0x2006;
    public const ushort SessionSelectionRequestType = 0x2009;
    public const ushort SessionLoginAcceptedType = 0x200A;
    public const ushort SessionLoginRejectedType = 0x200B;
    public const int CharacterListMessageSize = 0x06DC;
    public const int SessionListMessageSize = 0x5304;
    public const int MaximumSessions = 64;
    public const int MaximumSessionNameUnits = 13;
    public const int MaximumBeginDayUnits = 65;
    public const int MaximumAccountElements = 17;
    public const uint AgeSecondsPerYear = 0x01E13380;
    public const int MaximumCharacters = 2;
    private const int CharacterCardGapSize = 0x22;
    private const int CharacterCardNameUnits = 0x0D;
    private const int CharacterCardDescriptionUnits = 0x41;

    public static OriginalLobbyLoginParseResult DecodeLogin(ReadOnlySpan<byte> payload)
    {
        const int fixedBodySize = 9;
        if (payload.Length < sizeof(ushort) + fixedBodySize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != LoginRequestType)
        {
            return Invalid("original.lobby.login.length-or-type");
        }

        var body = payload[sizeof(ushort)..];
        var count = body[8];
        if (count > MaximumAccountElements)
        {
            return Invalid("original.lobby.login.account-count");
        }

        if (payload.Length != sizeof(ushort) + fixedBodySize + count * sizeof(ushort))
        {
            return Invalid("original.lobby.login.length");
        }

        var elements = new ushort[count];
        for (var index = 0; index < count; index++)
        {
            elements[index] = BinaryPrimitives.ReadUInt16BigEndian(
                body[(fixedBodySize + index * sizeof(ushort))..]);
        }

        return new OriginalLobbyLoginParseResult(
            true,
            new OriginalLobbyLoginMessage(body[..4].ToArray(), elements),
            null);
    }

    public static byte[] EncodeLoginOk(ReadOnlySpan<byte> code)
    {
        ValidateCode(code);
        var payload = new byte[9];
        code.CopyTo(payload);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(4), LoginAcceptedType);
        return payload;
    }

    public static byte[] EncodeEmptyCharacters(ReadOnlySpan<byte> code)
        => EncodeCharacters(code, []);

    public static byte[] EncodeCharacters(
        ReadOnlySpan<byte> code,
        IReadOnlyList<OriginalLobbyCharacterRecord> characters)
    {
        ValidateCode(code);
        ArgumentNullException.ThrowIfNull(characters);
        if (characters.Count > MaximumCharacters)
        {
            throw new ArgumentOutOfRangeException(nameof(characters), "ORIGINAL_CHARACTER_COUNT");
        }

        var payload = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + CharacterListMessageSize];
        code.CopyTo(payload);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(4), CharacterListResponseType);
        var body = payload.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        body[0] = checked((byte)characters.Count);
        var cursor = 1;
        foreach (var character in characters)
        {
            ValidateCharacter(character);
            var displayName = character.LastName + character.FirstName;

            BinaryPrimitives.WriteUInt16LittleEndian(
                body[cursor..], unchecked((ushort)character.CharacterId));
            cursor += sizeof(ushort);
            body[cursor++] = 1;
            cursor = WriteNullTerminatedPstr16(
                body, cursor, displayName, CharacterCardNameUnits, truncate: true);
            cursor = WriteNullTerminatedPstr16(
                body, cursor, displayName, CharacterCardDescriptionUnits, truncate: true);
            cursor += CharacterCardGapSize;
            body[cursor++] = 2;
            body[cursor++] = 1;

            BinaryPrimitives.WriteUInt32BigEndian(
                body[cursor..], checked((uint)character.CharacterId));
            cursor += sizeof(uint);
            var faction = ToLobbyFaction(character.Faction);
            body[cursor++] = faction;
            body[cursor++] = faction;
            body[cursor++] = 1;
            body[cursor++] = checked((byte)character.Sex);
            body[cursor++] = 1;
            body[cursor++] = 1;
            BinaryPrimitives.WriteUInt32BigEndian(body[cursor..], 18 * AgeSecondsPerYear);
            cursor += sizeof(uint);
            body[cursor++] = 1;
            foreach (var ability in character.AbilityValues)
            {
                BinaryPrimitives.WriteUInt16BigEndian(body[cursor..], checked((ushort)ability));
                cursor += sizeof(ushort);
            }

            cursor = WriteNullTerminatedPstr16(
                body, cursor, character.LastName, CharacterCardNameUnits, truncate: true);
            cursor = WriteNullTerminatedPstr16(
                body, cursor, character.FirstName, CharacterCardNameUnits, truncate: true);
            cursor = WriteNullTerminatedPstr16(
                body, cursor, displayName, CharacterCardNameUnits, truncate: true);
            cursor = WriteNullTerminatedPstr16(
                body, cursor, string.Empty, CharacterCardNameUnits, truncate: false);
            cursor = WriteNullTerminatedPstr16(
                body, cursor, character.FlagshipName, CharacterCardNameUnits, truncate: false);
            body[cursor++] = checked((byte)character.Blood);
            body[cursor++] = 0;
            BinaryPrimitives.WriteUInt32BigEndian(body[cursor..], checked((uint)character.Face));
            cursor += sizeof(uint);
            body[cursor++] = 0;
        }

        return payload;
    }

    public static byte[] EncodeEmptySessions(ReadOnlySpan<byte> code)
        => EncodeSessions(code, []);

    public static OriginalLobbySessionSelectionParseResult DecodeSessionSelection(
        ReadOnlySpan<byte> payload)
    {
        if (payload.Length != sizeof(ushort) + sizeof(ushort) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != SessionSelectionRequestType)
        {
            return new OriginalLobbySessionSelectionParseResult(
                false, 0, "original.lobby.session-selection.length-or-type");
        }

        return new OriginalLobbySessionSelectionParseResult(
            true,
            BinaryPrimitives.ReadUInt16LittleEndian(payload[sizeof(ushort)..]),
            null);
    }

    public static byte[] EncodeSessionLoginOk(
        uint sessionServerIpv4,
        ushort sessionServerPort,
        uint handoffToken)
    {
        if (handoffToken == 0)
        {
            throw new ArgumentOutOfRangeException(nameof(handoffToken), "ORIGINAL_HANDOFF_TOKEN_ZERO");
        }

        var payload = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 0x0C];
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(4), SessionLoginAcceptedType);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(6), sessionServerIpv4);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(10), sessionServerPort);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(12), 0);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(14), handoffToken);
        return payload;
    }

    public static byte[] EncodeSessions(
        ReadOnlySpan<byte> code,
        IReadOnlyList<OriginalLobbySessionRecord> sessions,
        bool lotteryAvailable = false,
        string? serverNotice = null)
    {
        ValidateCode(code);
        ArgumentNullException.ThrowIfNull(sessions);
        if (sessions.Count > MaximumSessions)
        {
            throw new ArgumentOutOfRangeException(nameof(sessions), "ORIGINAL_SESSION_COUNT");
        }

        var payload = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + SessionListMessageSize];
        code.CopyTo(payload);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(4), SessionListResponseType);
        var body = payload.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        // NEW DESIGN: this previously-zero byte is retained by the original
        // client beside sessionCount and carries account-scoped lottery
        // unavailability without adding a fake session or character.
        body[0] = lotteryAvailable ? (byte)1 : (byte)0;
        body[1] = checked((byte)sessions.Count);
        var noticeCarrier = string.IsNullOrEmpty(serverNotice)
            ? null
            : new string(OriginalServerNoticeCodec
                .EncodeText(serverNotice, MaximumBeginDayUnits)
                .Select(value => (char)value)
                .ToArray());
        var cursor = 2;
        for (var sessionIndex = 0; sessionIndex < sessions.Count; sessionIndex++)
        {
            var session = sessions[sessionIndex];
            BinaryPrimitives.WriteUInt16LittleEndian(body[cursor..], session.SessionId);
            cursor += sizeof(ushort);
            body[cursor++] = session.Status;
            cursor = WriteNullTerminatedPstr16(
                body,
                cursor,
                session.Name,
                MaximumSessionNameUnits,
                truncate: false);
            cursor = WriteNullTerminatedPstr16(
                body,
                cursor,
                sessionIndex == 0 && noticeCarrier is not null ? noticeCarrier : session.BeginDay,
                MaximumBeginDayUnits,
                truncate: false);
            BinaryPrimitives.WriteUInt32LittleEndian(body[cursor..], session.Term);
            cursor += sizeof(uint);

            for (byte powerId = 1; powerId <= 2; powerId++)
            {
                body[cursor++] = powerId;
                BinaryPrimitives.WriteUInt32LittleEndian(body[cursor..], 0);
                cursor += sizeof(uint);
                BinaryPrimitives.WriteUInt32LittleEndian(body[cursor..], 0);
                cursor += sizeof(uint);
                BinaryPrimitives.WriteUInt32LittleEndian(body[cursor..], 0);
                cursor += sizeof(uint);
                body[cursor++] = 0;
            }

            body[cursor++] = 0;
        }

        return payload;
    }

    private static int WritePstr16(Span<byte> destination, int cursor, string value, int maximumUnits)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (value.Length > maximumUnits || value.Any(character => char.IsSurrogate(character)))
        {
            throw new ArgumentOutOfRangeException(nameof(value), "ORIGINAL_SESSION_TEXT_LENGTH");
        }

        destination[cursor++] = checked((byte)value.Length);
        foreach (var character in value)
        {
            BinaryPrimitives.WriteUInt16BigEndian(destination[cursor..], character);
            cursor += sizeof(ushort);
        }

        return cursor;
    }

    private static int WriteNullTerminatedPstr16(
        Span<byte> destination,
        int cursor,
        string value,
        int maximumUnits,
        bool truncate)
    {
        ArgumentNullException.ThrowIfNull(value);
        if (value.Any(character => char.IsSurrogate(character)))
        {
            throw new ArgumentOutOfRangeException(nameof(value), "ORIGINAL_CHARACTER_TEXT_SURROGATE");
        }

        var maximumCharacters = maximumUnits - 1;
        if (!truncate && value.Length > maximumCharacters)
        {
            throw new ArgumentOutOfRangeException(nameof(value), "ORIGINAL_CHARACTER_TEXT_LENGTH");
        }

        var length = Math.Min(value.Length, maximumCharacters);
        destination[cursor++] = checked((byte)(length + 1));
        for (var index = 0; index < length; index++)
        {
            BinaryPrimitives.WriteUInt16BigEndian(destination[cursor..], value[index]);
            cursor += sizeof(ushort);
        }

        BinaryPrimitives.WriteUInt16BigEndian(destination[cursor..], 0);
        return cursor + sizeof(ushort);
    }

    private static void ValidateCharacter(OriginalLobbyCharacterRecord character)
    {
        ArgumentNullException.ThrowIfNull(character);
        if (character.CharacterId is <= 0 or > uint.MaxValue)
        {
            throw new ArgumentOutOfRangeException(nameof(character), "ORIGINAL_CHARACTER_ID");
        }

        if (character.Sex is < 0 or > byte.MaxValue ||
            character.Blood is < 0 or > byte.MaxValue ||
            character.Face < 0 ||
            character.AbilityValues is null ||
            character.AbilityValues.Length != 8 ||
            character.AbilityValues.Any(value => value < 0))
        {
            throw new ArgumentException("ORIGINAL_CHARACTER_RECORD", nameof(character));
        }
    }

    private static byte ToLobbyFaction(short faction) => faction switch
    {
        0x0500 => 2,
        0x0501 or 0x0502 => 3,
        0 or 1 => 2,
        >= 2 and <= byte.MaxValue => checked((byte)faction),
        _ => 2
    };

    private static void ValidateCode(ReadOnlySpan<byte> code)
    {
        if (code.Length != OriginalLoginCodec.MessageCodeSize)
        {
            throw new ArgumentException("ORIGINAL_MESSAGE_CODE_LENGTH", nameof(code));
        }
    }

    private static OriginalLobbyLoginParseResult Invalid(string code) =>
        new(false, null, code);
}
