using System.Buffers.Binary;
using System.Text.Json.Nodes;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalNpcCharacterMeritTests
{
    [Fact]
    public void Fleet_commander_frame_does_not_require_a_logged_in_character()
    {
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(Path.Combine(AppContext.BaseDirectory,
            "battlefields", "fleet-skirmish.json"));
        var fleet = catalog.Resolve(102).Fleets!.First(f => !string.IsNullOrEmpty(f.Commander));
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, new byte[16], catalog: catalog);
        var method = typeof(NaturalAuthoritySession).GetMethod("CommanderFrame",
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!;
        var before = (byte[]?)method.Invoke(session, [fleet, fleet.Ships[0].Id, fleet.Id]);
        Assert.NotNull(before);
        typeof(NaturalAuthoritySession).GetField("_createdCharacter",
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.SetValue(session, null);
        var after = (byte[]?)method.Invoke(session, [fleet, fleet.Ships[0].Id, fleet.Id]);
        Assert.NotNull(after);
        Assert.Equal(before, after);
    }

    [Theory]
    [InlineData("move")]
    [InlineData("supply")]
    [InlineData("same-controller")]
    [InlineData("new-controller")]
    public void Npc_updates_preserve_only_the_current_commanders_merit(string operation)
    {
        var actor = OriginalNpcAiTests.Actor(10, 3, 0);
        var merit = new OriginalTacticalCommanderMerit(actor.Ship.Character, 7, 1234);
        var initial = new OriginalTacticalParticipantSnapshot(actor.Unit, actor.Ship, actor.Corps,
            actor.CharacterFrame.ToArray(), actor.Power, commanderMerit: merit);
        var npc = new OriginalTacticalNpcController(initial,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities, OriginalAuthoredPlayableCatalog.TacticalArms);
        switch (operation)
        {
            case "move":
                var target = OriginalNpcAiTests.Actor(2, 2, 20);
                npc.Advance(100, [target]);
                npc.Advance(112, [target]);
                Assert.NotEqual(initial.Ship, npc.Snapshot.Ship);
                break;
            case "supply":
                ((Action)typeof(OriginalTacticalNpcController).GetMethod("PrepareSupplyUpdate",
                    System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!
                    .Invoke(npc, [50u, new OriginalTacticalDamageState(0, 0)])!)();
                break;
            case "same-controller": Assign(actor.Ship.Character, actor.Corps); break;
            case "new-controller":
                Assign(99, OriginalSystemSceneCodec.CreatePlayableTacticalCorps(99));
                break;
        }
        Assert.Equal(operation == "new-controller" ? null : merit, npc.Snapshot.CommanderMerit);
        void Assign(uint character, OriginalTacticalCorpsRecord corps) =>
            typeof(OriginalTacticalNpcController).GetMethod("ApplyControlAssignment",
                System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!
                .Invoke(npc, [character, corps, true]);
    }

    [Fact]
    public void A_participant_cannot_carry_another_characters_merit()
    {
        var actor = OriginalNpcAiTests.Actor(10, 3, 0);
        Assert.Throws<ArgumentException>(() => new OriginalTacticalParticipantSnapshot(actor.Unit, actor.Ship,
            actor.Corps, [], actor.Power, commanderMerit: new(999, 7, 1234)));
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(true, true)]
    [InlineData(false, false)]
    [InlineData(false, true)]
    public async Task Npc_rank_and_achievement_come_from_content_not_the_viewer(bool primary, bool configured)
    {
        var ct = TestContext.Current.CancellationToken;
        var json = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,
            "battlefields", primary ? "catalog.json" : "fleet-skirmish.json")))!;
        var target = primary ? json["templates"]![0]! : json["templates"]![1]!["fleets"]![0]!;
        var npcId = primary ? 0x7f000002u : target["id"]!.GetValue<uint>();
        if (configured)
        {
            target[primary ? "enemyRank" : "commanderRank"] = 7;
            target[primary ? "enemyAchievement" : "commanderAchievement"] = 0x11223344u;
        }
        var catalog = OriginalBattlefieldCatalog.Parse(json.ToJsonString());
        byte[]? firstNpcFrame = null;
        foreach (var viewer in new[] { (Rank: (short)4, Merit: 999u), (Rank: (short)13, Merit: 8765u) })
        {
            var key = new byte[16];
            var battles = new OriginalTacticalBattleRegistry();
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
                catalog: catalog, store: new ViewerStore(viewer.Rank, viewer.Merit),
                battles: battles);
            OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", primary ? 101u : 102u);
            var restore = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("032200000002"), key, 1), ct);
            Assert.True(restore.Status == NaturalAuthoritySessionStatus.Success, restore.ErrorCode);
            var scene = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
                Convert.FromHexString("0F02"), key, 2), ct);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, scene.Status);
            var query = new byte[6];
            BinaryPrimitives.WriteUInt16BigEndian(query, 0x0322);
            BinaryPrimitives.WriteUInt32BigEndian(query.AsSpan(2), npcId);
            var answer = await session.ProcessAsync(0x30,
                OriginalClientInnerFrameCodec.Encode(query, key, 3), ct);
            Assert.True(answer.Status == NaturalAuthoritySessionStatus.Success, answer.ErrorCode);
            var decoded = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
            var frame = decoded.Payload!;
            if (firstNpcFrame is null) firstNpcFrame = frame.ToArray();
            else Assert.Equal(firstNpcFrame, frame);
            Assert.Equal(npcId, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(6)));
            // One parentage record: rank u16, empty title pstr, four u32 values,
            // then 32 ability bytes and the 11-byte trailer (00417390).
            Assert.Equal(configured ? (ushort)7 : (ushort)20,
                BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(frame.Length - 62)));
            Assert.Equal(configured ? 0x11223344u : 0u,
                BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(frame.Length - 47)));
            var npc = battles.NpcSnapshot(primary ? 101u : 102u,
                primary ? 0x7f000001u : target["ships"]![0]!["id"]!.GetValue<uint>());
            Assert.NotNull(npc);
            var merit = npc.CommanderMerit;
            Assert.NotNull(merit);
            Assert.Equal(npcId, merit.CharacterId);
            Assert.Equal(configured ? (byte)7 : (byte)20, merit.Rank);
            Assert.Equal(configured ? 0x11223344u : 0u, merit.Achievement);
            var own = Assert.Single(battles.OtherParticipants(primary ? 101u : 102u, 0), p => p.Unit.Id == 2);
            Assert.Equal(new OriginalTacticalCommanderMerit(2, (byte)viewer.Rank, viewer.Merit), own.CommanderMerit);
        }
    }

    private sealed class ViewerStore(short rank, uint achievement) : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([new(2, 0, 2, 0, 0,
                "Pilot", "First", "Ship", rank, [(byte)rank,2,3,4,5,6,7,8], rank, 0, 0, Achievement: achievement)]);
        public override Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterCardRecord>>([]);
    }
}
