using System.Buffers.Binary;
using System.Text.Json.Nodes;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalInstitutionBootstrapTests
{
    [Fact]
    public async Task Initial_and_refresh_scenes_send_current_grid_facilities_before_import_terminators()
    {
        var key = new byte[16];
        var catalog = OriginalBattlefieldCatalog.Parse(Document().ToJsonString());
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, key, catalog: catalog, store: new RosterStore());
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Pilot", "First", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 3, "", 0, []));
        for (ushort sequence = 1; sequence <= 2; sequence++)
        {
            var result = await session.ProcessAsync(0x30,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, sequence),
                TestContext.Current.CancellationToken);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
            var frames = new[] { result.ResponsePayload! }.Concat(result.AdditionalResponses!.Select(p => p.Payload))
                .Select(p => OriginalClientInnerFrameCodec.Decode(p, key, 0).Payload!).ToArray();
            var facilities = Assert.Single(frames, f => Type(f) == 0x0321);
            Assert.Equal("000000000321010000000101000400000065010006000003E90007", Convert.ToHexString(facilities));
            var position = Array.IndexOf(frames, facilities);
            Assert.True(position < Array.FindIndex(frames, f => Type(f) == 0x0F03));
            if (sequence == 1) Assert.True(position < Array.FindIndex(frames, f => Type(f) == 0x0B0A));
        }
    }

    [Theory]
    [InlineData("undefined")]
    [InlineData("duplicate-base")]
    [InlineData("duplicate-institution")]
    [InlineData("duplicate-spot")]
    [InlineData("missing-spots")]
    [InlineData("zero-institution")]
    [InlineData("zero-spot")]
    [InlineData("too-many-spots")]
    [InlineData("too-many-institutions")]
    [InlineData("too-many-bases")]
    public void Invalid_facility_joins_are_rejected_when_loading_content(string fault)
    {
        var doc = Document();
        var bases = doc["templates"]![0]!["baseInstitutions"]!.AsArray();
        var institutions = bases[0]!["institutions"]!.AsArray();
        var spots = institutions[0]!["spots"]!.AsArray();
        switch (fault)
        {
            case "undefined": bases[0]!["id"] = 99; break;
            case "duplicate-base": bases.Add(bases[0]!.DeepClone()); break;
            case "duplicate-institution": institutions.Add(institutions[0]!.DeepClone()); break;
            case "duplicate-spot": spots.Add(spots[0]!.DeepClone()); break;
            case "missing-spots": institutions[0]!["spots"] = null; break;
            case "zero-institution": institutions[0]!["id"] = 0; break;
            case "zero-spot": spots[0]!["id"] = 0; break;
            case "too-many-spots":
                for (int i = 1; i <= 20; i++) { var row = spots[0]!.DeepClone(); row["id"] = 3000+i; spots.Add(row); }
                break;
            case "too-many-institutions":
                for (int i = 1; i <= 36; i++) { var row = institutions[0]!.DeepClone(); row["id"] = 300+i; row["spots"] = new JsonArray(); institutions.Add(row); }
                break;
            case "too-many-bases":
                bases.Clear();
                var definitions = doc["staticBases"]!.AsArray();
                for (int i = 10; i < 15; i++)
                {
                    var definition = definitions[0]!.DeepClone(); definition["id"] = i; definitions.Add(definition);
                    bases.Add(new JsonObject { ["id"] = i, ["institutions"] = new JsonArray() });
                }
                break;
        }
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(doc.ToJsonString()));
    }

    private static ushort Type(byte[] frame) => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4));

    private static JsonObject Document()
    {
        var doc = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!.AsObject();
        // Test-only selectors exercise serialization; file7 is NOT a shipped art mapping.
        doc["templates"] = new JsonArray(doc["templates"]![0]!.DeepClone());
        doc["templates"]![0]!["baseInstitutions"] = JsonNode.Parse("""
        [{"id":1,"institutions":[{"kind":4,"id":101,"spots":[{"kind":6,"id":1001,"file":7}]}]},
         {"id":2,"institutions":[{"kind":4,"id":201,"spots":[{"kind":6,"id":2001,"file":9}]}]}]
        """);
        return doc;
    }

    private sealed class RosterStore : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid account, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new CharacterReadRecord(2, 0, 2, 0, 0, "Pilot", "First", "", 5,
                    [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);
    }
}
