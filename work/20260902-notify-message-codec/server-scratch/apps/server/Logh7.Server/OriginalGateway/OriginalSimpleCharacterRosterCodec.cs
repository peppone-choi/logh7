using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalSimpleCharacterRosterEntry(
    uint CharacterId,
    string Name,
    byte Group);

public static class OriginalSimpleCharacterRosterCodec
{
    public const ushort RequestType = 0x1200;
    public const int RequestMessageSize = 31;
    public const int BeginWireBodySize = RequestMessageSize - sizeof(ushort);
    private const int EndBodySize = 1;
    private const int EntryRecordWireFixedSize = 28;
    private const int MaximumNameCharacters = 12;
    private const int MaximumEntryCount = 100;

    public static IReadOnlyList<byte[]> EncodeTransaction(uint characterId, string name)
    {
        return EncodeTransaction(
            [new OriginalSimpleCharacterRosterEntry(characterId, name, 2)]);
    }

    public static IReadOnlyList<byte[]> EncodeTransaction(
        IReadOnlyList<OriginalSimpleCharacterRosterEntry> entries)
    {
        return EncodeTransaction(entries, new byte[BeginWireBodySize]);
    }

    public static IReadOnlyList<byte[]> EncodeTransaction(
        IReadOnlyList<OriginalSimpleCharacterRosterEntry> entries,
        ReadOnlySpan<byte> beginRequestBody)
    {
        ArgumentNullException.ThrowIfNull(entries);
        if (beginRequestBody.Length != BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        if (entries.Count is 0 or > MaximumEntryCount)
        {
            throw new ArgumentOutOfRangeException(nameof(entries));
        }

        foreach (var record in entries)
        {
            if (record.CharacterId == 0 || record.Group is < 1 or > 2)
            {
                throw new ArgumentOutOfRangeException(nameof(entries));
            }

            ArgumentNullException.ThrowIfNull(record.Name);
            if (record.Name.Length > MaximumNameCharacters || record.Name.Any(char.IsSurrogate))
            {
                throw new ArgumentOutOfRangeException(
                    nameof(entries),
                    "ORIGINAL_SIMPLE_CHARACTER_NAME");
            }
        }

        // ORIGINAL_STATIC: 0x00565D60 reads a compact network field stream into
        // the fixed 0x73A4-byte NotifySimpleInformationCharacterEntry object.
        // 0x73A4 is the decoded allocation size, not the wire-body size.
        var entryBodySize = checked(
            sizeof(byte) + entries.Sum(
                record => EntryRecordWireFixedSize +
                    (record.Name.Length + 1) * sizeof(ushort)));
        var entry = Allocate(0x120f, entryBodySize);
        var body = entry.AsSpan(6);
        body[0] = checked((byte)entries.Count);
        var cursor = sizeof(byte);
        foreach (var record in entries)
        {
            var wireRecord = body[cursor..];
            wireRecord[0] = record.Group;
            wireRecord[1] = 0;
            BinaryPrimitives.WriteUInt16BigEndian(wireRecord[2..], 0);
            BinaryPrimitives.WriteUInt32BigEndian(wireRecord[4..], 0);
            BinaryPrimitives.WriteUInt32BigEndian(wireRecord[8..], record.CharacterId);
            // ORIGINAL_STATIC: the client parser copies the declared UTF-16
            // element count into a fixed buffer but does not append a NUL.
            // Original pstr16 fields include the terminator in that count.
            wireRecord[12] = checked((byte)(record.Name.Length + 1));
            for (var index = 0; index < record.Name.Length; index++)
            {
                BinaryPrimitives.WriteUInt16BigEndian(
                    wireRecord[(13 + index * sizeof(ushort))..],
                    record.Name[index]);
            }

            cursor += EntryRecordWireFixedSize +
                (record.Name.Length + 1) * sizeof(ushort);
        }

        // ORIGINAL_STATIC: request serializer 0x0055B140 writes 29 bytes,
        // while response parser 0x0055B3A0 reads the same field sequence into
        // a padded 0x24-byte object. The response therefore echoes the compact
        // request context rather than transmitting the padded object size.
        var begin = Allocate(0x1200, BeginWireBodySize);
        beginRequestBody.CopyTo(begin.AsSpan(6));

        return
        [
            begin,
            entry,
            Allocate(0x1201, EndBodySize)
        ];
    }

    private static byte[] Allocate(ushort type, int bodySize)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), type);
        return response;
    }
}
