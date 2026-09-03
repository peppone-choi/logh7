using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalSystemSceneIdRequest(
    ushort Type,
    IReadOnlyList<uint> Ids);

public readonly record struct OriginalTacticalBaseRecord(
    uint Id,
    float X,
    float Y,
    float Z,
    uint Antiaircraft,
    ushort CannonAngle,
    uint CannonStart,
    ushort Stamina);

public readonly record struct OriginalTacticalBaseResponse(
    IReadOnlyList<OriginalTacticalBaseRecord> Records);

public readonly record struct OriginalBasePositionRecord(
    uint Id,
    float X,
    float Y,
    float Z);

public readonly record struct OriginalBasePositionResponse(
    IReadOnlyList<OriginalBasePositionRecord> Records);

public static class OriginalSystemSceneCodec
{
    public const ushort BaseParametersRequestType = 0x031e;
    public const ushort BaseParametersResponseType = 0x031f;
    public const ushort TacticalBasesRequestType = 0x0344;
    public const ushort TacticalBasesResponseType = 0x0345;
    public const ushort BasePositionsRequestType = 0x034a;
    public const ushort BasePositionsResponseType = 0x034b;

    public const int MaximumBaseParameterCount = 4;
    public const int MaximumTacticalBaseCount = 16;
    public const int MaximumBasePositionCount = 4;

    private const int TacticalBaseRecordSize = 28;
    private const int BasePositionRecordSize = 16;

    public static int GetMaximumRequestIdCount(ushort type) => type switch
    {
        BaseParametersRequestType => MaximumBaseParameterCount,
        TacticalBasesRequestType => MaximumTacticalBaseCount,
        BasePositionsRequestType => MaximumBasePositionCount,
        _ => 0,
    };

    public static byte[] EncodeIdRequest(OriginalSystemSceneIdRequest request)
    {
        ArgumentNullException.ThrowIfNull(request.Ids);
        var maximumCount = GetMaximumRequestIdCount(request.Type);
        if (maximumCount == 0 || request.Ids.Count > maximumCount)
        {
            throw new ArgumentOutOfRangeException(nameof(request));
        }

        var payload = new byte[
            sizeof(ushort) + sizeof(byte) + request.Ids.Count * sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(payload, request.Type);
        payload[sizeof(ushort)] = checked((byte)request.Ids.Count);
        var cursor = sizeof(ushort) + sizeof(byte);
        foreach (var id in request.Ids)
        {
            BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(cursor), id);
            cursor += sizeof(uint);
        }

        return payload;
    }

    public static bool TryDecodeIdRequest(
        ReadOnlySpan<byte> payload,
        out OriginalSystemSceneIdRequest request)
    {
        request = default;
        if (payload.Length < sizeof(ushort) + sizeof(byte))
        {
            return false;
        }

        var type = BinaryPrimitives.ReadUInt16BigEndian(payload);
        var maximumCount = GetMaximumRequestIdCount(type);
        if (maximumCount == 0)
        {
            return false;
        }

        var count = payload[sizeof(ushort)];
        var expectedLength = sizeof(ushort) + sizeof(byte) + count * sizeof(uint);
        if (count > maximumCount || payload.Length != expectedLength)
        {
            return false;
        }

        var ids = new uint[count];
        var cursor = sizeof(ushort) + sizeof(byte);
        for (var index = 0; index < ids.Length; index++)
        {
            ids[index] = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
            cursor += sizeof(uint);
        }

        request = new OriginalSystemSceneIdRequest(type, ids);
        return true;
    }

    public static byte[] EncodeTacticalBases(OriginalTacticalBaseResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.Records);
        EnsureCountAtMost(
            response.Records.Count,
            MaximumTacticalBaseCount,
            nameof(response));

        var frame = Allocate(
            TacticalBasesResponseType,
            sizeof(byte) + response.Records.Count * TacticalBaseRecordSize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        body[0] = checked((byte)response.Records.Count);
        var cursor = sizeof(byte);
        foreach (var record in response.Records)
        {
            WriteUInt32(body, ref cursor, record.Id);
            WriteSingle(body, ref cursor, record.X);
            WriteSingle(body, ref cursor, record.Y);
            WriteSingle(body, ref cursor, record.Z);
            WriteUInt32(body, ref cursor, record.Antiaircraft);
            WriteUInt16(body, ref cursor, record.CannonAngle);
            WriteUInt32(body, ref cursor, record.CannonStart);
            WriteUInt16(body, ref cursor, record.Stamina);
        }

        return frame;
    }

    public static bool TryDecodeTacticalBases(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalBaseResponse response)
    {
        response = default;
        if (!TryGetResponseCount(
                payload,
                TacticalBasesResponseType,
                MaximumTacticalBaseCount,
                TacticalBaseRecordSize,
                out var count))
        {
            return false;
        }

        var records = new OriginalTacticalBaseRecord[count];
        var cursor = sizeof(ushort) + sizeof(byte);
        for (var index = 0; index < records.Length; index++)
        {
            records[index] = new OriginalTacticalBaseRecord(
                ReadUInt32(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadUInt32(payload, ref cursor),
                ReadUInt16(payload, ref cursor),
                ReadUInt32(payload, ref cursor),
                ReadUInt16(payload, ref cursor));
        }

        response = new OriginalTacticalBaseResponse(records);
        return true;
    }

    public static byte[] EncodeBasePositions(OriginalBasePositionResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.Records);
        EnsureCountAtMost(
            response.Records.Count,
            MaximumBasePositionCount,
            nameof(response));

        var frame = Allocate(
            BasePositionsResponseType,
            sizeof(byte) + response.Records.Count * BasePositionRecordSize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        body[0] = checked((byte)response.Records.Count);
        var cursor = sizeof(byte);
        foreach (var record in response.Records)
        {
            WriteUInt32(body, ref cursor, record.Id);
            WriteSingle(body, ref cursor, record.X);
            WriteSingle(body, ref cursor, record.Y);
            WriteSingle(body, ref cursor, record.Z);
        }

        return frame;
    }

    public static bool TryDecodeBasePositions(
        ReadOnlySpan<byte> payload,
        out OriginalBasePositionResponse response)
    {
        response = default;
        if (!TryGetResponseCount(
                payload,
                BasePositionsResponseType,
                MaximumBasePositionCount,
                BasePositionRecordSize,
                out var count))
        {
            return false;
        }

        var records = new OriginalBasePositionRecord[count];
        var cursor = sizeof(ushort) + sizeof(byte);
        for (var index = 0; index < records.Length; index++)
        {
            records[index] = new OriginalBasePositionRecord(
                ReadUInt32(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor));
        }

        response = new OriginalBasePositionResponse(records);
        return true;
    }

    private static bool TryGetResponseCount(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        int maximumCount,
        int recordSize,
        out int count)
    {
        count = 0;
        if (payload.Length < sizeof(ushort) + sizeof(byte) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }

        count = payload[sizeof(ushort)];
        return count <= maximumCount &&
            payload.Length == sizeof(ushort) + sizeof(byte) + count * recordSize;
    }

    private static byte[] Allocate(ushort type, int bodySize)
    {
        var frame = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        BinaryPrimitives.WriteUInt16BigEndian(
            frame.AsSpan(OriginalLoginCodec.MessageCodeSize),
            type);
        return frame;
    }

    private static void EnsureCountAtMost(
        int count,
        int maximumCount,
        string parameterName)
    {
        if (count > maximumCount)
        {
            throw new ArgumentOutOfRangeException(parameterName);
        }
    }

    private static void WriteUInt16(Span<byte> body, ref int cursor, ushort value)
    {
        BinaryPrimitives.WriteUInt16BigEndian(body[cursor..], value);
        cursor += sizeof(ushort);
    }

    private static void WriteUInt32(Span<byte> body, ref int cursor, uint value)
    {
        BinaryPrimitives.WriteUInt32BigEndian(body[cursor..], value);
        cursor += sizeof(uint);
    }

    private static void WriteSingle(Span<byte> body, ref int cursor, float value) =>
        WriteUInt32(body, ref cursor, BitConverter.SingleToUInt32Bits(value));

    private static ushort ReadUInt16(ReadOnlySpan<byte> payload, ref int cursor)
    {
        var value = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        return value;
    }

    private static uint ReadUInt32(ReadOnlySpan<byte> payload, ref int cursor)
    {
        var value = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        return value;
    }

    private static float ReadSingle(ReadOnlySpan<byte> payload, ref int cursor) =>
        BitConverter.UInt32BitsToSingle(ReadUInt32(payload, ref cursor));
}
