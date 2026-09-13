using System.Buffers.Binary;
using System.Reflection;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInjuryReturnSessionTests
{
    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task Return_checks_opposing_camp_content_before_and_after_npc_registration(bool registered, bool defeated)
    {
        var fleet = new OriginalBattlefieldFleet(0x7e000060,2,1,0,0,
            [new(0x7e000001,119,new(3,0,0,0))]);
        var document = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json")))!;
        document["templates"]![1]!["fleets"] = System.Text.Json.JsonSerializer.SerializeToNode(new[] { fleet });
        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        var store = new RecoveryStore();
        var battles = new OriginalTacticalBattleRegistry();
        var session = await Open(store,battles,catalog);
        if (registered)
            battles.RegisterNpc(fleet.Project(102).Single(),OriginalSubordinateShipCatalog.Capabilities,
                OriginalAuthoredPlayableCatalog.TacticalArms);
        if (defeated)
            battles.GetEncounter(102,100).RecordUnitDamage(0x7e000001,new(300,300));
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var result = await Send(session,"0F02",3);
        if (defeated)
            AssertReturned(Frames(result));
        else
        {
            Assert.Equal("original.injury-return.destination-not-quiet",result.ErrorCode);
            Assert.Equal(101u,(await store.FindOriginalGridUnitAsync(Guid.Empty,2,2,CancellationToken.None))!.CurrentCellId);
        }
    }
    [Fact]
    public async Task Damage_binding_passes_the_registered_single_hull_complement_to_storage()
    {
        var store = new RecoveryStore { Number = 1 };
        var battles = new OriginalTacticalBattleRegistry();
        var session = await Open(store, battles);
        battles.GetEncounter(101, 100).RegisterUnitNumber(2, 1);
        Frames(await Send(session, "0F02", 3));
        await battles.CommitUnitDamageAsync(101, 100, 2, new(1, 0), TestContext.Current.CancellationToken);
        Assert.NotNull(store.LastDamageWrite);
        Assert.Equal((ushort)1, store.LastDamageWrite.Number);
        Assert.Equal((ushort)0, store.LastDamageWrite.Destroyed);
    }
    [Fact]
    public async Task One_hull_defeat_uses_its_own_complement_in_the_return_request()
    {
        var store = new RecoveryStore { Number = 1 };
        var battles = new OriginalTacticalBattleRegistry();
        var session = await Open(store, battles);
        var encounter = battles.GetEncounter(101, 100);
        encounter.RegisterUnitNumber(2, 1);
        encounter.RecordUnitDamage(2, new(1, 1));
        Frames(await Send(session, "0F02", 3));
        var saved = await store.FindOriginalGridUnitAsync(Guid.Empty, 2, 2, CancellationToken.None);
        Assert.Equal(102u, saved!.CurrentCellId);
        Assert.Equal((ushort)1, saved.Destroyed);
        Assert.NotNull(saved.InjuryReturnId);
        Assert.False(encounter.IsCompleted);
    }
    private static readonly byte[] Key=new byte[16];

    [Fact]
    public async Task Dead_own_refresh_returns_to_selected_base_without_healing_or_ending_the_front()
    {
        var store=new RecoveryStore();
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var frames=Frames(await Send(session,"0F02",3));
        AssertReturned(frames);
        Assert.False(battles.GetEncounter(101,100).IsCompleted);
        Assert.DoesNotContain(battles.OtherParticipants(101,0),p=>p.Unit.Id==2);
        Assert.DoesNotContain(battles.OtherParticipants(102,0),p=>p.Unit.Id==2);
        Assert.DoesNotContain(frames,f=>Type(f)==0xF1F);
        AssertReturned(Frames(await Send(session,"0F02",4)));
        var fresh=await Open(store,new OriginalTacticalBattleRegistry());
        AssertReturned(Frames(await Send(fresh,"0F02",3)));
        Assert.Single(await store.ListCharactersAsync(Guid.Empty,CancellationToken.None));
        var shot=await Send(fresh,"0406000000000000000000000002010000000200017F000001",4);
        Assert.StartsWith("command-reject=",shot.ResponseMetadata);
    }

    [Fact]
    public async Task Failed_return_storage_does_not_move_the_player_or_erase_the_loss()
    {
        var store=new RecoveryStore { Fail=true };
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        await Assert.ThrowsAsync<IOException>(()=>Send(session,"0F02",3));
        Assert.Contains(battles.OtherParticipants(101,0),p=>p.Unit.Id==2);
        Assert.False(battles.GetEncounter(101,100).HasSurvivors(2));
        store.Fail=false;
        AssertReturned(Frames(await Send(session,"0F02",4)));
    }

    [Fact]
    public async Task Injured_character_is_not_reentered_when_others_fight_at_the_return_grid()
    {
        var store=new RecoveryStore();
        var battles=new OriginalTacticalBattleRegistry();
        var recovering=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        AssertReturned(Frames(await Send(recovering,"0F02",3)));
        var first=OriginalPlayerCombatTests.Session(battles,3,3);
        var second=OriginalPlayerCombatTests.Session(battles,4,2);
        foreach(var session in new[]{first,second})
        {
            OriginalWarpSessionClockTests.SetField(session,"_worldGridCellId",102u);
            Frames(await Send(session,"0F02",1));
        }
        var hit=Frames(await Send(second,"04060000000000000000000000020100000004000100000003",2));
        Assert.Contains(hit,f=>Type(f)==0x426);
        var queue=(Channel<OriginalTacticalNotificationBatch>)typeof(NaturalAuthoritySession)
            .GetProperty("PendingNotifications",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(recovering)!;
        Assert.False(queue.Reader.TryRead(out _));
        AssertReturned(Frames(await Send(recovering,"0F02",4)));
    }

    private static void AssertReturned(IReadOnlyList<byte[]> frames)
    {
        var grid=Assert.Single(frames,f=>Type(f)==0x317);
        Assert.Equal((ushort)102,BinaryPrimitives.ReadUInt16BigEndian(grid.AsSpan(6)));
        Assert.Equal((byte)0,grid[8]);
        var units=Assert.Single(frames,f=>Type(f)==0x325);
        Assert.Equal((ushort)1,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(6)));
        Assert.Equal((ushort)100,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(34)));
        Assert.Equal((ushort)100,BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(36)));
        var character=Assert.Single(frames,f=>Type(f)==0x323);
        Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(character.AsSpan(38)));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Destroyed_flagship_cannot_warp_before_or_after_return(bool returned)
    {
        var store=new RecoveryStore();
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        await OriginalWarpPowerGateTests.SetPower(session,Key,50,3);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        if(returned)AssertReturned(Frames(await Send(session,"0F02",4)));
        var result=await Send(session,"0404F1234567DEADBEEF000000020100000002",5);
        Assert.StartsWith("command-reject=",result.ResponseMetadata);
        Assert.DoesNotContain(Frames(result),f=>Type(f)==0x425);
    }

    [Fact]
    public async Task Destination_mutation_waits_until_return_commit_has_finished()
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var store=new RecoveryStore { Entered=entered,Release=release.Task };
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(store,battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var returning=Send(session,"0F02",3);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5),TestContext.Current.CancellationToken);
        var competing=battles.LockAsync(102,100,TestContext.Current.CancellationToken);
        var blocked=!competing.IsCompleted;
        release.SetResult();
        (await competing).Dispose();
        AssertReturned(Frames(await returning));
        Assert.True(blocked);
    }
    [Fact]
    public async Task Actual_strategic_warp_cannot_publish_an_arrival_during_return_commit()
    {
        var entered=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release=new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var store=new RecoveryStore { Entered=entered,Release=release.Task };
        var battles=new OriginalTacticalBattleRegistry();
        var recovering=await Open(store,battles);
        var mover=await Open(new MoverStore(),battles);
        battles.GetEncounter(101,100).RecordUnitDamage(2,new(100,100));
        var returning=Send(recovering,"0F02",3);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(5),TestContext.Current.CancellationToken);
        var moving=Send(mover,"0B0100000000000000000000000300270000000000000000006600000000000000",3);
        var blocked=!moving.IsCompleted;
        var appearedEarly=battles.OtherParticipants(102,0).Any(p=>p.Unit.Id==3);
        release.SetResult();
        AssertReturned(Frames(await returning));
        Assert.Contains(Frames(await moving),f=>Type(f)==0xB07);
        Assert.True(blocked);
        Assert.False(appearedEarly);
        Assert.Contains(battles.OtherParticipants(102,0),p=>p.Unit.Id==3);
    }

    [Fact]
    public async Task Terminal_loss_cannot_escape_through_a_strategic_warp_before_return()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var session=await Open(new MoverStore(),battles);
        battles.GetEncounter(101,100).RecordUnitDamage(3,new(100,100));
        var result=Frames(await Send(session,"0B0100000000000000000000000300270000000000000000006600000000000000",3));
        Assert.DoesNotContain(result,f=>Type(f)==0xB07);
        Assert.Contains(result,f=>Type(f)==0x500);
        Assert.DoesNotContain(battles.OtherParticipants(102,0),p=>p.Unit.Id==3);
    }

    [Fact]
    public async Task Old_scene_position_poll_after_move_does_not_activate_destination_before_its_bootstrap()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = await Open(new MoverStore(), battles);
        await Send(session, "0348000100000003", 3);
        var moved = Frames(await Send(session,
            "0B0100000000000000000000000300270000000000000000006600000000000000", 4));
        Assert.Contains(moved, f => Type(f) == 0xB07);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 2, 0, grid: 102),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        await Send(session, "0348000100000003", 5);
        var before = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick <= 202; tick += 6)
            before.AddRange(await battles.AdvanceNpcsAsync(tick, CancellationToken.None));
        Assert.DoesNotContain(before, e => e.Grid == 102 && e.Target == 3);
        Assert.Equal((ushort)0, battles.GetEncounter(102, 100).GetUnitDamage(3).Damaged);

        await Send(session, "0F02", 6);
        await Send(session, "0348000100000003", 7);
        var after = new List<OriginalNpcEvent>();
        for (uint tick = 208; tick <= 400; tick += 6)
            after.AddRange(await battles.AdvanceNpcsAsync(tick, CancellationToken.None));
        Assert.Contains(after, e => e.Grid == 102 && e.Target == 3 && e.Action == "fire");
    }

    private static async Task<NaturalAuthoritySession> Open(IAccountStore store,OriginalTacticalBattleRegistry battles,
        OriginalBattlefieldCatalog? catalog = null)
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

    private sealed class RecoveryStore:OriginalWarpSessionClockTests.UnusedStore,IAccountStore
    {
        private OriginalGridUnitRecord _unit=new(2,2,39,101,1,1);
        public bool Fail;
        public ushort Number = 100;
        public OriginalUnitDamageWrite? LastDamageWrite;
        public Task<OriginalUnitDamageStoreResult> SaveOriginalUnitDamageAsync(Guid account,
            OriginalUnitDamageWrite write, CancellationToken ct)
        {
            LastDamageWrite = write;
            _unit = _unit with { UnitNumber = Number, Damaged = write.Damaged, Destroyed = write.Destroyed,
                AuthorityVersion = _unit.AuthorityVersion + 1 };
            return Task.FromResult(new OriginalUnitDamageStoreResult(_unit, true));
        }
        public TaskCompletionSource? Entered;
        public Task? Release;
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new(2,0,2,0,0,"Pilot","First","Flag",5,
                [1,2,3,4,5,6,7,8],20,0,0,2)]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
        public Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(Guid a,long c,uint u,CancellationToken ct)=>
            Task.FromResult<OriginalGridUnitRecord?>(c==2&&u==2?_unit with { UnitNumber=Number }:null);
        public async Task<OriginalInjuryReturnStoreResult> ReturnInjuredOriginalUnitAsync(Guid a,OriginalInjuryReturnWrite w,CancellationToken ct)
        {
            if(Fail)throw new IOException("Test persistence failure");
            if(w.CharacterId!=2||w.UnitId!=2||w.SourceGrid!=101||w.DestinationGrid!=102||w.DestinationBase!=2||
                w.Number!=Number||w.Destroyed!=Number||w.Damaged!=Number)throw new InvalidOperationException("Unexpected return request");
            Entered?.TrySetResult();
            if(Release is not null)await Release.WaitAsync(ct);
            if(_unit.InjuryReturnId is not null)return new(_unit,false);
            _unit=_unit with { UnitNumber=Number,CurrentCellId=102,BaseId=2,Damaged=w.Damaged,Destroyed=w.Destroyed,
                InjuryReturnId=w.DefeatId,AuthorityVersion=2 };
            return new(_unit,true);
        }
    }

    private sealed class MoverStore:OriginalWarpSessionClockTests.UnusedStore,IAccountStore
    {
        private OriginalGridUnitRecord _unit=new(3,3,39,101,1,1);
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new(3,0,3,0,0,"Enemy","First","Flag",5,
                [1,2,3,4,5,6,7,8],20,0,89)]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid a,CancellationToken ct)=>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
        public Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(Guid a,long c,uint u,CancellationToken ct)=>
            Task.FromResult<OriginalGridUnitRecord?>(c==3&&u==3?_unit:null);
        public Task<OriginalMoveGridStoreResult> MoveOriginalGridUnitAsync(Guid a,OriginalMoveGridWrite w,CancellationToken ct)
        {
            if(w.CharacterId!=3||w.UnitId!=3||w.SourceCellId!=101||w.DestinationCellId!=102)
                throw new InvalidOperationException("Unexpected move request");
            _unit=_unit with { CurrentCellId=102,BaseId=0,AuthorityVersion=2,Cruising=_unit.Cruising-1 };
            return Task.FromResult(new OriginalMoveGridStoreResult(OriginalMoveGridStoreStatus.Moved,_unit,2,null));
        }
        public Task<OriginalUnitDamageStoreResult> SaveOriginalUnitDamageAsync(Guid a,OriginalUnitDamageWrite w,CancellationToken ct)
        {
            if(w.CharacterId!=3 || w.UnitId!=3 || w.Grid!=_unit.CurrentCellId ||
                w.ShipGeneration!=_unit.ShipGeneration || w.Damaged<_unit.Damaged || w.Destroyed> w.Damaged)
                throw new InvalidOperationException("Unexpected damage request");
            _unit=_unit with { Damaged=w.Damaged,Destroyed=w.Destroyed,AuthorityVersion=_unit.AuthorityVersion+1 };
            return Task.FromResult(new OriginalUnitDamageStoreResult(_unit,true));
        }
    }
}
