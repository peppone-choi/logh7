using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalCompletenessRepairShip(uint UnitId,uint ResultDamaged,uint ResultSupplies);
public readonly record struct OriginalCompletenessRepairCommand(uint Time,uint ActorId,uint Pcp,uint Mcp,
    IReadOnlyList<OriginalCompletenessRepairShip> Ships);

public static class OriginalCompletenessRepairCodec
{
    public const ushort CommandType=0x0c00;
    public const int MaximumShips=70;

    // Original writer00550DE0, length00550C90, logger00550FB0, registry0055A870.
    // Bidirectional record: incoming server results populate these result fields.
    // Original request helper004B4D40 only initializes actor/count/unit IDs; it
    // leaves result words unset. Preserve bytes here, but NEVER treat request
    // ResultDamaged/ResultSupplies as desired effects or meaningful preconditions.
    // Authority must derive repair and resources from authoritative state.
    public static bool TryDecode(ReadOnlySpan<byte> payload,out OriginalCompletenessRepairCommand command)
    {
        command=default;
        if(payload.Length<19 || BinaryPrimitives.ReadUInt16BigEndian(payload)!=CommandType)
            return false;
        var count=payload[18];
        if(count>MaximumShips || payload.Length!=19+12*count) return false;
        var ships=new OriginalCompletenessRepairShip[count];
        for(var i=0;i<count;i++)
        {
            var at=19+12*i;
            ships[i]=new(BinaryPrimitives.ReadUInt32BigEndian(payload[at..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[(at+4)..]),
                BinaryPrimitives.ReadUInt32BigEndian(payload[(at+8)..]));
        }
        command=new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),ships);
        return true;
    }

    // Four-byte message-code prefix, two-byte type, then the packed body.
    public static byte[] Encode(OriginalCompletenessRepairCommand command)
    {
        if(command.Ships is null || command.Ships.Count>MaximumShips)
            throw new ArgumentException("Original repair ship list absent or over capacity.",nameof(command));
        var frame=new byte[23+12*command.Ships.Count];
        var payload=frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload,CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..],command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..],command.ActorId);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..],command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(payload[14..],command.Mcp);
        payload[18]=checked((byte)command.Ships.Count);
        var at=19;
        foreach(var ship in command.Ships)
        {
            BinaryPrimitives.WriteUInt32BigEndian(payload[at..],ship.UnitId);
            BinaryPrimitives.WriteUInt32BigEndian(payload[(at+4)..],ship.ResultDamaged);
            BinaryPrimitives.WriteUInt32BigEndian(payload[(at+8)..],ship.ResultSupplies);
            at+=12;
        }
        return frame;
    }
}
