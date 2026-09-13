using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalRankUpCommand(
    uint Time,
    uint Id,
    uint Pcp,
    uint Mcp,
    byte TargetRank,
    uint RankChangedCharacterAchievement,
    uint MoveSpot,
    uint[] MoveCharacterIds);

public readonly record struct OriginalRankUpDecodeResult(
    bool Success,
    OriginalRankUpCommand? Command,
    string? ErrorCode);

public static class OriginalRankUpCodec
{
    public const ushort CommandType = 0x0704;
    public const int ResponseBodySize = 0xa0;
    public const int MaximumMoveCharacterCount = 32;
    private const int RequestFixedSize = sizeof(ushort) + 26;

    public static OriginalRankUpDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < RequestFixedSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return Invalid("original.rank-up.request-shape");
        }

        var moveCharacterCount = payload[27];
        if (moveCharacterCount > MaximumMoveCharacterCount ||
            payload.Length != RequestFixedSize + moveCharacterCount * sizeof(uint))
        {
            return Invalid("original.rank-up.move-character-count");
        }

        var moveCharacterIds = new uint[moveCharacterCount];
        for (var index = 0; index < moveCharacterIds.Length; index++)
        {
            moveCharacterIds[index] = BinaryPrimitives.ReadUInt32BigEndian(
                payload.Slice(RequestFixedSize + index * sizeof(uint), sizeof(uint)));
        }

        return new OriginalRankUpDecodeResult(
            true,
            new OriginalRankUpCommand(
                BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
                payload[18],
                BinaryPrimitives.ReadUInt32BigEndian(payload[19..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[23..]),
                moveCharacterIds),
            null);
    }

    public static byte[] EncodeAccepted(OriginalRankUpCommand command)
    {
        if (command.MoveCharacterIds.Length > MaximumMoveCharacterCount)
        {
            throw new ArgumentOutOfRangeException(nameof(command));
        }

        var response = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + ResponseBodySize];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), CommandType);
        var body = response.AsSpan(6);
        BinaryPrimitives.WriteUInt32BigEndian(body, command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(body[4..], command.Id);
        BinaryPrimitives.WriteUInt32BigEndian(body[8..], command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(body[12..], command.Mcp);
        body[16] = command.TargetRank;
        BinaryPrimitives.WriteUInt32BigEndian(
            body[20..], command.RankChangedCharacterAchievement);
        BinaryPrimitives.WriteUInt32BigEndian(body[24..], command.MoveSpot);
        body[28] = checked((byte)command.MoveCharacterIds.Length);
        for (var index = 0; index < command.MoveCharacterIds.Length; index++)
        {
            BinaryPrimitives.WriteUInt32BigEndian(
                body.Slice(32 + index * sizeof(uint), sizeof(uint)),
                command.MoveCharacterIds[index]);
        }

        return response;
    }

    private static OriginalRankUpDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
