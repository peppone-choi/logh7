using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

// Wire format recovered from 00412EB0 and hit[8] logger00412F90.
// Values must be supplied by a separately identified data source; this class
// does not choose combat balance or infer a percentage scale from the field name.
public sealed class OriginalStaticArmsTable
{
    private const int RowCount = 27;
    private const int DistanceBinCount = 8;
    private readonly byte[] _response;

    public OriginalStaticArmsTable(IReadOnlyList<short[]> rows)
    {
        ArgumentNullException.ThrowIfNull(rows);
        if (rows.Count != RowCount)
            throw new ArgumentException("The native arms reader requires exactly 27 rows.", nameof(rows));

        _response = new byte[6 + RowCount * DistanceBinCount * sizeof(short)];
        BinaryPrimitives.WriteUInt16BigEndian(_response.AsSpan(4), 0x0311);
        for (var rowIndex = 0; rowIndex < RowCount; rowIndex++)
        {
            var row = rows[rowIndex];
            if (row is null || row.Length != DistanceBinCount)
                throw new ArgumentException("Each arms row requires exactly eight signed 16-bit hit values.", nameof(rows));
            for (var bin = 0; bin < DistanceBinCount; bin++)
                BinaryPrimitives.WriteInt16BigEndian(
                    _response.AsSpan(6 + (rowIndex * DistanceBinCount + bin) * sizeof(short)), row[bin]);
        }
    }

    // Sessions and callers cannot mutate the shared configured table.
    public byte[] EncodeResponse() => (byte[])_response.Clone();
}
