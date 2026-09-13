using System.Threading.Channels;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalBaseTravelNotificationTests
{
    [Fact]
    public void Only_owner_receives_committed_completion_and_closed_subscription_is_removed()
    {
        var bus = new OriginalBaseTravelNotifications();
        var owner = Guid.NewGuid();
        var own = Channel.CreateBounded<OriginalTacticalNotificationBatch>(1);
        var other = Channel.CreateBounded<OriginalTacticalNotificationBatch>(1);
        var disconnected = 0;
        bus.Register(1,owner,own.Writer,()=>disconnected++);
        bus.Register(2,Guid.NewGuid(),other.Writer,()=>throw new Exception("Unrelated connection closed"));
        var done = new OriginalBaseTravelCompletion("completed",true,new OriginalGridUnitRecord(41,7,39,102,3,2),3);
        bus.Publish(owner,done with { Outcome="pending", Updated=false });
        Assert.False(own.Reader.TryRead(out _));
        bus.Publish(owner,done);
        Assert.True(own.Reader.TryRead(out var queued));
        Assert.Equal(owner,queued!.BaseTravel!.Value.Owner);
        Assert.Equal(done,queued.BaseTravel.Value.Completion);
        Assert.Empty(queued.Frames); // Cipher construction belongs to the connection actor.
        Assert.False(other.Reader.TryRead(out _));
        bus.Publish(owner,done);
        bus.Publish(owner,done); // Full owner queue cannot silently lose completion.
        Assert.Equal(1,disconnected);
        bus.Publish(owner,done);
        Assert.Equal(1,disconnected);
        bus.Register(3,owner,other.Writer,()=>disconnected++);
        bus.Remove(3);
        bus.Publish(owner,done);
        Assert.False(other.Reader.TryRead(out _));
    }

    [Fact]
    public void Committed_cancellation_is_queued_for_owner_without_becoming_movement()
    {
        var bus = new OriginalBaseTravelNotifications();
        var owner = Guid.NewGuid();
        var queue = Channel.CreateBounded<OriginalTacticalNotificationBatch>(2);
        bus.Register(1,owner,queue.Writer,()=>throw new Exception("Unexpected disconnect"));
        var cancelled = new OriginalBaseTravelCompletion("cancelled",true,
            new OriginalGridUnitRecord(41,7,39,105,3,9),4);
        bus.Publish(owner,cancelled);
        Assert.True(queue.Reader.TryRead(out var result));
        Assert.Equal("cancelled",result!.BaseTravel!.Value.Completion.Outcome);
        Assert.Empty(result.Frames);
    }
}
