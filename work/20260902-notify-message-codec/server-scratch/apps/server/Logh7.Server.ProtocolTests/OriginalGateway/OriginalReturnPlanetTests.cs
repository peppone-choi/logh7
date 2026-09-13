using System.Buffers.Binary;
using System.Reflection;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalReturnPlanetTests
{
    private static readonly byte[] Key = new byte[16];

    [Fact]
    public void Approved_return_base_has_global_definition_and_local_ownership()
    {
        var catalog = OriginalBattlefieldCatalog.LoadDefault();
        var planet = Assert.Single(catalog.StaticBases!, b => b.Id == 2);
        Assert.Equal((ushort)102,planet.Grid);
        Assert.Equal("NEW_DESIGN",planet.EvidenceStatus);
        Assert.Equal(2u,Assert.Single(catalog.ProjectTacticalBases(102)).Id);
        Assert.Equal((byte)2,Assert.Single(catalog.ProjectBaseObjectives(102)!).Power);
        Assert.DoesNotContain(catalog.ProjectTacticalBases(101),b => b.Id == 2);
    }

    [Fact]
    public async Task Quiet_return_scene_does_not_invent_an_enemy_or_a_battle_end()
    {
        var session = Session(new OriginalTacticalBattleRegistry(),2,2,102);
        var frames = Frames(await Send(session,"0F02",1));
        Assert.Equal((ushort)1,BinaryPrimitives.ReadUInt16BigEndian(Assert.Single(frames,f=>Type(f)==0x325).AsSpan(6)));
        Assert.Single(frames,f=>Type(f)==0x323);
        Assert.Equal((byte)0,Assert.Single(frames,f=>Type(f)==0x317)[8]);
        Assert.DoesNotContain(frames,f=>Type(f)==0xF1F);
        var again = Frames(await Send(session,"0F02",2));
        Assert.Equal((byte)0,Assert.Single(again,f=>Type(f)==0x317)[8]);
    }

    [Fact]
    public async Task Entering_return_scene_leaves_original_battle_active()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var front = Session(battles,2,2,101);
        await Send(front,"0F02",1);
        await Send(Session(battles,3,2,102),"0F02",1);
        Assert.False(battles.GetEncounter(101,100).IsCompleted);
        var frames = Frames(await Send(front,"0F02",2));
        Assert.Equal((byte)1,Assert.Single(frames,f=>Type(f)==0x317)[8]);
        Assert.Equal((ushort)2,BinaryPrimitives.ReadUInt16BigEndian(Assert.Single(frames,f=>Type(f)==0x325).AsSpan(6)));
    }

    [Fact]
    public async Task Absent_primary_npc_cannot_be_shot_or_queried()
    {
        var session = Session(new OriginalTacticalBattleRegistry(),2,2,102);
        await Send(session,"0F02",1);
        var shot = await Send(session,"0406000000000000000000000002010000000200017F000001",2);
        Assert.StartsWith("command-reject=",shot.ResponseMetadata);
        Assert.DoesNotContain(Frames(shot),f=>Type(f)==0x426);
        var info = await Send(session,"0322"+OriginalAuthoredPlayableCatalog.TacticalEnemyCharacterId.ToString("X8"),3);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid,info.Status);
    }

    [Fact]
    public async Task Actual_hostile_players_can_fight_on_the_return_grid_without_a_fabricated_npc()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var first = Session(battles,3,3,102);
        await Send(first,"0F02",1);
        var actor = Session(battles,2,2,102);
        var frames = Frames(await Send(actor,"0F02",1));
        var queue = Inbox(first);
        Assert.True(queue.Reader.TryRead(out var activation));
        var activated = activation!.Frames.Select(f=>f.ToArray()).ToArray();
        Assert.Equal(new ushort[]{0x317,0xF1F},activated.Select(Type).ToArray());
        Assert.Equal((byte)1,activated[0][8]);
        await Send(actor,"0F02",2);
        Assert.False(queue.Reader.TryRead(out _));
        Assert.Equal((byte)1,Assert.Single(frames,f=>Type(f)==0x317)[8]);
        Assert.Equal((ushort)2,BinaryPrimitives.ReadUInt16BigEndian(Assert.Single(frames,f=>Type(f)==0x325).AsSpan(6)));
        Assert.Null(battles.NpcSnapshot(102,0x7F000001));
        var hit = Assert.Single(Frames(await Send(actor,"04060000000000000000000000020100000002000100000003",3)),f=>Type(f)==0x426);
        Assert.Equal(3u,BinaryPrimitives.ReadUInt32BigEndian(hit.AsSpan(16)));
        Assert.Equal((ushort)25,BinaryPrimitives.ReadUInt16BigEndian(hit.AsSpan(20)));
        Assert.True(queue.Reader.TryRead(out var combat));
        Assert.Equal((ushort)0x426,Type(combat!.Frames[^1].ToArray()));
        Assert.DoesNotContain(combat.Frames,f=>Type(f.ToArray())==0xF1F);
    }

    [Fact]
    public async Task Quiet_observer_imports_a_later_primary_npc_before_its_event()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles,2,2,102);
        await Send(observer,"0F02",1);
        var queue = Inbox(observer);
        using var lease = await battles.LockAsync(102,100,TestContext.Current.CancellationToken);
        // A publisher supplies the native import group when a new combatant arrives.
        battles.Publish(102,100,null,[new byte[]{0x42}],0x7F000001,[new byte[]{0x32}]);
        Assert.True(queue.Reader.TryRead(out var batch));
        Assert.Equal(new byte[]{0x32,0x42},batch!.Frames.SelectMany(f=>f.ToArray()).ToArray());
    }

    [Fact]
    public async Task Later_enemy_npc_activates_only_its_grid_before_any_ai_event()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var rear = Session(battles,2,2,102);
        var otherGrid = Session(battles,4,2,103);
        await Send(rear,"0F02",1);
        await Send(otherGrid,"0F02",1);
        using (await battles.LockAsync(102,100,TestContext.Current.CancellationToken))
            battles.RegisterNpc(OriginalNpcAiTests.Actor(0x7F000001,3,0,grid:102),
                OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,OriginalAuthoredPlayableCatalog.TacticalArms);
        await battles.AdvanceNpcsAsync(100,TestContext.Current.CancellationToken);
        Assert.True(Inbox(rear).Reader.TryRead(out var activation));
        Assert.Equal(new ushort[]{0x317,0xF1F},activation!.Frames.Select(f=>Type(f.ToArray())).ToArray());
        Assert.False(Inbox(otherGrid).Reader.TryRead(out _));
        await Send(rear,"0348000100000002",2);
        await battles.AdvanceNpcsAsync(106,TestContext.Current.CancellationToken);
        Assert.True(Inbox(rear).Reader.TryRead(out var motion));
        Assert.Contains(motion!.Frames,f=>Type(f.ToArray())==0x325);
        Assert.DoesNotContain(motion.Frames,f=>Type(f.ToArray())==0xF1F);
    }

    private static NaturalAuthoritySession Session(OriginalTacticalBattleRegistry battles,uint id,byte power,uint grid)
    {
        var session = OriginalPlayerCombatTests.Session(battles,id,power);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridCellId",grid);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridBaseId",grid==102?2u:1u);
        return session;
    }

    private static ushort Type(byte[] frame)=>BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4));
    private static Channel<OriginalTacticalNotificationBatch> Inbox(NaturalAuthoritySession session) =>
        (Channel<OriginalTacticalNotificationBatch>)typeof(NaturalAuthoritySession)
            .GetProperty("PendingNotifications",BindingFlags.Instance|BindingFlags.NonPublic)!.GetValue(session)!;
    private static List<byte[]> Frames(NaturalAuthoritySessionResult result)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        return new[]{result.ResponsePayload!}.Concat(result.AdditionalResponses?.Select(p=>p.Payload)??[])
            .Select(f=>OriginalClientInnerFrameCodec.Decode(f,Key,0).Payload!).ToList();
    }
    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session,string hex,uint sequence)=>
        session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),Key,sequence),CancellationToken.None);
}
