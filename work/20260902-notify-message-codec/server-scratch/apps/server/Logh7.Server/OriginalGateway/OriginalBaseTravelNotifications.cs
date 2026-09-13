using System.Threading.Channels;
using Logh7.Server.Storage;

namespace Logh7.Server.OriginalGateway;

public sealed class OriginalBaseTravelNotifications
{
    private readonly object _gate = new();
    private readonly Dictionary<int, (Guid Owner, ChannelWriter<OriginalTacticalNotificationBatch> Writer,
        Action Disconnect)> _subscriptions = [];

    public void Register(int connection, Guid owner, ChannelWriter<OriginalTacticalNotificationBatch> writer,
        Action disconnect)
    {
        lock (_gate) _subscriptions[connection] = (owner,writer,disconnect);
    }
    public void Remove(int connection)
    {
        lock (_gate) _subscriptions.Remove(connection);
    }
    public void Publish(Guid owner, OriginalBaseTravelCompletion completion)
    {
        if (!completion.Updated || completion.Outcome is not ("completed" or "cancelled") ||
            completion.Unit is null) return;
        var failed = new List<Action>();
        lock (_gate)
        {
            foreach (var (id,subscription) in _subscriptions.ToArray())
            {
                if (subscription.Owner != owner) continue;
                if (subscription.Writer.TryWrite(new OriginalTacticalNotificationBatch(owner,completion))) continue;
                _subscriptions.Remove(id);
                failed.Add(subscription.Disconnect);
            }
        }
        // Do not block all actors behind a slow subscriber. Reconnect restores
        // the durable location; closing is preferable to silent event loss.
        foreach (var disconnect in failed) disconnect();
    }
}
