using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalControlTests
{
    [Fact]
    public void Live_future_dated_control_response_executes_immediately_without_mutating_request()
    {
        // v12 receive queue: deadline 222547400, client clock 3019.
        var request = Convert.FromHexString(
            "040C0D43CD5000000078000000020000000200321414040403030303140A05");
        var before = request.ToArray();
        var response = OriginalTacticalControlCodec.EncodeImmediateResponse(request);
        Assert.Equal("00000000040C0000000000000000000000020000000200321414040403030303140A05",
            Convert.ToHexString(response));
        Assert.Equal(before, request);
        Assert.True(OriginalTacticalControlCodec.TryDecode(response.AsSpan(4), out var decoded));
        Assert.Equal(0u, decoded.Time + decoded.Wait);
        Assert.Equal((byte)5, decoded.Sensor);
    }

    [Fact]
    public void Immediate_control_response_rejects_wrong_type_and_malformed_payload()
    {
        Assert.Throws<ArgumentException>(() => OriginalTacticalControlCodec.EncodeImmediateResponse([4, 12]));
        var wrongType = LivePayload();
        wrongType[1] = 5;
        Assert.Throws<ArgumentException>(() => OriginalTacticalControlCodec.EncodeImmediateResponse(wrongType));
        Assert.Throws<ArgumentException>(() => OriginalTacticalControlCodec.EncodeImmediateResponse([.. LivePayload(), 0]));
    }

    private static byte[] LivePayload() => Convert.FromHexString(
        "040C0D4ECD5000000078000000020000000200321414040403030303140A0A");

    [Fact]
    public void Actual_slider_packet_decodes_all_eleven_power_channels()
    {
        Assert.True(OriginalTacticalControlCodec.TryDecode(LivePayload(), out var command));
        Assert.Equal(0x0D4ECD50u, command.Time);
        Assert.Equal(120u, command.Wait);
        Assert.Equal(2u, command.ActorId);
        Assert.Equal(2u, command.UnitId);
        Assert.Equal((ushort)50, command.Condenser);
        Assert.Equal((byte)20, command.Beam);
        Assert.Equal((byte)20, command.Gun);
        Assert.Equal(new byte[] { 4, 4, 3, 3, 3, 3 }, command.Shields);
        Assert.Equal((byte)20, command.Engine);
        Assert.Equal((byte)10, command.Warp);
        Assert.Equal((byte)10, command.Sensor);
    }

    [Fact]
    public void Control_rejects_truncated_and_trailing_bytes()
    {
        Assert.False(OriginalTacticalControlCodec.TryDecode(LivePayload()[..^1], out _));
        Assert.False(OriginalTacticalControlCodec.TryDecode([.. LivePayload(), 0], out _));
    }

    [Fact]
    public void Accepted_control_changes_distribution_without_refilling_or_changing_corps_identity()
    {
        Assert.True(OriginalTacticalControlCodec.TryDecode(LivePayload(), out var command));
        var before = OriginalSystemSceneCodec.CreatePlayableTacticalCorps(7) with { FillBeam = 17 };
        var decision = OriginalTacticalControlCodec.Apply(
            command with { ActorId = 7, Beam = 10, Sensor = 20 }, 2, 100, before);

        Assert.True(decision.Accepted);
        var after = Assert.NotNull(decision.State);
        Assert.Equal(7u, after.Id);
        Assert.Equal((byte)10, after.PowerBeam);
        Assert.Equal((byte)20, after.PowerSensor);
        Assert.Equal((ushort)17, after.FillBeam);
        Assert.Equal((byte)20, after.PowerGun);
        Assert.Equal(new byte[] { 4, 4, 3, 3, 3, 3 }, after.PowerShield);
    }

    [Fact]
    public void Foreign_unit_and_over_budget_control_cannot_change_state()
    {
        Assert.True(OriginalTacticalControlCodec.TryDecode(LivePayload(), out var command));
        var before = OriginalSystemSceneCodec.CreatePlayableTacticalCorps(7);
        command = command with { ActorId = 7 };
        var foreign = OriginalTacticalControlCodec.Apply(command, 99, 100, before);
        var over = OriginalTacticalControlCodec.Apply(command with { Sensor = 11 }, 2, 100, before);

        Assert.False(foreign.Accepted);
        Assert.Equal("TACTICAL_UNIT_NOT_CONTROLLED", foreign.ErrorCode);
        Assert.Null(foreign.State);
        Assert.False(over.Accepted);
        Assert.Equal("TACTICAL_POWER_BUDGET_EXCEEDED", over.ErrorCode);
        Assert.Null(over.State);
    }

    [Fact]
    public void Control_cannot_impersonate_a_different_commanding_character()
    {
        Assert.True(OriginalTacticalControlCodec.TryDecode(LivePayload(), out var command));
        var result = OriginalTacticalControlCodec.Apply(command with { ActorId = 99 },
            2, 100, OriginalSystemSceneCodec.CreatePlayableTacticalCorps(7));
        Assert.False(result.Accepted);
        Assert.Equal("TACTICAL_ACTOR_NOT_CONTROLLED", result.ErrorCode);
        Assert.Null(result.State);
    }
}
