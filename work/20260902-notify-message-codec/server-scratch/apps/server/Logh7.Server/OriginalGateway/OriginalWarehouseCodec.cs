using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalWarehouseRequest(uint BaseId, uint OutfitId);
public readonly record struct OriginalWarehouseShip(ushort Kind, byte UnitNumber, ushort BoatNumber);
public readonly record struct OriginalWarehouseTroop(ushort Kind, byte TroopGrade, ushort UnitNumber);
public readonly record struct OriginalWarehouseResponse(uint BaseId, uint OutfitId, uint Index,
    IReadOnlyList<OriginalWarehouseShip> Ships, IReadOnlyList<OriginalWarehouseTroop> Troops,
    uint Supplies, uint Food, uint Mineral);

public static class OriginalWarehouseCodec
{
    public const ushort RequestType = 0x0326;
    public const ushort ResponseType = 0x0327;
    public const int MaximumShips = 99;
    public const int MaximumTroops = 24;

    // Request writer0040C2D0 and logger0040C320: base:u32, outfit:u32.
    // Identity is preserved here, not authorized. Outfit0 is used by the
    // original UI for base stock; it is not a wildcard for any outfit.
    public static bool TryDecodeRequest(ReadOnlySpan<byte> payload, out OriginalWarehouseRequest request)
    {
        request = default;
        if (payload.Length != 10 || BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
            return false;
        request = new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]));
        return true;
    }
    public static byte[] EncodeRequest(OriginalWarehouseRequest request)
    {
        var frame = new byte[14];
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(4), RequestType);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(6), request.BaseId);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(10), request.OutfitId);
        return frame;
    }

    // Reader0041A870/logger0041AFF0: expanded0x300 is NOT wire length.
    // Packed body is26+5*N+5*M. Stock quantities are unsigned, unlike
    // the signed transfers carried by CommandReorganization (0C02).
    public static bool TryDecodeResponse(ReadOnlySpan<byte> payload, out OriginalWarehouseResponse response)
    {
        response = default;
        if (payload.Length < 28 || BinaryPrimitives.ReadUInt16BigEndian(payload) != ResponseType)
            return false;
        var shipsCount = payload[14];
        if (shipsCount > MaximumShips) return false;
        var troopsOffset = 15 + 5 * shipsCount;
        if (payload.Length < troopsOffset + 13) return false;
        var troopsCount = payload[troopsOffset];
        if (troopsCount > MaximumTroops || payload.Length != 28 + 5 * (shipsCount + troopsCount))
            return false;

        var ships = new OriginalWarehouseShip[shipsCount];
        var cursor = 15;
        for (var i = 0; i < ships.Length; i++, cursor += 5)
            ships[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        cursor++;
        var troops = new OriginalWarehouseTroop[troopsCount];
        for (var i = 0; i < troops.Length; i++, cursor += 5)
            troops[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        response = new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]), ships, troops,
            BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(cursor + 4)..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(cursor + 8)..]));
        return true;
    }
    public static byte[] EncodeResponse(OriginalWarehouseResponse response)
    {
        if (response.Ships is null || response.Troops is null ||
            response.Ships.Count > MaximumShips || response.Troops.Count > MaximumTroops)
            throw new ArgumentException("Warehouse arrays absent or exceed original capacity.", nameof(response));
        var frame = new byte[32 + 5 * (response.Ships.Count + response.Troops.Count)];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, ResponseType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], response.BaseId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], response.OutfitId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], response.Index);
        payload[14] = checked((byte)response.Ships.Count);
        var cursor = 15;
        foreach (var row in response.Ships)
        {
            BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], row.Kind);
            payload[cursor + 2] = row.UnitNumber;
            BinaryPrimitives.WriteUInt16BigEndian(payload[(cursor + 3)..], row.BoatNumber);
            cursor += 5;
        }
        payload[cursor++] = checked((byte)response.Troops.Count);
        foreach (var row in response.Troops)
        {
            BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], row.Kind);
            payload[cursor + 2] = row.TroopGrade;
            BinaryPrimitives.WriteUInt16BigEndian(payload[(cursor + 3)..], row.UnitNumber);
            cursor += 5;
        }
        BinaryPrimitives.WriteUInt32BigEndian(payload[cursor..], response.Supplies);
        BinaryPrimitives.WriteUInt32BigEndian(payload[(cursor + 4)..], response.Food);
        BinaryPrimitives.WriteUInt32BigEndian(payload[(cursor + 8)..], response.Mineral);
        return frame;
    }
}
