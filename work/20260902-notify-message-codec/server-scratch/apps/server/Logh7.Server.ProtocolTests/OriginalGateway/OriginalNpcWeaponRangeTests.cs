using Logh7.Server.OriginalGateway;
using System.Buffers.Binary;
using System.Threading.Channels;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcWeaponRangeTests
{
    [Theory]
    [InlineData(false, 3f)]
    [InlineData(true, 3f)]
    [InlineData(false, 10f)]
    [InlineData(true, 10f)]
    [InlineData(false, 0f)]
    [InlineData(true, 0f)]
    public async Task Sparse_range_repositioning_reaches_damage_and_wire(bool victimNpc, float targetDistance)
    {
        var rows = Enumerable.Range(0, 27).Select(_ => new short[8]).ToArray();
        rows[1][6] = 25;
        var caps = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            BeamArms = 1, BeamPower = 100, BeamAngleMask = 63,
            GunPower = 0, MissilePower = 0
        };
        var battles = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, viewer.Writer, 2);
        var victim = OriginalNpcAiTests.Actor(2, 2, targetDistance);
        if (victimNpc)
            battles.RegisterNpc(victim, caps with { BeamPower = 0 }, new(rows));
        else
            battles.UpdateParticipant(viewer.Writer, victim);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 3, 0), caps, new(rows));
        var fired = false;
        for (uint tick = 100; tick <= 1000; tick += 6)
        {
            var events = await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken);
            if (!events.Any(e => e.Actor == 10 && e.Action == "fire")) continue;
            fired = true;
            break;
        }
        Assert.True(fired, "Sparse-range NPC never fired through registry");
        Assert.True(battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged > 0);
        var frames = new List<byte[]>();
        while (viewer.Reader.TryRead(out var batch)) frames.AddRange(batch.Frames.Select(f => f.ToArray()));
        var hit = Assert.Single(frames, f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0x426
            && BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(10)) == 10);
        Assert.Equal((byte)1, hit[14]);
    }

    [Theory]
    [InlineData(3f)]
    [InlineData(10f)]
    [InlineData(0f)]
    public void Npc_reaches_sparse_effective_band_before_firing(float targetDistance)
    {
        var rows = Enumerable.Range(0, 27).Select(_ => new short[8]).ToArray();
        rows[1][6] = 25;
        var caps = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            BeamArms = 1, BeamPower = 100, BeamAngleMask = 63,
            GunPower = 0, MissilePower = 0
        };
        var npc = new OriginalTacticalNpcController(OriginalNpcAiTests.Actor(10, 3, 0), caps, new(rows));
        var target = OriginalNpcAiTests.Actor(2, 2, targetDistance);
        var fired = false;
        for (uint tick = 100; tick <= 1000; tick += 6)
        {
            var step = npc.Advance(tick, [target]);
            if (step.Arms is null) continue;
            var dx = step.Ship.X - target.Ship.X;
            var dy = step.Ship.Y - target.Ship.Y;
            var distance = MathF.Sqrt(dx * dx + dy * dy);
            Assert.True(distance >= 6 && distance < 7, $"Shot outside effective band: {distance}");
            fired = true;
        }
        Assert.True(fired, "NPC never reached its effective firing band");
    }

    [Fact]
    public void Npc_uses_effective_missile_instead_of_beam_with_zero_hit_at_target_distance()
    {
        // Authored fixture: beam only works in [6,7), missile works at 3.
        // This catches selecting a row solely by its last positive distance bin.
        var rows = Enumerable.Range(0, 27).Select(_ => new short[8]).ToArray();
        rows[1][6] = 25;
        // Original 004C7790: ship missile effects are arms12..15, not18.
        Array.Fill(rows[12], (short)25);
        var actor = OriginalNpcAiTests.Actor(10, 3, 0);
        var caps = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            BeamArms = 1, BeamPower = 100, BeamAngleMask = 63,
            GunPower = 0, MissileArms = 12, MissilePower = 100, MissileAngleMask = 63
        };
        var npc = new OriginalTacticalNpcController(actor, caps, new OriginalStaticArmsTable(rows));
        var target = OriginalNpcAiTests.Actor(2, 2, 3);
        Assert.Null(npc.Advance(100, [target]).Arms);
        OriginalNpcStep shot = default;
        for (uint tick = 106; tick <= 172; tick += 6)
            shot = npc.Advance(tick, [target]);
        Assert.Equal((byte)12, shot.Arms);
        Assert.Equal(actor.Ship.X, shot.Ship.X);
        Assert.Equal(actor.Ship.Y, shot.Ship.Y);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Range_selected_missile_reaches_damage_and_wire_for_player_or_npc(bool victimNpc)
    {
        var rows = Enumerable.Range(0, 27).Select(_ => new short[8]).ToArray();
        rows[1][6] = 25;
        Array.Fill(rows[12], (short)25);
        var caps = OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with
        {
            BeamArms = 1, BeamPower = 100, BeamAngleMask = 63,
            GunPower = 0, MissileArms = 12, MissilePower = 100, MissileAngleMask = 63
        };
        var battles = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, viewer.Writer, 2);
        var victim = OriginalNpcAiTests.Actor(2, 2, 3);
        if (victimNpc)
            battles.RegisterNpc(victim, caps with { BeamPower = 0, MissilePower = 0 }, new(rows));
        else
            battles.UpdateParticipant(viewer.Writer, victim);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(10, 3, 0), caps, new(rows));
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick <= 172; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        var shot = Assert.Single(events, e => e.Actor == 10 && e.Action == "fire");
        Assert.Equal((byte)12, shot.Arms);
        Assert.Equal((ushort)25, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
        var frames = new List<byte[]>();
        while (viewer.Reader.TryRead(out var batch)) frames.AddRange(batch.Frames.Select(f => f.ToArray()));
        var frame = Assert.Single(frames, f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4)) == 0x426
            && BinaryPrimitives.ReadUInt32BigEndian(f.AsSpan(10)) == 10);
        Assert.Equal((byte)12, frame[14]);
    }
}
