using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalRegularShipSelectionTests
{
    [Fact]
    public async Task Static_catalog_uses_persisted_flagship_complement_without_changing_subordinate_slots()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2);
        OriginalWarpSessionClockTests.SetField(session, "_persistedGridUnit",
            new OriginalGridUnitRecord(2, 2, 39, 101, 1, UnitNumber: 1));
        battles.GetEncounter(101, 100).RegisterUnitNumber(2, 1);
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("030A"), Key, 1), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frame = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, Key, 0).Payload!;
        var quantities = new Dictionary<ushort, ushort>();
        for (int i = 0, cursor = 7; i < frame[6]; i++)
        {
            quantities[Read16(frame, cursor)] = Read16(frame, cursor + 9 + frame[cursor + 8] * 2);
            cursor += 106 + frame[cursor + 8] * 2;
        }
        Assert.Equal((ushort)1, quantities[0]);
        Assert.Equal((ushort)300, quantities[56]);
        Assert.Equal((ushort)100, quantities[89]);
    }

    [Theory]
    [InlineData("030A")]
    [InlineData("0F02")]
    public async Task Same_native_kind_cannot_silently_describe_different_live_complements(string request)
    {
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2);
        OriginalWarpSessionClockTests.SetField(session, "_persistedGridUnit",
            new OriginalGridUnitRecord(2, 2, 39, 101, 1, UnitNumber: 1));
        battles.GetEncounter(101, 100).RegisterUnitNumber(2, 1);
        var other = System.Threading.Channels.Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(other.Writer, OriginalNpcAiTests.Actor(10, 3, 3));
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString(request), Key, 1), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, result.Status);
        Assert.Equal("original.unit-template.complement-conflict", result.ErrorCode);
    }
    // The manual's unit section gives an ordinary vessel unit 300 hulls and a
    // flagship unit 1. Kind139 is the authored flagship-class template, so it
    // states 1; the ordinary kinds keep 300. The served template is what the
    // client counts hulls from, so these numbers are also the authority's.
    [Theory]
    [InlineData(32, 300)]
    [InlineData(56, 300)]
    [InlineData(119, 300)]
    [InlineData(139, 1)]
    public void Subordinate_unit_templates_supply_the_manual_ship_complement(ushort kind, int complement)
    {
        Assert.Equal(complement, OriginalSubordinateShipCatalog.ComplementFor(kind));
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        for (int i = 0, cursor = 7; i < frame[6]; i++)
        {
            if (Read16(frame, cursor) == kind)
            {
                Assert.Equal(complement, Read16(frame, cursor + 9 + frame[cursor + 8] * 2));
                return;
            }
            cursor += 106 + frame[cursor + 8] * 2;
        }
        Assert.Fail($"No subordinate template for kind {kind}");
    }
    private static readonly byte[] Key = new byte[16];

    [Theory]
    [InlineData(3, 0, 2, 49)]
    [InlineData(93, 89, 1001, 1049)]
    public void Recovery_kind_has_its_own_nonfallback_static_model(
        ushort recoveredKind, ushort regularKind, ushort minimumModel, ushort maximumModel)
    {
        // Missing normalized3/93 slots read model0 in004F3D80, regardless of
        // a correct0325 kind. This checks the join, not a canonical hull name.
        var frame=OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        var models=new Dictionary<ushort,ushort>();
        for(int i=0,cursor=7;i<frame[6];i++)
        {
            models.Add(Read16(frame,cursor),Read16(frame,cursor+6));
            cursor+=106+frame[cursor+8]*2;
        }
        Assert.True(models.ContainsKey(recoveredKind));
        Assert.InRange(models[recoveredKind],minimumModel,maximumModel);
        Assert.NotEqual(models[regularKind],models[recoveredKind]);
    }

    [Fact]
    public void Bootstrap_supplies_distinct_nonfallback_models_for_both_regular_ship_kinds()
    {
        // Catches serving only the shared kind0/model0 placeholder. This tests
        // the wire catalog join, not the visual identity of candidate meshes.
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips();
        Assert.Equal(10, frame[6]);
        var models = new Dictionary<ushort, ushort>();
        var cursor = 7;
        for (var i = 0; i < frame[6]; i++)
        {
            models.Add(Read16(frame, cursor), Read16(frame, cursor + 6));
            cursor += 106 + frame[cursor + 8] * 2;
        }
        Assert.Equal(frame.Length, cursor);
        // 57 and 58 are the 工作艦 and 補給艦 the 修理/補給 filters demand.
        Assert.Equal(new ushort[] { 0, 3, 32, 56, 57, 58, 89, 93, 119, 139 },
            models.Keys.Order().ToArray());
        Assert.NotEqual(models[0], models[89]);
        Assert.DoesNotContain(models[0], new ushort[] { 0, 1 });
        Assert.InRange(models[89], (ushort)1001, (ushort)1049);
    }

    [Theory]
    [InlineData(0, 3, 89)]
    [InlineData(89, 3, 89)]
    [InlineData(89, 2, 0)]
    public async Task Scene_projects_selected_player_kind_and_independent_npc_kind(
        ushort playerKind, byte npcPower, ushort npcKind)
    {
        // Real encrypted dispatch/bootstrap; store fixture only supplies the
        // owned roster. No native/model/parity claim follows from this test.
        var field = OriginalBattlefieldCatalog.LoadDefault().Resolve(101) with { EnemyPower = npcPower };
        var catalog = OriginalBattlefieldCatalog.Parse(System.Text.Json.JsonSerializer.Serialize(
            new { SchemaVersion = 1, Templates = new[] { field } }));
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, Key, catalog: catalog, store: new RosterStore());
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, playerKind, "", 0, []));
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), Key, 1),
            TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var frames = new[] { result.ResponsePayload! }
            .Concat(result.AdditionalResponses!.Select(push => push.Payload))
            .Select(bytes => OriginalClientInnerFrameCodec.Decode(bytes, Key, 0).Payload!).ToArray();
        var units = Assert.Single(frames, frame => Read16(frame, 4) == 0x325);
        Assert.Equal(2, Read16(units, 6));
        // Each packed 0325 record has42 bytes (troop count0), including kind.
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(units.AsSpan(8)));
        Assert.Equal(playerKind, Read16(units, 12));
        Assert.Equal(0x7f000001u, BinaryPrimitives.ReadUInt32BigEndian(units.AsSpan(50)));
        Assert.Equal(npcKind, Read16(units, 54)); // field content, not viewer's kind
    }

    private static ushort Read16(byte[] frame, int offset) =>
        BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(offset));

    private sealed class RosterStore : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account,
            CancellationToken ct) => Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new CharacterReadRecord(2, 0, 2, 0, 0, "Pilot", "First", "", 5,
                    [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);
    }
}
