using System.Text.Json.Nodes;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalBattlefieldBaseJoinTests
{
    [Fact]
    public void Shipped_catalog_keeps_the_existing_authored_tactical_record()
    {
        Assert.Equal(new OriginalTacticalBaseRecord(1,0,30,0,0,0,0,100),
            Assert.Single(OriginalBattlefieldCatalog.LoadDefault().ProjectTacticalBases(101)));
        Assert.Equal(2u, Assert.Single(OriginalBattlefieldCatalog.LoadDefault().ProjectTacticalBases(102)).Id);
        Assert.Empty(OriginalBattlefieldCatalog.LoadDefault().ProjectTacticalBases(103));
    }

    [Fact]
    public void Rejects_nonfinite_tactical_coordinates()
    {
        var document=Document();
        document["templates"]![0]!["tacticalBases"]![0]!["x"]=JsonNode.Parse("1e39");
        Assert.Throws<InvalidDataException>(()=>OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Theory]
    [InlineData(11)]
    [InlineData(16)]
    [InlineData(17)]
    public void Rejects_more_than_ten_native_tactical_bases_on_one_grid(int count)
    {
        var document=Document();
        var definitions=new JsonArray();
        var states=new JsonArray();
        for(var i=1;i<=count;i++)
        {
            var definition=document["staticBases"]![0]!.DeepClone();definition["id"]=i;definitions.Add(definition);
            var state=document["templates"]![0]!["tacticalBases"]![0]!.DeepClone();state["id"]=i;states.Add(state);
        }
        document["staticBases"]=definitions;
        document["templates"]![0]!["tacticalBases"]=states;
        document["templates"]![0]!["baseInformation"]=new JsonArray();
        Assert.Throws<InvalidDataException>(()=>OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Ten_bases_per_grid_are_preserved_without_a_global_ten_base_limit()
    {
        var document = Document();
        var definitions = new JsonArray();
        var states = new JsonArray();
        for (var i = 1; i <= 20; i++)
        {
            var definition = document["staticBases"]![0]!.DeepClone();
            definition["id"] = i;
            definition["grid"] = i <= 10 ? 101 : 102;
            definitions.Add(definition);
            var state = document["templates"]![0]!["tacticalBases"]![0]!.DeepClone();
            state["id"] = i;
            state["x"] = i;
            states.Add(state);
        }
        document["staticBases"] = definitions;
        document["templates"]![0]!["tacticalBases"] = states;
        document["templates"]![0]!["baseInformation"] = new JsonArray();

        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        Assert.Equal(Enumerable.Range(1, 10).Select(x => (uint)x),
            catalog.ProjectTacticalBases(101).Select(x => x.Id));
        Assert.Equal(Enumerable.Range(11, 10).Select(x => (uint)x),
            catalog.ProjectTacticalBases(102).Select(x => x.Id));
        Assert.Equal(Enumerable.Range(11, 10).Select(x => (float)x),
            catalog.ProjectBasePositions(102).Select(x => x.X));
        Assert.Equal(new byte[] { 4, 4, 2 },
            catalog.EncodeBasePositionFrames(101).Select(frame => frame[6]).ToArray());
    }

    [Fact]
    public void Tactical_and_position_queries_use_same_grid_ID_and_authored_coordinates()
    {
        var catalog = OriginalBattlefieldCatalog.Parse(Document().ToJsonString());
        Assert.Equal(new OriginalTacticalBaseRecord(7,12,3,4,5,6,7,80),
            Assert.Single(catalog.ProjectTacticalBases(101)));
        Assert.Equal(new OriginalBasePositionRecord(7,12,3,4),
            Assert.Single(catalog.ProjectBasePositions(101)));
        Assert.Equal(8u, Assert.Single(catalog.ProjectTacticalBases(102)).Id);
    }

    [Fact]
    public void Empty_grid_and_unknown_or_other_grid_requests_do_not_invent_Base1()
    {
        var catalog = OriginalBattlefieldCatalog.Parse(Document().ToJsonString());
        Assert.Empty(catalog.ProjectTacticalBases(999));
        Assert.Empty(catalog.ProjectBasePositions(101, [8,99]));
        Assert.Empty(catalog.ProjectTacticalBases(101, []));
        Assert.Equal(7u, Assert.Single(catalog.ProjectTacticalBases(101, [7,7,8])).Id);
    }

    [Fact]
    public void Missing_ownership_for_an_existing_definition_blocks_victory()
    {
        var document = Document();
        document["templates"]![0]!["baseInformation"] = new JsonArray();
        var catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        var encounter = new OriginalTacticalEncounter(100);
        encounter.RecordEnemyDamage(new(100,100));
        Assert.Empty(encounter.TryComplete(101,2,0,catalog.ProjectBaseObjectives(101)));
        Assert.False(encounter.IsCompleted);
    }

    [Fact]
    public void No_base_definitions_on_grid_is_a_known_empty_objective_set()
    {
        var catalog = OriginalBattlefieldCatalog.Parse(Document().ToJsonString());
        Assert.Empty(catalog.ProjectBaseObjectives(999)!);
    }

    [Fact]
    public void Five_bases_bootstrap_without_overflowing_four_record_position_or_ownership_packets()
    {
        var document = Document();
        var definitions = new JsonArray();
        var states = new JsonArray();
        var information = new JsonArray();
        for (var i=1; i<=5; i++)
        {
            var definition=document["staticBases"]![0]!.DeepClone(); definition["id"]=i;
            definitions.Add(definition);
            var state=document["templates"]![0]!["tacticalBases"]![0]!.DeepClone(); state["id"]=i;
            states.Add(state);
            var owner=document["templates"]![0]!["baseInformation"]![0]!.DeepClone(); owner["id"]=i;
            information.Add(owner);
        }
        document["staticBases"]=definitions;
        document["templates"]![0]!["tacticalBases"]=states;
        document["templates"]![0]!["baseInformation"]=information;
        var catalog=OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        var frames=catalog.EncodeBasePositionFrames(101);
        Assert.Equal(new byte[]{4,1},frames.Select(frame=>frame[6]).ToArray());
        var ids=new List<uint>();
        foreach(var frame in frames)
        {
            Assert.True(OriginalSystemSceneCodec.TryDecodeBasePositions(frame.AsSpan(4),out var positions));
            ids.AddRange(positions.Records.Select(record=>record.Id));
        }
        Assert.Equal(new uint[]{1,2,3,4,5},ids);
        Assert.Equal(new byte[]{4,1},catalog.EncodeBaseInformationFrames(101).Select(frame=>frame[6]).ToArray());
    }

    private static JsonObject Document()
    {
        var document = JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!.AsObject();
        // Keep synthetic definitions independent of later shipped grid-specific templates.
        document["templates"] = new JsonArray(document["templates"]![0]!.DeepClone());
        document["templates"]![0]!["baseInstitutions"] = new JsonArray();
        document["staticBases"] = JsonNode.Parse("""
        [
          {"id":7,"grid":101,"modelFile":0,"kind":1,"name":"A","class":1,"diameter":1,"evidenceStatus":"NEW_DESIGN"},
          {"id":8,"grid":102,"modelFile":10,"kind":1,"name":"B","class":1,"diameter":1,"evidenceStatus":"NEW_DESIGN"}
        ]
        """);
        document["templates"]![0]!["baseInformation"] = JsonNode.Parse("""
        [{"id":7,"power":2,"camp":0,"grid":101},{"id":8,"power":3,"camp":0,"grid":102}]
        """);
        document["templates"]![0]!["tacticalBases"] = JsonNode.Parse("""
        [{"id":7,"x":12,"y":3,"z":4,"antiaircraft":5,"cannonAngle":6,"cannonStart":7,"stamina":80},
         {"id":8,"x":-20,"y":0,"z":2,"stamina":90}]
        """);
        return document;
    }

    [Fact]
    public void Rejects_tactical_instance_without_a_static_definition()
    {
        var document = Document();
        document["templates"]![0]!["tacticalBases"]![0]!["id"] = 99;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Rejects_duplicate_tactical_instances()
    {
        var document = Document();
        var instances = document["templates"]![0]!["tacticalBases"]!.AsArray();
        instances.Add(instances[0]!.DeepClone());
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }

    [Fact]
    public void Rejects_ownership_assigned_to_a_different_static_grid()
    {
        var document = Document();
        document["templates"]![0]!["baseInformation"]![0]!["grid"] = 102;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }
}
