using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcSceneImportTests
{
    // The original 0348 request enumerates imported tactical entity IDs.
    // This is an authored NPC activation boundary, not an original Ready opcode.
    [Fact]
    public async Task Bootstrap_roster_is_visible_but_npc_does_not_approach_until_own_entity_is_polled()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var player = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(player, "0F02", 1);
        var start = battles.NpcSnapshot(101, 0x7f000001)!.Ship;
        Assert.Contains(battles.OtherParticipants(101, 0), p => p.Unit.Id == 2);

        var before = await Advance(battles, 100, 400);
        Assert.Empty(before);
        Assert.Equal(start, battles.NpcSnapshot(101, 0x7f000001)!.Ship);
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);

        var poll = await Send(player, "0348000100000002", 2);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, poll.Status);
        var after = await Advance(battles, 406, 700);
        Assert.Contains(after, e => e.Action == "move" && e.Target == 2);
        Assert.Contains(after, e => e.Action == "fire" && e.Target == 2);
    }

    [Theory]
    [InlineData("03480000")] // empty
    [InlineData("034800017F000001")] // only the NPC, not self
    [InlineData("0348000100000003")] // another player
    [InlineData("0348000200000002")] // truncated
    [InlineData("034A0100000001")] // bases
    [InlineData("0300")] // time is also requested while loading
    public async Task Unrelated_or_malformed_polls_do_not_activate_loading_player(string request)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var player = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(player, "0F02", 1);
        await Send(player, request, 2);
        Assert.Empty(await Advance(battles, 100, 400));
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
    }

    [Fact]
    public async Task Same_grid_reimport_requires_new_own_poll_without_resetting_pose_or_damage()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var player = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(player, "0F02", 1);
        await Send(player, "0348000100000002", 2);
        var events = await Advance(battles, 100, 202);
        Assert.Contains(events, e => e.Action == "fire");
        var encounter = battles.GetEncounter(101, 100);
        var damage = encounter.GetUnitDamage(2);
        var pose = battles.NpcSnapshot(101, 0x7f000001)!.Ship;

        await Send(player, "0F02", 3);
        Assert.Empty(await Advance(battles, 208, 502));
        Assert.Equal(damage, encounter.GetUnitDamage(2));
        Assert.Equal(pose, battles.NpcSnapshot(101, 0x7f000001)!.Ship);

        await Send(player, "0348000100000002", 4);
        Assert.Contains(await Advance(battles, 508, 610), e => e.Action == "fire" && e.Target == 2);
        Assert.True(encounter.GetUnitDamage(2).Damaged > damage.Damaged);
    }

    [Fact]
    public async Task Loading_player_does_not_pause_npc_versus_npc_or_disappear_from_roster()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var player = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(player, "0F02", 1);
        battles.RegisterNpc(OriginalNpcAiTests.Actor(20, 2, 12),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities,
            OriginalAuthoredPlayableCatalog.TacticalArms);
        var events = await Advance(battles, 100, 202);
        Assert.Contains(events, e => e.Action == "fire" && (e.Actor == 20 || e.Target == 20));
        Assert.DoesNotContain(events, e => e.Target == 2);
        Assert.Contains(battles.OtherParticipants(101, 0), p => p.Unit.Id == 2);
        Assert.Equal((ushort)0, battles.GetEncounter(101, 100).GetUnitDamage(2).Damaged);
    }

    [Fact]
    public async Task A_loading_duplicate_connection_does_not_shield_an_already_active_unit()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var active = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(active, "0F02", 1);
        await Send(active, "0348000100000002", 2);
        var loading = OriginalPlayerCombatTests.Session(battles, 2, 2);
        await Send(loading, "0F02", 1);
        Assert.Contains(await Advance(battles, 100, 202), e => e.Action == "fire" && e.Target == 2);
    }

    [Fact]
    public async Task Accepted_player_fire_removes_npc_protection_even_without_position_poll()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var player = OriginalPlayerCombatTests.Session(battles, 2, 2,OriginalSharedBattleTests.CloseCombatCatalog());
        await Send(player, "0F02", 1);
        var shot = await Send(player, "0406000000000000000000000002010000000200017F000001", 2);
        Assert.StartsWith("tactical-command-accepted", shot.ResponseMetadata);
        Assert.Contains(await Advance(battles, 100, 202), e => e.Action == "fire" && e.Target == 2);
    }

    [Fact]
    public async Task Living_loading_player_still_prevents_enemy_npc_victory()
    {
        // No authored objectives, so the loading player's presence is the
        // only reason this otherwise-finished encounter cannot complete.
        var isolated = new OriginalTacticalBattleRegistry();
        var viewer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        isolated.UpdateParticipant(viewer.Writer, OriginalNpcAiTests.Actor(2, 2, 30), npcTargetReady: false);
        isolated.RegisterNpc(OriginalNpcAiTests.Actor(10, 3, 0),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, OriginalAuthoredPlayableCatalog.TacticalArms, []);
        isolated.RegisterNpc(OriginalNpcAiTests.Actor(20, 2, 3),
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, OriginalAuthoredPlayableCatalog.TacticalArms);
        var e = isolated.GetEncounter(101, 100);
        e.RecordUnitDamage(20, new(75, 0));
        await Advance(isolated, 100, 202);
        Assert.False(e.HasSurvivors(20));
        Assert.True(e.HasSurvivors(2));
        Assert.False(e.IsCompleted);
    }

    private static Task<NaturalAuthoritySessionResult> Send(NaturalAuthoritySession session, string hex, uint sequence) =>
        session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),
            new byte[16], sequence), CancellationToken.None);

    private static async Task<List<OriginalNpcEvent>> Advance(OriginalTacticalBattleRegistry battles, uint start, uint end)
    {
        var events = new List<OriginalNpcEvent>();
        for (var tick = start; tick <= end; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, CancellationToken.None));
        return events;
    }
}
