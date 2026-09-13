using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalSystemSceneIdRequest(
    ushort Type,
    IReadOnlyList<uint> Ids);

public readonly record struct OriginalTacticalUnitShipIdRequest(
    IReadOnlyList<uint> Ids);

public readonly record struct OriginalTacticalSceneRequest(
    ushort Type,
    uint Qualifier,
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

public readonly record struct OriginalTacticalUnitShipRecord(
    uint Id,
    byte Morale,
    byte Confusion,
    uint Character,
    float X,
    float Y,
    float Z,
    float Direction,
    uint DetachmentLeader,
    float DetachmentX,
    float DetachmentY,
    float DetachmentZ,
    float DetachmentDirection,
    byte Search);

public readonly record struct OriginalTacticalUnitShipResponse(
    IReadOnlyList<OriginalTacticalUnitShipRecord> Records);

public readonly record struct OriginalTacticalCorpsRecord(
    uint Id,
    byte Mission,
    byte TargetKind,
    uint Target,
    float CommandRange,
    byte TacticsChief,
    byte File,
    byte PowerMove,
    byte PowerWarp,
    byte PowerSensor,
    byte PowerBeam,
    byte PowerGun,
    IReadOnlyList<byte> PowerShield,
    ushort FillBeam,
    ushort FillGun,
    IReadOnlyList<ushort> FillShield,
    IReadOnlyList<ushort> DamagedShield);

public readonly record struct OriginalTacticalCorpsResponse(
    IReadOnlyList<OriginalTacticalCorpsRecord> Records);

public readonly record struct OriginalBlackHoleObstacle(
    uint Id,
    byte Kind,
    ushort ModelFile,
    float MaxSuctionSpeed,
    float Radius);

public readonly record struct OriginalAsteroidBeltObstacle(
    uint Id,
    byte Kind,
    ushort ModelFile,
    float Radius,
    float Range);

public readonly record struct OriginalGasCloudObstacle(
    uint Id,
    byte Kind,
    ushort ModelFile,
    float RevolutionRadius,
    uint RevolutionCycle,
    byte RevolutionDirection,
    float RevolutionInitialAngle,
    float Radius);

public readonly record struct OriginalAbnormalGravityObstacle(
    uint Id,
    byte Kind,
    ushort ModelFile,
    float GravityUpRange,
    float GravityDownRange);

public readonly record struct OriginalCircleObstacle(
    uint Id,
    byte Kind,
    ushort ModelFile,
    float X,
    float Y,
    float Z,
    float Radius);

public readonly record struct OriginalTacticalObstacleResponse(
    uint Grid,
    IReadOnlyList<OriginalBlackHoleObstacle> BlackHoles,
    IReadOnlyList<OriginalAsteroidBeltObstacle> AsteroidBelts,
    IReadOnlyList<OriginalGasCloudObstacle> GasClouds,
    IReadOnlyList<OriginalAbnormalGravityObstacle> AbnormalGravities,
    IReadOnlyList<OriginalCircleObstacle> Circles);

public static class OriginalSystemSceneCodec
{
    public static OriginalTacticalUnitShipResponse CreateTacticalBattlefield(
        uint playerUnitId,
        uint playerCharacterId,
        OriginalBattlefieldTemplate template) =>
        new(
        [
            CreateAuthoredTacticalUnitShip(playerUnitId, playerCharacterId) with
            {
                Morale = 100,
                X = template.PlayerSpawn.X,
                Y = template.PlayerSpawn.Y,
                Z = template.PlayerSpawn.Z,
                Direction = template.PlayerSpawn.Direction,
                Search = 1,
            },
            CreateAuthoredTacticalUnitShip(
                OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId,
                OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId) with
            {
                Morale = 100,
                X = template.EnemySpawn.X,
                Y = template.EnemySpawn.Y,
                Z = template.EnemySpawn.Z,
                Direction = template.EnemySpawn.Direction,
                Search = 1,
            },
        ]);

    public const ushort BaseParametersRequestType = 0x031e;
    public const ushort BaseParametersResponseType = 0x031f;
    public const ushort TacticalBasesRequestType = 0x0344;
    public const ushort TacticalBasesResponseType = 0x0345;
    public const ushort BasePositionsRequestType = 0x034a;
    public const ushort BasePositionsResponseType = 0x034b;
    public const ushort TacticalUnitShipsRequestType = 0x033a;
    public const ushort TacticalUnitShipsResponseType = 0x033b;
    public const ushort TacticalCharactersRequestType = 0x0336;
    public const ushort TacticalCharactersResponseType = 0x0337;
    public const ushort TacticalCorpsRequestType = 0x033e;
    public const ushort TacticalCorpsResponseType = 0x033f;
    public const ushort TacticalFillShieldRequestType = 0x0340;
    public const ushort TacticalFillShieldResponseType = 0x0341;
    public const ushort TacticalObstaclesRequestType = 0x0346;
    public const ushort TacticalObstaclesResponseType = 0x0347;
    public const ushort UnitPositionsRequestType = 0x0348;
    public const ushort UnitPositionsResponseType = 0x0349;

    public const int MaximumBaseParameterCount = 4;
    public const int MaximumTacticalBaseCount = 16;
    public const int MaximumBasePositionCount = 4;
    public const int MaximumTacticalUnitShipCount = 600;
    public const int MaximumBlackHoleCount = 1;
    public const int MaximumAsteroidBeltCount = 1;
    public const int MaximumGasCloudCount = 10;
    public const int MaximumAbnormalGravityCount = 1;
    public const int MaximumCircleCount = 5;
    // ORIGINAL_STATIC: FUN_004E1F70 indexes four parallel seven-entry tables
    // (fs000..006, fs_glow_000..006, s000..006 and l000..006). Entries 7..9
    // are null even though the surrounding guard is wider.
    public const ushort MaximumObstacleModelFile = 6;

    private const int TacticalBaseRecordSize = 28;
    private const int BasePositionRecordSize = 16;
    private const int TacticalUnitShipRecordSize = 47;
    private const int TacticalCorpsRecordSize = 55;
    private const int UnitPositionRecordSize = 20;

    public static OriginalTacticalCorpsRecord CreatePlayableTacticalCorps(uint id) =>
        new(
            Id: id,
            Mission: 0,
            TargetKind: 0,
            Target: 0,
            CommandRange: 100,
            TacticsChief: 1,
            File: 0,
            PowerMove: 20,
            PowerWarp: 10,
            PowerSensor: 10,
            PowerBeam: 20,
            PowerGun: 20,
            PowerShield: [4, 4, 3, 3, 3, 3],
            FillBeam: 100,
            FillGun: 100,
            FillShield: [100, 100, 100, 100, 100, 100],
            DamagedShield: [0, 0, 0, 0, 0, 0]);

    public static OriginalTacticalCorpsResponse ProjectTacticalCorps(
        IReadOnlyList<uint> requestedIds,
        IReadOnlyList<OriginalTacticalCorpsRecord> available)
    {
        ArgumentNullException.ThrowIfNull(requestedIds);
        ArgumentNullException.ThrowIfNull(available);
        if (requestedIds.Count == 0)
        {
            return new OriginalTacticalCorpsResponse(
                Array.Empty<OriginalTacticalCorpsRecord>());
        }
        var requested = requestedIds.ToHashSet();
        return new OriginalTacticalCorpsResponse(
            available.Where(record => requested.Contains(record.Id)).ToArray());
    }

    public static bool IsTacticalSceneRequestType(ushort type) => type is
        TacticalCharactersRequestType or
        TacticalCorpsRequestType or
        TacticalFillShieldRequestType or
        TacticalBasesRequestType or
        TacticalObstaclesRequestType or
        UnitPositionsRequestType or
        BasePositionsRequestType;

    public static bool TryDecodeTacticalSceneRequest(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalSceneRequest request)
    {
        request = default;
        if (payload.Length < sizeof(ushort))
        {
            return false;
        }
        var type = BinaryPrimitives.ReadUInt16BigEndian(payload);
        if (type == TacticalCharactersRequestType)
        {
            if (payload.Length != sizeof(ushort) * 2) return false;
            request = new OriginalTacticalSceneRequest(
                type,
                BinaryPrimitives.ReadUInt16BigEndian(payload[sizeof(ushort)..]),
                Array.Empty<uint>());
            return true;
        }
        if (type == TacticalObstaclesRequestType)
        {
            if (payload.Length != sizeof(ushort) + sizeof(uint)) return false;
            request = new OriginalTacticalSceneRequest(
                type,
                BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]),
                Array.Empty<uint>());
            return true;
        }

        int count;
        int cursor;
        int maximum;
        if (type is TacticalCorpsRequestType or
            TacticalFillShieldRequestType or
            UnitPositionsRequestType)
        {
            if (payload.Length < sizeof(ushort) * 2) return false;
            count = BinaryPrimitives.ReadUInt16BigEndian(payload[sizeof(ushort)..]);
            cursor = sizeof(ushort) * 2;
            maximum = MaximumTacticalUnitShipCount;
        }
        else if (type is TacticalBasesRequestType or BasePositionsRequestType)
        {
            if (payload.Length < sizeof(ushort) + sizeof(byte)) return false;
            count = payload[sizeof(ushort)];
            cursor = sizeof(ushort) + sizeof(byte);
            maximum = type == TacticalBasesRequestType
                ? MaximumTacticalBaseCount
                : MaximumBasePositionCount;
        }
        else
        {
            return false;
        }

        if (count > maximum || payload.Length != cursor + count * sizeof(uint))
        {
            return false;
        }
        var ids = new uint[count];
        for (var index = 0; index < ids.Length; index++)
        {
            ids[index] = ReadUInt32(payload, ref cursor);
        }
        request = new OriginalTacticalSceneRequest(type, 0, ids);
        return true;
    }

    public static byte[] EncodeTacticalCharacters(IReadOnlyList<uint> characterIds)
    {
        ArgumentNullException.ThrowIfNull(characterIds);
        EnsureCountAtMost(
            characterIds.Count,
            MaximumTacticalUnitShipCount,
            nameof(characterIds));
        var frame = Allocate(
            TacticalCharactersResponseType,
            sizeof(ushort) + characterIds.Count * sizeof(uint));
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        var cursor = 0;
        WriteUInt16(body, ref cursor, checked((ushort)characterIds.Count));
        foreach (var id in characterIds) WriteUInt32(body, ref cursor, id);
        return frame;
    }

    public static byte[] EncodeTacticalCorps(OriginalTacticalCorpsResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.Records);
        EnsureCountAtMost(
            response.Records.Count,
            MaximumTacticalUnitShipCount,
            nameof(response));
        var frame = Allocate(
            TacticalCorpsResponseType,
            sizeof(ushort) + response.Records.Count * TacticalCorpsRecordSize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        var cursor = 0;
        WriteUInt16(body, ref cursor, checked((ushort)response.Records.Count));
        foreach (var record in response.Records)
        {
            EnsureSix(record.PowerShield, nameof(record.PowerShield));
            EnsureSix(record.FillShield, nameof(record.FillShield));
            EnsureSix(record.DamagedShield, nameof(record.DamagedShield));

            // ORIGINAL_STATIC: Input_ResponseTacticsInformationCorps::
            // input_from_stream at 0x00422D80 expands this packed 55-byte
            // record into a 0x3C-byte aligned client record. The adjacent
            // logger at 0x00423560 supplies the semantic field names.
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Mission;
            body[cursor++] = record.TargetKind;
            WriteUInt32(body, ref cursor, record.Target);
            WriteSingle(body, ref cursor, record.CommandRange);
            body[cursor++] = record.TacticsChief;
            body[cursor++] = record.File;
            body[cursor++] = record.PowerMove;
            body[cursor++] = record.PowerWarp;
            body[cursor++] = record.PowerSensor;
            body[cursor++] = record.PowerBeam;
            body[cursor++] = record.PowerGun;
            foreach (var value in record.PowerShield) body[cursor++] = value;
            WriteUInt16(body, ref cursor, record.FillBeam);
            WriteUInt16(body, ref cursor, record.FillGun);
            foreach (var value in record.FillShield) WriteUInt16(body, ref cursor, value);
            foreach (var value in record.DamagedShield) WriteUInt16(body, ref cursor, value);
        }
        return frame;
    }

    public static byte[] EncodeEmptyTacticalInformation(ushort responseType)
    {
        if (responseType is not TacticalCorpsResponseType and
            not TacticalFillShieldResponseType)
        {
            throw new ArgumentOutOfRangeException(nameof(responseType));
        }
        return Allocate(responseType, sizeof(ushort));
    }

    public static byte[] EncodeEmptyObstacles(uint grid)
    {
        // ORIGINAL_STATIC: InformationObstacle is grid:u32 followed by five
        // independently bounded u8-count arrays. The authored battlefield has
        // no tactical obstacles, so each array takes its valid zero-count path.
        return EncodeObstacles(new OriginalTacticalObstacleResponse(
            grid,
            Array.Empty<OriginalBlackHoleObstacle>(),
            Array.Empty<OriginalAsteroidBeltObstacle>(),
            Array.Empty<OriginalGasCloudObstacle>(),
            Array.Empty<OriginalAbnormalGravityObstacle>(),
            Array.Empty<OriginalCircleObstacle>()));
    }

    public static byte[] EncodeObstacles(OriginalTacticalObstacleResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.BlackHoles);
        ArgumentNullException.ThrowIfNull(response.AsteroidBelts);
        ArgumentNullException.ThrowIfNull(response.GasClouds);
        ArgumentNullException.ThrowIfNull(response.AbnormalGravities);
        ArgumentNullException.ThrowIfNull(response.Circles);
        EnsureCountAtMost(response.BlackHoles.Count, MaximumBlackHoleCount, nameof(response));
        EnsureCountAtMost(response.AsteroidBelts.Count, MaximumAsteroidBeltCount, nameof(response));
        EnsureCountAtMost(response.GasClouds.Count, MaximumGasCloudCount, nameof(response));
        EnsureCountAtMost(
            response.AbnormalGravities.Count,
            MaximumAbnormalGravityCount,
            nameof(response));
        EnsureCountAtMost(response.Circles.Count, MaximumCircleCount, nameof(response));

        const int blackHoleSize = 15;
        const int asteroidBeltSize = 15;
        const int gasCloudSize = 24;
        const int abnormalGravitySize = 15;
        const int circleSize = 23;
        var bodySize = sizeof(uint) + 5 +
            response.BlackHoles.Count * blackHoleSize +
            response.AsteroidBelts.Count * asteroidBeltSize +
            response.GasClouds.Count * gasCloudSize +
            response.AbnormalGravities.Count * abnormalGravitySize +
            response.Circles.Count * circleSize;
        var frame = Allocate(TacticalObstaclesResponseType, bodySize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        var cursor = 0;
        WriteUInt32(body, ref cursor, response.Grid);
        body[cursor++] = checked((byte)response.BlackHoles.Count);
        foreach (var record in response.BlackHoles)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Kind;
            WriteUInt16(body, ref cursor, record.ModelFile);
            WriteSingle(body, ref cursor, record.MaxSuctionSpeed);
            WriteSingle(body, ref cursor, record.Radius);
        }
        body[cursor++] = checked((byte)response.AsteroidBelts.Count);
        foreach (var record in response.AsteroidBelts)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Kind;
            WriteUInt16(body, ref cursor, record.ModelFile);
            WriteSingle(body, ref cursor, record.Radius);
            WriteSingle(body, ref cursor, record.Range);
        }
        body[cursor++] = checked((byte)response.GasClouds.Count);
        foreach (var record in response.GasClouds)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Kind;
            WriteUInt16(body, ref cursor, record.ModelFile);
            WriteSingle(body, ref cursor, record.RevolutionRadius);
            WriteUInt32(body, ref cursor, record.RevolutionCycle);
            body[cursor++] = record.RevolutionDirection;
            WriteSingle(body, ref cursor, record.RevolutionInitialAngle);
            WriteSingle(body, ref cursor, record.Radius);
        }
        body[cursor++] = checked((byte)response.AbnormalGravities.Count);
        foreach (var record in response.AbnormalGravities)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Kind;
            WriteUInt16(body, ref cursor, record.ModelFile);
            WriteSingle(body, ref cursor, record.GravityUpRange);
            WriteSingle(body, ref cursor, record.GravityDownRange);
        }
        body[cursor++] = checked((byte)response.Circles.Count);
        foreach (var record in response.Circles)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Kind;
            WriteUInt16(body, ref cursor, record.ModelFile);
            WriteSingle(body, ref cursor, record.X);
            WriteSingle(body, ref cursor, record.Y);
            WriteSingle(body, ref cursor, record.Z);
            WriteSingle(body, ref cursor, record.Radius);
        }
        return frame;
    }

    public static bool TryDecodeObstacles(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalObstacleResponse response)
    {
        response = default;
        if (payload.Length < sizeof(ushort) + sizeof(uint) + 5 ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != TacticalObstaclesResponseType)
        {
            return false;
        }

        var cursor = sizeof(ushort);
        if (!TryReadUInt32(payload, ref cursor, out var grid) ||
            !TryReadByte(payload, ref cursor, out var blackHoleCount) ||
            blackHoleCount > MaximumBlackHoleCount)
        {
            return false;
        }
        var blackHoles = new OriginalBlackHoleObstacle[blackHoleCount];
        for (var index = 0; index < blackHoles.Length; index++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var id) ||
                !TryReadByte(payload, ref cursor, out var kind) ||
                !TryReadUInt16(payload, ref cursor, out var modelFile) ||
                !TryReadSingle(payload, ref cursor, out var maxSuctionSpeed) ||
                !TryReadSingle(payload, ref cursor, out var radius)) return false;
            blackHoles[index] = new(id, kind, modelFile, maxSuctionSpeed, radius);
        }

        if (!TryReadByte(payload, ref cursor, out var asteroidCount) ||
            asteroidCount > MaximumAsteroidBeltCount) return false;
        var asteroidBelts = new OriginalAsteroidBeltObstacle[asteroidCount];
        for (var index = 0; index < asteroidBelts.Length; index++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var id) ||
                !TryReadByte(payload, ref cursor, out var kind) ||
                !TryReadUInt16(payload, ref cursor, out var modelFile) ||
                !TryReadSingle(payload, ref cursor, out var radius) ||
                !TryReadSingle(payload, ref cursor, out var range)) return false;
            asteroidBelts[index] = new(id, kind, modelFile, radius, range);
        }

        if (!TryReadByte(payload, ref cursor, out var gasCloudCount) ||
            gasCloudCount > MaximumGasCloudCount) return false;
        var gasClouds = new OriginalGasCloudObstacle[gasCloudCount];
        for (var index = 0; index < gasClouds.Length; index++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var id) ||
                !TryReadByte(payload, ref cursor, out var kind) ||
                !TryReadUInt16(payload, ref cursor, out var modelFile) ||
                !TryReadSingle(payload, ref cursor, out var revolutionRadius) ||
                !TryReadUInt32(payload, ref cursor, out var revolutionCycle) ||
                !TryReadByte(payload, ref cursor, out var revolutionDirection) ||
                !TryReadSingle(payload, ref cursor, out var initialAngle) ||
                !TryReadSingle(payload, ref cursor, out var radius)) return false;
            gasClouds[index] = new(
                id,
                kind,
                modelFile,
                revolutionRadius,
                revolutionCycle,
                revolutionDirection,
                initialAngle,
                radius);
        }

        if (!TryReadByte(payload, ref cursor, out var abnormalCount) ||
            abnormalCount > MaximumAbnormalGravityCount) return false;
        var abnormalGravities = new OriginalAbnormalGravityObstacle[abnormalCount];
        for (var index = 0; index < abnormalGravities.Length; index++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var id) ||
                !TryReadByte(payload, ref cursor, out var kind) ||
                !TryReadUInt16(payload, ref cursor, out var modelFile) ||
                !TryReadSingle(payload, ref cursor, out var upRange) ||
                !TryReadSingle(payload, ref cursor, out var downRange)) return false;
            abnormalGravities[index] = new(id, kind, modelFile, upRange, downRange);
        }

        if (!TryReadByte(payload, ref cursor, out var circleCount) ||
            circleCount > MaximumCircleCount) return false;
        var circles = new OriginalCircleObstacle[circleCount];
        for (var index = 0; index < circles.Length; index++)
        {
            if (!TryReadUInt32(payload, ref cursor, out var id) ||
                !TryReadByte(payload, ref cursor, out var kind) ||
                !TryReadUInt16(payload, ref cursor, out var modelFile) ||
                !TryReadSingle(payload, ref cursor, out var x) ||
                !TryReadSingle(payload, ref cursor, out var y) ||
                !TryReadSingle(payload, ref cursor, out var z) ||
                !TryReadSingle(payload, ref cursor, out var radius)) return false;
            circles[index] = new(id, kind, modelFile, x, y, z, radius);
        }

        if (cursor != payload.Length) return false;
        response = new OriginalTacticalObstacleResponse(
            grid,
            blackHoles,
            asteroidBelts,
            gasClouds,
            abnormalGravities,
            circles);
        return true;
    }

    public static byte[] EncodeUnitPositions(OriginalTacticalUnitShipResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.Records);
        EnsureCountAtMost(
            response.Records.Count,
            MaximumTacticalUnitShipCount,
            nameof(response));
        var frame = Allocate(
            UnitPositionsResponseType,
            sizeof(ushort) + response.Records.Count * UnitPositionRecordSize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        var cursor = 0;
        WriteUInt16(body, ref cursor, checked((ushort)response.Records.Count));
        foreach (var record in response.Records)
        {
            WriteUInt32(body, ref cursor, record.Id);
            WriteSingle(body, ref cursor, record.X);
            WriteSingle(body, ref cursor, record.Y);
            WriteSingle(body, ref cursor, record.Z);
            WriteSingle(body, ref cursor, record.Direction);
        }
        return frame;
    }

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

    public static byte[] EncodeTacticalUnitShipIdRequest(
        OriginalTacticalUnitShipIdRequest request)
    {
        ArgumentNullException.ThrowIfNull(request.Ids);
        EnsureCountAtMost(
            request.Ids.Count,
            MaximumTacticalUnitShipCount,
            nameof(request));
        var payload = new byte[
            sizeof(ushort) + sizeof(ushort) + request.Ids.Count * sizeof(uint)];
        BinaryPrimitives.WriteUInt16BigEndian(payload, TacticalUnitShipsRequestType);
        BinaryPrimitives.WriteUInt16BigEndian(
            payload.AsSpan(sizeof(ushort)),
            checked((ushort)request.Ids.Count));
        var cursor = sizeof(ushort) + sizeof(ushort);
        foreach (var id in request.Ids)
        {
            BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(cursor), id);
            cursor += sizeof(uint);
        }

        return payload;
    }

    public static bool TryDecodeTacticalUnitShipIdRequest(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalUnitShipIdRequest request)
    {
        request = default;
        if (payload.Length < sizeof(ushort) + sizeof(ushort) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != TacticalUnitShipsRequestType)
        {
            return false;
        }

        var count = BinaryPrimitives.ReadUInt16BigEndian(payload[sizeof(ushort)..]);
        var expectedLength = sizeof(ushort) + sizeof(ushort) + count * sizeof(uint);
        if (count > MaximumTacticalUnitShipCount || payload.Length != expectedLength)
        {
            return false;
        }

        var ids = new uint[count];
        var cursor = sizeof(ushort) + sizeof(ushort);
        for (var index = 0; index < ids.Length; index++)
        {
            ids[index] = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
            cursor += sizeof(uint);
        }

        request = new OriginalTacticalUnitShipIdRequest(ids);
        return true;
    }

    public static OriginalTacticalUnitShipResponse ProjectTacticalUnitShips(
        OriginalTacticalUnitShipIdRequest request,
        IReadOnlyList<OriginalTacticalUnitShipRecord> available)
    {
        ArgumentNullException.ThrowIfNull(request.Ids);
        ArgumentNullException.ThrowIfNull(available);
        var requestedIds = request.Ids.ToHashSet();
        return new OriginalTacticalUnitShipResponse(
            available.Where(record => requestedIds.Contains(record.Id)).ToArray());
    }

    public static OriginalTacticalUnitShipRecord CreateAuthoredTacticalUnitShip(
        uint unitId,
        uint characterId) =>
        new(
            Id: unitId,
            Morale: 0,
            Confusion: 0,
            Character: characterId,
            X: 0,
            Y: 0,
            Z: 0,
            Direction: 0,
            DetachmentLeader: 0,
            DetachmentX: 0,
            DetachmentY: 0,
            DetachmentZ: 0,
            DetachmentDirection: 0,
            Search: 0);

    public static byte[] EncodeTacticalUnitShips(
        OriginalTacticalUnitShipResponse response)
    {
        ArgumentNullException.ThrowIfNull(response.Records);
        EnsureCountAtMost(
            response.Records.Count,
            MaximumTacticalUnitShipCount,
            nameof(response));
        var frame = Allocate(
            TacticalUnitShipsResponseType,
            sizeof(ushort) + response.Records.Count * TacticalUnitShipRecordSize);
        var body = frame.AsSpan(OriginalLoginCodec.MessageCodeSize + sizeof(ushort));
        var cursor = 0;
        WriteUInt16(body, ref cursor, checked((ushort)response.Records.Count));
        foreach (var record in response.Records)
        {
            WriteUInt32(body, ref cursor, record.Id);
            body[cursor++] = record.Morale;
            body[cursor++] = record.Confusion;
            WriteUInt32(body, ref cursor, record.Character);
            WriteSingle(body, ref cursor, record.X);
            WriteSingle(body, ref cursor, record.Y);
            WriteSingle(body, ref cursor, record.Z);
            WriteSingle(body, ref cursor, record.Direction);
            WriteUInt32(body, ref cursor, record.DetachmentLeader);
            WriteSingle(body, ref cursor, record.DetachmentX);
            WriteSingle(body, ref cursor, record.DetachmentY);
            WriteSingle(body, ref cursor, record.DetachmentZ);
            WriteSingle(body, ref cursor, record.DetachmentDirection);
            body[cursor++] = record.Search;
        }

        return frame;
    }

    public static bool TryDecodeTacticalUnitShips(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalUnitShipResponse response)
    {
        response = default;
        if (payload.Length < sizeof(ushort) + sizeof(ushort) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != TacticalUnitShipsResponseType)
        {
            return false;
        }

        var count = BinaryPrimitives.ReadUInt16BigEndian(payload[sizeof(ushort)..]);
        var expectedLength = sizeof(ushort) + sizeof(ushort) + count * TacticalUnitShipRecordSize;
        if (count > MaximumTacticalUnitShipCount || payload.Length != expectedLength)
        {
            return false;
        }

        var records = new OriginalTacticalUnitShipRecord[count];
        var cursor = sizeof(ushort) + sizeof(ushort);
        for (var index = 0; index < records.Length; index++)
        {
            records[index] = new OriginalTacticalUnitShipRecord(
                ReadUInt32(payload, ref cursor),
                payload[cursor++],
                payload[cursor++],
                ReadUInt32(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadUInt32(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                payload[cursor++]);
        }

        response = new OriginalTacticalUnitShipResponse(records);
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

    private static void EnsureSix<T>(IReadOnlyList<T>? values, string parameterName)
    {
        ArgumentNullException.ThrowIfNull(values, parameterName);
        if (values.Count != 6)
        {
            throw new ArgumentOutOfRangeException(
                parameterName,
                values.Count,
                "The original tactical corps record requires exactly six values.");
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

    private static bool TryReadByte(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out byte value)
    {
        value = 0;
        if ((uint)cursor >= (uint)payload.Length) return false;
        value = payload[cursor++];
        return true;
    }

    private static bool TryReadUInt16(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out ushort value)
    {
        value = 0;
        if (payload.Length - cursor < sizeof(ushort)) return false;
        value = BinaryPrimitives.ReadUInt16BigEndian(payload[cursor..]);
        cursor += sizeof(ushort);
        return true;
    }

    private static bool TryReadUInt32(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out uint value)
    {
        value = 0;
        if (payload.Length - cursor < sizeof(uint)) return false;
        value = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        return true;
    }

    private static bool TryReadSingle(
        ReadOnlySpan<byte> payload,
        ref int cursor,
        out float value)
    {
        value = 0;
        if (!TryReadUInt32(payload, ref cursor, out var bits)) return false;
        value = BitConverter.UInt32BitsToSingle(bits);
        return true;
    }
}
