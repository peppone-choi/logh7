using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMoveGridRequest(
    uint Time,
    uint Wait,
    uint Id,
    ushort Card,
    uint Pcp,
    uint Mcp,
    ushort Grid,
    byte Erange,
    uint Base,
    ushort Mode);

public readonly record struct OriginalMovedGridCruisingRecord(
    uint Unit,
    uint Cruising);

public readonly record struct OriginalMovedGridNotification(
    uint Time,
    uint Id,
    uint Grid,
    uint Base,
    ushort Mode,
    IReadOnlyList<OriginalMovedGridCruisingRecord> Records);

public static class OriginalMoveGridCodec
{
    public const ushort RequestType = 0x0b01;
    public const ushort NotificationType = 0x0b07;
    public const int RequestBodySize = 31;
    public const int NotificationFixedBodySize = 0x13;
    public const int MaximumCruisingRecordCount = 70;

    private const int RequestPayloadSize = sizeof(ushort) + RequestBodySize;
    private const int CruisingRecordSize = sizeof(uint) * 2;

    public static bool TryDecodeRequest(
        ReadOnlySpan<byte> payload,
        out OriginalMoveGridRequest request)
    {
        request = default;
        if (payload.Length != RequestPayloadSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != RequestType)
        {
            return false;
        }

        // ORIGINAL_STATIC: expose the fixed wire fields without assigning a
        // request-category meaning that has not been established.
        request = new OriginalMoveGridRequest(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[14..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[16..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[20..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[24..]),
            payload[26],
            BinaryPrimitives.ReadUInt32BigEndian(payload[27..]),
            BinaryPrimitives.ReadUInt16BigEndian(payload[31..]));
        return true;
    }

    public static byte[] EncodeNotification(
        OriginalMovedGridNotification notification)
    {
        ArgumentNullException.ThrowIfNull(notification.Records);
        if (notification.Records.Count > MaximumCruisingRecordCount)
        {
            throw new ArgumentOutOfRangeException(
                nameof(notification),
                "ORIGINAL_MOVED_GRID_CRUISING_COUNT");
        }

        var bodySize = checked(
            NotificationFixedBodySize +
            notification.Records.Count * CruisingRecordSize);
        var frame = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, NotificationType);

        var body = payload[sizeof(ushort)..];
        BinaryPrimitives.WriteUInt32BigEndian(body, notification.Time);
        BinaryPrimitives.WriteUInt32BigEndian(body[4..], notification.Id);
        BinaryPrimitives.WriteUInt32BigEndian(body[8..], notification.Grid);
        BinaryPrimitives.WriteUInt32BigEndian(body[12..], notification.Base);
        BinaryPrimitives.WriteUInt16BigEndian(body[16..], notification.Mode);
        body[18] = checked((byte)notification.Records.Count);

        for (var index = 0; index < notification.Records.Count; index++)
        {
            var record = notification.Records[index];
            var recordBody = body[(NotificationFixedBodySize +
                index * CruisingRecordSize)..];
            BinaryPrimitives.WriteUInt32BigEndian(recordBody, record.Unit);
            BinaryPrimitives.WriteUInt32BigEndian(recordBody[4..], record.Cruising);
        }

        return frame;
    }

    public static bool TryDecodeNotification(
        ReadOnlySpan<byte> payload,
        out OriginalMovedGridNotification notification)
    {
        notification = default;
        var minimumPayloadSize = sizeof(ushort) + NotificationFixedBodySize;
        if (payload.Length < minimumPayloadSize ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != NotificationType)
        {
            return false;
        }

        var body = payload[sizeof(ushort)..];
        var count = body[18];
        if (count > MaximumCruisingRecordCount ||
            payload.Length != minimumPayloadSize + count * CruisingRecordSize)
        {
            return false;
        }

        var records = new OriginalMovedGridCruisingRecord[count];
        for (var index = 0; index < records.Length; index++)
        {
            var recordBody = body[(NotificationFixedBodySize +
                index * CruisingRecordSize)..];
            records[index] = new OriginalMovedGridCruisingRecord(
                BinaryPrimitives.ReadUInt32BigEndian(recordBody),
                BinaryPrimitives.ReadUInt32BigEndian(recordBody[4..]));
        }

        notification = new OriginalMovedGridNotification(
            BinaryPrimitives.ReadUInt32BigEndian(body),
            BinaryPrimitives.ReadUInt32BigEndian(body[4..]),
            BinaryPrimitives.ReadUInt32BigEndian(body[8..]),
            BinaryPrimitives.ReadUInt32BigEndian(body[12..]),
            BinaryPrimitives.ReadUInt16BigEndian(body[16..]),
            records);
        return true;
    }
}
