using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalAssignmentStock(
    IReadOnlyList<OriginalWarehouseShip> Ships, IReadOnlyList<OriginalWarehouseTroop> Troops, uint Supplies);
public readonly record struct OriginalAssignmentCommand(
    uint Time, uint ActorId, uint Pcp, uint Mcp, uint BaseId, uint OutfitId, byte Kind,
    IReadOnlyList<OriginalReorganizationShip> Ships, IReadOnlyList<OriginalReorganizationTroop> Troops,
    int Supplies, OriginalAssignmentStock BaseResult, OriginalAssignmentStock OutfitResult);

public static class OriginalAssignmentCodec
{
    public const ushort CommandType = 0x0c0b;

    public static byte[] Encode(OriginalAssignmentCommand command)
    {
        if (command.Ships is null || command.Troops is null ||
            command.BaseResult.Ships is null || command.BaseResult.Troops is null ||
            command.OutfitResult.Ships is null || command.OutfitResult.Troops is null ||
            command.Ships.Count > 99 || command.Troops.Count > 24 ||
            command.BaseResult.Ships.Count > 99 || command.BaseResult.Troops.Count > 24 ||
            command.OutfitResult.Ships.Count > 99 || command.OutfitResult.Troops.Count > 24)
            throw new ArgumentException("Original assignment array capacity exceeded or absent.", nameof(command));
        var rows = command.Ships.Count + command.Troops.Count + command.BaseResult.Ships.Count +
            command.BaseResult.Troops.Count + command.OutfitResult.Ships.Count + command.OutfitResult.Troops.Count;
        var frame = new byte[49 + rows * 5];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], command.ActorId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..], command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[14..], command.Mcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[18..], command.BaseId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[22..], command.OutfitId);
        payload[26] = command.Kind;
        var cursor = 27;
        payload[cursor++] = checked((byte)command.Ships.Count);
        foreach (var ship in command.Ships)
        {
            WriteRow(payload, ref cursor, ship.Kind, unchecked((byte)ship.UnitNumber), ship.BoatNumber);
        }
        payload[cursor++] = checked((byte)command.Troops.Count);
        foreach (var troop in command.Troops)
        {
            WriteRow(payload, ref cursor, troop.Kind, troop.TroopGrade, unchecked((ushort)troop.UnitNumber));
        }
        BinaryPrimitives.WriteInt32BigEndian(payload[cursor..], command.Supplies);
        cursor += 4;
        WriteStock(payload, ref cursor, command.BaseResult);
        WriteStock(payload, ref cursor, command.OutfitResult);
        return frame;
    }

    private static void WriteRow(Span<byte> payload, ref int cursor, ushort kind, byte middle, ushort quantity)
    {
        BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], kind);
        payload[cursor + 2] = middle;
        BinaryPrimitives.WriteUInt16BigEndian(payload[(cursor + 3)..], quantity);
        cursor += 5;
    }

    private static void WriteStock(Span<byte> payload, ref int cursor, OriginalAssignmentStock stock)
    {
        payload[cursor++] = checked((byte)stock.Ships.Count);
        foreach (var ship in stock.Ships)
            WriteRow(payload, ref cursor, ship.Kind, ship.UnitNumber, ship.BoatNumber);
        payload[cursor++] = checked((byte)stock.Troops.Count);
        foreach (var troop in stock.Troops)
            WriteRow(payload, ref cursor, troop.Kind, troop.TroopGrade, troop.UnitNumber);
        BinaryPrimitives.WriteUInt32BigEndian(payload[cursor..], stock.Supplies);
        cursor += 4;
    }

    // Original writer 00553FC0 / reader 00558B70: 43-byte body plus six
    // variable arrays. Movement quantities are signed; result stocks unsigned.
    // Decoding is not authorization: incoming balances and result snapshots
    // must never be used as authority for a warehouse transfer.
    public static bool TryDecode(ReadOnlySpan<byte> payload, out OriginalAssignmentCommand command)
    {
        command = default;
        if (payload.Length < 45 || BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
            return false;
        var cursor = 27;
        for (var array = 0; array < 6; array++)
        {
            if (cursor >= payload.Length) return false;
            var count = payload[cursor++];
            if (count > (array % 2 == 0 ? 99 : 24)) return false;
            cursor += count * 5;
            if (array % 2 == 1) cursor += 4;
            if (cursor > payload.Length) return false;
        }
        if (cursor != payload.Length) return false;

        cursor = 27;
        var ships = new OriginalReorganizationShip[payload[cursor++]];
        for (var i = 0; i < ships.Length; i++, cursor += 5)
            ships[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                unchecked((sbyte)payload[cursor + 2]), BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        var troops = new OriginalReorganizationTroop[payload[cursor++]];
        for (var i = 0; i < troops.Length; i++, cursor += 5)
            troops[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadInt16BigEndian(payload[(cursor + 3)..]));
        var supplies = BinaryPrimitives.ReadInt32BigEndian(payload[cursor..]);
        cursor += 4;
        var baseResult = ReadStock(payload, ref cursor);
        var outfitResult = ReadStock(payload, ref cursor);
        command = new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]), BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]), BinaryPrimitives.ReadUInt32BigEndian(payload[18..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[22..]), payload[26], ships, troops, supplies, baseResult, outfitResult);
        return true;
    }

    private static OriginalAssignmentStock ReadStock(ReadOnlySpan<byte> payload, ref int cursor)
    {
        var ships = new OriginalWarehouseShip[payload[cursor++]];
        for (var i = 0; i < ships.Length; i++, cursor += 5)
            ships[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        var troops = new OriginalWarehouseTroop[payload[cursor++]];
        for (var i = 0; i < troops.Length; i++, cursor += 5)
            troops[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        var supplies = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += 4;
        return new(ships, troops, supplies);
    }
}
