using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public enum OriginalLoginParseStatus
{
    Success,
    Invalid
}

public readonly record struct OriginalLoginMessage(
    byte[] Code,
    ushort VersionMajor,
    ushort VersionMinor,
    byte UnknownByte08,
    ushort[] AccountElements,
    ushort[] PasswordElements);

public readonly record struct OriginalLoginParseResult(
    OriginalLoginParseStatus Status,
    OriginalLoginMessage? Message,
    string? ErrorCode);

public static class OriginalLoginCodec
{
    public const ushort RequestType = 0x7000;
    public const ushort AcceptedType = 0x7001;
    public const ushort RejectedType = 0x7002;
    public const int MaximumAccountElements = 31;
    public const int MaximumPasswordElements = 11;
    public const int MessageCodeSize = 4;

    private const int FixedBodyBeforeAccountCount = 9;

    public static OriginalLoginParseResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < sizeof(ushort) + FixedBodyBeforeAccountCount + 1 ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return Invalid("original.login.length-or-type");
        }

        var body = payload[sizeof(ushort)..];
        var accountCount = body[FixedBodyBeforeAccountCount];
        if (accountCount > MaximumAccountElements)
        {
            return Invalid("original.login.account-count");
        }

        var passwordCountOffset = FixedBodyBeforeAccountCount + 1 + accountCount * sizeof(ushort);
        if (passwordCountOffset >= body.Length)
        {
            return Invalid("original.login.length");
        }

        var passwordCount = body[passwordCountOffset];
        if (passwordCount > MaximumPasswordElements)
        {
            return Invalid("original.login.password-count");
        }

        var expectedLength = sizeof(ushort) + passwordCountOffset + 1 + passwordCount * sizeof(ushort);
        if (payload.Length != expectedLength)
        {
            return Invalid("original.login.length");
        }

        return new OriginalLoginParseResult(
            OriginalLoginParseStatus.Success,
            new OriginalLoginMessage(
                body[..MessageCodeSize].ToArray(),
                BinaryPrimitives.ReadUInt16BigEndian(body[4..]),
                BinaryPrimitives.ReadUInt16BigEndian(body[6..]),
                body[8],
                ReadElements(body, FixedBodyBeforeAccountCount + 1, accountCount),
                ReadElements(body, passwordCountOffset + 1, passwordCount)),
            null);
    }

    public static byte[] EncodeAccepted(
        ReadOnlySpan<byte> code,
        uint sessionServerIpv4,
        ushort sessionServerPort,
        uint handoffToken)
    {
        ValidateCode(code);
        var payload = new byte[sizeof(ushort) + MessageCodeSize + sizeof(ushort) + sizeof(uint) + sizeof(ushort) + sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(payload, AcceptedType);
        code.CopyTo(payload.AsSpan(sizeof(ushort)));
        var offset = sizeof(ushort) + MessageCodeSize;
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(offset), 0);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(offset + 2), sessionServerIpv4);
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(offset + 6), sessionServerPort);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(offset + 8), handoffToken);
        return payload;
    }

    public static byte[] EncodeGenericRejection()
    {
        // Static client evidence proves the 0x7002 body size but not the meaning
        // of its fields. Zero is the fail-closed UNKNOWN_NEUTRAL value; live L1
        // uses only the independently tested accepted path.
        var payload = new byte[sizeof(ushort) + 0x106];
        BinaryPrimitives.WriteUInt16BigEndian(payload, RejectedType);
        return payload;
    }

    private static ushort[] ReadElements(ReadOnlySpan<byte> body, int offset, int count)
    {
        var result = new ushort[count];
        for (var index = 0; index < count; index++)
        {
            result[index] = BinaryPrimitives.ReadUInt16BigEndian(body[(offset + index * sizeof(ushort))..]);
        }

        return result;
    }

    private static void ValidateCode(ReadOnlySpan<byte> code)
    {
        if (code.Length != MessageCodeSize)
        {
            throw new ArgumentException("ORIGINAL_MESSAGE_CODE_LENGTH", nameof(code));
        }
    }

    private static OriginalLoginParseResult Invalid(string code) =>
        new(OriginalLoginParseStatus.Invalid, null, code);
}
