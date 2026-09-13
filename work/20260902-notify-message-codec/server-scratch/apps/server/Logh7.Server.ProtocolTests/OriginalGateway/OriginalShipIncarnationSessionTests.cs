using System.Buffers.Binary;
using System.Threading.Channels;
using System.Reflection;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalShipIncarnationSessionTests
{
    private static readonly byte[] Key=new byte[16];

    [Fact]
    public async Task Old_scene_waiting_for_command_lease_cannot_fire_after_other_session_recovery()
    {
        var store=new IncarnationStore();
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var gate=await battles.LockAsync(101,100,timeout.Token);
        var shooting=Send(session,"0406000000000000000000000002010000000200017F000001",3);
        // Restore is queued first; this recovery acquires the lease before
        // the command's second acquisition. Exercise the real semaphore path.
        var recovery=battles.LockAsync(101,100,timeout.Token);
        gate.Dispose();
        using (await recovery.WaitAsync(timeout.Token))
        {
            store.Unit=store.Unit with { ShipGeneration=1,AuthorityVersion=3 };
            battles.ObserveShipGeneration(2,1);
            battles.GetEncounter(101,100).ObserveShipGeneration(2,1,default);
        }
        var result=await shooting.WaitAsync(timeout.Token);
        Assert.DoesNotContain(Frames(result),f=>Type(f)==0x426);
        Assert.Equal((ushort)0,battles.GetEncounter(101,100).EnemyDamage.Damaged);
    }

    [Fact]
    public async Task Batch_queued_before_other_session_recovery_is_discarded_before_encoding()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(new IncarnationStore(),battles);
        var subscription=(Guid)typeof(NaturalAuthoritySession).GetField("_battleSubscriptionId",
            BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(session)!;
        var queued=new OriginalTacticalNotificationBatch(101,subscription,[[0x05,0x00]]);
        var sequence=typeof(NaturalAuthoritySession).GetField("_nextServerApplicationSequence",
            BindingFlags.Instance|BindingFlags.NonPublic)!;
        var before=sequence.GetValue(session);
        battles.ObserveShipGeneration(2,1);
        var encode=typeof(NaturalAuthoritySession).GetMethod("EncodeNotificationBatch",
            BindingFlags.Instance|BindingFlags.NonPublic)!
            .CreateDelegate<Func<OriginalTacticalNotificationBatch,IReadOnlyList<NaturalAuthorityPush>>>(session);
        Assert.Empty(encode(queued));
        Assert.Equal(before,sequence.GetValue(session));
    }

    [Fact]
    public async Task Other_session_reimport_refreshes_character_kind_after_recovery()
    {
        var store=new IncarnationStore { RecoverEnabled=true,Kind=0 };
        var battles=new OriginalTacticalBattleRegistry();
        var first=await Open(store,battles);
        var second=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        Frames(await Send(first,"0F02",3));
        var frames=Frames(await Send(second,"0F02",3));
        var units=Assert.Single(frames,f=>Type(f)==0x325);
        Assert.Equal((ushort)3,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(12)));
    }

    [Fact]
    public async Task A_second_life_lost_in_the_same_encounter_gets_a_distinct_return_identity()
    {
        var store=new IncarnationStore { RecoverEnabled=true };
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        Frames(await Send(session,"0F02",3));
        // Fixture relocation, NOT evidence that the reverse warp route works.
        store.Unit=store.Unit with { CurrentCellId=101,BaseId=0,AuthorityVersion=4 };
        Frames(await Send(session,"0F02",4));
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        Frames(await Send(session,"0F02",5));
        Assert.Equal(2L,store.Unit.ShipGeneration);
        Assert.Equal(2,store.DefeatRequests.Count);
        Assert.NotEqual(store.DefeatRequests[0],store.DefeatRequests[1]);
    }

    [Fact]
    public void Observer_from_old_own_incarnation_cannot_receive_new_life_combat()
    {
        var registry=new OriginalTacticalBattleRegistry();
        var channel=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var observer=registry.Subscribe(101,100,channel.Writer,2);
        registry.ObserveShipGeneration(2,1);
        registry.Publish(101,100,null,[[0x42]],3,[[0x32]],2,[[0x33]]);
        Assert.False(channel.Reader.TryRead(out _));
    }

    [Fact]
    public async Task Returned_scene_issues_replacement_and_projects_new_kind_without_erasing_old_battle_loss()
    {
        var store=new IncarnationStore { RecoverEnabled=true, Kind=0 };
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var frames=Frames(await Send(session,"0F02",3));
        var units=Assert.Single(frames,f=>Type(f)==0x325);
        Assert.Equal((ushort)0,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(34)));
        Assert.Equal((ushort)3,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(12)));
        Assert.Equal((ushort)100,battles.GetEncounter(101,100).GetUnitDamage(2).Destroyed);
        Assert.Equal(1L,store.Unit.ShipGeneration);
        Assert.Equal(102u,store.Unit.CurrentCellId);
        Frames(await Send(session,"0F02",4));
        Assert.Equal(1L,store.Unit.ShipGeneration);
    }

    [Fact]
    public void Known_unit_is_reimported_before_an_event_from_its_new_incarnation()
    {
        var registry=new OriginalTacticalBattleRegistry();
        var channel=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var subscription=Guid.NewGuid();
        using var observer=registry.Subscribe(101,100,channel.Writer,3,subscription);
        registry.MarkProjectedParticipants(101,100,channel.Writer,subscription,[2]);
        registry.ObserveShipGeneration(2,1);
        registry.Publish(101,100,null,[[0x42]],2,[[0x32]]);
        Assert.True(channel.Reader.TryRead(out var batch));
        Assert.Equal(2,batch.Frames.Count);
        Assert.Equal(new byte[]{0x32},batch.Frames[0].ToArray());
    }

    [Fact]
    public async Task New_incarnation_import_does_not_inherit_previous_ship_terminal_damage()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var old=await Open(new IncarnationStore(),battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var fresh=await Open(new IncarnationStore { Unit=new(2,2,39,101,3,0,0,0,null,1) },battles);
        var frames=Frames(await Send(fresh,"0F02",3));
        var units=Assert.Single(frames,f=>Type(f)==0x325);
        Assert.Equal((ushort)0,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(34)));
        Assert.Equal((ushort)0,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(36)));
        Assert.True(battles.GetEncounter(101,100).HasSurvivors(2));
        GC.KeepAlive(old);
    }

    [Fact]
    public async Task Old_scene_cannot_attack_as_replacement_before_reimport()
    {
        var store=new IncarnationStore();
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles,OriginalSharedBattleTests.CloseCombatCatalog());
        store.Unit=store.Unit with { ShipGeneration=1,AuthorityVersion=3 };
        var attempted=await Send(session,"0406000000000000000000000002010000000200017F000001",3);
        Assert.DoesNotContain(Frames(attempted),f=>Type(f)==0x426);
        Assert.Equal((ushort)0,battles.GetEncounter(101,100).EnemyDamage.Damaged);
        Frames(await Send(session,"0F02",4));
        var fresh=await Send(session,"0406000000000000000000000002010000000200017F000001",5);
        Assert.Contains(Frames(fresh),f=>Type(f)==0x426);
    }

    [Fact]
    public void Older_participant_cannot_override_replacement_even_after_new_owner_disconnects()
    {
        var registry=new OriginalTacticalBattleRegistry();
        var first=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var next=Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var actor=OriginalNpcAiTests.Actor(2,2,0);
        var old=new OriginalTacticalParticipantSnapshot(actor.Unit,actor.Ship,actor.Corps,
            actor.CharacterFrame.ToArray(),2,0);
        var replacement=new OriginalTacticalParticipantSnapshot(actor.Unit,actor.Ship,actor.Corps,
            actor.CharacterFrame.ToArray(),2,1);
        registry.UpdateParticipant(first.Writer,old);
        registry.UpdateParticipant(next.Writer,replacement);
        registry.UpdateParticipant(first.Writer,old);
        Assert.Equal(1L,Assert.Single(registry.OtherParticipants(101,0)).ShipGeneration);
        registry.RemoveParticipant(next.Writer);
        Assert.Empty(registry.OtherParticipants(101,0));
    }

    private static async Task<NaturalAuthoritySession> Open(IncarnationStore store,OriginalTacticalBattleRegistry battles,
        OriginalBattlefieldCatalog? catalog=null)
    {
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,Key,store:store,battles:battles,catalog:catalog);
        Frames(await Send(session,"0205",1));
        Frames(await Send(session,"0F02",2));
        return session;
    }
    private static ushort Type(byte[] frame)=>BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4));
    private static IReadOnlyList<byte[]> Frames(NaturalAuthoritySessionResult result)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        return new[]{result.ResponsePayload!}.Concat(result.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,Key,0).Payload!).ToArray();
    }
    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session,string hex,uint seq)=>
        session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),Key,seq),CancellationToken.None);

    private sealed class IncarnationStore:OriginalWarpSessionClockTests.UnusedStore,IAccountStore
    {
        public OriginalGridUnitRecord Unit=new(2,2,39,101,1,0);
        public bool RecoverEnabled;
        public ushort Kind=3;
        public List<Guid> DefeatRequests { get; }=[];
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new(2,0,2,0,0,"Pilot","First","Flag",5,
                [1,2,3,4,5,6,7,8],20,0,Kind,2)]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
        public Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(Guid a,long c,uint u,CancellationToken ct)=>
            Task.FromResult<OriginalGridUnitRecord?>(c==2&&u==2?Unit:null);
        public Task<OriginalInjuryReturnStoreResult> ReturnInjuredOriginalUnitAsync(Guid a,OriginalInjuryReturnWrite w,CancellationToken ct)
        {
            if(!RecoverEnabled)throw new NotSupportedException();
            DefeatRequests.Add(w.DefeatId);
            Unit=Unit with { CurrentCellId=w.DestinationGrid,BaseId=w.DestinationBase,
                Damaged=w.Damaged,Destroyed=w.Destroyed,InjuryReturnId=w.DefeatId,AuthorityVersion=Unit.AuthorityVersion+1 };
            return Task.FromResult(new OriginalInjuryReturnStoreResult(Unit,true));
        }
        public Task<OriginalInjuryReturnStoreResult> RecoverOriginalFlagshipAsync(Guid a,long c,uint u,Guid id,long version,CancellationToken ct)
        {
            if(!RecoverEnabled)throw new NotSupportedException();
            if(c!=Unit.CharacterId||u!=Unit.UnitId||id!=Unit.InjuryReturnId||version!=Unit.AuthorityVersion)
                throw new InvalidOperationException("Unexpected recovery request");
            Unit=Unit with { Damaged=0,Destroyed=0,InjuryReturnId=null,
                ShipGeneration=Unit.ShipGeneration+1,AuthorityVersion=Unit.AuthorityVersion+1 };
            Kind=3;
            return Task.FromResult(new OriginalInjuryReturnStoreResult(Unit,true));
        }
    }
}
