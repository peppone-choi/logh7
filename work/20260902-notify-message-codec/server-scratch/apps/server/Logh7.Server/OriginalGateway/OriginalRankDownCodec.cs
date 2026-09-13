using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

// 降等 (CommandRankDown, 0x0706). Captured live 2026-09-03 (run 20260903T063644Z, LOGH7_COMMAND_ECHO):
//   0706 00000000 00000002 00000000 00000000 14 00000002 00000000 00000000 00 00000000   (36 bytes)
// = [u16 type][u32 time][u32 actorId][u32 pcp][u32 mcp][u8 targetRank (the target's current rank chosen on the ladder)]
//   [u32 targetCharacterId][u32 achievement][u32 moveSpot][u8 moveCount + u32[moveCount]][u32 tail]
// (note: unlike 0x0705 the rank byte precedes the target id). Client flow: card 降等 -> ladder (0x1200 selector 0x0011)
// -> person picker (state 3, 0x1200 selector 0x0015, 0x1202 list, 「左欄より降等させたい人物を選択してください」)
// -> confirm -> 0x0706. The client ignores the response body beyond the type (0x0704 family).
public readonly record struct OriginalRankDownCommand(
    uint Time,
    uint ActorId,
    uint Pcp,
    uint Mcp,
    byte TargetRank,
    uint TargetCharacterId,
    uint Achievement,
    uint MoveSpot,
    uint[] MoveCharacterIds,
    uint Tail);

public readonly record struct OriginalRankDownDecodeResult(
    bool Success,
    OriginalRankDownCommand? Command,
    string? ErrorCode);

public static class OriginalRankDownCodec
{
    public const ushort CommandType = 0x0706;
    public const int ResponseBodySize = 0xa0;
    public const int MaximumMoveCharacterCount = 32;
    private const int RequestFixedSize = sizeof(ushort) + 30 + sizeof(uint);

    public static OriginalRankDownDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < RequestFixedSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return Invalid("original.rank-down.request-shape");
        }

        var moveCharacterCount = payload[31];
        if (moveCharacterCount > MaximumMoveCharacterCount ||
            payload.Length != RequestFixedSize + moveCharacterCount * sizeof(uint))
        {
            return Invalid("original.rank-down.move-character-count");
        }

        var moveCharacterIds = new uint[moveCharacterCount];
        for (var index = 0; index < moveCharacterIds.Length; index++)
        {
            moveCharacterIds[index] = BinaryPrimitives.ReadUInt32BigEndian(
                payload.Slice(32 + index * sizeof(uint), sizeof(uint)));
        }

        var tailOffset = 32 + moveCharacterCount * sizeof(uint);
        return new OriginalRankDownDecodeResult(
            true,
            new OriginalRankDownCommand(
                BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
                payload[18],
                BinaryPrimitives.ReadUInt32BigEndian(payload[19..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[23..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[27..]),
                moveCharacterIds,
                BinaryPrimitives.ReadUInt32BigEndian(payload[tailOffset..])),
            null);
    }

    public static byte[] EncodeAccepted(OriginalRankDownCommand command)
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
        BinaryPrimitives.WriteUInt32BigEndian(body[4..], command.ActorId);
        BinaryPrimitives.WriteUInt32BigEndian(body[8..], command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(body[12..], command.Mcp);
        body[16] = command.TargetRank;
        BinaryPrimitives.WriteUInt32BigEndian(body[20..], command.TargetCharacterId);
        BinaryPrimitives.WriteUInt32BigEndian(body[24..], command.Achievement);
        BinaryPrimitives.WriteUInt32BigEndian(body[28..], command.MoveSpot);
        body[32] = checked((byte)command.MoveCharacterIds.Length);
        for (var index = 0; index < command.MoveCharacterIds.Length; index++)
        {
            BinaryPrimitives.WriteUInt32BigEndian(
                body.Slice(36 + index * sizeof(uint), sizeof(uint)),
                command.MoveCharacterIds[index]);
        }

        return response;
    }

    private static OriginalRankDownDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
