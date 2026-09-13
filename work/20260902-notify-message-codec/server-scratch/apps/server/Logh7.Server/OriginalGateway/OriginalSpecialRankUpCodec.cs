using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

// 抜擢 (CommandSpeciallyRankUp, 0x0705). Captured live 2026-09-03 (run 20260903T061617Z, LOGH7_COMMAND_ECHO):
//   0705 00000000 00000002 00000000 00000000 00000002 14 00000000 00000000 00 0000   (34 bytes)
// = 0x0704 CommandRankUp with a u32 target character inserted after mcp and a u16 tail:
//   [u16 type][u32 time][u32 actorId][u32 pcp][u32 mcp][u32 targetCharacterId][u8 targetRank (the target's current
//   rank chosen on the ladder, e.g. 0x14 = 二等兵)][u32 achievement][u32 moveSpot][u8 moveCount + u32[moveCount]][u16 tail]
// Client flow: card 抜擢 -> ladder (0x1200 selector 0x0011) -> person picker (state 3, 0x1200 selector 0x0015, 0x1202
// list) -> confirm 「…を…に抜擢します。コマンドポイント320MCP消費」 -> 0x0705. The client handler ignores the response
// body beyond the type (same family as 0x0704/0x0707); the accepted response mirrors the 0x0704 shape (0xA0 body).
public readonly record struct OriginalSpecialRankUpCommand(
    uint Time,
    uint ActorId,
    uint Pcp,
    uint Mcp,
    uint TargetCharacterId,
    byte TargetRank,
    uint Achievement,
    uint MoveSpot,
    uint[] MoveCharacterIds,
    ushort Tail);

public readonly record struct OriginalSpecialRankUpDecodeResult(
    bool Success,
    OriginalSpecialRankUpCommand? Command,
    string? ErrorCode);

public static class OriginalSpecialRankUpCodec
{
    public const ushort CommandType = 0x0705;
    public const int ResponseBodySize = 0xa0;
    public const int MaximumMoveCharacterCount = 32;
    private const int RequestFixedSize = sizeof(ushort) + 30 + sizeof(ushort);

    public static OriginalSpecialRankUpDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < RequestFixedSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return Invalid("original.special-rank-up.request-shape");
        }

        var moveCharacterCount = payload[31];
        if (moveCharacterCount > MaximumMoveCharacterCount ||
            payload.Length != RequestFixedSize + moveCharacterCount * sizeof(uint))
        {
            return Invalid("original.special-rank-up.move-character-count");
        }

        var moveCharacterIds = new uint[moveCharacterCount];
        for (var index = 0; index < moveCharacterIds.Length; index++)
        {
            moveCharacterIds[index] = BinaryPrimitives.ReadUInt32BigEndian(
                payload.Slice(32 + index * sizeof(uint), sizeof(uint)));
        }

        var tailOffset = 32 + moveCharacterCount * sizeof(uint);
        return new OriginalSpecialRankUpDecodeResult(
            true,
            new OriginalSpecialRankUpCommand(
                BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[18..]),
                payload[22],
                BinaryPrimitives.ReadUInt32BigEndian(payload[23..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[27..]),
                moveCharacterIds,
                BinaryPrimitives.ReadUInt16BigEndian(payload[tailOffset..])),
            null);
    }

    public static byte[] EncodeAccepted(OriginalSpecialRankUpCommand command)
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
        BinaryPrimitives.WriteUInt32BigEndian(body[16..], command.TargetCharacterId);
        body[20] = command.TargetRank;
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

    private static OriginalSpecialRankUpDecodeResult Invalid(string errorCode) =>
        new(false, null, errorCode);
}
