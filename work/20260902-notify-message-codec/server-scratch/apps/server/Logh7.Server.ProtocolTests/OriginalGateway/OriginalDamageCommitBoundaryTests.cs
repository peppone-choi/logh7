using System.Buffers.Binary;
using System.Threading.Channels;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Compatibility;
using Xunit;
namespace Logh7.Server.ProtocolTests.OriginalGateway;
public sealed class OriginalDamageCommitBoundaryTests
{
    [Theory]
    [InlineData(1, 2, 2)]
    [InlineData(100, 101, 100)]
    [InlineData(100, 20, 21)]
    public async Task Invalid_unit_casualties_are_rejected_before_the_persistence_callback(
        ushort number, ushort damaged, ushort destroyed)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var owner = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var persisted = new List<OriginalTacticalDamageState>();
        battles.UpdateParticipant(owner.Writer, OriginalNpcAiTests.Actor(2, 2, 3),
            persistDamage: (damage, _) => { persisted.Add(damage); return Task.CompletedTask; });
        var encounter = battles.GetEncounter(101, 100);
        encounter.RegisterUnitNumber(2, number);
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() =>
            battles.CommitUnitDamageAsync(101, 100, 2, new(damaged, destroyed),
                TestContext.Current.CancellationToken));
        Assert.Empty(persisted);
        Assert.Equal(new OriginalTacticalDamageState(0, 0), encounter.GetUnitDamage(2));
    }
    [Fact]
    public async Task An_old_generation_hit_cannot_follow_a_replacement_ship()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var owner=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var source=OriginalNpcAiTests.Actor(2,2,3);
        var replacement=new OriginalTacticalParticipantSnapshot(source.Unit,source.Ship,
            source.Corps,source.CharacterFrame.ToArray(),source.Power,1);
        battles.UpdateParticipant(owner.Writer,replacement,
            persistDamage:(_,_)=>throw new IOException("must-not-save-new-ship"));
        var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>
            battles.CommitUnitDamageAsync(101,100,2,new(25,0),CancellationToken.None,expectedGeneration:0));
        Assert.Equal("DAMAGE_TARGET_GENERATION_STALE",error.Message);
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(2));
    }

    [Fact]
    public async Task Moving_to_another_grid_does_not_redirect_an_old_hit()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var owner=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(owner.Writer,OriginalNpcAiTests.Actor(2,2,3,grid:102),
            persistDamage:(_,_)=>throw new IOException("must-not-save-wrong-grid"));
        battles.RemoveParticipant(owner.Writer);
        var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>
            battles.CommitUnitDamageAsync(101,100,2,new(25,0),CancellationToken.None,expectedGeneration:0));
        Assert.Equal("DAMAGE_TARGET_LOCATION_STALE",error.Message);
    }

    [Fact]
    public async Task Disconnect_does_not_erase_the_accepted_targets_persistence_failure()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var owner=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(owner.Writer,OriginalNpcAiTests.Actor(2,2,3),
            persistDamage:(_,_)=>Task.FromException(new IOException("durable-write-failed")));
        battles.RemoveParticipant(owner.Writer);
        await Assert.ThrowsAsync<IOException>(()=>
            battles.CommitUnitDamageAsync(101,100,2,new(25,0),CancellationToken.None));
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(2));
    }

    [Fact]
    public async Task Damage_stays_unapplied_while_a_disconnected_targets_commit_is_pending()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var owner=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var commit=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        battles.UpdateParticipant(owner.Writer,OriginalNpcAiTests.Actor(2,2,3),
            persistDamage:(_,_)=>commit.Task);
        battles.RemoveParticipant(owner.Writer);
        var hit=battles.CommitUnitDamageAsync(101,100,2,new(25,0),CancellationToken.None);
        try
        {
            Assert.False(hit.IsCompleted);
            Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(2));
        }
        finally { commit.TrySetResult(); }
        await hit;
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).GetUnitDamage(2));
    }

    [Fact]
    public async Task Failed_persistence_prevents_player_shot_damage()
    {
        var clock=new OriginalPlayerFireCadenceTests.Clock();
        var battles=new OriginalTacticalBattleRegistry();
        var victim=OriginalPlayerCombatTests.Session(battles,3,3,clock:clock);
        var actor=OriginalPlayerCombatTests.Session(battles,2,2,clock:clock);
        var key=new byte[16];
        await victim.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),CancellationToken.None);
        await actor.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),CancellationToken.None);
        var snapshot=Assert.Single(battles.OtherParticipants(101,2),p=>p.Unit.Id==3);
        var persistenceOwner=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(persistenceOwner.Writer,snapshot,
            persistDamage:(_,_)=>Task.FromException(new IOException("durable-write-failed")));
        var payload=Convert.FromHexString("04060000000000000000000000020100000002000100000003");
        await Assert.ThrowsAsync<IOException>(()=>actor.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(payload,key,2),CancellationToken.None));
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(3));
        battles.UpdateParticipant(persistenceOwner.Writer,snapshot,persistDamage:(_,_)=>Task.CompletedTask);
        var retried=await actor.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(payload,key,3),CancellationToken.None);
        Assert.StartsWith("tactical-command-accepted",retried.ResponseMetadata);
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).GetUnitDamage(3));
    }

    [Fact]
    public async Task Failed_persistence_prevents_npc_casualty_and_hit_publication()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var channel=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription=battles.Subscribe(101,100,channel.Writer,2);
        battles.UpdateParticipant(channel.Writer,OriginalNpcAiTests.Actor(2,2,3),
            persistDamage:(_,_)=>Task.FromException(new IOException("durable-write-failed")));
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10,3,0),OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        await battles.AdvanceNpcsAsync(0,CancellationToken.None);
        await Assert.ThrowsAsync<IOException>(()=>battles.AdvanceNpcsAsync(120,CancellationToken.None));
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(2));
        while(channel.Reader.TryRead(out var batch))
            Assert.DoesNotContain(batch.Frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.Span[4..])==0x0426);
        // Failure must not consume a successful shot's recharge interval.
        battles.UpdateParticipant(channel.Writer,OriginalNpcAiTests.Actor(2,2,3),
            persistDamage:(_,_)=>Task.CompletedTask);
        var recovered=await battles.AdvanceNpcsAsync(121,CancellationToken.None);
        Assert.Contains(recovered,e=>e.Actor==10 && e.Target==2 && e.Action=="fire");
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).GetUnitDamage(2));
        Assert.DoesNotContain(await battles.AdvanceNpcsAsync(122,CancellationToken.None),e=>e.Action=="fire");
    }
}
