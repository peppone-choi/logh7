namespace Logh7.Server.OriginalGateway;

// ORIGINAL_STATIC: 031D reader004142E0/logger00414A30; E013.
// Names contain at most13 UTF-16 code units; that bound does not constrain Class.
public sealed record OriginalStaticBaseRecord(
    uint Id, ushort Grid, ushort ModelFile, ushort Kind, string Name, byte Class,
    float RevolutionRadius, uint RevolutionCycle, byte RevolutionDirection,
    float RevolutionInitialAngle, float Diameter)
{
    public string EvidenceStatus { get; init; } = "CANDIDATE";

    internal static void ValidateAll(IReadOnlyList<OriginalStaticBaseRecord> records)
    {
        if (records.Count > 350)
            throw new InvalidDataException("031D exceeds350 Base records");
        var ids = new HashSet<uint>();
        foreach (var record in records)
        {
            if (record is null || record.Id == 0 || !ids.Add(record.Id))
                throw new InvalidDataException("031D has a null, zero-ID or duplicate Base");
            // E013: 130 extracted model slots; client lookup has no full bounds check.
            // Null slots within the table use the original fallback, not an invented class cap.
            if (record.ModelFile >= 130)
                throw new InvalidDataException("Base model_file exceeds the recovered client table");
            if (record.Name is null || record.Name.Length > 13)
                throw new InvalidDataException("Base name exceeds13 UTF-16 units");
            if (!float.IsFinite(record.Diameter) || record.Diameter < 0 ||
                !float.IsFinite(record.RevolutionRadius) || record.RevolutionRadius < 0 ||
                !float.IsFinite(record.RevolutionInitialAngle))
                throw new InvalidDataException("Base dimensions/orbit must be finite and nonnegative");
            //004C32A0 enables orbit for kind!=1;004B2740 uses clock%cycle.
            if (record.Kind != 1 && record.RevolutionCycle == 0)
                throw new InvalidDataException("Orbiting Base requires a nonzero cycle");
            if (record.EvidenceStatus is not ("ORIGINAL_OBSERVED" or "ORIGINAL_STATIC" or "CANDIDATE" or "NEW_DESIGN"))
                throw new InvalidDataException("Base evidenceStatus is invalid");
        }
    }
}
