namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMoveGridAuthorityState(
    uint UnitId,
    ushort AuthorityCardId,
    uint CellId,
    uint BaseId = 0,
    float Cruising = 10);

public readonly record struct OriginalMoveGridAuthorityCommand(
    uint UnitId,
    ushort AuthorityCardId,
    uint SourceCellId,
    uint DestinationCellId,
    ushort Action)
{
    /// <summary>
    /// The base the unit belongs to on arrival, or 0 to arrive in open space.
    /// The caller resolves it from the destination grid's own content; this
    /// transition only carries it.
    /// </summary>
    public uint DestinationBaseId { get; init; }
}

public enum OriginalMoveGridAuthorityStatus
{
    Allowed,
    Rejected,
}

public readonly record struct OriginalMoveGridAuthorityDecision(
    OriginalMoveGridAuthorityStatus Status,
    OriginalMoveGridAuthorityState State,
    OriginalMovedGridNotification? Notification,
    string? ErrorCode);

public static class OriginalMoveGridAuthority
{
    public const uint MinimalWorldUnitId = 2;
    public const ushort MinimalWorldAuthorityCardId = 39;
    public const uint MinimalWorldSourceCellId = 101;
    public const uint MinimalWorldDestinationCellId = 102;
    public const ushort MinimalWorldWarpAction = 0x2b;
    public const float MinimalWorldStartingCruising = 10.0f;
    public const float MinimalWorldWarpCruisingCost = 1.0f;

    // ORIGINAL_OBSERVED 2026-09-09: the client's own ワープ航行 confirmation
    // reads 「通常はコマンドポイント320MCP消費、指示されている作戦目標に近接
    // する場合は80MCP。」 The cost is original; the balance, regeneration and
    // cap it is spent against remain the authored, user-approved policy.
    public const uint WarpMilitaryPointCost = 320;
    public const uint WarpMilitaryPointCostNearAssignedObjective = 80;

    public static OriginalMoveGridAuthorityState CreateNewDesignMinimalWorld() =>
        new(
            MinimalWorldUnitId,
            MinimalWorldAuthorityCardId,
            MinimalWorldSourceCellId);

    public static OriginalMoveGridAuthorityDecision Transition(
        OriginalMoveGridAuthorityState state,
        OriginalMoveGridAuthorityCommand command)
    {
        // NEW_DESIGN: this is the smallest authored authority world. It does
        // not promote the structural 0x0B01/0x0B07 field layout to observed
        // original-server movement semantics.
        if (command.UnitId != state.UnitId)
        {
            return Reject(state, "MOVE_GRID_UNIT_NOT_OWNED");
        }

        if (command.AuthorityCardId != state.AuthorityCardId)
        {
            return Reject(state, "MOVE_GRID_CARD_NOT_AUTHORIZED");
        }

        if (command.SourceCellId != state.CellId)
        {
            return Reject(state, "MOVE_GRID_SOURCE_STALE");
        }

        if (command.Action != MinimalWorldWarpAction)
        {
            return Reject(state, "MOVE_GRID_ACTION_NOT_AUTHORIZED");
        }

        // Authored adjacent101<->102 route, not a recovered original galaxy.
        if (!((command.SourceCellId == MinimalWorldSourceCellId &&
               command.DestinationCellId == MinimalWorldDestinationCellId) ||
              (command.SourceCellId == MinimalWorldDestinationCellId &&
               command.DestinationCellId == MinimalWorldSourceCellId)))
        {
            return Reject(state, "MOVE_GRID_DESTINATION_NOT_LEGAL");
        }

        if (!float.IsFinite(state.Cruising) || state.Cruising < 0)
            return Reject(state,"MOVE_GRID_CRUISING_INVALID");
        if (state.Cruising < MinimalWorldWarpCruisingCost)
            return Reject(state,"MOVE_GRID_CRUISING_EXHAUSTED");
        // NEW_DESIGN: arriving in open space with no base was a one-way door.
        // The client refuses 態勢変更 with 「拠点から離れているため態勢変更でき
        // ません。宇宙空間にいる場合は、まず星系グリッドの拠点に向かって航行して
        // ください。」 - and this authored world has no in-system navigation to
        // offer, so a warped-in unit could never dock, refuel or undock again.
        // Until the original in-system route (unit command 碇泊 over the 0x1207
        // unit list) is recovered, a warp joins the destination grid's own base
        // when the caller found one. Caller-resolved, never invented here.
        var nextState = state with { CellId = command.DestinationCellId,
            BaseId = command.DestinationBaseId,
            Cruising = state.Cruising-MinimalWorldWarpCruisingCost };
        var notification = new OriginalMovedGridNotification(
            Time: 0,
            Id: 0,
            Grid: command.DestinationCellId,
            Base: nextState.BaseId,
            Mode: 0,
            Records:
            [
                new OriginalMovedGridCruisingRecord(
                    Unit: command.UnitId,
                    Cruising: BitConverter.SingleToUInt32Bits(nextState.Cruising)),
            ]);
        return new OriginalMoveGridAuthorityDecision(
            OriginalMoveGridAuthorityStatus.Allowed,
            nextState,
            notification,
            null);
    }

    private static OriginalMoveGridAuthorityDecision Reject(
        OriginalMoveGridAuthorityState state,
        string errorCode) =>
        new(
            OriginalMoveGridAuthorityStatus.Rejected,
            state,
            null,
            errorCode);
}
