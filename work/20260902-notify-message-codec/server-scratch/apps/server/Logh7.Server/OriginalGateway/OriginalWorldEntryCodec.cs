using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public static class OriginalWorldEntryCodec
{
    private const int CharacterNameMaximum = 13;

    public static byte[] EncodeCharacterContext(uint characterId)
    {
        var response = Allocate(0x0204, sizeof(uint));
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), characterId);
        return response;
    }

    public static byte[] EncodeUnit(uint gridUnitId) =>
        EncodeUnit(gridUnitId, OriginalAuthoredPlayableCatalog.CurrentGridCell);

    public static byte[] EncodeUnit(uint gridUnitId, uint currentCell)
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
        var body = new WireWriter();
        body.WriteUInt16(1);
        body.WriteUInt32(gridUnitId);
        body.WriteUInt16(0);
        body.WriteByte(0);
        body.WriteUInt32(currentCell);
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        body.WriteByte(0);
        body.WriteUInt32(0);
        body.WriteByte(0);
        body.WriteByte(0);
        body.WriteUInt16(0);
        body.WriteUInt16(0);
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        return Wrap(0x0325, body);
    }

    public static byte[] EncodeCharacter(
        uint characterId,
        uint gridUnitId,
        ushort authorityCardId,
        OriginalCreateCharacterCommand command)
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
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        body.WriteUInt32(0);
        body.WriteUInt32(gridUnitId);
        body.WritePstr16(string.Empty);

        for (var index = 0; index < 6; index++)
        {
            body.WriteUInt32(0);
        }

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
        body.WriteUInt32(0);

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
