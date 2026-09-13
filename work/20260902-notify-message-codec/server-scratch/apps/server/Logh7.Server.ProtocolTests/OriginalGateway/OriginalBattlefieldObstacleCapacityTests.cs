using System.Text.Json.Nodes;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalBattlefieldObstacleCapacityTests
{
    [Theory]
    [InlineData(1, 1, 4, 1, 4)] // 11 total; omitting any family loses this failure.
    [InlineData(0, 0, 10, 0, 1)] // Each array is valid, shared pool is full.
    [InlineData(1, 1, 10, 1, 5)] // All individual limits, 18 combined.
    public void Catalog_rejects_maps_exceeding_the_shared_native_obstacle_pool(
        int blackHoles, int asteroids, int gasClouds, int gravity, int circles)
    {
        var json = CreateCatalog(blackHoles, asteroids, gasClouds, gravity, circles);
        var error = Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(json));
        Assert.Contains("obstacle", error.Message, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("10", error.Message);
    }

    [Theory]
    [InlineData(0, 0, 0, 0, 0, 0)]
    [InlineData(0, 0, 10, 0, 0, 10)]
    [InlineData(1, 1, 3, 1, 4, 10)]
    public void Catalog_projects_every_obstacle_when_the_native_pool_has_capacity(
        int blackHoles, int asteroids, int gasClouds, int gravity, int circles, int total)
    {
        var catalog = OriginalBattlefieldCatalog.Parse(
            CreateCatalog(blackHoles, asteroids, gasClouds, gravity, circles));
        var response = catalog.Resolve(101).ProjectObstacles(101);
        Assert.Equal(101u, response.Grid);
        Assert.Equal(blackHoles, response.BlackHoles.Count);
        Assert.Equal(asteroids, response.AsteroidBelts.Count);
        Assert.Equal(gasClouds, response.GasClouds.Count);
        Assert.Equal(gravity, response.AbnormalGravities.Count);
        Assert.Equal(circles, response.Circles.Count);
        var ids = response.BlackHoles.Select(x => x.Id)
            .Concat(response.AsteroidBelts.Select(x => x.Id))
            .Concat(response.GasClouds.Select(x => x.Id))
            .Concat(response.AbnormalGravities.Select(x => x.Id))
            .Concat(response.Circles.Select(x => x.Id));
        Assert.Equal(Enumerable.Range(1, total).Select(x => (uint)x), ids);
    }

    private static string CreateCatalog(params int[] counts)
    {
        var document = JsonNode.Parse(File.ReadAllText(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        var template = document["templates"]![0]!;
        string[] names = ["blackHoles", "asteroidBelts", "gasClouds", "abnormalGravities", "circles"];
        // Complete record shapes; independent fixture, no production serializer.
        string[] records = [
            """{"id":1,"kind":0,"modelFile":0,"maxSuctionSpeed":1,"radius":10}""",
            """{"id":1,"kind":0,"modelFile":0,"radius":10,"range":2}""",
            """{"id":1,"kind":0,"modelFile":0,"revolutionRadius":10,"revolutionCycle":24,"revolutionDirection":0,"revolutionInitialAngle":0,"radius":1}""",
            """{"id":1,"kind":0,"modelFile":0,"gravityUpRange":1,"gravityDownRange":1}""",
            """{"id":1,"kind":0,"modelFile":0,"x":0,"y":0,"z":0,"radius":1}""",
        ];
        var id = 1;
        for (var family = 0; family < names.Length; family++)
        {
            var array = new JsonArray();
            for (var index = 0; index < counts[family]; index++)
            {
                var record = JsonNode.Parse(records[family])!;
                record["id"] = id++;
                array.Add(record);
            }
            template[names[family]] = array;
        }
        return document.ToJsonString();
    }
}
