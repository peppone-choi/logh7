using System.Buffers.Binary;
using System.Reflection;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalSharedControlTests
{
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task A_connection_reads_the_accepted_power_allocation_without_actor_movement(bool duplicateUnit)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2);
        var observer = OriginalPlayerCombatTests.Session(battles, duplicateUnit ? 2u : 3u, 2);
        await Send(actor, "0F02", 1);
        await Send(observer, "0F02", 1);
        var control = await Send(actor,
            "040C0000000000000000000000020000000200320A14040403030303140A14", 2);
        Assert.StartsWith("tactical-control-accepted", control.ResponseMetadata);
        var query = await Send(observer, "033E000100000002", 2);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, query.Status);
        var frame = OriginalClientInnerFrameCodec.Decode(query.ResponsePayload!, new byte[16], 0).Payload!;
        Assert.Equal(0x33f, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal(1, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(8)));
        Assert.Equal(20, frame[26]); // record+18 SENSOR
        Assert.Equal(10, frame[27]); // record+19 BEAM
        Assert.Equal(100, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(35))); // FillBeam unchanged
    }

    [Fact]
    public async Task Destroyed_ship_cannot_change_the_shared_power_allocation()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2);
        var observer = OriginalPlayerCombatTests.Session(battles, 3, 2);
        await Send(actor, "0F02", 1);
        await Send(observer, "0F02", 1);
        battles.GetEncounter(101, 100).RecordUnitDamage(2, new(100, 100));
        var control = await Send(actor,
            "040C0000000000000000000000020000000200320A14040403030303140A14", 2);
        Assert.StartsWith("command-reject=TACTICAL_ACTOR_DESTROYED", control.ResponseMetadata);
        Assert.Null(typeof(OriginalTacticalBattleRegistry)
            .GetMethod("PlayerControl", BindingFlags.Instance | BindingFlags.NonPublic)!
            .Invoke(battles, [2u, 0L]));
        Assert.Null(typeof(NaturalAuthoritySession)
            .GetField("_tacticalPlayerCorps", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(actor));
        Assert.DoesNotContain(control.AdditionalResponses ?? [], p =>
            BinaryPrimitives.ReadUInt16BigEndian(OriginalClientInnerFrameCodec.Decode(p.Payload,
                new byte[16], 0).Payload!.AsSpan(4)) == 0x33f);
    }

    [Fact]
    public async Task Duplicate_connection_cannot_fire_with_an_old_enabled_beam_allocation()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = OriginalPlayerCombatTests.Session(battles, 2, 2);
        var duplicate = OriginalPlayerCombatTests.Session(battles, 2, 2);
        var enemy = OriginalPlayerCombatTests.Session(battles, 3, 3);
        await Send(actor, "0F02", 1);
        await Send(duplicate, "0F02", 1);
        await Send(enemy, "0F02", 1);
        var control = await Send(actor,
            "040C0000000000000000000000020000000200320014040403030303140A14", 2);
        Assert.StartsWith("tactical-control-accepted", control.ResponseMetadata);
        var shot = await Send(duplicate, "04060000000000000000000000020100000002000100000003", 2);
        Assert.StartsWith("command-reject=TACTICAL_WEAPON_POWER_INSUFFICIENT", shot.ResponseMetadata);
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(3).Damaged);
    }

    [Fact]
    public void Replaced_ship_does_not_inherit_or_get_overwritten_by_old_generation_control()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var old = OriginalSystemSceneCodec.CreatePlayableTacticalCorps(2) with { PowerBeam = 0 };
        var current = old with { PowerBeam = 10 };
        void Record(long generation, OriginalTacticalCorpsRecord value) => typeof(OriginalTacticalBattleRegistry)
            .GetMethod("RecordPlayerControl", BindingFlags.Instance | BindingFlags.NonPublic)!
            .Invoke(battles, [2u, generation, value]);
        OriginalTacticalCorpsRecord? Read(long generation) => (OriginalTacticalCorpsRecord?)typeof(OriginalTacticalBattleRegistry)
            .GetMethod("PlayerControl", BindingFlags.Instance | BindingFlags.NonPublic)!
            .Invoke(battles, [2u, generation]);
        Record(1, old);
        Assert.Null(Read(2));
        Record(2, current);
        Record(1, old);
        Assert.Equal((byte)10, Read(2)!.Value.PowerBeam);
        Assert.Null(Read(1));
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string payload, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(payload),
            new byte[16], sequence), TestContext.Current.CancellationToken);
}
