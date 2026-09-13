using System.Buffers.Binary;
using System.Text.Json.Nodes;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalMoveStateIntegrityTests
{
    public static IEnumerable<object[]> NonfiniteFields()
    {
        // Unit heading/XYZ, velocity, final heading, destination XYZ.
        foreach (var offset in new[] { 19, 23, 27, 31, 35, 39, 44, 48, 52 })
        foreach (var value in new[] { float.NaN, float.PositiveInfinity, float.NegativeInfinity })
            yield return [offset, value];
    }

    [Theory]
    [MemberData(nameof(NonfiniteFields))]
    public void Nonfinite_movement_never_produces_an_authorized_state(int offset, float value)
    {
        var payload = MoveRequest(2);
        BinaryPrimitives.WriteSingleBigEndian(payload.AsSpan(offset), value);
        Assert.True(OriginalTacticalCommandCodec.TryDecodeMoveShipCommand(payload, out var command));
        var current = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(2, 2);
        var result = OriginalTacticalCommandAuthority.AuthorizeMoveShip(command, 2, current);
        Assert.False(result.Accepted);
        Assert.Null(result.State);
        Assert.Equal("TACTICAL_MOVE_NONFINITE", result.ErrorCode);
    }

    [Fact]
    public void Finite_move_remains_accepted_without_changing_its_requested_destination()
    {
        Assert.True(OriginalTacticalCommandCodec.TryDecodeMoveShipCommand(MoveRequest(2), out var command));
        var result = OriginalTacticalCommandAuthority.AuthorizeMoveShip(command, 2,
            OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(2, 2));
        Assert.True(result.Accepted);
        Assert.Equal(100f, result.State!.Value.X);
        Assert.Equal(0f, result.State.Value.Y);
        Assert.Equal(20f, result.State.Value.Z);
    }

    [Theory]
    [InlineData(false, 0f)]
    [InlineData(true, float.NaN)]
    [InlineData(true, float.PositiveInfinity)]
    [InlineData(true, float.NegativeInfinity)]
    public async Task Rejected_first_move_preserves_map_spawn_and_queryable_ship_state(bool owned, float x)
    {
        var document = JsonNode.Parse(File.ReadAllText(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        document["templates"]![0]!["playerSpawn"] = JsonNode.Parse(
            """{"x":50,"y":7,"z":-20,"direction":1.5}""");
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            OriginalBattlefieldCatalog.Parse(document.ToJsonString()));
        // World-entered fixture only; subsequent decrypt, authorization and
        // query projection are real. No login/database/native claim.
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Viewer", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flagship", 0, []));
        var move = MoveRequest(owned ? 2u : 3u);
        if (owned) BinaryPrimitives.WriteSingleBigEndian(move.AsSpan(44), x);
        var rejected = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(move, key, 1), CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, rejected.Status); // Visible rejection, not disconnect.
        Assert.True(rejected.AdditionalResponses is null || rejected.AdditionalResponses.Count == 0);

        var query = Convert.FromHexString("033A000100000002");
        var result = await session.ProcessAsync(0x0030,
            OriginalClientInnerFrameCodec.Encode(query, key, 2), CancellationToken.None);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(decoded.Payload!.AsSpan(4), out var ships));
        var ship = Assert.Single(ships.Records);
        Assert.Equal(2u, ship.Id);
        Assert.Equal(50f, ship.X);
        Assert.Equal(7f, ship.Y);
        Assert.Equal(-20f, ship.Z);
        Assert.Equal(1.5f, ship.Direction);
        Assert.Equal((byte)100, ship.Morale);
        Assert.Equal((byte)1, ship.Search);
    }

    private static byte[] MoveRequest(uint unitId)
    {
        var payload = new byte[56];
        BinaryPrimitives.WriteUInt16BigEndian(payload, 0x0400);
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(10), 7);
        payload[14] = 1;
        BinaryPrimitives.WriteUInt32BigEndian(payload.AsSpan(15), unitId);
        BinaryPrimitives.WriteSingleBigEndian(payload.AsSpan(35), 1);
        payload[43] = 1;
        BinaryPrimitives.WriteSingleBigEndian(payload.AsSpan(44), 100);
        BinaryPrimitives.WriteSingleBigEndian(payload.AsSpan(52), 20);
        return payload;
    }
}
