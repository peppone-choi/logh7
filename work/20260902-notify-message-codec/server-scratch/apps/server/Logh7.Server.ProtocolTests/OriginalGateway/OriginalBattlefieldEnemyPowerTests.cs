using System.Buffers.Binary;
using System.Text.Json.Nodes;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalBattlefieldEnemyPowerTests
{
    [Theory]
    [InlineData(2, 0x7f000002u, 3, null)]
    [InlineData(3, 0x7f000002u, 3, null)]
    [InlineData(2, 2u, 2, null)]
    [InlineData(3, 2u, 3, null)]
    [InlineData(3, 0x7f000002u, 0, 0)]
    [InlineData(3, 0x7f000002u, 1, 1)]
    [InlineData(3, 0x7f000002u, 2, 2)]
    [InlineData(3, 0x7f000002u, 4, 4)]
    public async Task Same_npc_has_one_power_without_changing_the_viewers_own_power(
        byte viewerPower, uint requestedId, byte expectedPower, int? configuredPower)
    {
        // Authenticated/world-entered fixture: actual decrypt/dispatch/encode,
        // but no login, database, shared combat or native-rendering claim.
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        OriginalBattlefieldCatalog? catalog = null;
        if (configuredPower.HasValue)
        {
            var document = JsonNode.Parse(File.ReadAllText(
                Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
            document["templates"]![0]!["enemyPower"] = configuredPower.Value;
            catalog = OriginalBattlefieldCatalog.Parse(document.ToJsonString());
        }
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key, catalog,
            store: new ViewerStore(viewerPower));
        var viewer = new OriginalCreateCharacterCommand(
            4, 2, viewerPower, 0, 0, "Viewer", "Pilot", 18, 1, 1,
            0, new byte[8], 0, 0, 0, 20, 0, 0, "Flagship", 0, []);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter", viewer);
        var request = new byte[6];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0322);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(2), requestedId);

        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(request, key, 1), CancellationToken.None);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.Equal((ushort)0x0323, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
        Assert.Equal(requestedId, BinaryPrimitives.ReadUInt32BigEndian(decoded.Payload.AsSpan(6)));
        Assert.Equal(expectedPower, decoded.Payload![10]);
    }

    private sealed class ViewerStore(byte power) : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid accountId,
            CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new(2,0,power,0,0,"Viewer","Pilot","Flagship",0,new short[8])]);
    }

    [Theory]
    [InlineData(null)]
    [InlineData(5)]
    [InlineData(255)]
    public void Catalog_rejects_unassigned_or_undefined_npc_power(int? power)
    {
        var document = JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        var template = document["templates"]![0]!.AsObject();
        template["enemyPower"] = power.HasValue ? JsonValue.Create(power.Value) : null;
        Assert.Throws<InvalidDataException>(() => OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
    }
}
