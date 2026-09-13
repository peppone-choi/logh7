using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMovedBaseNotification(
    uint Time, uint Id, uint Base, ushort Mode, uint Spot, uint SpotOwner,
    IReadOnlyList<uint> MoveCharacters);

public static class OriginalMovedBaseCodec
{
    public const ushort NotificationType=0x0b0b;
    public const int MaximumMoveCharacters=10;

    // ORIGINAL_STATIC reader0044BEE0, logger0044C310, consumer004BEE60.
    // Unlike042F's tactical entity update, this updates player contexts.
    // Empty lists are structurally valid but update no player; the authority
    // must select the actual affected characters and commit before notifying.
    public static byte[] Encode(OriginalMovedBaseNotification notification)
    {
        ArgumentNullException.ThrowIfNull(notification.MoveCharacters);
        if(notification.MoveCharacters.Count>MaximumMoveCharacters)
            throw new ArgumentOutOfRangeException(nameof(notification));
        var frame=new byte[29+4*notification.MoveCharacters.Count];
        var payload=frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload,NotificationType);
        BinaryPrimitives.WriteUInt32BigEndian(payload[2..],notification.Time);
        BinaryPrimitives.WriteUInt32BigEndian(payload[6..],notification.Id);
        BinaryPrimitives.WriteUInt32BigEndian(payload[10..],notification.Base);
        BinaryPrimitives.WriteUInt16BigEndian(payload[14..],notification.Mode);
        BinaryPrimitives.WriteUInt32BigEndian(payload[16..],notification.Spot);
        BinaryPrimitives.WriteUInt32BigEndian(payload[20..],notification.SpotOwner);
        payload[24]=checked((byte)notification.MoveCharacters.Count);
        for(var i=0;i<notification.MoveCharacters.Count;i++)
            BinaryPrimitives.WriteUInt32BigEndian(payload[(25+4*i)..],notification.MoveCharacters[i]);
        return frame;
    }
}
