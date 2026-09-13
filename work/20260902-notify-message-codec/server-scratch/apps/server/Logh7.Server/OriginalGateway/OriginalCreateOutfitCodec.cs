using System.Buffers.Binary;
namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalCreateOutfitCommand(uint Time,uint ActorId,byte Mode,uint Pcp,uint Mcp,
    uint BaseId,byte Kind,IReadOnlyList<OriginalReorganizationShip> Ships,
    IReadOnlyList<OriginalReorganizationTroop> Troops,uint MaxTroop,uint MaxCrew,OriginalInformationOutfit Outfit);

public static class OriginalCreateOutfitCodec
{
    public const ushort CommandType=0x0903;
    public static byte[] Encode(OriginalCreateOutfitCommand command)
    {
        if(command.Ships is null || command.Troops is null || command.Ships.Count>99 || command.Troops.Count>24)
            throw new ArgumentException("CreateOutfit arrays absent or over original capacity",nameof(command));
        var frame=new byte[58+5*(command.Ships.Count+command.Troops.Count)];
        var p=frame.AsSpan(4);
        BinaryPrimitives.WriteUInt16BigEndian(p,CommandType);
        BinaryPrimitives.WriteUInt32BigEndian(p[2..],command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(p[6..],command.ActorId);
        p[10]=command.Mode;
        BinaryPrimitives.WriteUInt32BigEndian(p[11..],command.Pcp);
        BinaryPrimitives.WriteUInt32BigEndian(p[15..],command.Mcp);
        BinaryPrimitives.WriteUInt32BigEndian(p[19..],command.BaseId);
        p[23]=command.Kind; p[24]=(byte)command.Ships.Count;
        int at=25;
        foreach(var ship in command.Ships)
        {
            BinaryPrimitives.WriteUInt16BigEndian(p[at..],ship.Kind);
            p[at+2]=unchecked((byte)ship.UnitNumber);
            BinaryPrimitives.WriteUInt16BigEndian(p[(at+3)..],ship.BoatNumber); at+=5;
        }
        p[at++]=(byte)command.Troops.Count;
        foreach(var troop in command.Troops)
        {
            BinaryPrimitives.WriteUInt16BigEndian(p[at..],troop.Kind); p[at+2]=troop.TroopGrade;
            BinaryPrimitives.WriteInt16BigEndian(p[(at+3)..],troop.UnitNumber); at+=5;
        }
        BinaryPrimitives.WriteUInt32BigEndian(p[at..],command.MaxTroop);
        BinaryPrimitives.WriteUInt32BigEndian(p[(at+4)..],command.MaxCrew);
        var r=command.Outfit; var tail=p[(at+8)..];
        BinaryPrimitives.WriteUInt32BigEndian(tail,r.Id);
        tail[4]=r.Kind; tail[5]=r.Power; tail[6]=r.Camp; tail[7]=r.Index;
        BinaryPrimitives.WriteUInt16BigEndian(tail[8..],r.Achievement);
        tail[10]=r.PracticeWarp; tail[11]=r.PracticeSpeed; tail[12]=r.PracticeCommand;
        tail[13]=r.PracticeOffence; tail[14]=r.PracticeDefence; tail[15]=r.PracticeAntiaircraft;
        tail[16]=r.PracticeSearch; tail[17]=r.PracticeDeception; tail[18]=r.PracticeLandbattle;
        tail[19]=r.PracticeAirbattle;
        return frame;
    }
    public static bool TryDecode(ReadOnlySpan<byte> payload,out OriginalCreateOutfitCommand command)
    {
        // Original length0048D860, writer0048DA80, binary reader0048FB80.
        // Expanded804-byte record differs from packed52+5N+5M body.
        // Result outfit omits StrategyId; this decoder does not grant authority.
        command=default;
        if(payload.Length<54 || BinaryPrimitives.ReadUInt16BigEndian(payload)!=CommandType) return false;
        int shipsCount=payload[24];
        if(shipsCount>99) return false;
        int troopOffset=25+5*shipsCount;
        if(payload.Length<troopOffset+29) return false;
        int troopCount=payload[troopOffset];
        if(troopCount>24 || payload.Length!=54+5*(shipsCount+troopCount)) return false;
        var ships=new OriginalReorganizationShip[shipsCount];
        int at=25;
        for(int i=0;i<shipsCount;i++,at+=5)
            ships[i]=new(BinaryPrimitives.ReadUInt16BigEndian(payload[at..]),unchecked((sbyte)payload[at+2]),
                BinaryPrimitives.ReadUInt16BigEndian(payload[(at+3)..]));
        at++;
        var troops=new OriginalReorganizationTroop[troopCount];
        for(int i=0;i<troopCount;i++,at+=5)
            troops[i]=new(BinaryPrimitives.ReadUInt16BigEndian(payload[at..]),payload[at+2],
                BinaryPrimitives.ReadInt16BigEndian(payload[(at+3)..]));
        uint maxTroop=BinaryPrimitives.ReadUInt32BigEndian(payload[at..]);
        uint maxCrew=BinaryPrimitives.ReadUInt32BigEndian(payload[(at+4)..]);
        var result=payload[(at+8)..];
        var outfit=new OriginalInformationOutfit(BinaryPrimitives.ReadUInt32BigEndian(result),
            result[4],result[5],result[6],result[7],BinaryPrimitives.ReadUInt16BigEndian(result[8..]),0,
            result[10],result[11],result[12],result[13],result[14],result[15],result[16],result[17],result[18],result[19]);
        command=new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            payload[10],BinaryPrimitives.ReadUInt32BigEndian(payload[11..]),BinaryPrimitives.ReadUInt32BigEndian(payload[15..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[19..]),payload[23],ships,troops,maxTroop,maxCrew,outfit);
        return true;
    }
}
