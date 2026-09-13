using System.Text.Json.Nodes;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalStaticBaseTests
{
    [Fact]
    public void Shipped_catalog_declares_existing_base_and_preserves_its_wire_values()
    {
        var records = OriginalBattlefieldCatalog.LoadDefault().StaticBases;
        Assert.NotNull(records);
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("031C"), out var wire, [Assert.Single(records, b => b.Id == 1)]));
        Assert.Equal("00000000031D000100000001006500000000047B2C003162E070B9013F80000000000001003F0000003F800000",
            Convert.ToHexString(wire));
    }

    [Fact]
    public void Legacy_catalog_without_definitions_retains_legacy_fallback()
    {
        var document = CatalogWithBase();
        document.Remove("staticBases");
        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        Assert.Null(catalog.StaticBases);
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("031C"), out var wire, catalog.StaticBases));
        Assert.Equal(OriginalWorldBootstrapCodec.EncodeStaticBases(), wire);
    }

    [Fact]
    public void Catalog_values_reach_031D_wire_without_model_kind_or_diameter_substitution()
    {
        var catalog = OriginalBattlefieldCatalog.Parse(CatalogWithBase().ToJsonString());
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("031C"), out var wire, catalog.StaticBases));
        // Literal from00414A30 fields; class200 is NOT constrained by nameCount<=13.
        Assert.Equal("00000000031D0001010203040066006E00010200410042C8402000000000012C0142B4000041000000",
            Convert.ToHexString(wire));
    }

    [Fact]
    public void Explicit_empty_definitions_do_not_invent_Base1()
    {
        var document = CatalogWithBase();
        document["staticBases"] = new JsonArray();
        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("031C"), out var wire, catalog.StaticBases));
        Assert.Equal("00000000031D0000", Convert.ToHexString(wire));
    }

    [Fact]
    public void Explicit_base_data_is_validated_at_encoding_boundary_too()
    {
        var records = OriginalBattlefieldCatalog.Parse(CatalogWithBase().ToJsonString()).StaticBases!;
        Assert.Throws<InvalidDataException>(() => OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("031C"), out _, [records[0] with { ModelFile = 130 }]));
    }

    [Fact]
    public void Nonorbiting_base_can_have_zero_cycle_and_negative_initial_angle()
    {
        var document = CatalogWithBase();
        document["staticBases"]![0]!["revolutionCycle"] = 0;
        document["staticBases"]![0]!["revolutionInitialAngle"] = -90;
        Assert.NotNull(OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    private static JsonObject CatalogWithBase()
    {
        var document = JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!.AsObject();
        document["templates"] = new JsonArray(document["templates"]![0]!.DeepClone());
        // This fixture tests global definitions only, without unrelated Base1 runtime rows.
        document["templates"]![0]!["baseInstitutions"] = new JsonArray();
        document["templates"]![0]!["baseInformation"] = new JsonArray();
        document["templates"]![0]!["tacticalBases"] = new JsonArray();
        document["staticBases"] = JsonNode.Parse("""
            [{"id":16909060,"grid":102,"modelFile":110,"kind":1,"name":"AB",
              "class":200,"revolutionRadius":2.5,"revolutionCycle":300,
              "revolutionDirection":1,"revolutionInitialAngle":90,"diameter":8,
              "evidenceStatus":"NEW_DESIGN"}]
            """);
        return document;
    }

    [Theory]
    [InlineData("modelFile", 130)]
    [InlineData("diameter", -1)]
    [InlineData("revolutionRadius", -1)]
    [InlineData("id", 0)]
    public void Catalog_rejects_unsafe_base_fields_instead_of_ignoring_them(string field, int value)
    {
        var document = CatalogWithBase();
        document["staticBases"]![0]![field] = value;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Catalog_rejects_duplicate_global_base_ids()
    {
        var document = CatalogWithBase();
        var records = document["staticBases"]!.AsArray();
        records.Add(records[0]!.DeepClone());
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Catalog_rejects_orbit_modulo_zero()
    {
        var document = CatalogWithBase();
        document["staticBases"]![0]!["kind"] = 0;
        document["staticBases"]![0]!["revolutionCycle"] = 0;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Theory]
    [InlineData("name", "12345678901234")]
    [InlineData("evidenceStatus", "ORIGINAL_ASSUMED")]
    public void Catalog_rejects_invalid_base_text_fields(string field, string value)
    {
        var document = CatalogWithBase();
        document["staticBases"]![0]![field] = value;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Catalog_rejects_more_than_350_base_records()
    {
        var document = CatalogWithBase();
        var records = document["staticBases"]!.AsArray();
        for (var i = 1; i <= 350; i++)
        {
            var record = records[0]!.DeepClone();
            record["id"] = i;
            records.Add(record);
        }
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }
}
