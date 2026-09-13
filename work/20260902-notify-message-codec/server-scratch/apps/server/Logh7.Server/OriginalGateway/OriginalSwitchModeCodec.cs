using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalSwitchModeRequest(
    uint Time, uint ActorId, ushort Card, uint Pcp, uint Mcp, ushort Mode,
    IReadOnlyList<uint> Units, uint Spot, uint SpotOwner, IReadOnlyList<uint> MoveCharacters);

public readonly record struct OriginalChangeModeUnit(
    uint Id, float Direction, float X, float Y, float Z);

public readonly record struct OriginalChangeModeNotification(
    uint Time, byte Kind, uint TargetBase, IReadOnlyList<OriginalChangeModeUnit> Units,
    uint Spot, uint SpotOwner);

public static class OriginalSwitchModeCodec
{
    public const ushort RequestType = 0x0b06;
    public const ushort NotificationType = 0x042f;
    public const int MaximumRequestUnits = 70;
    public const int MaximumMoveCharacters = 10;
    public const int MaximumNotificationUnits = 32;

    // ORIGINAL_STATIC: writer00448EA0, logger00449190. Expanded C++ padding is
    // absent on the wire: 30+4*N+4*M body bytes. Actor is the logger's "id".
    // Decoding does not authorize modes, IDs, PCP/MCP, spot or ownership.
    public static bool TryDecodeRequest(ReadOnlySpan<byte> payload, out OriginalSwitchModeRequest request)
    {
        request = default;
        if (payload.Length < 32 || BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
            return false;
        var unitCount = payload[22];
        if (unitCount > MaximumRequestUnits) return false;
        var tail = 23 + unitCount * 4;
        if (payload.Length < tail + 9) return false;
        var characterCount = payload[tail + 8];
        if (characterCount > MaximumMoveCharacters ||
            payload.Length != 32 + (unitCount + characterCount) * 4)
            return false;

        var units = new uint[unitCount];
        for (var i = 0; i < units.Length; i++)
            units[i] = BinaryPrimitives.ReadUInt32BigEndian(payload[(23 + i * 4)..]);
        var characters = new uint[characterCount];
        for (var i = 0; i < characters.Length; i++)
            characters[i] = BinaryPrimitives.ReadUInt32BigEndian(payload[(tail + 9 + i * 4)..]);
        request = new(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[12..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[16..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[20..]), units,
            BinaryPrimitives.ReadUInt32BigEndian(payload[tail..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(tail + 4)..]), characters);
        return true;
    }

    // ORIGINAL_STATIC: reader004A79B0, logger004A7F20, dispatcher004BC169.
    // 18+20*N body bytes. Kind is one byte, followed by an unaligned base ID.
    // The authority must split notifications if a request affects over32 units.
    public static byte[] EncodeNotification(OriginalChangeModeNotification notification)
    {
        ArgumentNullException.ThrowIfNull(notification.Units);
        if (notification.Units.Count > MaximumNotificationUnits)
            throw new ArgumentOutOfRangeException(nameof(notification));
        var frame = new byte[24 + notification.Units.Count * 20];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, NotificationType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..], notification.Time);
        payload[6] = notification.Kind;
        BinaryPrimitives.WriteUInt32BigEndian(payload[7..], notification.TargetBase);
        payload[11] = checked((byte)notification.Units.Count);
        var cursor = 12;
        foreach (var unit in notification.Units)
        {
            BinaryPrimitives.WriteUInt32BigEndian(payload[cursor..], unit.Id);
            BinaryPrimitives.WriteSingleBigEndian(payload[(cursor + 4)..], unit.Direction);
            BinaryPrimitives.WriteSingleBigEndian(payload[(cursor + 8)..], unit.X);
            BinaryPrimitives.WriteSingleBigEndian(payload[(cursor + 12)..], unit.Y);
            BinaryPrimitives.WriteSingleBigEndian(payload[(cursor + 16)..], unit.Z);
            cursor += 20;
        }
        BinaryPrimitives.WriteUInt32BigEndian(payload[cursor..], notification.Spot);
        BinaryPrimitives.WriteUInt32BigEndian(payload[(cursor + 4)..], notification.SpotOwner);
        return frame;
    }
}
