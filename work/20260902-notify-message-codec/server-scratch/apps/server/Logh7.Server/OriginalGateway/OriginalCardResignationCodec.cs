using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

// 辞任 (CommandCardResignation, 0x0709). Captured live 2026-09-03 (run 20260903T063644Z, LOGH7_COMMAND_ECHO):
//   0709 00000000 00000002 00000000 00000000 00000027 00000000 00   (27 bytes)
// = [u16 type][u32 time][u32 actorId][u32 pcp][u32 mcp][u32 cardId (the card being resigned, 0x27 = 39)][u32 zero][u8 zero]
// Client flow: card 辞任 -> confirm 「を辞任します。コマンドポイント80MCP消費…」 決定 -> 0x0709 (no list request).
// Decode only for now: accepting it requires the character's current card to be authority state (world entry still
// serves the constant AuthorityCardId), so the handler is deferred until that model exists.
public readonly record struct OriginalCardResignationCommand(
    uint Time,
    uint ActorId,
    uint Pcp,
    uint Mcp,
    uint CardId,
    uint Reserved,
    byte Flag);

public readonly record struct OriginalCardResignationDecodeResult(
    bool Success,
    OriginalCardResignationCommand? Command,
    string? ErrorCode);

public static class OriginalCardResignationCodec
{
    public const ushort CommandType = 0x0709;
    public const int RequestSize = sizeof(ushort) + 25;

    public static OriginalCardResignationDecodeResult Decode(ReadOnlySpan<byte> payload)
    {
        if (payload.Length != RequestSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return new OriginalCardResignationDecodeResult(false, null, "original.card-resignation.request-shape");
        }

        return new OriginalCardResignationDecodeResult(
            true,
            new OriginalCardResignationCommand(
                BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[18..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[22..]),
                payload[26]),
            null);
    }
}
