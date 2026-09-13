using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalTacticalShieldFillRecord(
    uint UnitId, IReadOnlyList<uint> NextTime, IReadOnlyList<ushort> ShieldStep);

public static class OriginalTacticalShieldCodec
{
    // ORIGINAL_STATIC 00423890 / logger 00423CC0: count:u16, then
    // unit:u32 + next_time[6]:u32 + shield_step[6]:u16. No packed padding.
    public static byte[] Encode(IReadOnlyList<OriginalTacticalShieldFillRecord> records)
    {
        ArgumentNullException.ThrowIfNull(records);
        if (records.Count > OriginalSystemSceneCodec.MaximumTacticalUnitShipCount)
            throw new ArgumentOutOfRangeException(nameof(records));
        var frame = new byte[8 + records.Count * 40];
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(4), OriginalSystemSceneCodec.TacticalFillShieldResponseType);
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(6), checked((ushort)records.Count));
        var cursor = 8;
        foreach (var record in records)
        {
            ArgumentNullException.ThrowIfNull(record.NextTime);
            ArgumentNullException.ThrowIfNull(record.ShieldStep);
            if (record.NextTime.Count != 6 || record.ShieldStep.Count != 6)
                throw new ArgumentException("Shield timing requires six directions", nameof(records));
            BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(cursor), record.UnitId);
            cursor += 4;
            foreach (var time in record.NextTime)
            {
                BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(cursor), time);
                cursor += 4;
            }
            foreach (var step in record.ShieldStep)
            {
                BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(cursor), step);
                cursor += 2;
            }
        }
        return frame;
    }

    public static IReadOnlyList<OriginalTacticalShieldFillRecord> Project(
        IReadOnlyList<uint> requestedIds, IReadOnlyList<OriginalTacticalShieldFillRecord> available)
    {
        ArgumentNullException.ThrowIfNull(requestedIds);
        ArgumentNullException.ThrowIfNull(available);
        var requested = requestedIds.ToHashSet();
        return available.Where(record => requested.Contains(record.UnitId)).ToArray();
    }

    public static OriginalTacticalShieldFillRecord CreateAuthoredInitialState(uint unitId) =>
        // E064: native import initializes elapsed = period - next_time;
        // these are remaining intervals, NOT absolute game-clock timestamps.
        // Preserve the existing authored static table's period and start at
        // zero elapsed. This is not server-side recharge simulation/persistence.
        new(unitId, Enumerable.Repeat(OriginalAuthoredPlayableCatalog.TacticalShieldRecoveryPeriod, 6).ToArray(),
            new ushort[6]);
}
