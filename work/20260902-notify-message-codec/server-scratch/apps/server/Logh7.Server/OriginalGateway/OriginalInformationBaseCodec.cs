using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public sealed record OriginalInformationBaseRecord(uint Id, byte Power, byte Camp, uint Grid)
{
    public uint DefenseOutfit { get; init; }
    public float PriceIndex { get; init; }
    public uint Antiaircraft { get; init; }
    public uint Supplies { get; init; }
    public IReadOnlyList<uint> OutfitSupplies { get; init; } = [];
    public IReadOnlyList<uint> TransportSupplies { get; init; } = [];
    public uint PatrolSupplies { get; init; }
    public uint GroundSupplies { get; init; }
    public uint DefenceSupplies { get; init; }
    public uint Population { get; init; }
    public uint AdultPopulation { get; init; }
    public ushort Tax { get; init; }
    public IReadOnlyList<ushort> Budgeting { get; init; } = [];
    public IReadOnlyList<uint> Budget { get; init; } = [];
    public ushort Approval { get; init; }
    public ushort Peace { get; init; }
    public ushort Thought { get; init; }
    public ushort Religion { get; init; }
    public uint Food { get; init; }
    public uint Living { get; init; }
    public IReadOnlyList<uint> Commodity { get; init; } = [];
    public float AvailabilityRatio { get; init; }
    public byte Atmosphere { get; init; }
    public byte Habitability { get; init; }
    public ushort Armor { get; init; }
    public byte CannonAngle { get; init; }
    public uint CannonStart { get; init; }
}

public static class OriginalInformationBaseCodec
{
    public static byte[] EncodeResponse(IReadOnlyList<OriginalInformationBaseRecord> records)
    {
        ArgumentNullException.ThrowIfNull(records);
        CheckCount(records, 4);
        using var stream = new MemoryStream();
        void U8(byte value) => stream.WriteByte(value);
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
        void F32(float value) => U32(BitConverter.SingleToUInt32Bits(value));
        void Array32(IReadOnlyList<uint> values, int maximum)
        {
            CheckCount(values, maximum);
            U8(checked((byte)values.Count));
            foreach (var value in values) U32(value);
        }
        void Array16(IReadOnlyList<ushort> values, int maximum)
        {
            CheckCount(values, maximum);
            U8(checked((byte)values.Count));
            foreach (var value in values) U16(value);
        }

        U32(0); // original message-code prefix
        U16(OriginalSystemSceneCodec.BaseParametersResponseType);
        U8(checked((byte)records.Count));
        //00414C70 wire order; expanded record stride180 is not a wire size.
        foreach (var record in records)
        {
            U32(record.Id); U8(record.Power); U8(record.Camp); U32(record.Grid);
            U32(record.DefenseOutfit); F32(record.PriceIndex);
            U32(record.Antiaircraft); U32(record.Supplies);
            Array32(record.OutfitSupplies, 30); Array32(record.TransportSupplies, 30);
            U32(record.PatrolSupplies); U32(record.GroundSupplies); U32(record.DefenceSupplies);
            U32(record.Population); U32(record.AdultPopulation); U16(record.Tax);
            Array16(record.Budgeting, 6); Array32(record.Budget, 5);
            U16(record.Approval); U16(record.Peace); U16(record.Thought); U16(record.Religion);
            U32(record.Food); U32(record.Living); Array32(record.Commodity, 3);
            F32(record.AvailabilityRatio); U8(record.Atmosphere); U8(record.Habitability);
            U16(record.Armor); U8(record.CannonAngle); U32(record.CannonStart);
        }
        return stream.ToArray();
    }

    public static bool TryEncodeResponse(ReadOnlySpan<byte> request,
        IReadOnlyList<OriginalInformationBaseRecord> available, out byte[] response)
    {
        response = [];
        if (!OriginalSystemSceneCodec.TryDecodeIdRequest(request, out var decoded) ||
            decoded.Type != OriginalSystemSceneCodec.BaseParametersRequestType) return false;
        var requested = decoded.Ids.ToHashSet();
        response = EncodeResponse(available.Where(record => requested.Contains(record.Id)).ToArray());
        return true;
    }

    private static void CheckCount<T>(IReadOnlyList<T> values, int maximum)
    {
        ArgumentNullException.ThrowIfNull(values);
        if (values.Count > maximum) throw new ArgumentOutOfRangeException(nameof(values));
    }
}
