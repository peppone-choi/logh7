namespace Logh7.Server.OriginalGateway;

public sealed record OriginalTacticalCommanderMerit(uint CharacterId, byte Rank, uint Achievement);

// One coherent public battlefield projection, not another session's private
// identity context. Mutable command arrays are detached before sharing.
public sealed class OriginalTacticalParticipantSnapshot
{
    public OriginalInformationUnitProjection Unit { get; }
    public OriginalTacticalUnitShipRecord Ship { get; }
    public OriginalTacticalCorpsRecord Corps { get; }
    public ReadOnlyMemory<byte> CharacterFrame { get; }
    public byte Power { get; }
    // Current character codec serves regular camp0; fleet content carries its
    // explicit camp. Do not infer camp from Power (regular/rebel may share it).
    public byte Camp => Outfit?.Camp ?? 0;
    public bool IsHostileTo(byte power, byte camp) => Power != power || Camp != camp;
    public long ShipGeneration { get; }
    public OriginalInformationOutfit? Outfit { get; }
    // Null means the controlling character's merit is not available, not rank0.
    public OriginalTacticalCommanderMerit? CommanderMerit { get; }

    public IReadOnlyList<byte[]> EncodeEntry() =>
    [
        OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0B09),
        ..(Outfit is { } outfit ? new[] { OriginalInformationOutfitCodec.Encode([outfit]) } : Array.Empty<byte[]>()),
        ..(CharacterFrame.IsEmpty ? Array.Empty<byte[]>() : new[] { CharacterFrame.ToArray() }),
        OriginalWorldEntryCodec.EncodeUnits([Unit]),
        OriginalSystemSceneCodec.EncodeTacticalCorps(new([Corps])),
        OriginalTacticalShieldCodec.Encode([OriginalTacticalShieldCodec.CreateAuthoredInitialState(Ship.Id)]),
        OriginalSystemSceneCodec.EncodeTacticalUnitShips(new([Ship])),
        OriginalWorldEntryCodec.EncodeGridEnterBoundary(0x0B0A),
    ];

    public OriginalTacticalParticipantSnapshot(OriginalInformationUnitProjection unit,
        OriginalTacticalUnitShipRecord ship, OriginalTacticalCorpsRecord corps, byte[] characterFrame, byte power = 0,
        long shipGeneration = 0, OriginalInformationOutfit? outfit = null,
        OriginalTacticalCommanderMerit? commanderMerit = null)
    {
        if (unit.Id == 0 || unit.Id != ship.Id || ship.Character == 0)
            throw new ArgumentException("Tactical participant identity mismatch");
        if (commanderMerit is not null && commanderMerit.CharacterId != ship.Character)
            throw new ArgumentException("Tactical commander merit identity mismatch");
        Unit = unit;
        if (outfit is { } info && (unit.Outfit != info.Id || power != info.Power || corps.Id != ship.Character))
            throw new ArgumentException("Tactical fleet membership mismatch");
        Outfit = outfit;
        CommanderMerit = commanderMerit;
        Power = power;
        ShipGeneration = shipGeneration;
        Ship = ship;
        Corps = corps with
        {
            PowerShield = Array.AsReadOnly(corps.PowerShield.ToArray()),
            FillShield = Array.AsReadOnly(corps.FillShield.ToArray()),
            DamagedShield = Array.AsReadOnly(corps.DamagedShield.ToArray())
        };
        CharacterFrame = characterFrame.ToArray();
    }
}
