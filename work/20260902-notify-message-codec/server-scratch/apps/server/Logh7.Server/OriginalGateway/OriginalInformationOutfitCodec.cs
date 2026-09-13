using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalInformationOutfit(
    uint Id, byte Kind, byte Power, byte Camp, byte Index, ushort Achievement,
    uint StrategyId, byte PracticeWarp, byte PracticeSpeed, byte PracticeCommand,
    byte PracticeOffence, byte PracticeDefence, byte PracticeAntiaircraft,
    byte PracticeSearch, byte PracticeDeception, byte PracticeLandbattle, byte PracticeAirbattle);

public static class OriginalInformationOutfitCodec
{
    // ORIGINAL_STATIC: reader0041BBD0, logger0041C330.
    // Native records are 0x1C bytes; the packed stream has 24 bytes per record.
    // Cache rebuild004C2A80 consumes this response before tactical unit import.
    public static byte[] Encode(IReadOnlyList<OriginalInformationOutfit> records)
    {
        ArgumentNullException.ThrowIfNull(records);
        if (records.Count > 100) throw new ArgumentOutOfRangeException(nameof(records));
        var frame = new byte[7 + records.Count * 24];
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(4), 0x032b);
        frame[6] = (byte)records.Count;
        var offset = 7;
        foreach (var r in records)
        {
            var row = frame.AsSpan(offset, 24);
            BinaryPrimitives.WriteUInt32BigEndian(row, r.Id);
            row[4] = r.Kind;
            row[5] = r.Power;
            row[6] = r.Camp;
            row[7] = r.Index;
            BinaryPrimitives.WriteUInt16BigEndian(row[8..], r.Achievement);
            BinaryPrimitives.WriteUInt32BigEndian(row[10..], r.StrategyId);
            row[14] = r.PracticeWarp;
            row[15] = r.PracticeSpeed;
            row[16] = r.PracticeCommand;
            row[17] = r.PracticeOffence;
            row[18] = r.PracticeDefence;
            row[19] = r.PracticeAntiaircraft;
            row[20] = r.PracticeSearch;
            row[21] = r.PracticeDeception;
            row[22] = r.PracticeLandbattle;
            row[23] = r.PracticeAirbattle;
            offset += 24;
        }
        return frame;
    }
}
