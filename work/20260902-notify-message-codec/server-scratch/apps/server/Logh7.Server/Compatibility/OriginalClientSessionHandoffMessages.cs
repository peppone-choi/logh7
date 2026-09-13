using System.Buffers.Binary;

namespace Logh7.Server.Compatibility;

public enum OriginalClientSessionHandoffParseStatus
{
    Success,
    Invalid
}

public readonly record struct OriginalClientSessionHandoff(uint HandoffToken);

public readonly record struct OriginalClientSessionHandoffParseResult(
    OriginalClientSessionHandoffParseStatus Status,
    OriginalClientSessionHandoff? Message,
    string? ErrorCode);

public static class OriginalClientSessionHandoffMessages
{
    public const ushort HandoffType = 0x0020;
    private const int PayloadSize = sizeof(ushort) + sizeof(uint);

    public static OriginalClientSessionHandoffParseResult Decode(
        ReadOnlySpan<byte> applicationPayload)
    {
        if (applicationPayload.Length != PayloadSize)
        {
            return Invalid("original.session-handoff.length");
        }

        if (BinaryPrimitives.ReadUInt16BigEndian(applicationPayload) != HandoffType)
        {
            return Invalid("original.session-handoff.type");
        }

        return new OriginalClientSessionHandoffParseResult(
            OriginalClientSessionHandoffParseStatus.Success,
            new OriginalClientSessionHandoff(
                BinaryPrimitives.ReadUInt32BigEndian(applicationPayload[sizeof(ushort)..])),
            null);
    }

    private static OriginalClientSessionHandoffParseResult Invalid(string errorCode) =>
        new(OriginalClientSessionHandoffParseStatus.Invalid, null, errorCode);
}

