using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public static class OriginalCharacterChargeCodec
{
    public const ushort CommandType = 0x1006;
    public const int MaximumCandidateCount = 5;

    public static bool TryDecode(
        ReadOnlySpan<byte> payload,
        out IReadOnlyList<uint> candidateCharacterIds)
    {
        candidateCharacterIds = Array.Empty<uint>();
        if (payload.Length < sizeof(ushort) + sizeof(byte) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
        {
            return false;
        }

        var count = payload[sizeof(ushort)];
        var expectedLength = sizeof(ushort) + sizeof(byte) + count * sizeof(uint);
        if (count is 0 or > MaximumCandidateCount || payload.Length != expectedLength)
        {
            return false;
        }

        var decoded = new uint[count];
        var unique = new HashSet<uint>();
        for (var index = 0; index < count; index++)
        {
            var characterId = BinaryPrimitives.ReadUInt32BigEndian(
                payload[(sizeof(ushort) + sizeof(byte) + index * sizeof(uint))..]);
            if (characterId == 0 || !unique.Add(characterId))
            {
                return false;
            }

            decoded[index] = characterId;
        }

        candidateCharacterIds = decoded;
        return true;
    }

    public static byte[] EncodeAccepted(IReadOnlyList<uint> candidateCharacterIds)
    {
        ArgumentNullException.ThrowIfNull(candidateCharacterIds);
        if (candidateCharacterIds.Count is 0 or > MaximumCandidateCount ||
            candidateCharacterIds.Any(candidateId => candidateId == 0) ||
            candidateCharacterIds.Distinct().Count() != candidateCharacterIds.Count)
        {
            throw new ArgumentOutOfRangeException(
                nameof(candidateCharacterIds),
                "ORIGINAL_CHARACTER_CHARGE_CANDIDATES");
        }

        // ORIGINAL_STATIC: the original client submits 0x1006 without a paired
        // response type. Receiving the accepted 0x1006 command posts UI event
        // 0x16 through FUN_004be760 -> FUN_00517cd0, which is the success path
        // consumed by FUN_00595d30.
        var response = new byte[
            OriginalLoginCodec.MessageCodeSize +
            sizeof(ushort) +
            sizeof(byte) +
            candidateCharacterIds.Count * sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), CommandType);
        response[6] = checked((byte)candidateCharacterIds.Count);
        for (var index = 0; index < candidateCharacterIds.Count; index++)
        {
            BinaryPrimitives.WriteUInt32BigEndian(
                response.AsSpan(7 + index * sizeof(uint)),
                candidateCharacterIds[index]);
        }

        return response;
    }
}
