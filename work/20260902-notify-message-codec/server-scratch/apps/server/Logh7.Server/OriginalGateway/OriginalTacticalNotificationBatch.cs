using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

// An import boundary and its event must remain one connection-actor operation.
// Take copies at publication so subsequent authority mutations cannot alter it.
public sealed class OriginalTacticalNotificationBatch
{
    public uint Grid { get; }
    public Guid SubscriptionId { get; }
    public IReadOnlyList<ReadOnlyMemory<byte>> Frames { get; }
    public (Guid Owner, OriginalBaseTravelCompletion Completion)? BaseTravel { get; }

    public OriginalTacticalNotificationBatch(Guid owner, OriginalBaseTravelCompletion completion)
    {
        BaseTravel = (owner, completion);
        Grid = completion.Unit?.CurrentCellId ?? 0;
        Frames = Array.Empty<ReadOnlyMemory<byte>>();
    }

    public OriginalTacticalNotificationBatch(uint grid, Guid subscriptionId, IEnumerable<byte[]> frames)
    {
        Grid = grid;
        SubscriptionId = subscriptionId;
        Frames = Array.AsReadOnly(frames.Select(frame => (ReadOnlyMemory<byte>)frame.ToArray()).ToArray());
    }
}
