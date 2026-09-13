using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public sealed record OriginalInstitutionSpot(ushort Kind, uint Id, ushort File);
public sealed record OriginalInstitution(ushort Kind, uint Id, IReadOnlyList<OriginalInstitutionSpot> Spots);
public sealed record OriginalBaseInstitutions(uint Id, IReadOnlyList<OriginalInstitution> Institutions);

public static class OriginalInstitutionCodec
{
    public static byte[] EncodeResponse(IReadOnlyList<OriginalBaseInstitutions> records)
    {
        CheckCount(records, 4);
        using var stream = new MemoryStream();
        void U16(ushort value)
        {
            Span<byte> bytes = stackalloc byte[2];
            BinaryPrimitives.WriteUInt16BigEndian(bytes, value);
            stream.Write(bytes);
        }
        void U32(uint value)
        {
            Span<byte> bytes = stackalloc byte[4];
            BinaryPrimitives.WriteUInt32BigEndian(bytes, value);
            stream.Write(bytes);
        }

        U32(0);
        U16(0x0321);
        stream.WriteByte((byte)records.Count);
        // ORIGINAL_STATIC reader004167F0: packed fields, not expanded stride0x2378.
        // The receiver replaces its cache; multiple batches cannot be concatenated.
        foreach (var record in records)
        {
            ArgumentNullException.ThrowIfNull(record);
            CheckCount(record.Institutions, 36);
            U32(record.Id);
            stream.WriteByte((byte)record.Institutions.Count);
            foreach (var institution in record.Institutions)
            {
                ArgumentNullException.ThrowIfNull(institution);
                CheckCount(institution.Spots, 20);
                U16(institution.Kind);
                U32(institution.Id);
                stream.WriteByte((byte)institution.Spots.Count);
                foreach (var spot in institution.Spots)
                {
                    ArgumentNullException.ThrowIfNull(spot);
                    U16(spot.Kind);
                    U32(spot.Id);
                    U16(spot.File);
                }
            }
        }
        return stream.ToArray();
    }

    private static void CheckCount<T>(IReadOnlyList<T> records, int maximum)
    {
        ArgumentNullException.ThrowIfNull(records);
        if (records.Count > maximum) throw new ArgumentOutOfRangeException(nameof(records));
    }
}
