using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public static class OriginalChangedAuthorityCodec
{
    public const ushort Type = 0x0439;
    public const int MaximumUnitCount = 32;

    // Original reader 004A94D0: u32 reference UNIT ID, u8 count, u32 IDs.
    // The client copies the reference entity's controlling CHARACTER to targets.
    // Native +5..+7 alignment bytes are not transmitted. This codec grants no authority.
    public static byte[] Encode(uint referenceUnitId, IReadOnlyList<uint> unitIds)
    {
        ArgumentNullException.ThrowIfNull(unitIds);
        if (unitIds.Count > MaximumUnitCount)
            throw new ArgumentOutOfRangeException(nameof(unitIds));

        var frame = new byte[OriginalLoginCodec.MessageCodeSize + 7 + 4 * unitIds.Count];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, Type);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], referenceUnitId);
        payload[6] = checked((byte)unitIds.Count);
        for (var index = 0; index < unitIds.Count; index++)
            BinaryPrimitives.WriteUInt32BigEndian(payload[(7 + 4 * index)..], unitIds[index]);
        return frame;
    }
}
