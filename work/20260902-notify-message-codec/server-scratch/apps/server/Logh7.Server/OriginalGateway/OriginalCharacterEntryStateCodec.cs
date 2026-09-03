using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public static class OriginalCharacterEntryStateCodec
{
    public const ushort SelectionRequestType = 0x1004;
    public const ushort StateResponseType = 0x1005;
    public const int SelectionRequestSize = sizeof(ushort) + sizeof(uint);
    public const int MaximumCandidateCount = 5;

    public static bool TryDecodeSelection(ReadOnlySpan<byte> payload, out uint characterId)
    {
        characterId = 0;
        if (payload.Length != SelectionRequestSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != SelectionRequestType)
        {
            return false;
        }

        characterId = BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]);
        return characterId != 0;
    }

    public static byte[] EncodeState(
        uint characterId,
        IReadOnlyList<uint> candidateCharacterIds)
    {
        if (characterId == 0)
        {
            throw new ArgumentOutOfRangeException(nameof(characterId));
        }

        ArgumentNullException.ThrowIfNull(candidateCharacterIds);
        if (candidateCharacterIds.Count > MaximumCandidateCount ||
            candidateCharacterIds.Any(candidateId => candidateId == 0))
        {
            throw new ArgumentOutOfRangeException(
                nameof(candidateCharacterIds),
                "ORIGINAL_CHARACTER_ENTRY_CANDIDATES");
        }

        // ORIGINAL_STATIC: 0x00409B60 reads the 0x1005 wire stream as
        // u8 state, u32 characterId, u8 candidateCount, then candidateCount
        // u32 IDs. The leading stream read is FUN_00610420 at 0x00409B97.
        // The parser's 0x20 allocation is the decoded object size, not a
        // fixed wire-body size.
        var response = new byte[
            OriginalLoginCodec.MessageCodeSize +
            sizeof(ushort) +
            sizeof(byte) +
            sizeof(uint) +
            sizeof(byte) +
            candidateCharacterIds.Count * sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), StateResponseType);
        var body = response.AsSpan(6);
        body[0] = 0;
        BinaryPrimitives.WriteUInt32BigEndian(body[sizeof(byte)..], characterId);
        body[sizeof(byte) + sizeof(uint)] = checked((byte)candidateCharacterIds.Count);
        for (var index = 0; index < candidateCharacterIds.Count; index++)
        {
            BinaryPrimitives.WriteUInt32BigEndian(
                body[(sizeof(byte) + sizeof(uint) + sizeof(byte) + index * sizeof(uint))..],
                candidateCharacterIds[index]);
        }

        return response;
    }
}
