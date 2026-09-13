using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalReorganizationShip(ushort Kind, sbyte UnitNumber, ushort BoatNumber);
public readonly record struct OriginalReorganizationTroop(ushort Kind, byte TroopGrade, short UnitNumber);
public readonly record struct OriginalReorganizationCommand(
    uint Time, uint ActorId, byte Mode, uint Pcp, uint Mcp, uint BaseId, uint OutfitId, byte Kind,
    IReadOnlyList<OriginalReorganizationShip> Ships, IReadOnlyList<OriginalReorganizationTroop> Troops,
    int MaxTroop, int MaxCrew, uint Supplies);

public static class OriginalReorganizationCodec
{
    public const ushort CommandType = 0x0c02;
    public const int MaximumShips = 99;
    public const int MaximumTroops = 24;

    // Original: output length 00551860, writer 00551A80, reader 00555EB0,
    // field logger 00551DD0. The expanded record is 0x310 bytes, but the wire
    // body is 40 + 5*ships + 5*troops. Transfer quantities are signed, not u8/u16.
    // This codec does not authorize mode/kind, client-provided PCP/MCP, capacity,
    // supplies or inventory changes. The authority must calculate those effects.
    public static bool TryDecode(ReadOnlySpan<byte> payload, out OriginalReorganizationCommand command)
    {
        command = default;
        if (payload.Length < 42 || BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
            return false;
        var shipCount = payload[28];
        if (shipCount > MaximumShips) return false;
        var troopCountOffset = 29 + shipCount * 5;
        if (payload.Length < troopCountOffset + 13) return false;
        var troopCount = payload[troopCountOffset];
        if (troopCount > MaximumTroops || payload.Length != 42 + (shipCount + troopCount) * 5)
            return false;

        var ships = new OriginalReorganizationShip[shipCount];
        var cursor = 29;
        for (var i = 0; i < shipCount; i++, cursor += 5)
            ships[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                unchecked((sbyte)payload[cursor + 2]),
                BinaryPrimitives.ReadUInt16BigEndian(payload[(cursor + 3)..]));
        cursor++; // troop count
        var troops = new OriginalReorganizationTroop[troopCount];
        for (var i = 0; i < troopCount; i++, cursor += 5)
            troops[i] = new(BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]),
                payload[cursor + 2], BinaryPrimitives.ReadInt16BigEndian(payload[(cursor + 3)..]));
        command = new(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]), payload[10],
            BinaryPrimitives.ReadUInt32BigEndian(payload[11..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[15..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[19..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[23..]), payload[27], ships, troops,
            BinaryPrimitives.ReadInt32BigEndian(payload[cursor..]),
            BinaryPrimitives.ReadInt32BigEndian(payload[(cursor + 4)..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(cursor + 8)..]));
        return true;
    }

    // Standard application message-code prefix (4 bytes), type (2), packed body.
    public static byte[] Encode(OriginalReorganizationCommand command)
    {
        if (command.Ships is null || command.Troops is null ||
            command.Ships.Count > MaximumShips || command.Troops.Count > MaximumTroops)
            throw new ArgumentException("Original reorganization array capacity exceeded or absent.", nameof(command));
        var frame = new byte[46 + (command.Ships.Count + command.Troops.Count) * 5];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..], command.ActorId);
        payload[10] = command.Mode;
        BinaryPrimitives.WriteUInt32BigEndian(payload[11..], command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[15..], command.Mcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[19..], command.BaseId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[23..], command.OutfitId);
        payload[27] = command.Kind;
        payload[28] = checked((byte)command.Ships.Count);
        var cursor = 29;
        foreach (var ship in command.Ships)
        {
            BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], ship.Kind);
            payload[cursor + 2] = unchecked((byte)ship.UnitNumber);
            BinaryPrimitives.WriteUInt16BigEndian(payload[(cursor + 3)..], ship.BoatNumber);
            cursor += 5;
        }
        payload[cursor++] = checked((byte)command.Troops.Count);
        foreach (var troop in command.Troops)
        {
            BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], troop.Kind);
            payload[cursor + 2] = troop.TroopGrade;
            BinaryPrimitives.WriteInt16BigEndian(payload[(cursor + 3)..], troop.UnitNumber);
            cursor += 5;
        }
        BinaryPrimitives.WriteInt32BigEndian(payload[cursor..], command.MaxTroop);
        BinaryPrimitives.WriteInt32BigEndian(payload[(cursor + 4)..], command.MaxCrew);
        BinaryPrimitives.WriteUInt32BigEndian(payload[(cursor + 8)..], command.Supplies);
        return frame;
    }
}
