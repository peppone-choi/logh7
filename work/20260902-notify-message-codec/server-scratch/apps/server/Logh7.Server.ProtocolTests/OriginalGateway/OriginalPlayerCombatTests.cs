using System.Buffers.Binary;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalPlayerCombatTests
{
    private static readonly byte[] Key = new byte[16];

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Turning_into_an_equipped_arc_enables_an_in_range_target(bool attack)
    {
        var doc=JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json")))!;
        doc["templates"]![0]!["enemySpawn"]!["x"]=-7;
        doc["templates"]![0]!["playerSpawn"]!["direction"]=MathF.PI/2;
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2,OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
        await Send(actor,"0F02",1);
        // range3, bearingPI/2, headingPI/2 => sector0, absent from mask0x34.
        var blocked=await Send(actor,Shot(2,0x7f000001,attack),2);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUTSIDE_WEAPON_ARC",blocked.ResponseMetadata);
        Assert.DoesNotContain(Frames(blocked),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).EnemyDamage);
        var turn=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,2,
            [new(2,MathF.PI/2,-10,0,0)],1,0,[new(-10,0,0)]));
        Assert.Contains("tactical-move-ship-accepted",(await Send(actor,Convert.ToHexString(turn.AsSpan(4)),3)).ResponseMetadata);
        Assert.Single(Frames(await Send(actor,Shot(2,0x7f000001,attack),4)),f=>Type(f)==0x426);
    }

    // ORIGINAL_OBSERVED: the exact 0x0401 body the native 旋回 widget sent on
    // 2026-09-09 (run 20260906T181000Z-departure-v124). The v50 authority
    // rejected this type and closed the session, ending the player's game.
    private const string NativeTurnBody = "040100007B000000000000000002010000000200000000BFC4B9F4";

    /// <summary>
    /// The encounter must be able to end because the player finished it. A
    /// single-hull authored opponent dies to one accepted beam, and with no
    /// hostile survivor left and the grid's base already held, the authority
    /// closes the battle and tells the client so.
    /// </summary>
    [Fact]
    public async Task One_accepted_beam_destroys_the_single_hull_picket_and_closes_the_encounter()
    {
        var doc=JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,
            "battlefields","fleet-skirmish.json")))!;
        var template=doc["templates"]!.AsArray().Single(node=>(string?)node!["id"]=="authored-fleet-skirmish-v1")!;
        var fleets=template["fleets"]!.AsArray();
        // Only the picket, so the battle can actually end; the older 300-hull
        // outfits on the shipped grid would keep a hostile survivor alive.
        for(var index=fleets.Count-1;index>=0;index--)
            if((uint?)fleets[index]!["id"]!=2113929571u) fleets.RemoveAt(index);
        var catalog=OriginalBattlefieldCatalog.Parse(doc.ToJsonString());
        var picket=Assert.Single(Assert.Single(catalog.Resolve(101).Fleets!).Ships);
        Assert.Equal((ushort)1,picket.Complement);
        Assert.Equal((ushort)1,catalog.ShipComplement(picket.Id,300));
        Assert.Equal((ushort)300,catalog.ShipComplement(2113929477,300));

        var clock=new CombatClock();
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2,catalog,clock);
        await Send(actor,"0F02",1);
        var encounter=battles.GetEncounter(101,100);
        Assert.Equal((ushort)1,encounter.UnitNumber(picket.Id));

        // A single-hull unit goes normal -> damaged -> destroyed, so the first
        // accepted beam wounds it and the second ends it. The battle must not
        // close while the picket still has a survivor.
        clock.Recharge();
        var first=Frames(await Send(actor,Shot(2,picket.Id),2));
        Assert.Single(first,f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(1,0),encounter.GetUnitDamage(picket.Id));
        Assert.True(encounter.HasSurvivors(picket.Id));
        Assert.DoesNotContain(first,f=>Type(f)==OriginalTacticalCommandCodec.NotifyTacticsType);

        // The picket is unarmed, so it cannot answer while the player reloads.
        var quiet=new List<OriginalNpcEvent>();
        for(uint tick=100;tick<400;tick+=6)
            quiet.AddRange(await battles.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        Assert.DoesNotContain(quiet,e=>e.Action=="fire");

        clock.Recharge();
        var final=Frames(await Send(actor,Shot(2,picket.Id),3));
        Assert.Single(final,f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(1,1),encounter.GetUnitDamage(picket.Id));
        Assert.False(encounter.HasSurvivors(picket.Id));
        // 0x0f1f carries the client's end-of-battle signal.
        Assert.Contains(final,f=>Type(f)==OriginalTacticalCommandCodec.NotifyTacticsType);
        Assert.True(encounter.IsCompleted);
    }

    /// <summary>
    /// v270: the client builds a pick node with kind 0 when the scene record's
    /// Search byte is 0, and 004EF95B skips a kind-0 node before any flag, range
    /// or arc test. Every ship the player must be able to click therefore has to
    /// carry the same Search value the player's own and the legacy enemy record
    /// carry, or the unit is structurally untargetable in the original client.
    /// </summary>
    [Fact]
    public void Every_projected_fleet_ship_carries_the_search_byte_the_client_needs_to_pick_it()
    {
        var catalog=OriginalBattlefieldCatalog.Load(Path.Combine(AppContext.BaseDirectory,
            "battlefields","fleet-skirmish.json"));
        var fleets=catalog.Resolve(101).Fleets!;
        Assert.NotEmpty(fleets);
        var player=OriginalSystemSceneCodec.CreateTacticalBattlefield(2,2,catalog.Resolve(101)).Records[0];
        Assert.Equal((byte)1,player.Search);
        foreach(var fleet in fleets)
            foreach(var participant in fleet.Project(101))
                Assert.Equal(player.Search,participant.Ship.Search);
    }

    [Fact]
    public void The_native_turn_body_decodes_to_one_unit_and_its_requested_heading()
    {
        Assert.True(OriginalTacticalCommandCodec.TryDecodeTurnShipCommand(
            Convert.FromHexString(NativeTurnBody), out var command));
        var unit = Assert.Single(command.Units);
        Assert.Equal(2u, unit.UnitId);
        Assert.Equal(0f, unit.From);
        Assert.Equal(-1.5369248f, unit.To, 6);
        Assert.Equal(0x00007B00u, command.Time);
        var encoded = OriginalTacticalCommandCodec.EncodeTurnShipCommand(command);
        Assert.Equal(NativeTurnBody, Convert.ToHexString(encoded.AsSpan(4)));
        // Truncated and over-long bodies stay refusals, not partial commands.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeTurnShipCommand(
            Convert.FromHexString(NativeTurnBody)[..26], out _));
        Assert.False(OriginalTacticalCommandCodec.TryDecodeTurnShipCommand(
            Convert.FromHexString(NativeTurnBody + "00"), out _));
    }

    /// <summary>
    /// Turning is what lets a player bring a target into an equipped arc, so the
    /// accepted turn must be followed by an accepted shot at the same target
    /// that the pre-turn heading refused.
    /// </summary>
    [Fact]
    public async Task Turning_is_accepted_and_opens_the_shot_the_previous_heading_refused()
    {
        var doc=JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,
            "battlefields","catalog.json")))!;
        doc["templates"]![0]!["enemySpawn"]!["x"]=-7;
        doc["templates"]![0]!["playerSpawn"]!["direction"]=MathF.PI/2;
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2,OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
        await Send(actor,"0F02",1);
        var blocked=await Send(actor,Shot(2,0x7f000001),2);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUTSIDE_WEAPON_ARC",blocked.ResponseMetadata);

        var turn=OriginalTacticalCommandCodec.EncodeTurnShipCommand(
            new(0x7B00,0,2,[new(2,MathF.PI/2,0)]));
        var accepted=await Send(actor,Convert.ToHexString(turn.AsSpan(4)),3);
        Assert.Contains("tactical-turn-ship-accepted",accepted.ResponseMetadata);
        Assert.Contains(Frames(accepted),f=>Type(f)==0x424);
        Assert.Single(Frames(await Send(actor,Shot(2,0x7f000001),4)),f=>Type(f)==0x426);
    }

    [Fact]
    public async Task Turning_another_controllers_unit_is_refused_without_closing_the_session()
    {
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2);
        await Send(actor,"0F02",1);
        var turn=OriginalTacticalCommandCodec.EncodeTurnShipCommand(
            new(0x7B00,0,2,[new(9,0,1f)]));
        var refused=await Send(actor,Convert.ToHexString(turn.AsSpan(4)),2);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,refused.Status);
        Assert.StartsWith("command-reject=TACTICAL_UNIT_NOT_CONTROLLED",refused.ResponseMetadata);
    }

    /// <summary>
    /// An authored defensive outfit must hold its fire until it is actually hit,
    /// so the player decides when an engagement starts. Only the ship that was
    /// hit is released; a sister ship in the same outfit keeps holding.
    /// </summary>
    [Fact]
    public async Task A_defensive_outfit_holds_until_the_player_hits_it()
    {
        var doc=JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,
            "battlefields","fleet-skirmish.json")))!;
        var template=doc["templates"]!.AsArray().Single(node=>(string?)node!["id"]=="authored-fleet-skirmish-v1")!;
        var fleets=template["fleets"]!.AsArray();
        // Keep only the defensive outfit; the older opposing fleets would
        // provoke it and hide the behaviour under test.
        for(var index=fleets.Count-1;index>=0;index--)
            if((uint?)fleets[index]!["id"]!=2113929570u) fleets.RemoveAt(index);
        var catalog=OriginalBattlefieldCatalog.Parse(doc.ToJsonString());
        Assert.True(catalog.IsDefensiveOutfit(2113929570));
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2,catalog);
        await Send(actor,"0F02",1);
        const uint hostile=2113929477;
        const uint sister=2113929478;
        var holding=new List<OriginalNpcEvent>();
        for(uint tick=100;tick<400;tick+=6)
            holding.AddRange(await battles.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        Assert.Empty(holding);

        var close=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,2,
            [new(2,0,-10,0,0)],1,0,[new(-4,0,0)]));
        Assert.Contains("tactical-move-ship-accepted",
            (await Send(actor,Convert.ToHexString(close.AsSpan(4)),2)).ResponseMetadata);
        Assert.Single(Frames(await Send(actor,Shot(2,hostile),3)),f=>Type(f)==0x426);

        var answered=new List<OriginalNpcEvent>();
        for(uint tick=400;tick<700;tick+=6)
            answered.AddRange(await battles.AdvanceNpcsAsync(tick,TestContext.Current.CancellationToken));
        Assert.Contains(answered,e=>e.Actor==hostile);
        Assert.DoesNotContain(answered,e=>e.Actor==sister);
    }

    /// <summary>
    /// The authored hostile fleet added for the next live encounter must be a
    /// legal player target: out of range at the spawn distance, and damaged only
    /// after the player closes in. This is the server side of the native firing
    /// path; it does not prove the original client sent the request.
    /// </summary>
    [Fact]
    public async Task Player_engages_the_authored_hostile_fleet_after_closing_into_the_weapon_arc()
    {
        var catalog=OriginalBattlefieldCatalog.Load(Path.Combine(AppContext.BaseDirectory,
            "battlefields","fleet-skirmish.json"));
        var battles=new OriginalTacticalBattleRegistry();
        var actor=Session(battles,2,2,catalog);
        await Send(actor,"0F02",1);
        const uint hostile=2113929477;
        Assert.Contains(battles.OtherParticipants(101,2),p=>p.Unit.Id==hostile && p.Power==3);
        var far=await Send(actor,Shot(2,hostile),2);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUT_OF_RANGE",far.ResponseMetadata);
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(hostile));
        var close=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,2,
            [new(2,0,-10,0,0)],1,0,[new(-4,0,0)]));
        Assert.Contains("tactical-move-ship-accepted",
            (await Send(actor,Convert.ToHexString(close.AsSpan(4)),3)).ResponseMetadata);
        Assert.Single(Frames(await Send(actor,Shot(2,hostile),4)),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).GetUnitDamage(hostile));
        // Only the addressed unit is hit. This fixture has no persisted rows, so
        // it says nothing about the already destroyed v234 fleets on the guest.
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(2113929478));
    }

    [Fact]
    public async Task Shooting_outside_the_equipped_weapon_range_does_not_damage_the_npc()
    {
        var battles=new OriginalTacticalBattleRegistry();
        // 移動 carries the original's 48 G秒 実行待機時間 (constmsg group 0 row 4),
        // so the two repositioning moves below need the clock to advance.
        var clock=new OriginalPlayerFireCadenceTests.Clock();
        var actor=Session(battles,2,2,clock:clock,gameClock:new OriginalGameClock(clock));
        await Send(actor,"0F02",1); // initial positions -10 and+10; beam range5
        var rejected=await Send(actor,Shot(2,0x7f000001),2);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUT_OF_RANGE",rejected.ResponseMetadata);
        Assert.DoesNotContain(Frames(rejected),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).EnemyDamage);
        // Establish a legal firing position through the accepted movement path.
        var move=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,2,
            [new(2,0,-10,0,0)],1,0,[new(5,0,0)]));
        Assert.Contains("tactical-move-ship-accepted",(await Send(actor,Convert.ToHexString(move.AsSpan(4)),3)).ResponseMetadata);
        var boundary=await Send(actor,Shot(2,0x7f000001),4);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUT_OF_RANGE",boundary.ResponseMetadata);
        clock.Timestamp+=2000; // 48 G秒
        move=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,2,
            [new(2,0,5,0,0)],1,0,[new(6,0,0)]));
        Assert.Contains("tactical-move-ship-accepted",(await Send(actor,Convert.ToHexString(move.AsSpan(4)),5)).ResponseMetadata);
        Assert.Single(Frames(await Send(actor,Shot(2,0x7f000001),6)),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).EnemyDamage);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(false, true)]
    [InlineData(true, false)]
    [InlineData(true, true)]
    public async Task Accepted_zero_beam_power_blocks_damage_until_power_is_restored(bool attack, bool npcTarget)
    {
        var battles=new OriginalTacticalBattleRegistry();
        var catalog=OriginalSharedBattleTests.CloseCombatCatalog();
        if(!npcTarget) await Send(Session(battles,3,3,catalog),"0F02",1);
        var actor=Session(battles,2,2,catalog);
        await Send(actor,"0F02",1);
        var target=npcTarget ? 0x7f000001u : 3u;
        var control=Convert.FromHexString("040C000000000000000000000002000000020032000004040303030314000A");
        var disabled=await Send(actor,Convert.ToHexString(control),2);
        Assert.Contains("tactical-control-accepted",disabled.ResponseMetadata);
        var blocked=await Send(actor,Shot(2,target,attack),3);
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_POWER_INSUFFICIENT",blocked.ResponseMetadata);
        Assert.DoesNotContain(Frames(blocked),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(0,0),battles.GetEncounter(101,100).GetUnitDamage(target));
        control[20]=20; // accepted original040C beam allocation, not a private state fixture
        Assert.Contains("tactical-control-accepted",(await Send(actor,Convert.ToHexString(control),4)).ResponseMetadata);
        var enabled=await Send(actor,Shot(2,target,attack),5);
        Assert.Single(Frames(enabled),f=>Type(f)==0x426);
        Assert.Equal(new OriginalTacticalDamageState(25,0),battles.GetEncounter(101,100).GetUnitDamage(target));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Player_target_receives_damage_and_self_refresh_preserves_it(bool attack)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var victim = Session(battles, 3, 3);
        await Send(victim, "0F02", 1);
        var actor = Session(battles, 2, 2);
        await Send(actor, "0F02", 1);
        var result = await Send(actor, Shot(2, 3, attack), 2);
        var hit = Assert.Single(Frames(result), f => Type(f) == 0x426);
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(hit.AsSpan(16)));
        Assert.Equal((ushort)25, BinaryPrimitives.ReadUInt16BigEndian(hit.AsSpan(20)));
        var refresh = Frames(await Send(victim, "0F02", 2));
        Assert.Equal((25, 0), Damage(refresh, 3));
        Assert.Equal((0, 0), Damage(refresh, 0x7F000001));
    }

    [Theory]
    [InlineData(99, 2, 101u)] // nonexistent
    [InlineData(3, 2, 101u)] // same faction
    [InlineData(3, 3, 102u)] // other battlefield
    public async Task Invalid_target_is_rejected_instead_of_successful_noop(uint target, byte power, uint grid)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var other = Session(battles, 3, power);
        OriginalWarpSessionClockTests.SetField(other, "_worldGridCellId", grid);
        await Send(other, "0F02", 1);
        var actor = Session(battles, 2, 2);
        await Send(actor, "0F02", 1);
        var result = await Send(actor, Shot(2, target), 2);
        Assert.StartsWith("command-reject=", result.ResponseMetadata);
        Assert.DoesNotContain(Frames(result), f => Type(f) == 0x426);
    }

    [Fact]
    public async Task Observer_imports_both_previously_unknown_combatants_before_the_hit()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles, 4, 2);
        await Send(observer, "0F02", 1);
        await Send(Session(battles, 3, 3), "0F02", 1);
        var actor = Session(battles, 2, 2);
        await Send(actor, "0F02", 1);
        await Send(actor, Shot(2, 3), 2);
        var queue = (Channel<OriginalTacticalNotificationBatch>)typeof(NaturalAuthoritySession)
            .GetProperty("PendingNotifications", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(observer)!;
        Assert.True(queue.Reader.TryRead(out var batch));
        var frames = batch!.Frames.Select(f => f.ToArray()).ToArray();
        var imported = frames.Where(f => Type(f) == 0x325)
            .Select(f => BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(8))).ToArray();
        Assert.Equal(new uint[] { 2, 3 }, imported);
        var targetImport = Assert.Single(frames, f => Type(f) == 0x325 &&
            BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(8)) == 3);
        Assert.Equal((ushort)0, BinaryPrimitives.ReadUInt16BigEndian(targetImport.AsSpan(34)));
        Assert.Equal((ushort)0x426, Type(frames[^1]));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(frames[^1].AsSpan(16)));
    }

    [Fact]
    public async Task Living_hostile_player_prevents_completion_after_the_npc_dies()
    {
        var clock = new CombatClock();
        var battles = new OriginalTacticalBattleRegistry();
        var catalog=OriginalSharedBattleTests.CloseCombatCatalog();
        await Send(Session(battles, 3, 3,catalog,clock), "0F02", 1);
        var actor = Session(battles, 2, 2,catalog,clock);
        await Send(actor, "0F02", 1);
        for (uint i = 2; i <= 5; i++)
        {
            clock.Recharge();
            Assert.DoesNotContain(Frames(await Send(actor, Shot(2, 0x7F000001), i)), f => Type(f) == 0xF1F);
        }
        for (uint i = 6; i <= 8; i++) { clock.Recharge(); await Send(actor, Shot(2, 3), i); }
        clock.Recharge();
        Assert.Contains(Frames(await Send(actor, Shot(2, 3), 9)), f => Type(f) == 0xF1F);
    }

    [Fact]
    public async Task Destroyed_player_cannot_keep_attacking_after_refresh()
    {
        var clock = new CombatClock();
        var battles = new OriginalTacticalBattleRegistry();
        var victim = Session(battles, 3, 3,clock: clock);
        await Send(victim, "0F02", 1);
        var actor = Session(battles, 2, 2,clock: clock);
        await Send(actor, "0F02", 1);
        for (uint i = 2; i <= 5; i++) { clock.Recharge(); await Send(actor, Shot(2, 3), i); }
        Assert.Equal((100, 100), Damage(Frames(await Send(victim, "0F02", 2)), 3));
        var rejected = await Send(victim, Shot(3, 2), 3);
        Assert.StartsWith("command-reject=", rejected.ResponseMetadata);
        Assert.DoesNotContain(Frames(rejected), f => Type(f) == 0x426);
    }

    private static (int, int) Damage(IEnumerable<byte[]> frames, uint id)
    {
        var units = Assert.Single(frames, f => Type(f) == 0x325);
        int count = BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(6));
        for (int i = 0; i < count; i++)
        {
            int offset = 8 + i * 42;
            if (BinaryPrimitives.ReadUInt32BigEndian(units.AsSpan(offset)) == id)
                return (BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(offset + 26)),
                    BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(offset + 28)));
        }
        throw new InvalidOperationException("Target missing from unit projection");
    }

    [Fact]
    public async Task Alliance_automatic_attack_finds_enemy_player_instead_of_friendly_npc()
    {
        var battles = new OriginalTacticalBattleRegistry();
        await Send(Session(battles, 2, 2), "0F02", 1);
        var actor = Session(battles, 3, 3);
        await Send(actor, "0F02", 1);
        var result = await Send(actor, Shot(3, 0, attack: true), 2);
        var hit = Assert.Single(Frames(result), f => Type(f) == 0x426);
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(hit.AsSpan(16)));
    }

    [Fact]
    public async Task Alliance_victory_does_not_require_destroying_its_friendly_npc()
    {
        var clock = new CombatClock();
        var battles = new OriginalTacticalBattleRegistry();
        var document = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        document["templates"]![0]!["baseInformation"]![0]!["power"] = 3;
        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        await Send(Session(battles, 2, 2, catalog, clock), "0F02", 1);
        var actor = Session(battles, 3, 3, catalog, clock);
        await Send(actor, "0F02", 1);
        for (uint i = 2; i <= 4; i++) { clock.Recharge(); await Send(actor, Shot(3, 2), i); }
        clock.Recharge();
        Assert.Contains(Frames(await Send(actor, Shot(3, 2), 5)), f => Type(f) == 0xF1F);
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(23u)]
    [InlineData(uint.MaxValue)]
    public async Task Scene_projects_persisted_flagship_supplies(uint supplies)
    {
        var session = Session(new OriginalTacticalBattleRegistry(),2,2);
        OriginalWarpSessionClockTests.SetField(session,"_persistedGridUnit",
            new OriginalGridUnitRecord(2,2,39,101,1,Supplies:supplies));
        var frames = Frames(await Send(session,"0F02",1));
        var units = Assert.Single(frames,f=>Type(f)==0x325);
        var count = BinaryPrimitives.ReadUInt16BigEndian(units.AsSpan(6));
        var offsets = Enumerable.Range(0,count).Select(i=>8+i*42);
        var offset = Assert.Single(offsets,i=>BinaryPrimitives.ReadUInt32BigEndian(units.AsSpan(i))==2);
        Assert.Equal(supplies,BinaryPrimitives.ReadUInt32BigEndian(units.AsSpan(offset+30)));
    }

    private sealed class CombatClock : TimeProvider
    {
        private long _timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => _timestamp;
        public void Recharge() => _timestamp += 3000;
    }

    /// <summary>
    /// The rear grid's only fleet ship is the flagship-class picket. Asserting
    /// the ordinary 300-hull complement for every authored fleet ship made its
    /// kind carry two different numbers, the whole grid bootstrap was refused,
    /// and the live client hung on NOW LOADING with no way back.
    /// </summary>
    [Fact]
    public async Task A_grid_whose_fleet_is_flagship_class_still_bootstraps()
    {
        var catalog = OriginalBattlefieldCatalog.Load(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles, 2, 2, catalog, grid: 102);

        var entered = await Send(actor, "0F02", 1);

        Assert.Null(entered.ErrorCode);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, entered.Status);
        // The served template must carry the picket's own single hull.
        var ships = Frames(entered).Where(f => Type(f) is 0x0326 or 0x033b).ToList();
        Assert.NotEmpty(ships);
    }

    private static string Shot(uint actor, uint target, bool attack = false) =>
        (attack ? "0405" : "0406") + "00000000000000000000000201" + actor.ToString("X8") +
        (attack ? "01" : "0001") + target.ToString("X8");
    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession s, string hex, uint seq) =>
        s.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex), Key, seq), CancellationToken.None);
    private static ushort Type(byte[] f) => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4));
    private static List<byte[]> Frames(NaturalAuthoritySessionResult r)
    {
        Assert.Equal(NaturalAuthoritySessionStatus.Success, r.Status);
        return new[] { r.ResponsePayload! }.Concat(r.AdditionalResponses?.Select(p => p.Payload) ?? [])
            .Select(f => OriginalClientInnerFrameCodec.Decode(f, Key, 0).Payload!).ToList();
    }
    internal static NaturalAuthoritySession Session(OriginalTacticalBattleRegistry battles, uint id, byte power,
        OriginalBattlefieldCatalog? catalog = null, TimeProvider? clock = null, OriginalGameClock? gameClock = null,
        uint grid = 101)
    {
        var s = OriginalWarpSessionClockTests.CreateWorldEnteredSession(clock ?? TimeProvider.System, Key,
            catalog: catalog, store: new RosterStore(id, power), battles: battles, gameClock: gameClock);
        OriginalWarpSessionClockTests.SetField(s, "_worldCharacterId", id);
        OriginalWarpSessionClockTests.SetField(s, "_worldGridUnitId", id);
        // Give opposing combat fixtures distinct, in-range positions. Coincident
        // ships have no target bearing and cannot exercise accepted-shot paths.
        if (grid != 101) OriginalWarpSessionClockTests.SetField(s, "_worldGridCellId", grid);
        var pose = OriginalSystemSceneCodec.CreateTacticalBattlefield(id, id,
            (catalog ?? OriginalBattlefieldCatalog.LoadDefault()).Resolve(grid)).Records[0];
        OriginalWarpSessionClockTests.SetField(s, "_tacticalUnitShip",
            pose with { X = pose.X + (power == 3 ? 3 : 0) });
        OriginalWarpSessionClockTests.SetField(s, "_createdCharacter", new OriginalCreateCharacterCommand(
            4, id, power, 0, 0, $"Pilot{id}", "First", 18, 1, 1, 0, new byte[8], 0, 0, 0, 20,
            0, power == 3 ? (ushort)89 : (ushort)0, $"Flag{id}", 0, []));
        return s;
    }
    private sealed class RosterStore(uint id, byte power) : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new CharacterReadRecord(id, 0, power, 0, 0,
                $"Pilot{id}", "First", $"Flag{id}", 5, [1,2,3,4,5,6,7,8], 20, 0, power == 3 ? (ushort)89 : (ushort)0)]);
    }
}
