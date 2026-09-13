using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalSessionServerLoginMessage(
    uint HandoffToken,
    ushort Marker,
    ushort Reserved,
    ushort[] AccountElements);

public readonly record struct OriginalSessionServerLoginParseResult(
    bool Success,
    OriginalSessionServerLoginMessage? Message,
    string? ErrorCode);

public static class OriginalSessionServerCodec
{
    public const ushort LoginRequestType = 0x0200;
    public const ushort LoginAcceptedType = 0x0201;
    public const ushort CharacterContextRequestType = 0x0203;
    public const ushort CharacterContextResponseType = 0x0204;
    public const ushort GameLoginRequestType = 0x0205;
    public const ushort GameLoginAcceptedType = 0x0206;
    public const ushort LoginMarker = 0x0057;
    public const int MaximumAccountElements = 17;

    private const int FixedBodySize = 9;

    public static OriginalSessionServerLoginParseResult DecodeLogin(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < sizeof(ushort) + FixedBodySize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != LoginRequestType)
        {
            return Invalid("original.session-server.login.length-or-type");
        }

        var body = payload[sizeof(ushort)..];
        var count = body[8];
        if (count > MaximumAccountElements)
        {
            return Invalid("original.session-server.login.account-count");
        }

        if (payload.Length != sizeof(ushort) + FixedBodySize + count * sizeof(ushort))
        {
            return Invalid("original.session-server.login.length");
        }

        var marker = BinaryPrimitives.ReadUInt16BigEndian(body[4..]);
        var reserved = BinaryPrimitives.ReadUInt16BigEndian(body[6..]);
        if (marker != LoginMarker || reserved != 0)
        {
            return Invalid("original.session-server.login.marker");
        }

        var account = new ushort[count];
        for (var index = 0; index < count; index++)
        {
            account[index] = BinaryPrimitives.ReadUInt16BigEndian(
                body[(FixedBodySize + index * sizeof(ushort))..]);
        }

        return new OriginalSessionServerLoginParseResult(
            true,
            new OriginalSessionServerLoginMessage(
                BinaryPrimitives.ReadUInt32BigEndian(body), marker, reserved, account),
            null);
    }

    public static byte[] EncodeLoginOk() => [0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x01];

    // Experiment (create-flow blocker, 2026-09-03): the lobby LoginOK carries a non-zero code in its leading
    // 4 bytes and the client proceeds; the session-server LoginOK leaves them zero and the client goes silent
    // after 0x201 (never sends 0x203). This overload lets a run set that leading field to test whether a
    // non-zero session code unblocks the create flow. Reversible; default path (no arg) is unchanged.
    public static byte[] EncodeLoginOk(uint leadingCode)
    {
        var payload = new byte[] { 0x00, 0x00, 0x00, 0x00, 0x02, 0x01, 0x01 };
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(0, 4), leadingCode);
        return payload;
    }

    public static byte[] EncodeCharacterContext(uint characterId)
    {
        var payload = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(payload.AsSpan(4), CharacterContextResponseType);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(6), characterId);
        return payload;
    }

    public static byte[] EncodeGameLoginOk() =>
        [0x00, 0x00, 0x00, 0x00, 0x02, 0x06, 0x01];

    private static OriginalSessionServerLoginParseResult Invalid(string code) =>
        new(false, null, code);
}
