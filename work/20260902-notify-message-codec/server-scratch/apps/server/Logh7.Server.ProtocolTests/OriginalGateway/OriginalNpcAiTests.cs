using System.Buffers.Binary;
using System.Threading.Channels;
using System.Reflection;
using System.Net;
using Logh7.Server.Compatibility;
using Logh7.Server.Hosting;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcAiTests
{
    [Fact]
    public async Task Long_service_pause_does_not_accumulate_npc_movement()
    {
        var ct = TestContext.Current.CancellationToken;
        var normal = new OriginalTacticalBattleRegistry();
        var paused = new OriginalTacticalBattleRegistry();
        var normalViewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var pausedViewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var normalSubscription = normal.Subscribe(101, 100, normalViewer.Writer, 2);
        using var pausedSubscription = paused.Subscribe(101, 100, pausedViewer.Writer, 2);
        var initial = Actor(10, 3, 0);
        normal.UpdateParticipant(normalViewer.Writer, Actor(2, 2, 20));
        paused.UpdateParticipant(pausedViewer.Writer, Actor(2, 2, 20));
        foreach (var registry in new[] { normal, paused })
        {
            registry.RegisterNpc(initial, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
                OriginalAuthoredPlayableCatalog.TacticalArms);
            for (uint tick = 100; tick <= 112; tick += 6)
                await registry.AdvanceNpcsAsync(tick, ct);
        }
        var before = paused.NpcSnapshot(101, 10)!.Ship;
        paused.RecordExecuting(10, initial.ShipGeneration, 1912);
        Assert.Empty(await paused.AdvanceNpcsAsync(1911, ct));
        Assert.Equal(before, paused.NpcSnapshot(101, 10)!.Ship);
        await normal.AdvanceNpcsAsync(118, ct);
        await paused.AdvanceNpcsAsync(1912, ct);
        var expected = normal.NpcSnapshot(101, 10)!.Ship;
        Assert.NotEqual(before, expected);
        Assert.Equal(expected, paused.NpcSnapshot(101, 10)!.Ship);
    }

    [Theory]
    [InlineData(3f)]
    [InlineData(20f)]
    public async Task Executing_npc_neither_moves_nor_fires_until_its_operation_expires(float targetX)
    {
        var ct = TestContext.Current.CancellationToken;
        var battles = new OriginalTacticalBattleRegistry();
        var initial = Actor(10, 3, 0);
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, viewer.Writer, 2);
        battles.UpdateParticipant(viewer.Writer, Actor(2, 2, targetX));
        battles.RegisterNpc(initial, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        battles.RecordExecuting(10, initial.ShipGeneration, 200);
        for (uint tick = 100; tick < 200; tick += 6)
            Assert.Empty(await battles.AdvanceNpcsAsync(tick, ct));
        Assert.Equal(initial.Ship, battles.NpcSnapshot(101, 10)!.Ship);
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
        var after = new List<OriginalNpcEvent>();
        // Allow the normal turn/recharge cycle after resuming; expiry is not an instant shot.
        for (uint tick = 200; tick <= 290; tick += 6)
            after.AddRange(await battles.AdvanceNpcsAsync(tick, ct));
        Assert.Contains(after, e => e.Actor == 10 && e.Action == (targetX == 3 ? "fire" : "move"));
    }

    [Fact]
    public async Task Player_killed_by_npc_cannot_accept_a_new_movement_command()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2);
        battles.GetEncounter(101, 100).RecordUnitDamage(2, new(100, 100));
        var move = OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0, 0, 2,
            [new(2, 0, -10, 0, 0)], 1, 0, [new(10, 0, 0)]));
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(move.AsSpan(4), new byte[16], 1), CancellationToken.None);
        Assert.StartsWith("command-reject=TACTICAL_ACTOR_DESTROYED", result.ResponseMetadata);
    }

    [Fact]
    public async Task Native_session_bootstrap_registers_npc_and_refresh_keeps_moved_pose()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2);
        var key = new byte[16];
        var entry = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, entry.Status);
        Assert.NotNull(battles.NpcSnapshot(101, 0x7f000001));
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0348000100000002"), key, 2), CancellationToken.None);
        for (uint tick = 100; tick <= 136; tick += 6) await battles.AdvanceNpcsAsync(tick, CancellationToken.None);
        var moved = battles.NpcSnapshot(101, 0x7f000001)!.Ship;
        Assert.True(moved.X < 10);
        var refresh = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 3), CancellationToken.None);
        var raw = new[] { refresh.ResponsePayload! }.Concat(refresh.AdditionalResponses!.Select(p => p.Payload))
            .Select(f => OriginalClientInnerFrameCodec.Decode(f, key, 0).Payload!).Single(
                f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0x33b);
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(raw.AsSpan(4), out var ships));
        Assert.Equal(moved, Assert.Single(ships.Records, s => s.Id == 0x7f000001));
    }

    [Fact]
    public async Task Running_server_automatically_ticks_without_network_input_and_stops_cleanly()
    {
        var fixture = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, new byte[16]);
        T Field<T>(object value, string name) => (T)value.GetType().GetField(name,
            BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(value)!;
        var path = Path.Combine(Path.GetTempPath(), $"logh7-npc-host-{Guid.NewGuid():N}.jsonl");
        await using var host = new NaturalAuthorityServer(new(IPAddress.Loopback, 0, IPAddress.Loopback, 47900, path),
            Field<OriginalLoginAuthority>(fixture, "_loginAuthority"), Field<HandoffRegistry>(fixture, "_handoffs"),
            new OriginalWarpSessionClockTests.UnusedStore(), Field<MetadataOnlyGatewayReceipt>(fixture, "_receipt"));
        var registry = Field<OriginalTacticalBattleRegistry>(host, "_battles");
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = registry.Subscribe(101, 100, viewer.Writer, 2);
        registry.UpdateParticipant(viewer.Writer, Actor(2, 2, 3));
        registry.RegisterNpc(Actor(10, 3, 0), OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        await host.StartAsync(CancellationToken.None);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(6));
        var hit = false;
        try
        {
            while (!hit && await viewer.Reader.WaitToReadAsync(timeout.Token))
                while (viewer.Reader.TryRead(out var batch))
                    hit |= batch.Frames.Any(f => f.Length >= 6 &&
                        BinaryPrimitives.ReadUInt16BigEndian(f.Span[4..]) == 0x426);
        }
        catch (OperationCanceledException) when (timeout.IsCancellationRequested) { }
        await host.StopAsync(CancellationToken.None);
        Assert.True(hit, "No autonomous NPC damage arrived within six seconds");
        Assert.Contains("npc-ai", File.ReadAllText(path));
        using var fireReceipt=System.Text.Json.JsonDocument.Parse(File.ReadLines(path)
            .First(line=>line.Contains("\"npc-ai\"") && line.Contains("\"fire\"")));
        Assert.True(fireReceipt.RootElement.GetProperty("decision").TryGetProperty("arms",out var recordedArms),
            "Autonomous fire receipt must identify the actual 0426 weapon, not just damage");
        Assert.Equal(1,recordedArms.GetInt32());
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Registry_runs_npc_vs_player_and_npc_vs_npc_without_client_attacks(bool victimNpc)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, viewer.Writer, 2);
        var victim = Actor(2, 2, 3);
        using (await battles.LockAsync(101, 100, CancellationToken.None))
        {
            battles.RegisterNpc(Actor(10, 3, 0), OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
                OriginalAuthoredPlayableCatalog.TacticalArms);
            if (victimNpc) battles.RegisterNpc(victim, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
                OriginalAuthoredPlayableCatalog.TacticalArms);
            else battles.UpdateParticipant(viewer.Writer, victim);
        }
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick <= 190; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, CancellationToken.None));
        Assert.Contains(events, e => e.Actor == 10 && e.Target == 2 && e.Action == "fire");
        Assert.Equal((ushort)25, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
        var frames = new List<byte[]>();
        while (viewer.Reader.TryRead(out var batch)) frames.AddRange(batch.Frames.Select(f => f.ToArray()));
        Assert.Contains(frames, f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0x426);
        var actualHit=Assert.Single(frames,f=>BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))==0x426
            && BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(10))==10);
        var fire=Assert.Single(events,e=>e.Actor==10 && e.Target==2 && e.Action=="fire");
        Assert.Equal((byte)1,fire.Arms);
        Assert.Equal(actualHit[14],fire.Arms); // header6 + time4 + attacker4
        Assert.All(events.Where(e=>e.Action=="move"),e=>Assert.Null(e.Arms));
        var actorEntry = frames.FindIndex(f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0xB09);
        var hitIndex = frames.FindIndex(f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0x426);
        Assert.InRange(actorEntry, 0, hitIndex - 1);
    }

    [Fact]
    public async Task Registering_from_another_scene_does_not_reset_npc_pose_or_cooldown()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var initial = Actor(10, 3, 0);
        battles.RegisterNpc(initial, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        battles.UpdateParticipant(viewer.Writer, Actor(2, 2, 20));
        for (uint tick = 100; tick <= 124; tick += 6) await battles.AdvanceNpcsAsync(tick, CancellationToken.None);
        var moved = battles.NpcSnapshot(101, 10);
        Assert.NotNull(moved);
        Assert.True(moved.Ship.X > 0);
        battles.RegisterNpc(initial, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        Assert.Equal(moved.Ship, battles.NpcSnapshot(101, 10)!.Ship);
    }

    [Fact]
    public async Task Npc_tick_waits_for_player_command_lease_and_stops_after_death()
    {
        var battles = new OriginalTacticalBattleRegistry();
        battles.RegisterNpc(Actor(10, 3, 0), OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        using var lease = await battles.LockAsync(101, 100, CancellationToken.None);
        var tick = battles.AdvanceNpcsAsync(100, CancellationToken.None);
        Assert.False(tick.IsCompleted);
        battles.GetEncounter(101, 100).RecordUnitDamage(10, new(100, 100));
        lease.Dispose();
        Assert.Empty(await tick);
        Assert.Empty(await battles.AdvanceNpcsAsync(200, CancellationToken.None));
    }

    [Fact]
    public void Npc_approaches_an_enemy_without_any_player_command()
    {
        var npc = Controller();
        npc.Advance(100, [Actor(2, 2, 20)]);
        npc.Advance(106, [Actor(2, 2, 20)]); // turn before translation
        var step = npc.Advance(112, [Actor(2, 2, 20)]);
        Assert.Equal(2u, step.TargetId);
        Assert.InRange(step.Ship.X, 0.01f, 2f);
        Assert.Null(step.Arms);
    }

    [Fact]
    public void Npc_finishes_approach_then_fires_instead_of_stalling_at_standoff_distance()
    {
        var npc = Controller();
        var target = Actor(2, 2, 20);
        var fired = false;
        for (uint tick = 100; tick <= 400; tick += 6)
            fired |= npc.Advance(tick, [target]).Arms is not null;
        Assert.True(fired);
        Assert.InRange(npc.Snapshot.Ship.X, 15.9f, 16.1f);
    }

    [Fact]
    public async Task Disconnected_player_is_no_longer_an_npc_target()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(viewer.Writer, Actor(2, 2, 3));
        battles.RegisterNpc(Actor(10, 3, 0), OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        await battles.AdvanceNpcsAsync(100, CancellationToken.None);
        await battles.AdvanceNpcsAsync(106, CancellationToken.None);
        battles.RemoveParticipant(viewer.Writer);
        Assert.Empty(await battles.AdvanceNpcsAsync(200, CancellationToken.None));
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
    }

    [Fact]
    public void Encoded_ai_move_roundtrips_the_original_command_field_order()
    {
        var command = new OriginalTacticalMoveShipCommand(123, 0, 2,
            [new(10, -.5f, 12, 3, 0)], .2f, .75f, [new(11, 4, 0)]);
        var encoded = OriginalTacticalCommandCodec.EncodeMoveShipCommand(command);
        Assert.Equal(60, encoded.Length);
        Assert.Equal("04000000007B0000000000000002010000000A", Convert.ToHexString(encoded.AsSpan(4, 19)));
        Assert.True(OriginalTacticalCommandCodec.TryDecodeMoveShipCommand(encoded.AsSpan(4), out var decoded));
        Assert.Equal(command.Time, decoded.Time);
        Assert.Equal(command.Units, decoded.Units);
        Assert.Equal(command.Destinations, decoded.Destinations);
        Assert.Equal(command.Velocity, decoded.Velocity);
        Assert.Equal(command.ToDirection, decoded.ToDirection);
    }

    [Theory]
    [InlineData(3, 101u, 0, 2f)] // friendly
    [InlineData(2, 102u, 0, 2f)] // other map
    [InlineData(2, 101u, 100, 2f)] // dead
    [InlineData(2, 101u, 0, 200f)] // beyond sensor
    public void Npc_does_not_select_illegal_targets(byte power, uint grid, ushort destroyed, float x)
    {
        var npc = Controller();
        var target = Actor(2, power, x, grid: grid, destroyed: destroyed);
        npc.Advance(100, [target]);
        var step = npc.Advance(200, [target]);
        Assert.Equal(0u, step.TargetId);
        Assert.Equal(0f, step.Ship.X);
        Assert.Null(step.Arms);
    }

    [Fact]
    public void Npc_turns_into_an_equipped_arc_then_fires_with_a_cooldown()
    {
        var npc = Controller();
        var target = Actor(2, 2, 3);
        npc.Advance(100, [target]);
        var turn = npc.Advance(106, [target]);
        Assert.NotEqual(0, turn.Ship.Direction);
        Assert.Null(turn.Arms);
        OriginalNpcStep shot = default;
        for (uint tick = 112; tick <= 172; tick += 6) shot = npc.Advance(tick, [target]);
        Assert.Equal((byte)1, shot.Arms);
        Assert.Null(npc.Advance(172, [target]).Arms);
        Assert.Null(npc.Advance(173, [target]).Arms);
        Assert.Equal((byte)1, npc.Advance(244, [target]).Arms);
    }

    [Fact]
    public void Death_or_departure_reselects_a_living_enemy_deterministically()
    {
        var npc = Controller();
        npc.Advance(100, [Actor(2, 2, 2), Actor(3, 2, 3)]);
        Assert.Equal(2u, npc.Advance(106, [Actor(2, 2, 2), Actor(3, 2, 3)]).TargetId);
        Assert.Equal(3u, npc.Advance(112, [Actor(2, 2, 2, destroyed: 100), Actor(3, 2, 3)]).TargetId);
        Assert.Equal(0u, npc.Advance(118, []).TargetId);
    }

    [Fact]
    public void Unpowered_weapons_cannot_create_periodic_damage()
    {
        var actor = Actor(10, 3, 0);
        actor = new(actor.Unit, actor.Ship, actor.Corps with { PowerBeam = 0, PowerGun = 0 }, [], 3);
        var npc = new OriginalTacticalNpcController(actor, OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        for (uint tick = 100; tick <= 500; tick += 6)
            Assert.Null(npc.Advance(tick, [Actor(2, 2, 2)]).Arms);
    }

    [Theory]
    [InlineData(.50053215f, 1)]
    [InlineData(1.5673075f, 0)]
    [InlineData(3.1415927f, 4)]
    public void Arc_selection_matches_E074_live_input_model(float heading, int sector)
    {
        Assert.Equal(sector, OriginalTacticalNpcController.Sector(1.54301345f, heading));
    }

    [Fact]
    public void Npc_reaction_delay_starts_when_a_late_target_becomes_available()
    {
        var npc = Controller();
        npc.Advance(100, []);
        npc.Advance(200, []);
        var target = Actor(2, 2, 3);
        Assert.Null(npc.Advance(206, [target]).Arms);
        for (uint tick = 212; tick <= 272; tick += 6)
            Assert.Null(npc.Advance(tick, [target]).Arms);
        Assert.Equal((byte)1, npc.Advance(278, [target]).Arms);
    }

    [Theory]
    [InlineData(0, 50f, true)]
    [InlineData(0, 51f, false)]
    [InlineData(7, 51f, false)]
    [InlineData(8, 70f, true)]
    [InlineData(8, 71f, false)]
    [InlineData(10, 70f, true)] // v174 live entity+958: SENSOR10, template100 ->70
    [InlineData(14, 71f, false)]
    [InlineData(15, 90f, true)]
    [InlineData(15, 91f, false)]
    [InlineData(22, 91f, false)]
    [InlineData(23, 100f, true)]
    [InlineData(100, 100f, true)]
    [InlineData(100, 101f, false)]
    public void Original_sensor_bands_limit_npc_target_acquisition(byte sensor, float distance, bool detectable)
    {
        var actor = Actor(10, 3, 0);
        actor = new(actor.Unit, actor.Ship, actor.Corps with { PowerSensor = sensor },
            actor.CharacterFrame.ToArray(), actor.Power);
        var npc = new OriginalTacticalNpcController(actor,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with { SearchingRange = 100 },
            OriginalAuthoredPlayableCatalog.TacticalArms);
        var step = npc.Advance(100, [Actor(2, 2, distance)]);
        Assert.Equal(detectable ? 2u : 0u, step.TargetId);
        Assert.Null(step.Arms);
        Assert.Equal(0f, step.Ship.X);
    }

    private static OriginalTacticalNpcController Controller() => new(Actor(10, 3, 0),
        OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, OriginalAuthoredPlayableCatalog.TacticalArms);

    internal static OriginalTacticalParticipantSnapshot Actor(uint id, byte power, float x, float y = 0,
        uint grid = 101, ushort destroyed = 0) => new(
            new(id, grid, 0, 100, destroyed, destroyed, 100, 100, 10),
            new(id, 100, 0, id, x, y, 0, 0, 0, 0, 0, 0, 0, 1),
            OriginalSystemSceneCodec.CreatePlayableTacticalCorps(id),
            OriginalWorldEntryCodec.EncodeCharacter(id, id, 0,
                new(4, id, power, 0, 0, $"Actor{id}", "", 18, 1, 1, 0, new byte[8], 0, 0, 0, 20,
                    0, 0, "", 0, [])), power);
}
