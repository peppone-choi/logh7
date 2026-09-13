using System.Buffers.Binary;
using System.Reflection;
using Logh7.Server.Compatibility;
using System.Text.Json.Nodes;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalFleetContentTests
{
    [Fact]
    public async Task Regular_camp_player_can_fire_at_same_power_opposing_camp()
    {
        var doc = Document();
        doc["templates"]![2]!["fleets"]![1]!["power"] = 2;
        doc["templates"]![2]!["fleets"]![1]!["camp"] = 1;
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles,2,2,
            OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
        OriginalWarpSessionClockTests.SetField(session,"_tacticalUnitShip",
            OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(2,2) with { Morale=100 });
        var key = new byte[16];
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),key,1),TestContext.Current.CancellationToken);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0406000000000000000000000002010000000200017E000003"),key,2),
            TestContext.Current.CancellationToken);
        Assert.Equal((ushort)25,battles.GetEncounter(101,100).GetUnitDamage(2113929219).Damaged);
        Assert.False(battles.GetEncounter(101,100).IsCompleted);
    }
    [Fact]
    public async Task Distinct_camps_of_the_same_power_fight_as_distinct_sides()
    {
        var doc = Document();
        doc["templates"]![2]!["fleets"]![1]!["power"] = 2;
        doc["templates"]![2]!["fleets"]![1]!["camp"] = 1;
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2,
            OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
        var entered = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), new byte[16], 1), TestContext.Current.CancellationToken);
        Assert.Contains(entered.AdditionalResponses!, response =>
            BinaryPrimitives.ReadUInt16BigEndian(OriginalClientInnerFrameCodec.Decode(
                response.Payload,new byte[16],0).Payload!.AsSpan(4)) == 0x0f1f);
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick < 250; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        Assert.Contains(events, e => e.Action == "fire" && e.Actor == 2113929217 &&
            e.Target is 2113929219 or 2113929220);
        Assert.DoesNotContain(events, e => e.Action == "fire" && e.Actor == 2113929217 &&
            e.Target == 2113929218);
    }
    internal static JsonNode Document()
    {
        var doc = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,
            "battlefields", "catalog.json")))!;
        var field = doc["templates"]![0]!.DeepClone();
        field["grid"] = 101;
        field["spawnEnemy"] = false;
        field["fleets"] = JsonNode.Parse("""
            [{"id":2113929312,"power":2,"ships":[
                {"id":2113929217,"kind":56,"spawn":{"x":-3,"y":0,"z":0,"direction":0}},
                {"id":2113929218,"kind":56,"spawn":{"x":-3,"y":2,"z":0,"direction":0}}]},
             {"id":2113929313,"power":3,"ships":[
                {"id":2113929219,"kind":119,"spawn":{"x":3,"y":0,"z":0,"direction":3.14}},
                {"id":2113929220,"kind":119,"spawn":{"x":3,"y":2,"z":0,"direction":3.14}}]}]
            """);
        doc["templates"]!.AsArray().Add(field);
        return doc;
    }

    [Fact]
    public async Task Scene_import_registers_two_fleets_and_sends_membership_before_scene_terminator()
    {
        var catalog = OriginalBattlefieldCatalog.Parse(Document().ToJsonString());
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(new OriginalTacticalBattleRegistry(), 2, 2, catalog);
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var battles = (OriginalTacticalBattleRegistry)session.GetType().GetField("_battles",
            BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(session)!;
        for (uint id = 2113929217; id <= 2113929220; id++)
            Assert.NotNull(battles.NpcSnapshot(101, id));
        Assert.Equal(2113929312u, battles.NpcSnapshot(101, 2113929217)!.Unit.Outfit);
        Assert.Equal(2113929313u, battles.NpcSnapshot(101, 2113929220)!.Unit.Outfit);
        Assert.Equal(300, battles.GetEncounter(101, 100).UnitNumber(2113929217));
        Assert.Null(battles.NpcSnapshot(102, 2113929217));
        var frames = new[] { result.ResponsePayload! }.Concat(result.AdditionalResponses!.Select(p => p.Payload))
            .Select(f => OriginalClientInnerFrameCodec.Decode(f, key, 0).Payload!).ToArray();
        var types = frames.Select(f => BinaryPrimitives.ReadUInt16BigEndian(f.AsSpan(4))).ToArray();
        var outfitIndex = Array.IndexOf(types, (ushort)0x032b);
        Assert.True(outfitIndex >= 0);
        Assert.Equal(2, frames[outfitIndex][6]);
        Assert.True(outfitIndex < Array.IndexOf(types, (ushort)0x0f03));
        Assert.Equal(0, battles.NpcSnapshot(101, 2113929217)!.CharacterFrame.Length);
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick < 250; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        Assert.Contains(events, e => e.Action == "fire" && e.Actor >= 2113929217 && e.Target >= 2113929217);
        var moved = battles.NpcSnapshot(101, 2113929217)!;
        Assert.Equal(2113929312u, moved.Outfit!.Value.Id);
        Assert.All(moved.EncodeEntry(), frame => Assert.True(frame.Length >= 6));
        Assert.Contains(moved.EncodeEntry(), frame =>
            BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x032b);
        Assert.DoesNotContain(moved.EncodeEntry(), frame =>
            BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x0323);
    }

    [Fact]
    public void Duplicate_ship_identity_in_two_fleets_is_rejected()
    {
        var doc = Document();
        doc["templates"]![2]!["fleets"]![1]!["ships"]![0]!["id"] = 2113929217;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
    }

    [Fact]
    public async Task Fleet_party_query_returns_live_membership_and_casualties_not_fictional_characters()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2,
            OriginalBattlefieldCatalog.Parse(Document().ToJsonString()));
        var key = new byte[16];
        byte sequence = 0;
        async Task<NaturalAuthoritySessionResult> Send(byte[] request) => await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, ++sequence), TestContext.Current.CancellationToken);
        await Send(Convert.FromHexString("0F02"));
        battles.GetEncounter(101, 100).RecordUnitDamage(2113929217, new(50, 50));
        var result = await Send(OriginalOutfitPartyCodec.EncodeRequest(new(2113929312, 0, 1))[4..]);
        Assert.True(result.Status == NaturalAuthoritySessionStatus.Success, result.ToString());
        var raw = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0).Payload!;
        Assert.True(OriginalOutfitPartyCodec.TryDecodeResponse(raw.AsSpan(4), out var party));
        Assert.Equal(2113929312u, party.OutfitId);
        Assert.Empty(party.Characters);
        var ship = Assert.Single(party.Ships);
        Assert.Equal(new uint[] { 2113929217, 2113929218 }, ship.Units);
        Assert.Equal(550, ship.BoatNumber);
        Assert.Equal(2, ship.UnitNumber);
    }

    [Fact]
    public async Task Fleet_command_identity_is_not_returned_as_an_empty_character_frame()
    {
        var session = OriginalPlayerCombatTests.Session(new OriginalTacticalBattleRegistry(), 2, 2,
            OriginalBattlefieldCatalog.Parse(Document().ToJsonString()));
        var key = new byte[16];
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), TestContext.Current.CancellationToken);
        var request = new byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0322);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(2), 2113929312);
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            request, key, 2), TestContext.Current.CancellationToken);
        Assert.NotEqual(NaturalAuthoritySessionStatus.Success, result.Status);
    }

    [Theory]
    [InlineData(2113929312u, 1u, 1)]
    [InlineData(2113929312u, 0u, 0)]
    [InlineData(2113929312u, 0u, 2)]
    [InlineData(2113929999u, 0u, 1)]
    public async Task Fleet_party_query_rejects_unimplemented_or_absent_context(uint outfit, uint baseId, byte mode)
    {
        var session = OriginalPlayerCombatTests.Session(new OriginalTacticalBattleRegistry(), 2, 2,
            OriginalBattlefieldCatalog.Parse(Document().ToJsonString()));
        var key = new byte[16];
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), TestContext.Current.CancellationToken);
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            OriginalOutfitPartyCodec.EncodeRequest(new(outfit, baseId, mode))[4..], key, 2),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
        Assert.StartsWith("original.outfit-party.", result.ErrorCode);
    }

    [Fact]
    public async Task Two_grids_advance_fleet_combat_without_cross_grid_targets()
    {
        var doc = Document();
        var second = doc["templates"]![2]!.DeepClone();
        second["grid"] = 103;
        foreach (var fleet in second["fleets"]!.AsArray())
        {
            fleet!["id"] = fleet["id"]!.GetValue<uint>() + 1000;
            foreach (var ship in fleet["ships"]!.AsArray())
                ship!["id"] = ship["id"]!.GetValue<uint>() + 1000;
        }
        doc["templates"]!.AsArray().Add(second);
        var catalog = OriginalBattlefieldCatalog.Parse(doc.ToJsonString());
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        foreach (var grid in new uint[] { 101, 103 })
        {
            var session = OriginalPlayerCombatTests.Session(battles, grid, 2, catalog);
            OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", grid);
            var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("0F02"), key, 1), TestContext.Current.CancellationToken);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        }
        var events = new List<OriginalNpcEvent>();
        for (uint tick = 100; tick < 250; tick += 6)
            events.AddRange(await battles.AdvanceNpcsAsync(tick, TestContext.Current.CancellationToken));
        Assert.Contains(events, e => e.Grid == 101 && e.Action == "fire");
        Assert.Contains(events, e => e.Grid == 103 && e.Action == "fire");
        foreach (var shot in events.Where(e => e.Action == "fire"))
        {
            var first = shot.Grid == 101 ? 2113929217u : 2113930217u;
            Assert.InRange(shot.Actor, first, first + 3);
            Assert.InRange(shot.Target, first, first + 3);
        }
    }
}
