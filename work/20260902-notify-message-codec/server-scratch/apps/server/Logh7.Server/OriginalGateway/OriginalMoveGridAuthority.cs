namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMoveGridAuthorityState(
    uint UnitId,
    ushort AuthorityCardId,
    uint CellId);

public readonly record struct OriginalMoveGridAuthorityCommand(
    uint UnitId,
    ushort AuthorityCardId,
    uint SourceCellId,
    uint DestinationCellId,
    ushort Action);

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

        if (command.SourceCellId != MinimalWorldSourceCellId ||
            command.DestinationCellId != MinimalWorldDestinationCellId)
        {
            return Reject(state, "MOVE_GRID_DESTINATION_NOT_LEGAL");
        }

        var nextState = state with { CellId = command.DestinationCellId };
        var notification = new OriginalMovedGridNotification(
            Time: 0,
            Id: 0,
            Grid: command.DestinationCellId,
            Base: 0,
            Mode: 0,
            Records:
            [
                new OriginalMovedGridCruisingRecord(
                    Unit: command.UnitId,
                    Cruising: 0),
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
