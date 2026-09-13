using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalTacticalControlCommand(
    uint Time, uint Wait, uint ActorId, uint UnitId, ushort Condenser,
    byte Beam, byte Gun, IReadOnlyList<byte> Shields, byte Engine, byte Warp, byte Sensor);

public readonly record struct OriginalTacticalControlDecision(
    bool Accepted, OriginalTacticalCorpsRecord? State, string? ErrorCode);

public static class OriginalTacticalControlCodec
{
    public const ushort CommandType = 0x040c;

    public static byte[] EncodeImmediateResponse(ReadOnlySpan<byte> payload)
    {
        if (payload.Length != 31 || BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
            throw new ArgumentException("A complete CommandControl payload is required.", nameof(payload));
        var frame = OriginalTacticalCommandCodec.EncodeCommandEcho(payload);
        // ORIGINAL_STATIC: 004B8850 queues Time + Wait; 004B8950 dispatches
        // only zero/due entries. 004B4370 leaves both outgoing stack fields
        // uninitialized. The v12 live echo stayed at deadline 222547400 while
        // the client's clock was 3019. Never adopt that client scheduling.
        // NEW DESIGN: this authority applies control immediately, so both
        // scheduling fields are zero; the owned actor and eleven channels stay.
        frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort), sizeof(uint) * 2).Clear();
        return frame;
    }

    public static bool TryDecode(ReadOnlySpan<byte> payload, out OriginalTacticalControlCommand command)
    {
        command = default;
        // Original logger 00495B70, sender 004B4370, plus the v11 live packet.
        if (payload.Length != 31 || BinaryPrimitives.ReadUInt16BigEndian(payload) != CommandType)
            return false;
        command = new(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[18..]),
            payload[20], payload[21], payload.Slice(22, 6).ToArray(),
            payload[28], payload[29], payload[30]);
        return true;
    }

    public static OriginalTacticalControlDecision Apply(
        OriginalTacticalControlCommand command, uint controlledUnitId, ushort totalPower,
        OriginalTacticalCorpsRecord state)
    {
        if (controlledUnitId == 0 || command.UnitId != controlledUnitId)
            return new(false, null, "TACTICAL_UNIT_NOT_CONTROLLED");
        // Logger 00495B70 calls the third u32 "id="; 004B4A90 obtains it
        // from the current character, and 004C1700 uses it to find that actor.
        if (command.ActorId != state.Id)
            return new(false, null, "TACTICAL_ACTOR_NOT_CONTROLLED");
        if (command.Shields is null || command.Shields.Count != 6)
            return new(false, null, "TACTICAL_SHIELD_CHANNEL_COUNT");
        // The original HUD enforces sum <= template +0x252 (TotalPower).
        var used = command.Beam + command.Gun + command.Engine + command.Warp + command.Sensor
            + command.Shields.Sum(value => (int)value);
        if (used > totalPower)
            return new(false, null, "TACTICAL_POWER_BUDGET_EXCEEDED");
        return new(true, state with
        {
            PowerBeam = command.Beam,
            PowerGun = command.Gun,
            PowerShield = command.Shields.ToArray(),
            PowerMove = command.Engine,
            PowerWarp = command.Warp,
            PowerSensor = command.Sensor,
        }, null);
    }
}
