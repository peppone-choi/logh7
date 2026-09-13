using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalOutfitPartyRequest(uint OutfitId, uint BaseId, byte Mode);
public readonly record struct OriginalOutfitPartyCharacter(uint Id, byte Kind, byte Rank, string Name);
public readonly record struct OriginalOutfitPartyShip(ushort Kind, byte UnitNumber, ushort BoatNumber, IReadOnlyList<uint> Units);
public readonly record struct OriginalOutfitPartyPackage(byte Kind, ushort UnitKind, byte Grade, uint PackageNumber);
public readonly record struct OriginalOutfitPartyResponse(uint OutfitId, uint BaseId, byte Mode, byte Power, byte Camp, uint Kind, uint Index,
    IReadOnlyList<OriginalOutfitPartyCharacter> Characters, IReadOnlyList<OriginalOutfitPartyShip> Ships,
    IReadOnlyList<OriginalWarehouseTroop> Troops, uint Supplies, uint MaxSupplies, ushort Package,
    IReadOnlyList<OriginalOutfitPartyPackage> OtherPackages, IReadOnlyList<OriginalOutfitPartyPackage> TroopPackages,
    byte TransportPackageEmpty, byte TroopTransportPackageEmpty, byte Carrying,
    IReadOnlyList<OriginalOutfitPartyShip> NotTogetherShips, IReadOnlyList<OriginalWarehouseTroop> NotTogetherTroops);

public static class OriginalOutfitPartyCodec
{
    public const ushort RequestType = 0x032e;
    public const ushort ResponseType = 0x032f;
    public const int MaximumCharacters = 10;
    public const int MaximumNameCodeUnits = 13;
    public const int MaximumShips = 60;
    public const int MaximumShipUnits = 70;
    public const int MaximumTroops = 24;
    public const int MaximumOtherPackages = 3;
    public const int MaximumTroopPackages = 24;

    public static bool TryDecodeRequest(ReadOnlySpan<byte> payload, out OriginalOutfitPartyRequest request)
    {
        request = default;
        if (payload.Length != 11 || BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
            return false;
        request = new(BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]), payload[10]);
        return true;
    }

    public static byte[] EncodeRequest(OriginalOutfitPartyRequest request)
    {
        var frame = new byte[15];
        var writer = new Writer(frame.AsSpan(4));
        writer.U16(RequestType);
        writer.U32(request.OutfitId);
        writer.U32(request.BaseId);
        writer.U8(request.Mode);
        return frame;
    }

    // Original reader0041CBA0/logger0041EAA0: 39-byte empty body, no expanded-structure padding.
    // Raw modes, counts and package fields have no authored interpretation in this codec.
    public static bool TryDecodeResponse(ReadOnlySpan<byte> payload, out OriginalOutfitPartyResponse response)
    {
        response = default;
        if (payload.Length < 41 || BinaryPrimitives.ReadUInt16BigEndian(payload) != ResponseType)
            return false;
        try
        {
            var reader = new Reader(payload[2..]);
            var outfit = reader.U32();
            var baseId = reader.U32();
            var mode = reader.U8();
            var power = reader.U8();
            var camp = reader.U8();
            var kind = reader.U32();
            var index = reader.U32();
            var characters = ReadCharacters(ref reader);
            var ships = ReadShips(ref reader);
            var troops = ReadTroops(ref reader);
            var supplies = reader.U32();
            var maxSupplies = reader.U32();
            var package = reader.U16();
            var otherPackages = ReadPackages(ref reader, MaximumOtherPackages);
            var troopPackages = ReadPackages(ref reader, MaximumTroopPackages);
            var transportEmpty = reader.U8();
            var troopTransportEmpty = reader.U8();
            var carrying = reader.U8();
            var notTogetherShips = ReadShips(ref reader);
            var notTogetherTroops = ReadTroops(ref reader);
            if (!reader.AtEnd) return false;
            response = new(outfit, baseId, mode, power, camp, kind, index, characters, ships, troops,
                supplies, maxSupplies, package, otherPackages, troopPackages, transportEmpty,
                troopTransportEmpty, carrying, notTogetherShips, notTogetherTroops);
            return true;
        }
        catch (InvalidDataException)
        {
            return false;
        }
    }

    public static byte[] EncodeResponse(OriginalOutfitPartyResponse response)
    {
        ValidateList(response.Characters, MaximumCharacters);
        ValidateList(response.Ships, MaximumShips);
        ValidateList(response.Troops, MaximumTroops);
        ValidateList(response.OtherPackages, MaximumOtherPackages);
        ValidateList(response.TroopPackages, MaximumTroopPackages);
        ValidateList(response.NotTogetherShips, MaximumShips);
        ValidateList(response.NotTogetherTroops, MaximumTroops);
        var length = 45;
        foreach (var character in response.Characters)
        {
            if (character.Name is null || character.Name.Length > MaximumNameCodeUnits)
                throw new ArgumentException("Outfit character name is absent or exceeds 13 UTF-16 code units.", nameof(response));
            length += 7 + 2 * character.Name.Length;
        }
        length += ShipBytes(response.Ships) + ShipBytes(response.NotTogetherShips);
        length += 5 * (response.Troops.Count + response.NotTogetherTroops.Count);
        length += 8 * (response.OtherPackages.Count + response.TroopPackages.Count);
        var frame = new byte[length];
        var writer = new Writer(frame.AsSpan(4));
        writer.U16(ResponseType);
        writer.U32(response.OutfitId);
        writer.U32(response.BaseId);
        writer.U8(response.Mode);
        writer.U8(response.Power);
        writer.U8(response.Camp);
        writer.U32(response.Kind);
        writer.U32(response.Index);
        writer.U8((byte)response.Characters.Count);
        foreach (var character in response.Characters)
        {
            writer.U32(character.Id);
            writer.U8(character.Kind);
            writer.U8(character.Rank);
            writer.U8((byte)character.Name.Length);
            foreach (var codeUnit in character.Name) writer.U16(codeUnit);
        }
        WriteShips(ref writer, response.Ships);
        WriteTroops(ref writer, response.Troops);
        writer.U32(response.Supplies);
        writer.U32(response.MaxSupplies);
        writer.U16(response.Package);
        WritePackages(ref writer, response.OtherPackages);
        WritePackages(ref writer, response.TroopPackages);
        writer.U8(response.TransportPackageEmpty);
        writer.U8(response.TroopTransportPackageEmpty);
        writer.U8(response.Carrying);
        WriteShips(ref writer, response.NotTogetherShips);
        WriteTroops(ref writer, response.NotTogetherTroops);
        return frame;
    }

    private static OriginalOutfitPartyCharacter[] ReadCharacters(ref Reader reader)
    {
        var rows = new OriginalOutfitPartyCharacter[reader.Count(MaximumCharacters)];
        for (var i = 0; i < rows.Length; i++)
        {
            var id = reader.U32();
            var kind = reader.U8();
            var rank = reader.U8();
            var name = new char[reader.Count(MaximumNameCodeUnits)];
            for (var n = 0; n < name.Length; n++) name[n] = (char)reader.U16();
            rows[i] = new(id, kind, rank, new string(name));
        }
        return rows;
    }

    private static OriginalOutfitPartyShip[] ReadShips(ref Reader reader)
    {
        var rows = new OriginalOutfitPartyShip[reader.Count(MaximumShips)];
        for (var i = 0; i < rows.Length; i++)
        {
            var kind = reader.U16();
            var number = reader.U8();
            var boats = reader.U16();
            var units = new uint[reader.Count(MaximumShipUnits)];
            for (var u = 0; u < units.Length; u++) units[u] = reader.U32();
            rows[i] = new(kind, number, boats, units);
        }
        return rows;
    }

    private static OriginalWarehouseTroop[] ReadTroops(ref Reader reader)
    {
        var rows = new OriginalWarehouseTroop[reader.Count(MaximumTroops)];
        for (var i = 0; i < rows.Length; i++) rows[i] = new(reader.U16(), reader.U8(), reader.U16());
        return rows;
    }

    private static OriginalOutfitPartyPackage[] ReadPackages(ref Reader reader, int maximum)
    {
        var rows = new OriginalOutfitPartyPackage[reader.Count(maximum)];
        for (var i = 0; i < rows.Length; i++) rows[i] = new(reader.U8(), reader.U16(), reader.U8(), reader.U32());
        return rows;
    }

    private static void WriteShips(ref Writer writer, IReadOnlyList<OriginalOutfitPartyShip> rows)
    {
        writer.U8((byte)rows.Count);
        foreach (var row in rows)
        {
            writer.U16(row.Kind);
            writer.U8(row.UnitNumber);
            writer.U16(row.BoatNumber);
            writer.U8((byte)row.Units.Count);
            foreach (var unit in row.Units) writer.U32(unit);
        }
    }

    private static void WriteTroops(ref Writer writer, IReadOnlyList<OriginalWarehouseTroop> rows)
    {
        writer.U8((byte)rows.Count);
        foreach (var row in rows)
        {
            writer.U16(row.Kind);
            writer.U8(row.TroopGrade);
            writer.U16(row.UnitNumber);
        }
    }

    private static void WritePackages(ref Writer writer, IReadOnlyList<OriginalOutfitPartyPackage> rows)
    {
        writer.U8((byte)rows.Count);
        foreach (var row in rows)
        {
            writer.U8(row.Kind);
            writer.U16(row.UnitKind);
            writer.U8(row.Grade);
            writer.U32(row.PackageNumber);
        }
    }

    private static int ShipBytes(IReadOnlyList<OriginalOutfitPartyShip> rows)
    {
        var length = 0;
        foreach (var row in rows)
        {
            ValidateList(row.Units, MaximumShipUnits);
            length += 6 + 4 * row.Units.Count;
        }
        return length;
    }

    private static void ValidateList<T>(IReadOnlyList<T>? rows, int maximum)
    {
        if (rows is null || rows.Count > maximum)
            throw new ArgumentException("Outfit party list is absent or exceeds original capacity.");
    }

    private ref struct Reader(ReadOnlySpan<byte> bytes)
    {
        private ReadOnlySpan<byte> _remaining = bytes;
        public readonly bool AtEnd => _remaining.IsEmpty;
        private ReadOnlySpan<byte> Take(int count)
        {
            if (_remaining.Length < count) throw new InvalidDataException("Truncated outfit party response.");
            var value = _remaining[..count];
            _remaining = _remaining[count..];
            return value;
        }
        public byte U8() => Take(1)[0];
        public ushort U16() => BinaryPrimitives.ReadUInt16BigEndian(Take(2));
        public uint U32() => BinaryPrimitives.ReadUInt32BigEndian(Take(4));
        public int Count(int maximum)
        {
            var count = U8();
            if (count > maximum) throw new InvalidDataException("Outfit party count exceeds original capacity.");
            return count;
        }
    }

    private ref struct Writer(Span<byte> bytes)
    {
        private Span<byte> _remaining = bytes;
        public void U8(byte value)
        {
            _remaining[0] = value;
            _remaining = _remaining[1..];
        }
        public void U16(ushort value)
        {
            BinaryPrimitives.WriteUInt16BigEndian(_remaining, value);
            _remaining = _remaining[2..];
        }
        public void U32(uint value)
        {
            BinaryPrimitives.WriteUInt32BigEndian(_remaining, value);
            _remaining = _remaining[4..];
        }
    }
}
