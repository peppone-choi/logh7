using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalInformationUnitProjection(
    uint Id,
    uint Grid,
    uint Base,
    byte MoraleMax,
    ushort Damaged,
    ushort Destroyed,
    uint Supplies,
    uint Mobilization,
    float Cruising,
    ushort Kind = 0,
    byte Mode = 0,
    uint Outfit = 0,
    uint BoardingShip = 0);

public static class OriginalWorldEntryCodec
{
    public static byte[] EncodeUnits(
        IReadOnlyList<OriginalInformationUnitProjection> units)
    {
        ArgumentNullException.ThrowIfNull(units);
        if (units.Count > OriginalSystemSceneCodec.MaximumTacticalUnitShipCount)
        {
            throw new ArgumentOutOfRangeException(nameof(units));
        }
        var body = new WireWriter();
        body.WriteUInt16(checked((ushort)units.Count));
        foreach (var unit in units)
        {
            body.WriteUInt32(unit.Id);
            // ORIGINAL_STATIC join: FUN_004C32A0 passes InformationUnit.kind
            // as argument 5 to FUN_004C46A0. Its case-1 branch copies that
            // u16 directly to tactical entity +0x8BC, where it indexes the
            // normalized StaticInformationUnitShip array (0-based).
            body.WriteUInt16(unit.Kind);
            // ORIGINAL_STATIC: InformationUnit+6, also changed by 042F via004B5DB0.
            body.WriteByte(unit.Mode);
            body.WriteUInt32(unit.Grid);
            body.WriteUInt32(unit.Outfit);
            body.WriteUInt32(unit.BoardingShip);
            body.WriteByte(0);   // troop count
            body.WriteUInt32(unit.Base);
            body.WriteByte(unit.MoraleMax);
            body.WriteByte(0);   // rebellion
            body.WriteUInt16(unit.Damaged);
            body.WriteUInt16(unit.Destroyed);
            body.WriteUInt32(unit.Supplies);
            body.WriteUInt32(unit.Mobilization);
            body.WriteUInt32(BitConverter.SingleToUInt32Bits(unit.Cruising));
        }
        return Wrap(0x0325, body);
    }

    private const int CharacterNameMaximum = 13;

    public static byte[] EncodeCharacterContext(uint characterId)
    {
        var response = Allocate(0x0204, sizeof(uint));
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), characterId);
        return response;
    }

    private static uint UnitBaseId() =>
        uint.TryParse(Environment.GetEnvironmentVariable("LOGH7_UNIT_BASE"), out var id)
            ? id
            : OriginalAuthoredPlayableCatalog.BaseId;

    public static byte[] EncodeUnit(uint gridUnitId) =>
        EncodeUnit(gridUnitId, OriginalAuthoredPlayableCatalog.CurrentGridCell);

    public static byte[] EncodeTacticalUnitShip(uint unitId, uint characterId) =>
        OriginalSystemSceneCodec.EncodeTacticalUnitShips(
            new OriginalTacticalUnitShipResponse(
            [
                OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(
                    unitId,
                    characterId),
            ]));

    public static byte[] EncodeUnit(uint gridUnitId, uint currentCell) =>
        EncodeUnit(gridUnitId, currentCell,
            currentCell == OriginalAuthoredPlayableCatalog.CurrentGridCell ? UnitBaseId() : 0);

    public static byte[] EncodeUnit(uint gridUnitId, uint currentCell, uint baseId)
    {
        // Input_ResponseInformationUnit::input_from_stream (0x00419ca0) reads a
        // packed count followed by only the live fields of each 0x58-byte
        // in-memory record. The unused capacity up to 600 records is not wire data.
        // ORIGINAL STATIC EVIDENCE: FUN_00419ca0 reads kind/mode followed by
        // three u32 fields. FUN_0042f930 labels them grid, outfit, and
        // boarding_ship in that order, so grid is the first u32 after mode.
        // NEW DESIGN: project the authoritative persisted cell supplied by the
        // session. The one-argument overload retains the initial authored cell.
        // Faction, ship type, commander/focus, owner, and active/selectable
        // semantics remain unproven and deliberately stay zero.
        return EncodeUnits(
        [
            new OriginalInformationUnitProjection(
                Id: gridUnitId,
                Grid: currentCell,
                Base: baseId,
                MoraleMax: 100,
                Damaged: 0,
                Destroyed: 0,
                Supplies: 100,
                Mobilization: 100,
                Cruising: AuthoredCruising(currentCell)),
        ]);
        // 2026-09-04: this u32 (the one right after the troop-unit array) is the record's **base** field.
        // FUN_0042F930, the `_INF:NotifyChangeFlagShip#` logger, labels the 0x58-byte record as
        //   +0x00 id, +0x04 kind, +0x08 mode, +0x0A grid, +0x0C outfit, +0x10 boarding_ship,
        //   +0x18.. troop_units[n], +0x40 base, +0x44 morale_max, +0x48 rebellion, +0x49 damaged,
        //   +0x4A destroyed, +0x4C supplies, +0x50 mobilization, +0x54 cruising
        // and the parser FUN_00419CA0 writes exactly this slot at record+0x40.
        // Live evidence shows this base field changes the HUD view button only.
        // Scene +0x320 is populated after a TacticsInformationUnitShip-to-
        // InformationUnit join and corresponds to this record's grid field.
        // LOGH7_UNIT_BASE remains an authored ownership override; it is not the
        // BASE-command context writer.
    }

    private static float AuthoredCruising(uint currentCell) =>
        currentCell == OriginalMoveGridAuthority.MinimalWorldDestinationCellId
            ? OriginalMoveGridAuthority.MinimalWorldStartingCruising -
                OriginalMoveGridAuthority.MinimalWorldWarpCruisingCost
            : OriginalMoveGridAuthority.MinimalWorldStartingCruising;

    public static byte[] EncodeCharacter(
        uint characterId,
        uint gridUnitId,
        ushort authorityCardId,
        OriginalCreateCharacterCommand command,
        uint spot = 0,
        uint spotOwner = 0,
        uint pcp = 0,
        uint mcp = 0)
    {
        // Input_ResponseInformationCharacter::input_from_stream (0x00417390)
        // expands this packed stream into the client's 0x2d4-byte record.
        var body = new WireWriter();
        body.WriteUInt32(characterId);
        body.WriteByte(command.Power);
        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteUInt32(0);
        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteUInt32(0);
        body.WriteUInt16(0);
        // E083: packed +20 -> expanded +18 -> HUD +3C (return_base).
        body.WriteUInt32(command.ReturnBaseId);
        body.WriteUInt32(spot);
        body.WriteUInt32(spotOwner);
        body.WriteUInt32(gridUnitId);
        // ORIGINAL_STATIC: 00419300 names expanded +28/+2A flagship_name.
        // Preserve the stored character's name, separately from the unit kind
        // and StaticInformationUnitShip model/name catalog.
        body.WritePstr16(command.FlagshipName);

        body.WriteUInt32(0); // strategy
        body.WriteUInt32(0); // coup_conduct
        body.WriteUInt32(0); // coup
        // ORIGINAL_STATIC 00419300: expanded +50 PCP, +54 MCP.
        // 00417390 reads both as u32 after the variable-length flagship name
        // and these three dwords. Values must come from server authority,
        // never echoed from the command request's PCP/MCP fields.
        body.WriteUInt32(pcp);
        body.WriteUInt32(mcp);
        body.WriteUInt32(0); // evaluation

        body.WriteUInt16(0);
        for (var index = 0; index < 6; index++)
        {
            body.WriteByte(0);
        }
        body.WriteByte(1);
        body.WriteUInt32(0);
        body.WriteZeros(16);
        body.WriteByte(0);

        body.WriteByte(1);
        body.WriteByte(1);
        body.WritePstr16(command.LastName);
        body.WritePstr16(command.FirstName);
        body.WritePstr16($"{command.FirstName}・{command.LastName}");
        body.WriteUInt16(0);
        body.WriteUInt16(command.Rank);
        body.WritePstr16(string.Empty);
        body.WriteUInt32(command.Face);
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        // 00417390/004178AB: u32 parentage achievement, expanded first entry+100.
        body.WriteUInt32(command.Achievement);

        for (var index = 0; index < 8; index++)
        {
            body.WriteUInt16(index < command.AbilityValues.Length
                ? command.AbilityValues[index]
                : (ushort)0);
            body.WriteUInt16(0);
        }

        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteByte(1);
        body.WriteUInt16(authorityCardId);
        body.WriteUInt32(characterId);
        body.WriteByte(0);
        return Wrap(0x0323, body);
    }

    public static byte[] EncodeGridEnterBoundary(ushort type)
    {
        if (type is not 0x0b09 and not 0x0b0a)
        {
            throw new ArgumentOutOfRangeException(nameof(type));
        }

        return Allocate(type, 1);
    }

    private static byte[] Wrap(ushort type, WireWriter body)
    {
        var response = Allocate(type, body.Count);
        body.CopyTo(response.AsSpan(6));
        return response;
    }

    private static byte[] Allocate(ushort type, int bodySize)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), type);
        return response;
    }

    private sealed class WireWriter
    {
        private readonly List<byte> _bytes = [];

        public int Count => _bytes.Count;

        public void WriteByte(byte value) => _bytes.Add(value);

        public void WriteUInt16(ushort value)
        {
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WriteUInt32(uint value)
        {
            _bytes.Add((byte)(value >> 24));
            _bytes.Add((byte)(value >> 16));
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WritePstr16(string value)
        {
            var characters = value.AsSpan(0, Math.Min(value.Length, CharacterNameMaximum));
            WriteByte(checked((byte)characters.Length));
            foreach (var character in characters)
            {
                WriteUInt16(character);
            }
        }

        public void WriteZeros(int count)
        {
            for (var index = 0; index < count; index++)
            {
                WriteByte(0);
            }
        }

        public void CopyTo(Span<byte> destination) =>
            _bytes.ToArray().CopyTo(destination);
    }
}
