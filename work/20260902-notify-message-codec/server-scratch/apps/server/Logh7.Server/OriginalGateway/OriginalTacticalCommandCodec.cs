using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalTacticalWarpCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds);

public readonly record struct OriginalTacticalWarpedNotification(
    uint Time,
    uint Grid,
    uint Base,
    ushort Mode,
    IReadOnlyList<uint> UnitIds);

public readonly record struct OriginalTacticalWarpDecision(
    bool Accepted,
    OriginalTacticalWarpedNotification? Notification,
    string? ErrorCode);

public readonly record struct OriginalTacticalVector(float X, float Y, float Z);

public readonly record struct OriginalTacticalReverseUnit(uint UnitId, float Direction);

public readonly record struct OriginalTacticalReverseShipCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<OriginalTacticalReverseUnit> Units,
    byte Direction);

public readonly record struct OriginalTacticalTurnedNotification(uint Time, uint UnitId, float Direction);

// ORIGINAL_OBSERVED 2026-09-09 (run 20260906T181000Z-departure-v124): the
// native 旋回 widget emits application type 0x0401 with one entry per unit
// carrying the unit's current heading and the requested heading. Captured
// body: 0401 0000007B 00000000 00000002 01 00000002 00000000 BFC4B9F4.
// The two header words after the time are preserved, not interpreted.
public readonly record struct OriginalTacticalTurnUnit(uint UnitId, float From, float To);
public readonly record struct OriginalTacticalTurnShipCommand(
    uint Time, uint Header1, uint Header2, IReadOnlyList<OriginalTacticalTurnUnit> Units);

public readonly record struct OriginalTacticalMovedShipNotification(
    uint Time, uint UnitId, float Direction, float X, float Y, float Z, sbyte Route);

public readonly record struct OriginalTacticalMoveUnit(
    uint UnitId,
    float Direction,
    float X,
    float Y,
    float Z);

public readonly record struct OriginalTacticalMoveShipCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<OriginalTacticalMoveUnit> Units,
    float Velocity,
    float ToDirection,
    IReadOnlyList<OriginalTacticalVector> Destinations);

public readonly record struct OriginalTacticalMoveDecision(
    bool Accepted,
    OriginalTacticalUnitShipRecord? State,
    string? ErrorCode);

public readonly record struct OriginalTacticalAttackShipCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds,
    byte Kind,
    uint TargetId);

public readonly record struct OriginalTacticalShootShipCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds,
    byte Arms,
    byte TargetKind,
    uint TargetId);

/// <summary>
/// 任務 (0x0421). Same shape as <see cref="OriginalTacticalShootShipCommand"/>:
/// the selected units under the player's command, then the mission the palette
/// chose and the object it was pointed at.
/// </summary>
public readonly record struct OriginalTacticalMissionCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds,
    byte Mission,
    byte TargetKind,
    uint TargetId);

/// <summary>
/// 修理 (0x0413) and 補給 (0x0414): the vessel that performs the command and the
/// unit it is performed on. ORIGINAL_STATIC: the client's own loggers
/// <c>_INF:CommandRepairFleet#</c> (0x00497680) and
/// <c>_INF:CommandSupplyFleet#</c> (0x00497770) print the identical layout -
/// <c>time / wait / id / unit / target</c> and
/// <c>time / wait / id / transport_unit / target</c> - so one record reads both.
/// </summary>
public readonly record struct OriginalTacticalSupportCommand(
    uint Time,
    uint Wait,
    uint Order,
    uint Unit,
    uint TargetId);

/// <summary>
/// 隊列変更 (0x040D): where each unit is to stand, and which formation it is.
/// </summary>
public readonly record struct OriginalTacticalFileFleetCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<OriginalTacticalMoveUnit> Positions,
    byte Kind);

/// <summary>
/// 態勢変更 (0x0411): the units whose posture changes, the posture, and the base
/// the change is made at.
/// </summary>
public readonly record struct OriginalTacticalChangeModeCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds,
    byte Kind,
    uint TargetBase);

public readonly record struct OriginalTacticalStopCommand(
    uint Time,
    uint Wait,
    uint Order,
    IReadOnlyList<uint> UnitIds,
    IReadOnlyList<uint> BaseIds);

public readonly record struct OriginalTacticalTargetDecision(
    bool Accepted,
    string? ErrorCode,
    uint TargetId = 0);

public readonly record struct OriginalTacticalAttackedNotification(
    uint Time,
    uint AttackerId,
    byte Arms,
    byte TargetKind,
    uint TargetId,
    ushort Damaged,
    ushort Destroyed,
    byte ShieldDirection,
    ushort DamagedShield,
    byte Morale);

public readonly record struct OriginalTacticalDamageState(
    ushort Damaged,
    ushort Destroyed);

public static class OriginalTacticalCommandAuthority
{
    /// <summary>The client's own name for a 射撃 weapon selection, or null.</summary>
    /// <remarks>
    /// ORIGINAL_OBSERVED: pressing 射撃 opens a sub-panel (palette base 33, page 2)
    /// whose three cells name themselves 「ビーム兵装」「ガン兵装」「ミサイル兵装」 in that
    /// order - read live with a cursor-only sweep, no command issued
    /// (evidence/palette-named-by-the-client-v405.md). That confirms the 0/1/2
    /// selection this method already resolved from the submenu indices.
    /// </remarks>
    public static string? ShotArmsName(byte selection) => selection switch
    {
        0 => "ビーム兵装",
        1 => "ガン兵装",
        2 => "ミサイル兵装",
        _ => null,
    };

    public static byte? ResolveShotArms(byte selection, OriginalStaticUnitShipCapabilities capabilities) =>
        // 0050D230 submenu 0x21/22/23 -> 0/1/2; 004B4110 writes that
        // family into CommandShootShip. NotifyAttackedShip instead takes the
        // actual template arms ID (004A6400, then 004C7790 effect lookup).
        // The client's own sub-panel names those three: see ShotArmsName.
        selection switch
        {
            0 when capabilities.BeamPower > 0 => capabilities.BeamArms,
            1 when capabilities.GunPower > 0 => capabilities.GunArms,
            2 when capabilities.MissilePower > 0 => capabilities.MissileArms,
            _ => null,
        };

    public static OriginalTacticalAttackedNotification CreateDamageNotification(
        uint time, uint attackerId, byte arms, byte targetKind, uint targetId,
        OriginalTacticalDamageState state, byte targetMorale) =>
        // 004A6400: damage/destroy/direction_shield/damaged_shield/morale.
        // Keep cumulative counts independent and never substitute a death flag
        // for morale (v13 live: server destroy=0, native survivors75/morale0).
        new(time, attackerId, arms, targetKind, targetId,
            state.Damaged, state.Destroyed, 0, 0, targetMorale);

    public static OriginalTacticalDamageState ApplyAuthoredDamage(
        OriginalTacticalDamageState state, ushort unitNumber = 100)
    {
        const ushort authoredDamagePerHit = 25;
        if (unitNumber == 0 || state.Damaged > unitNumber || state.Destroyed > state.Damaged)
            throw new ArgumentOutOfRangeException(nameof(unitNumber));
        // AUTHORED_PLACEHOLDER, not original hit probability or an Existence/HP
        // interpretation: a singleton transitions normal -> damaged -> destroyed.
        // The native wire fields remain cumulative hull counts (0/1).
        if (unitNumber == 1)
            return state.Damaged == 0 ? new(1, 0) : new(1, 1);
        var authoredDestroyedThreshold = unitNumber;
        var damaged = checked((ushort)Math.Min(
            authoredDestroyedThreshold,
            state.Damaged + authoredDamagePerHit));
        return new OriginalTacticalDamageState(
            damaged,
            // ORIGINAL_STATIC: 004C0DF0 computes remaining = template Number
            // minus destroy. It is a cumulative count, never a boolean.
            // NEW DESIGN: 25 damage per hit, capped by the target complement.
            // This is not recovered per-hull HP or the original weapon formula.
            checked((ushort)(damaged >= authoredDestroyedThreshold ? authoredDestroyedThreshold : state.Destroyed)));
    }

    public static OriginalTacticalTargetDecision AuthorizeTargetCommand(
        IReadOnlyList<uint> unitIds,
        uint controlledUnitId,
        uint targetId)
    {
        if (unitIds is null ||
            unitIds.Count != 1 ||
            unitIds[0] != controlledUnitId)
        {
            return new OriginalTacticalTargetDecision(
                false,
                "TACTICAL_UNIT_NOT_CONTROLLED");
        }
        if (targetId == 0 || targetId == controlledUnitId)
        {
            return new OriginalTacticalTargetDecision(
                false,
                "TACTICAL_TARGET_INVALID");
        }
        return new OriginalTacticalTargetDecision(true, null, targetId);
    }

    public static OriginalTacticalTargetDecision AuthorizeAttackCommand(
        IReadOnlyList<uint> unitIds,
        uint controlledUnitId,
        uint requestedTargetId,
        uint automaticTargetId,
        byte attackKind = 1)
    {
        if (unitIds is null ||
            unitIds.Count != 1 ||
            unitIds[0] != controlledUnitId)
        {
            return new OriginalTacticalTargetDecision(
                false,
                "TACTICAL_UNIT_NOT_CONTROLLED");
        }

        // ORIGINAL_STATIC: 0050D230 maps submenu indices 0x1E/1F/20 to UI
        // modes 1/2/3; 004B4110 serializes them as wire kinds 1/2/0.
        // Cease fire must work even with no enemy and must never select a
        // damage target. Ownership is still checked above.
        if (attackKind > 2)
        {
            return new OriginalTacticalTargetDecision(
                false,
                "TACTICAL_ATTACK_KIND_INVALID");
        }
        if (attackKind == 0)
        {
            return new OriginalTacticalTargetDecision(true, null);
        }

        // ORIGINAL_RUNTIME: the continuous/volley attack submenu emits
        // CommandAttackShip with target=0 because it means automatic attack.
        // The authority resolves that sentinel to a concrete enemy before
        // applying damage; ShootShip continues to require an explicit target.
        var targetId = requestedTargetId == 0
            ? automaticTargetId
            : requestedTargetId;
        if (targetId == 0 || targetId == controlledUnitId)
        {
            return new OriginalTacticalTargetDecision(
                false,
                "TACTICAL_TARGET_INVALID");
        }
        return new OriginalTacticalTargetDecision(true, null, targetId);
    }

    public static OriginalTacticalMoveDecision AuthorizeMoveShip(
        OriginalTacticalMoveShipCommand command,
        uint controlledUnitId,
        OriginalTacticalUnitShipRecord state)
    {
        if (state.Id != controlledUnitId ||
            command.Units is null ||
            command.Units.Count != 1 ||
            command.Units[0].UnitId != controlledUnitId ||
            command.Destinations is null ||
            command.Destinations.Count != 1)
        {
            return new OriginalTacticalMoveDecision(
                false,
                null,
                "TACTICAL_UNIT_NOT_CONTROLLED");
        }

        var destination = command.Destinations[0];
        var unit = command.Units[0];
        // Authority boundary: never store or echo nonfinite motion into the
        // native trajectory consumer. This is input validation, not a claim
        // that the original service accepted/rejected the same malformed bits.
        if (!float.IsFinite(command.Velocity) || !float.IsFinite(command.ToDirection) ||
            !float.IsFinite(unit.Direction) || !float.IsFinite(unit.X) ||
            !float.IsFinite(unit.Y) || !float.IsFinite(unit.Z) ||
            !float.IsFinite(destination.X) || !float.IsFinite(destination.Y) ||
            !float.IsFinite(destination.Z))
        {
            return new OriginalTacticalMoveDecision(false, null, "TACTICAL_MOVE_NONFINITE");
        }
        return new OriginalTacticalMoveDecision(
            true,
            state with
            {
                X = destination.X,
                Y = destination.Y,
                Z = destination.Z,
                Direction = command.ToDirection,
            },
            null);
    }

    public static OriginalTacticalWarpDecision AuthorizeWarp(
        OriginalTacticalWarpCommand command,
        uint controlledUnitId,
        uint grid,
        uint @base,
        ushort mode,
        uint authorityTick = 0)
    {
        if (command.UnitIds is null ||
            command.UnitIds.Count != 1 ||
            command.UnitIds[0] != controlledUnitId)
        {
            return new OriginalTacticalWarpDecision(
                false,
                null,
                "TACTICAL_UNIT_NOT_CONTROLLED");
        }

        return new OriginalTacticalWarpDecision(
            true,
            new OriginalTacticalWarpedNotification(
                // 004B4500 leaves request Time/Wait uninitialized. Only the
                // authority may date completion. Zero remains the queue's
                // immediate sentinel for callers without a clock; this does
                // not claim that retreat preparation takes zero time.
                authorityTick,
                grid,
                @base,
                mode,
                command.UnitIds.ToArray()),
            null);
    }
}

public enum OriginalTacticalTransitionStep
{
    WorldBootstrap,
    WorldRefresh
}

public sealed class OriginalTacticalTransitionGate
{
    private bool _worldBootstrapServed;

    public OriginalTacticalTransitionStep OnWorldInitializeRequest()
    {
        if (_worldBootstrapServed)
        {
            return OriginalTacticalTransitionStep.WorldRefresh;
        }

        _worldBootstrapServed = true;
        return OriginalTacticalTransitionStep.WorldBootstrap;
    }
}

public readonly record struct OriginalChangeAuthorityCommand(
    uint Time, uint Wait, uint RequestId, IReadOnlyList<uint> UnitIds, uint TargetId);

public static class OriginalTacticalCommandCodec
{
    public static bool TryDecodeChangeAuthorityCommand(
        ReadOnlySpan<byte> payload, out OriginalChangeAuthorityCommand command)
    {
        command = default;
        // Reader004A3D60: time, wait, request id, count<=32, unit IDs, target.
        // Logger00499F20 names the trailing field target; resolving/authorizing it
        // belongs to the session, not to this wire decoder.
        if (!TryReadUnitHeader(payload, 0x0420, sizeof(uint), out var time,
                out var wait, out var requestId, out var ids, out var cursor) ||
            payload.Length != cursor + sizeof(uint))
            return false;
        command = new OriginalChangeAuthorityCommand(
            time, wait, requestId, ids, ReadUInt32(payload, ref cursor));
        return true;
    }

    public static bool TryDecodeTurnShipCommand(
        ReadOnlySpan<byte> payload, out OriginalTacticalTurnShipCommand command)
    {
        command = default;
        const int headerLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        const int unitLength = sizeof(uint) + sizeof(float) * 2;
        if (payload.Length < headerLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != TurnShipCommandType)
        {
            return false;
        }
        var count = payload[headerLength - 1];
        if (count > MaximumWarpUnitCount || payload.Length != headerLength + count * unitLength)
        {
            return false;
        }
        var units = new OriginalTacticalTurnUnit[count];
        var cursor = headerLength;
        for (var index = 0; index < count; index++)
        {
            var unitId = ReadUInt32(payload, ref cursor);
            var from = ReadSingle(payload, ref cursor);
            var to = ReadSingle(payload, ref cursor);
            // Gateway safety only. A finite heading is not an authorization.
            if (!float.IsFinite(from) || !float.IsFinite(to)) return false;
            units[index] = new OriginalTacticalTurnUnit(unitId, from, to);
        }
        command = new OriginalTacticalTurnShipCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            units);
        return true;
    }

    public static byte[] EncodeTurnShipCommand(OriginalTacticalTurnShipCommand command)
    {
        ArgumentNullException.ThrowIfNull(command.Units);
        if (command.Units.Count > MaximumWarpUnitCount)
            throw new ArgumentOutOfRangeException(nameof(command));
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint) * 3 +
            sizeof(byte) + command.Units.Count * (sizeof(uint) + sizeof(float) * 2)];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, TurnShipCommandType);
        var cursor = sizeof(ushort);
        WriteUInt32(payload, ref cursor, command.Time);
        WriteUInt32(payload, ref cursor, command.Header1);
        WriteUInt32(payload, ref cursor, command.Header2);
        payload[cursor++] = checked((byte)command.Units.Count);
        foreach (var unit in command.Units)
        {
            WriteUInt32(payload, ref cursor, unit.UnitId);
            WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(unit.From));
            WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(unit.To));
        }
        return frame;
    }

    public static bool TryDecodeReverseShipCommand(
        ReadOnlySpan<byte> payload, out OriginalTacticalReverseShipCommand command)
    {
        command = default;
        // ORIGINAL_STATIC E031: 0049C020/00493C47. The final direction byte
        // is separate from every unit's float and from expanded-struct padding.
        const int headerLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        const int unitLength = sizeof(uint) + sizeof(float);
        if (payload.Length < headerLength + sizeof(byte) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != ReverseShipCommandType)
        {
            return false;
        }
        var count = payload[headerLength - 1];
        if (count > MaximumWarpUnitCount ||
            payload.Length != headerLength + count * unitLength + sizeof(byte))
        {
            return false;
        }
        var units = new OriginalTacticalReverseUnit[count];
        var cursor = headerLength;
        for (var index = 0; index < count; index++)
        {
            var unitId = ReadUInt32(payload, ref cursor);
            var direction = ReadSingle(payload, ref cursor);
            // Gateway safety, not a recovered original enum/range rule.
            if (!float.IsFinite(direction))
            {
                return false;
            }
            units[index] = new OriginalTacticalReverseUnit(unitId, direction);
        }
        command = new OriginalTacticalReverseShipCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            units, payload[cursor]);
        // Count zero and arbitrary tail bytes are wire-valid. They do not
        // establish ownership or authorize execution of a reverse command.
        return true;
    }

    public static byte[] EncodeTurnedNotification(OriginalTacticalTurnedNotification notification)
    {
        if (!float.IsFinite(notification.Direction))
        {
            throw new ArgumentOutOfRangeException(nameof(notification));
        }
        // ORIGINAL_STATIC E031/E033: 004A5B50 -> 004BF970 consumes time,
        // unit and an f32 target heading. Preserve its bits: normalization
        // and half-turn handedness belong to motion authority, not this codec.
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint) * 3];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, TurnedNotificationType);
        var cursor = sizeof(ushort);
        WriteUInt32(payload, ref cursor, notification.Time);
        WriteUInt32(payload, ref cursor, notification.UnitId);
        WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(notification.Direction));
        return frame;
    }

    public static byte[] EncodeMovedShipNotification(OriginalTacticalMovedShipNotification notification)
    {
        if (!float.IsFinite(notification.Direction) || !float.IsFinite(notification.X) ||
            !float.IsFinite(notification.Y) || !float.IsFinite(notification.Z))
        {
            throw new ArgumentOutOfRangeException(nameof(notification));
        }
        // ORIGINAL_STATIC 004A5870/004A5A20: packed 25-byte body, not the
        // padded 28-byte expanded record. 004BF870 branches on signed route.
        // Selecting route or computing a stopping position belongs to authority.
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 25];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, MovedShipNotificationType);
        var cursor = sizeof(ushort);
        WriteUInt32(payload, ref cursor, notification.Time);
        WriteUInt32(payload, ref cursor, notification.UnitId);
        WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(notification.Direction));
        WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(notification.X));
        WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(notification.Y));
        WriteUInt32(payload, ref cursor, BitConverter.SingleToUInt32Bits(notification.Z));
        payload[cursor] = unchecked((byte)notification.Route);
        return frame;
    }

    public const ushort MovedShipNotificationType = 0x0423;

    public const ushort MoveShipCommandType = 0x0400;
    public const ushort TurnShipCommandType = 0x0401;
    public const ushort ReverseShipCommandType = 0x0403;

    /// <summary>
    /// 平行移動. constmsg group 0 row 12: 「[ 平行移動 ] 選択ユニットを平行移動する
    /// 実行待機時間48G秒 実行所要時間目標地点まで継続」 - translate the selected units to a
    /// point without turning them. Same wire body as 移動; see
    /// <see cref="TryDecodeMoveShipCommand(ReadOnlySpan{byte}, ushort, out OriginalTacticalMoveShipCommand)"/>.
    /// </summary>
    public const ushort ParallelMoveShipCommandType = 0x0402;

    /// <summary>
    /// <c>CommandStopFleet</c> - 停止 addressed to named fleets rather than to the
    /// selection. Same body as 撤退; see
    /// <see cref="TryDecodeWarpCommand(ReadOnlySpan{byte}, ushort, out OriginalTacticalWarpCommand)"/>.
    /// </summary>
    public const ushort StopFleetCommandType = 0x0415;

    /// <summary>
    /// 態勢変更. constmsg group 0 row 20:
    /// 「[ 態勢変更 ] ユニットの態勢を変更する 実行待機時間48G秒 実行所要時間240G秒」.
    /// </summary>
    public const ushort ChangeModeCommandType = 0x0411;

    /// <summary>
    /// 隊列変更. constmsg group 0 row 16:
    /// 「[ 隊列変更 ] 任意の隊列に変更する 実行待機時間48G秒 実行所要時間0G秒」 - put the units
    /// into a chosen formation.
    /// </summary>
    public const ushort FileFleetCommandType = 0x040d;

    /// <summary>
    /// 修理. constmsg group 0 row 25:
    /// 「[ 修理 ] 工作艦による修理を行う 注）旗艦の右 実行待機時間48G秒 実行所要時間1800G秒」.
    /// </summary>
    public const ushort RepairFleetCommandType = 0x0413;

    /// <summary>
    /// 補給. constmsg group 0 row 26:
    /// 「[ 補給 ] 補給艦による補給を行う 注）旗艦の左 実行待機時間48G秒 実行所要時間1800G秒」.
    /// </summary>
    public const ushort SupplyFleetCommandType = 0x0414;

    /// <summary>
    /// 出撃. constmsg group 0 row 21:
    /// 「[ 出撃 ] 駐留状態から碇泊状態へ 実行待機時間48G秒 実行所要時間240G秒」 - the row states
    /// the transition itself, so this authority does not have to choose one.
    /// Same body as 撤退; see
    /// <see cref="TryDecodeWarpCommand(ReadOnlySpan{byte}, ushort, out OriginalTacticalWarpCommand)"/>.
    /// </summary>
    public const ushort SortieCommandType = 0x0412;
    public const ushort AttackShipCommandType = 0x0405;
    public const ushort ShootShipCommandType = 0x0406;
    public const ushort StopCommandType = 0x040a;

    /// <summary>任務. See <see cref="TryDecodeMissionCommand"/> for the receipt.</summary>
    public const ushort MissionCommandType = 0x0421;
    // 所属変更 ChangeAuthority - the command that binds a fleet already in the
    // field to a commander. ORIGINAL_OBSERVED: captured live from the palette's
    // r3c2 cell; logger 0x00499F20 names its fields.
    public const ushort ChangeAuthorityCommandType = 0x0420;

    /// <summary>
    /// 緊急補給 EmergencySupply. constmsg group 0 row 27:
    /// 「[ 緊急補給 ] 緊急補給可能にする 実行待機時間48G秒 実行所要時間0G秒」. Body is the support
    /// body - a base and a unit.
    /// </summary>
    public const ushort EmergencySupplyCommandType = 0x0422;

    /// <summary>
    /// 陸戦 SortieTroops. constmsg group 0 row 17:
    /// 「[ 陸戦 ] 碇泊状態から陸戦を投下する 実行待機時間48G秒 実行所要時間240G秒」. Body is a unit
    /// list and one byte.
    /// </summary>
    /// <summary>
    /// EncourageBase - 鼓舞 aimed at a base. Body is a header and a base.
    /// </summary>
    public const ushort EncourageBaseCommandType = 0x041d;

    /// <summary>AttackTroop - a landed force assaults a base. Body is 所属変更's.</summary>
    public const ushort AttackTroopCommandType = 0x0417;

    /// <summary>StopTroop - 停止 for a landing force. Body is 撤退's.</summary>
    public const ushort StopTroopCommandType = 0x0418;

    /// <summary>RepairBase - 修理 performed by a base. Body is header, base, target[].</summary>
    public const ushort RepairBaseCommandType = 0x041b;

    /// <summary>SupplyBase - 補給 performed by a base. Same body.</summary>
    public const ushort SupplyBaseCommandType = 0x041c;

    /// <summary>MoveTroop - a landed force marches. Body is unit[] plus an area byte.</summary>
    public const ushort MoveTroopCommandType = 0x0416;

    /// <summary>StopBase - 停止 for a base. Body is header plus a base, no list.</summary>
    public const ushort StopBaseCommandType = 0x041e;

    /// <summary>要塞砲 ShootFortress - a base firing along a bearing. Body is header,
    /// base, direction; constmsg group 0 row 19 gives 48 / 1800 G秒.</summary>
    public const ushort ShootFortressCommandType = 0x0419;

    /// <summary>白兵戦 Fight - a boarding party against an enemy flagship. Body is
    /// header, unit, from_direction{x,y,z}, target; constmsg group 0 row 8 gives
    /// 48 / 240 G秒 and states 「旗艦同士」.</summary>
    public const ushort FightCommandType = 0x0407;

    /// <summary>空戦 AirBattle - carried craft sent against an enemy. Body is 攻撃's,
    /// with skill where 攻撃 has kind; constmsg group 0 row 15 gives 48 / 0 G秒.</summary>
    public const ushort AirBattleCommandType = 0x040e;

    /// <summary>Admission - a unit admitting units. Body is header, unit, target[].</summary>
    public const ushort AdmissionCommandType = 0x040b;

    /// <summary>AdmissionBase - a base admitting units. Same body with a base.</summary>
    public const ushort AdmissionBaseCommandType = 0x041a;

    /// <summary>MoveFortress - 平行移動's shape with a base in front of it.</summary>
    public const ushort MoveFortressCommandType = 0x041f;

    public const ushort SortieTroopsCommandType = 0x040f;

    /// <summary>
    /// 陸戦解除 EvacuateTroops. constmsg group 0 row 18:
    /// 「[ 陸戦解除 ] 陸戦ユニットを帰還させる 実行待機時間48G秒 実行所要時間240G秒」. Body is a unit
    /// list, captured live from the shipped client.
    /// </summary>
    public const ushort EvacuateTroopsCommandType = 0x0410;

    /// <summary>
    /// The highest mission the shipped client can send. ORIGINAL_STATIC: the
    /// mission sub-panel (palette item 0x0D, case 7 at 0x005117D6) enables exactly six icons,
    /// 0x25..0x2A, and case 26 at 0x00511821 writes <c>[0x0077A900] = 0..5</c> -
    /// one value per icon - before moving the palette to command state 0x19.
    /// </summary>
    public const byte HighestMission = 5;

    /// <summary>
    /// The one mission that carries no target. ORIGINAL_STATIC: state 0x19's
    /// handler (0x0050EFA4) dispatches the mission through the table at
    /// 0x005122C8, and mission 5's branch (0x0050F14E) calls the frame builder
    /// FUN_004B4850 directly, skipping the pick at FUN_004EF8E0 that every other
    /// mission must pass.
    /// </summary>
    public const byte MissionWithoutTarget = 5;
    public const ushort WarpCommandType = 0x0404;
    public const ushort WarpedNotificationType = 0x0425;
    public const ushort TurnedNotificationType = 0x0424;
    public const ushort AttackedNotificationType = 0x0426;
    public const ushort EndingNotificationType = 0x035a;
    public const ushort NotifyTacticsType = 0x0f1f;
    public const int MaximumWarpUnitCount = 32;

    // ORIGINAL_STATIC: 0048CB80 consumes one byte and a network-order u32;
    // 0048CC40 names these state/grid. Dispatcher padding is not on the wire.
    public static byte[] EncodeNotifyTactics(byte state, uint grid)
    {
        var frame = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(byte) + sizeof(uint)];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, NotifyTacticsType);
        var cursor = sizeof(ushort);
        payload[cursor++] = state;
        WriteUInt32(payload, ref cursor, grid);
        return frame;
    }

    public static IReadOnlyList<byte[]> EncodeTacticalDamageUpdates(
        OriginalTacticalAttackedNotification attacked,
        byte[] unitInformation,
        byte[] unitShips)
    {
        // 00433250 identifies 035A as character/session ending information.
        // A tactical casualty is not a campaign ending. Tactical completion
        // additionally requires no enemy units and, where present, objective
        // occupation (original manual chapter4); its return path is separate.
        return [EncodeAttackedNotification(attacked), unitInformation, unitShips];
    }

    public static byte[] EncodeMoveShipCommand(OriginalTacticalMoveShipCommand command) =>
        EncodeMoveShipCommand(command, MoveShipCommandType);

    /// <summary>移動 (0x0400) and 平行移動 (0x0402) share this body.</summary>
    public static byte[] EncodeMoveShipCommand(OriginalTacticalMoveShipCommand command, ushort type)
    {
        if (command.Units.Count > MaximumWarpUnitCount || command.Destinations.Count > MaximumWarpUnitCount)
            throw new ArgumentOutOfRangeException(nameof(command));
        var frame = new byte[4 + 15 + command.Units.Count * 20 + 9 + command.Destinations.Count * 12];
        var span = frame.AsSpan(4);
        BinaryPrimitives.WriteUInt16BigEndian(span, type);
        var cursor = 2;
        WriteUInt32(span, ref cursor, command.Time);
        WriteUInt32(span, ref cursor, command.Wait);
        WriteUInt32(span, ref cursor, command.Order);
        span[cursor++] = checked((byte)command.Units.Count);
        foreach (var unit in command.Units)
        {
            WriteUInt32(span, ref cursor, unit.UnitId);
            foreach (var value in new[] { unit.Direction, unit.X, unit.Y, unit.Z })
                WriteUInt32(span, ref cursor, BitConverter.SingleToUInt32Bits(value));
        }
        WriteUInt32(span, ref cursor, BitConverter.SingleToUInt32Bits(command.Velocity));
        WriteUInt32(span, ref cursor, BitConverter.SingleToUInt32Bits(command.ToDirection));
        span[cursor++] = checked((byte)command.Destinations.Count);
        foreach (var destination in command.Destinations)
            foreach (var value in new[] { destination.X, destination.Y, destination.Z })
                WriteUInt32(span, ref cursor, BitConverter.SingleToUInt32Bits(value));
        return frame;
    }

    public static bool TryDecodeMoveShipCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalMoveShipCommand command) =>
        TryDecodeMoveShipCommand(payload, MoveShipCommandType, out command);

    /// <summary>
    /// 移動 (0x0400) and 平行移動 (0x0402) are the same body. ORIGINAL_STATIC: the
    /// client's own loggers <c>_INF:CommandMoveShip#</c> (0x00492C10) and
    /// <c>_INF:CommandParallelMoveShip#</c> (0x00493850) print the identical field
    /// list - <c>time / wait / id / unit[n]{id,direction,{x,y,z}} / velocity /
    /// to_direction / to_position[n]{x,y,z}</c> - at the identical offsets. What
    /// differs is what the command means, not how it is written.
    /// </summary>
    public static bool TryDecodeMoveShipCommand(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        out OriginalTacticalMoveShipCommand command)
    {
        command = default;
        const int headerLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        const int moveUnitLength = sizeof(uint) + sizeof(float) * 4;
        const int movementTailLength = sizeof(float) * 2 + sizeof(byte);
        const int destinationLength = sizeof(float) * 3;
        if (payload.Length < headerLength + movementTailLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }

        var unitCount = payload[sizeof(ushort) + sizeof(uint) * 3];
        if (unitCount > MaximumWarpUnitCount)
        {
            return false;
        }

        var cursor = headerLength;
        var minimumAfterUnits = cursor + unitCount * moveUnitLength + movementTailLength;
        if (payload.Length < minimumAfterUnits)
        {
            return false;
        }

        var units = new OriginalTacticalMoveUnit[unitCount];
        for (var index = 0; index < units.Length; index++)
        {
            units[index] = new OriginalTacticalMoveUnit(
                ReadUInt32(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor));
        }

        var velocity = ReadSingle(payload, ref cursor);
        var toDirection = ReadSingle(payload, ref cursor);
        var destinationCount = payload[cursor++];
        if (destinationCount > MaximumWarpUnitCount ||
            payload.Length != cursor + destinationCount * destinationLength)
        {
            return false;
        }

        var destinations = new OriginalTacticalVector[destinationCount];
        for (var index = 0; index < destinations.Length; index++)
        {
            destinations[index] = new OriginalTacticalVector(
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor),
                ReadSingle(payload, ref cursor));
        }

        command = new OriginalTacticalMoveShipCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint))..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint) * 2)..]),
            units,
            velocity,
            toDirection,
            destinations);
        return true;
    }

    /// <summary>
    /// The bare 「header, unit list, one byte」 body: 陸戦 <c>SortieTroops</c>
    /// (0x040F, logger 0x00496970, <c>skill</c>) and <c>MoveTroop</c> (0x0416,
    /// logger 0x00497F10, <c>area</c>) print the identical field list.
    /// </summary>
    public static bool TryDecodeUnitListWithByte(
        ReadOnlySpan<byte> payload, ushort expectedType,
        out OriginalTacticalWarpCommand units, out byte value)
    {
        units = default;
        value = 0;
        if (!TryReadUnitHeader(payload, expectedType, sizeof(byte),
                out var time, out var wait, out var order, out var unitIds, out var cursor))
        {
            return false;
        }
        value = payload[cursor++];
        units = new OriginalTacticalWarpCommand(time, wait, order, unitIds);
        return cursor == payload.Length;
    }

    /// <summary>
    /// The 「header, unit list, one target」 body: <c>AttackTroop</c> (0x0417,
    /// logger 0x00498300) and 所属変更 <c>ChangeAuthority</c> (0x0420, logger
    /// 0x00499F20) print the identical field list. ORIGINAL_OBSERVED for 0x0420:
    /// the shipped client sent
    /// <c>0420 3B627631 00000000 00000002 01 7E00010A 00000002</c> on 2026-09-10.
    /// </summary>
    public static bool TryDecodeUnitListWithTarget(
        ReadOnlySpan<byte> payload, ushort expectedType,
        out OriginalTacticalWarpCommand units, out uint target)
    {
        units = default;
        target = 0;
        if (!TryReadUnitHeader(payload, expectedType, sizeof(uint),
                out var time, out var wait, out var order, out var unitIds, out var cursor))
        {
            return false;
        }
        target = ReadUInt32(payload, ref cursor);
        units = new OriginalTacticalWarpCommand(time, wait, order, unitIds);
        return cursor == payload.Length;
    }

    /// <summary>
    /// The 「header, one base」 body: <c>EncourageBase</c> (0x041D, logger
    /// 0x00499540) and <c>StopBase</c> (0x041E, logger 0x00499610) print
    /// <c>time / wait / id / base</c> and nothing else.
    /// </summary>
    public static bool TryDecodeBaseCommand(
        ReadOnlySpan<byte> payload, ushort expectedType, out uint time, out uint wait,
        out uint order, out uint baseId)
    {
        time = wait = order = baseId = 0;
        const int length = sizeof(ushort) + sizeof(uint) * 4;
        if (payload.Length != length ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[2..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[6..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[10..]);
        baseId = BinaryPrimitives.ReadUInt32BigEndian(payload[14..]);
        return true;
    }

    /// <summary>
    /// MoveFortress's body: a base, a velocity and the points it moves through.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC, recovered from the client's own logger (v409).
    /// <c>_INF:CommandMoveFortress#</c> prints <c>time / wait / id / base /
    /// velocity / to_position[]{x,y,z}</c> and carries
    /// 「<c>to_position_size[%d] is over than 32</c>」 - **平行移動's shape with a base
    /// in front of it**.
    /// </remarks>
    public static bool TryDecodeMoveFortressCommand(
        ReadOnlySpan<byte> payload, out uint time, out uint wait, out uint order,
        out uint baseId, out float velocity, out IReadOnlyList<OriginalTacticalVector> destinations)
    {
        time = wait = order = baseId = 0;
        velocity = 0;
        destinations = Array.Empty<OriginalTacticalVector>();
        const int headerLength = sizeof(ushort) + sizeof(uint) * 4 + sizeof(float) + sizeof(byte);
        if (payload.Length < headerLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != MoveFortressCommandType)
        {
            return false;
        }
        var count = payload[headerLength - 1];
        if (count > MaximumWarpUnitCount ||
            payload.Length != headerLength + count * sizeof(float) * 3)
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[2..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[6..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[10..]);
        baseId = BinaryPrimitives.ReadUInt32BigEndian(payload[14..]);
        var cursor = 18;
        velocity = ReadSingle(payload, ref cursor);
        if (!float.IsFinite(velocity)) return false;
        cursor = headerLength;
        var points = new OriginalTacticalVector[count];
        for (var index = 0; index < count; index++)
        {
            var x = ReadSingle(payload, ref cursor);
            var y = ReadSingle(payload, ref cursor);
            var z = ReadSingle(payload, ref cursor);
            if (!float.IsFinite(x) || !float.IsFinite(y) || !float.IsFinite(z)) return false;
            points[index] = new OriginalTacticalVector(x, y, z);
        }
        destinations = points;
        return true;
    }

    /// <summary>
    /// 白兵戦's body: one acting unit, where its party crosses from, and a target.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC, recovered from the client's own logger (v409).
    /// <c>_INF:CommandFight#</c> prints <c>time / wait / id / unit /
    /// from_direction{x,y,z} / target</c> and carries no bounds-check string, so it
    /// names one unit and one target rather than lists - which fits row 8's
    /// 「敵旗艦に白兵戦を仕掛ける 旗艦同士」 exactly.
    /// </remarks>
    public static bool TryDecodeFightCommand(
        ReadOnlySpan<byte> payload, out uint time, out uint wait, out uint order,
        out uint unit, out OriginalTacticalVector from, out uint target)
    {
        time = wait = order = unit = target = 0;
        from = default;
        const int length = sizeof(ushort) + sizeof(uint) * 4 + sizeof(float) * 3 + sizeof(uint);
        if (payload.Length != length ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != FightCommandType)
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[2..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[6..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[10..]);
        unit = BinaryPrimitives.ReadUInt32BigEndian(payload[14..]);
        var cursor = 18;
        var x = ReadSingle(payload, ref cursor);
        var y = ReadSingle(payload, ref cursor);
        var z = ReadSingle(payload, ref cursor);
        if (!float.IsFinite(x) || !float.IsFinite(y) || !float.IsFinite(z)) return false;
        from = new OriginalTacticalVector(x, y, z);
        target = ReadUInt32(payload, ref cursor);
        return true;
    }

    /// <summary>
    /// 要塞砲's body: a base and the bearing it fires along.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC, recovered from the client's own logger (v409).
    /// <c>_INF:CommandShootFortress#</c> prints <c>time / wait / id / base /
    /// direction</c>, the last with the <c>%.3f</c> the protocol's other headings
    /// use, and - alone among the base commands with 白兵戦 and StopBase - it carries
    /// **no** bounds-check string, so it has no list. That is the whole reason no
    /// target was ever found for it: **要塞砲 aims by bearing, not at a unit.**
    /// </remarks>
    public static bool TryDecodeShootFortressCommand(
        ReadOnlySpan<byte> payload, out uint time, out uint wait, out uint order,
        out uint baseId, out float direction)
    {
        time = wait = order = baseId = 0;
        direction = 0;
        const int length = sizeof(ushort) + sizeof(uint) * 4 + sizeof(float);
        if (payload.Length != length ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != ShootFortressCommandType)
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[2..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[6..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[10..]);
        baseId = BinaryPrimitives.ReadUInt32BigEndian(payload[14..]);
        var cursor = 18;
        direction = ReadSingle(payload, ref cursor);
        return float.IsFinite(direction);
    }

    /// <summary>
    /// The 「header, base, target list」 body four base-side commands share.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_STATIC, recovered from the client's own loggers (v409). Each of
    /// <c>_INF:CommandAdmissionBase#</c>, <c>_INF:CommandRepairBase#</c> and
    /// <c>_INF:CommandSupplyBase#</c> prints <c>time / wait / id / base /
    /// target[]</c>, and each has its own bounds-check string
    /// 「<c>target_size[%d] is over than 32</c>」 - the same ceiling of 32 every other
    /// list in this protocol carries, and the reason the count is one byte.
    /// <c>_INF:CommandAdmission#</c> prints the same list with a unit in place of
    /// the base.
    ///
    /// A body whose length does not match its own count exactly is refused, so one
    /// of these can never be silently read as another.
    /// </remarks>
    public static bool TryDecodeBaseTargetListCommand(
        ReadOnlySpan<byte> payload, ushort expectedType, out uint time, out uint wait,
        out uint order, out uint baseId, out IReadOnlyList<uint> targets)
    {
        time = wait = order = baseId = 0;
        targets = Array.Empty<uint>();
        const int headerLength = sizeof(ushort) + sizeof(uint) * 4 + sizeof(byte);
        if (payload.Length < headerLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }
        var count = payload[headerLength - 1];
        if (count > MaximumWarpUnitCount ||
            payload.Length != headerLength + count * sizeof(uint))
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[2..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[6..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[10..]);
        baseId = BinaryPrimitives.ReadUInt32BigEndian(payload[14..]);
        var list = new uint[count];
        var cursor = headerLength;
        for (var index = 0; index < count; index++)
            list[index] = ReadUInt32(payload, ref cursor);
        targets = list;
        return true;
    }

    public static bool TryDecodeAttackShipCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalAttackShipCommand command) =>
        TryDecodeAttackShipCommand(payload, AttackShipCommandType, out command);

    /// <summary>
    /// 攻撃 (0x0405) and 空戦 (0x040E) share this body: the client's loggers
    /// 0x00494670 and 0x00496540 print <c>time / wait / id / unit[n] / kind|skill /
    /// target</c> at the identical offsets.
    /// </summary>
    public static bool TryDecodeAttackShipCommand(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        out OriginalTacticalAttackShipCommand command)
    {
        command = default;
        if (!TryReadUnitHeader(
                payload,
                expectedType,
                tailLength: sizeof(byte) + sizeof(uint),
                out var time,
                out var wait,
                out var order,
                out var unitIds,
                out var cursor))
        {
            return false;
        }
        command = new OriginalTacticalAttackShipCommand(
            time,
            wait,
            order,
            unitIds,
            payload[cursor++],
            ReadUInt32(payload, ref cursor));
        return cursor == payload.Length;
    }

    public static bool TryDecodeShootShipCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalShootShipCommand command)
    {
        command = default;
        if (!TryReadUnitHeader(
                payload,
                ShootShipCommandType,
                tailLength: sizeof(byte) * 2 + sizeof(uint),
                out var time,
                out var wait,
                out var order,
                out var unitIds,
                out var cursor))
        {
            return false;
        }
        command = new OriginalTacticalShootShipCommand(
            time,
            wait,
            order,
            unitIds,
            payload[cursor++],
            payload[cursor++],
            ReadUInt32(payload, ref cursor));
        return cursor == payload.Length;
    }

    /// <summary>
    /// 任務 = application type <c>0x0421</c>, the same body shape as 射撃 with the
    /// mission byte where the weapon selection sits. constmsg group 0 row 24:
    /// 「[ 任務 ] 麾下の戦隊に対し指令を下す 実行待機時間48G秒 実行所要時間0G秒」 - issue an
    /// order to the squadrons under your command.
    /// </summary>
    /// <remarks>
    /// ORIGINAL_OBSERVED 2026-09-09T23:38:45Z, run 20260906T181000Z-departure-v124:
    /// the shipped client sent
    /// <c>0421 3F800000 3F21E2AE 00000002 01 00000002 00 01 7E000106</c> after
    /// 旗艦 tab -> 停止 -> 旗艦 tab -> 具申 (r1 c7) -> mission icon 0 -> a click on
    /// the enemy fleet 0x7E000106 at its own published screen point. Mission 0 and
    /// target kind 1 are the two bytes the palette had just set.
    ///
    /// The type is the client's own: the palette's six-icon mission sub-panel
    /// (item 0x0D -> case 7 at 0x005117D6 enables icons 0x25..0x2A; case 26 at
    /// 0x00511821 writes <c>[0x0077A900] = 0..5</c> and moves the palette to command
    /// state 0x19) is served by state 0x19's handler 0x0050EFA4, reached through
    /// the command-state jump table at 0x00512224. That handler builds its frame
    /// through FUN_004B4850, which calls the request dispatcher FUN_004B78A0 with
    /// selector 0x7F, and selector 0x7F's arm at 0x004B80EC sets
    /// <c>ebx = esi = 0x421</c> - request and expected response are both 0x0421.
    ///
    /// 0x0421 is <c>CommandMission</c>, not <c>CommandSuggestion</c>: see
    /// <see cref="OriginalTacticalCommandCatalog"/>. Its logger at 0x0049A340
    /// prints <c>time / wait / id / unit[n] / mission / target_kind / target</c> -
    /// a unit *array* - which is exactly the captured body, while
    /// <c>_INF:CommandSuggestion#</c> (0x00494EC0) prints a single <c>unit</c> and
    /// belongs to 0x0408.
    /// </remarks>
    public static bool TryDecodeMissionCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalMissionCommand command)
    {
        command = default;
        if (!TryReadUnitHeader(
                payload,
                MissionCommandType,
                tailLength: sizeof(byte) * 2 + sizeof(uint),
                out var time,
                out var wait,
                out var order,
                out var unitIds,
                out var cursor))
        {
            return false;
        }
        command = new OriginalTacticalMissionCommand(
            time,
            wait,
            order,
            unitIds,
            payload[cursor++],
            payload[cursor++],
            ReadUInt32(payload, ref cursor));
        return cursor == payload.Length;
    }

    public static byte[] EncodeMissionCommand(OriginalTacticalMissionCommand command)
    {
        var body = new byte[sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte) +
            command.UnitIds.Count * sizeof(uint) + sizeof(byte) * 2 + sizeof(uint)];
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + body.Length];
        var span = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(span, MissionCommandType);
        BinaryPrimitives.WriteUInt32BigEndian(span[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(span[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(span[10..], command.Order);
        span[14] = checked((byte)command.UnitIds.Count);
        var cursor = 15;
        foreach (var unit in command.UnitIds)
        {
            BinaryPrimitives.WriteUInt32BigEndian(span[cursor..], unit);
            cursor += sizeof(uint);
        }
        span[cursor++] = command.Mission;
        span[cursor++] = command.TargetKind;
        BinaryPrimitives.WriteUInt32BigEndian(span[cursor..], command.TargetId);
        return frame;
    }

    /// <summary>
    /// 修理 (0x0413) and 補給 (0x0414). Both bodies are 22 bytes:
    /// type, time, wait, id, the performing vessel, and the target.
    /// </summary>
    public static bool TryDecodeSupportCommand(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        out OriginalTacticalSupportCommand command)
    {
        command = default;
        const int length = sizeof(ushort) + sizeof(uint) * 5;
        if (payload.Length != length ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }
        command = new OriginalTacticalSupportCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[2..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[6..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[10..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[14..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[18..]));
        return true;
    }

    public static byte[] EncodeSupportCommand(OriginalTacticalSupportCommand command, ushort type)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint) * 5];
        var span = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(span, type);
        BinaryPrimitives.WriteUInt32BigEndian(span[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(span[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(span[10..], command.Order);
        BinaryPrimitives.WriteUInt32BigEndian(span[14..], command.Unit);
        BinaryPrimitives.WriteUInt32BigEndian(span[18..], command.TargetId);
        return frame;
    }

    /// <summary>
    /// 隊列変更 (0x040D). ORIGINAL_STATIC: the client's own logger
    /// <c>_INF:CommandFileFleet#</c> (0x00496050) prints
    /// <c>time / wait / id / position[n]{id, direction, {x,y,z}} / kind</c> - the
    /// same 20-byte per-unit element 移動 carries, followed by one formation byte.
    /// </summary>
    public static bool TryDecodeFileFleetCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalFileFleetCommand command)
    {
        command = default;
        const int headerLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        const int positionLength = sizeof(uint) + sizeof(float) * 4;
        if (payload.Length < headerLength + sizeof(byte) ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != FileFleetCommandType)
        {
            return false;
        }
        var count = payload[headerLength - 1];
        if (count > MaximumWarpUnitCount ||
            payload.Length != headerLength + count * positionLength + sizeof(byte))
        {
            return false;
        }
        var positions = new OriginalTacticalMoveUnit[count];
        var cursor = headerLength;
        for (var index = 0; index < count; index++)
        {
            var id = ReadUInt32(payload, ref cursor);
            var direction = ReadSingle(payload, ref cursor);
            var x = ReadSingle(payload, ref cursor);
            var y = ReadSingle(payload, ref cursor);
            var z = ReadSingle(payload, ref cursor);
            // Gateway safety, not a recovered original range rule.
            if (!float.IsFinite(direction) || !float.IsFinite(x) ||
                !float.IsFinite(y) || !float.IsFinite(z))
            {
                return false;
            }
            positions[index] = new OriginalTacticalMoveUnit(id, direction, x, y, z);
        }
        command = new OriginalTacticalFileFleetCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint))..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint) * 2)..]),
            positions,
            payload[cursor]);
        return true;
    }

    public static byte[] EncodeFileFleetCommand(OriginalTacticalFileFleetCommand command)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint) * 3 +
            sizeof(byte) + command.Positions.Count * 20 + sizeof(byte)];
        var span = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(span, FileFleetCommandType);
        BinaryPrimitives.WriteUInt32BigEndian(span[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(span[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(span[10..], command.Order);
        span[14] = checked((byte)command.Positions.Count);
        var cursor = 15;
        foreach (var position in command.Positions)
        {
            BinaryPrimitives.WriteUInt32BigEndian(span[cursor..], position.UnitId);
            BinaryPrimitives.WriteSingleBigEndian(span[(cursor + 4)..], position.Direction);
            BinaryPrimitives.WriteSingleBigEndian(span[(cursor + 8)..], position.X);
            BinaryPrimitives.WriteSingleBigEndian(span[(cursor + 12)..], position.Y);
            BinaryPrimitives.WriteSingleBigEndian(span[(cursor + 16)..], position.Z);
            cursor += 20;
        }
        span[cursor] = command.Kind;
        return frame;
    }

    /// <summary>
    /// 態勢変更 (0x0411). ORIGINAL_STATIC: the client's own logger
    /// <c>_INF:CommandChangeMode#</c> (0x00497150) prints
    /// <c>time / wait / id / unit[n] / kind / target_base</c> - the 攻撃 shape with
    /// a base id where the target unit sits.
    /// </summary>
    public static bool TryDecodeChangeModeCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalChangeModeCommand command)
    {
        command = default;
        if (!TryReadUnitHeader(
                payload,
                ChangeModeCommandType,
                tailLength: sizeof(byte) + sizeof(uint),
                out var time,
                out var wait,
                out var order,
                out var unitIds,
                out var cursor))
        {
            return false;
        }
        command = new OriginalTacticalChangeModeCommand(
            time, wait, order, unitIds, payload[cursor++], ReadUInt32(payload, ref cursor));
        return cursor == payload.Length;
    }

    public static byte[] EncodeChangeModeCommand(OriginalTacticalChangeModeCommand command)
    {
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + sizeof(uint) * 3 +
            sizeof(byte) + command.UnitIds.Count * sizeof(uint) + sizeof(byte) + sizeof(uint)];
        var span = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(span, ChangeModeCommandType);
        BinaryPrimitives.WriteUInt32BigEndian(span[2..], command.Time);
        BinaryPrimitives.WriteUInt32BigEndian(span[6..], command.Wait);
        BinaryPrimitives.WriteUInt32BigEndian(span[10..], command.Order);
        span[14] = checked((byte)command.UnitIds.Count);
        var cursor = 15;
        foreach (var unit in command.UnitIds)
        {
            BinaryPrimitives.WriteUInt32BigEndian(span[cursor..], unit);
            cursor += sizeof(uint);
        }
        span[cursor++] = command.Kind;
        BinaryPrimitives.WriteUInt32BigEndian(span[cursor..], command.TargetBase);
        return frame;
    }

    public static bool TryDecodeStopCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalStopCommand command)
    {
        command = default;
        if (!TryReadUnitHeader(
                payload,
                StopCommandType,
                tailLength: sizeof(byte),
                out var time,
                out var wait,
                out var order,
                out var unitIds,
                out var cursor))
        {
            return false;
        }
        var baseCount = payload[cursor++];
        if (baseCount > MaximumWarpUnitCount ||
            payload.Length != cursor + baseCount * sizeof(uint))
        {
            return false;
        }
        var baseIds = new uint[baseCount];
        for (var index = 0; index < baseIds.Length; index++)
        {
            baseIds[index] = ReadUInt32(payload, ref cursor);
        }
        command = new OriginalTacticalStopCommand(
            time,
            wait,
            order,
            unitIds,
            baseIds);
        return true;
    }

    public static byte[] EncodeCommandEcho(ReadOnlySpan<byte> payload)
    {
        if (payload.Length < sizeof(ushort))
        {
            throw new ArgumentException("An application type is required.", nameof(payload));
        }
        var frame = new byte[OriginalLoginCodec.MessageCodeSize + payload.Length];
        payload.CopyTo(frame.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return frame;
    }

    public static bool TryDecodeWarpCommand(
        ReadOnlySpan<byte> payload,
        out OriginalTacticalWarpCommand command) =>
        TryDecodeWarpCommand(payload, WarpCommandType, out command);

    /// <summary>
    /// The bare 「header plus unit list」 body. ORIGINAL_STATIC: the client's own
    /// loggers print the identical field list - <c>time / wait / id / unit[n]</c>
    /// and nothing else - for 撤退 <c>CommandWarpShip</c> (0x00494280), 陸戦解除
    /// <c>CommandEvacuateTroops</c> (0x00496D60), 出撃 <c>CommandSortie</c>
    /// (0x00497570), <c>CommandStopFleet</c> (0x00497B30) and
    /// <c>CommandStopTroop</c> (0x004986F0).
    /// </summary>
    public static bool TryDecodeWarpCommand(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        out OriginalTacticalWarpCommand command)
    {
        command = default;
        const int fixedLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        if (payload.Length < fixedLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }

        var count = payload[sizeof(ushort) + sizeof(uint) * 3];
        if (count > MaximumWarpUnitCount ||
            payload.Length != fixedLength + count * sizeof(uint))
        {
            return false;
        }

        var unitIds = new uint[count];
        var cursor = fixedLength;
        for (var index = 0; index < unitIds.Length; index++)
        {
            unitIds[index] = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
            cursor += sizeof(uint);
        }

        command = new OriginalTacticalWarpCommand(
            BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint))..]),
            BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint) * 2)..]),
            unitIds);
        return true;
    }

    public static byte[] EncodeWarpedNotification(
        OriginalTacticalWarpedNotification notification)
    {
        ArgumentNullException.ThrowIfNull(notification.UnitIds);
        if (notification.UnitIds.Count > MaximumWarpUnitCount)
        {
            throw new ArgumentOutOfRangeException(nameof(notification));
        }

        const int fixedBodyLength = sizeof(uint) * 3 + sizeof(ushort) + sizeof(byte);
        var frame = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) +
            fixedBodyLength + notification.UnitIds.Count * sizeof(uint)];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, WarpedNotificationType);
        var body = payload[sizeof(ushort)..];
        BinaryPrimitives.WriteUInt32BigEndian(body, notification.Time);
        BinaryPrimitives.WriteUInt32BigEndian(body[sizeof(uint)..], notification.Grid);
        BinaryPrimitives.WriteUInt32BigEndian(body[(sizeof(uint) * 2)..], notification.Base);
        BinaryPrimitives.WriteUInt16BigEndian(body[(sizeof(uint) * 3)..], notification.Mode);
        body[sizeof(uint) * 3 + sizeof(ushort)] =
            checked((byte)notification.UnitIds.Count);
        var cursor = fixedBodyLength;
        foreach (var unitId in notification.UnitIds)
        {
            BinaryPrimitives.WriteUInt32BigEndian(body[cursor..], unitId);
            cursor += sizeof(uint);
        }
        return frame;
    }

    public static byte[] EncodeAttackedNotification(
        OriginalTacticalAttackedNotification notification)
    {
        const int wireBodyLength = 22;
        var frame = new byte[
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + wireBodyLength];
        var payload = frame.AsSpan(OriginalLoginCodec.MessageCodeSize);
        BinaryPrimitives.WriteUInt16BigEndian(payload, AttackedNotificationType);
        var cursor = sizeof(ushort);
        WriteUInt32(payload, ref cursor, notification.Time);
        WriteUInt32(payload, ref cursor, notification.AttackerId);
        payload[cursor++] = notification.Arms;
        payload[cursor++] = notification.TargetKind;
        WriteUInt32(payload, ref cursor, notification.TargetId);
        WriteUInt16(payload, ref cursor, notification.Damaged);
        WriteUInt16(payload, ref cursor, notification.Destroyed);
        payload[cursor++] = notification.ShieldDirection;
        WriteUInt16(payload, ref cursor, notification.DamagedShield);
        payload[cursor] = notification.Morale;
        return frame;
    }

    private static uint ReadUInt32(ReadOnlySpan<byte> payload, ref int cursor)
    {
        var value = BinaryPrimitives.ReadUInt32BigEndian(payload[cursor..]);
        cursor += sizeof(uint);
        return value;
    }

    private static float ReadSingle(ReadOnlySpan<byte> payload, ref int cursor) =>
        BitConverter.UInt32BitsToSingle(ReadUInt32(payload, ref cursor));

    private static void WriteUInt16(Span<byte> payload, ref int cursor, ushort value)
    {
        BinaryPrimitives.WriteUInt16BigEndian(payload[cursor..], value);
        cursor += sizeof(ushort);
    }

    private static void WriteUInt32(Span<byte> payload, ref int cursor, uint value)
    {
        BinaryPrimitives.WriteUInt32BigEndian(payload[cursor..], value);
        cursor += sizeof(uint);
    }

    private static bool TryReadUnitHeader(
        ReadOnlySpan<byte> payload,
        ushort expectedType,
        int tailLength,
        out uint time,
        out uint wait,
        out uint order,
        out uint[] unitIds,
        out int cursor)
    {
        time = 0;
        wait = 0;
        order = 0;
        unitIds = Array.Empty<uint>();
        cursor = 0;
        const int headerLength = sizeof(ushort) + sizeof(uint) * 3 + sizeof(byte);
        if (payload.Length < headerLength + tailLength ||
            BinaryPrimitives.ReadUInt16BigEndian(payload) != expectedType)
        {
            return false;
        }
        var count = payload[sizeof(ushort) + sizeof(uint) * 3];
        if (count > MaximumWarpUnitCount ||
            payload.Length < headerLength + count * sizeof(uint) + tailLength)
        {
            return false;
        }
        time = BinaryPrimitives.ReadUInt32BigEndian(payload[sizeof(ushort)..]);
        wait = BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint))..]);
        order = BinaryPrimitives.ReadUInt32BigEndian(payload[(sizeof(ushort) + sizeof(uint) * 2)..]);
        cursor = headerLength;
        unitIds = new uint[count];
        for (var index = 0; index < unitIds.Length; index++)
        {
            unitIds[index] = ReadUInt32(payload, ref cursor);
        }
        return true;
    }
}
