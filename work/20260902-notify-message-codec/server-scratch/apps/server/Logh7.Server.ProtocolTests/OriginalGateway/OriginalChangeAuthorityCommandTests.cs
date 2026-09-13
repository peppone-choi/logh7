using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 所属変更 (0x0420) - the command that puts a fleet already in the field under
/// this character's command.
/// </summary>
/// <remarks>
/// Until it existed the client could build 移動 or 修理 for an authored fleet -
/// its palette filters allow it - and the authority could only answer
/// TACTICAL_UNIT_NOT_CONTROLLED, because nothing recorded anyone commanding that
/// fleet. See evidence/move-point-pick-and-control-rule-v403.md.
/// </remarks>
public sealed class OriginalChangeAuthorityCommandTests
{
    private static byte[] Body(uint target, params uint[] units)
    {
        var body = new List<byte>();
        body.AddRange(Convert.FromHexString("0420"));
        body.AddRange(Be(0));            // time
        body.AddRange(Be(0));            // wait
        body.AddRange(Be(1));            // request id
        body.Add(checked((byte)units.Length));
        foreach (var unit in units) body.AddRange(Be(unit));
        body.AddRange(Be(target));
        return [.. body];
    }

    private static byte[] Be(uint value) =>
    [
        (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value,
    ];

    [Fact]
    public void The_catalogue_now_counts_it_as_handled()
    {
        Assert.True(OriginalTacticalCommandCoverage.IsHandled(
            OriginalTacticalCommandCodec.ChangeAuthorityCommandType));
        Assert.DoesNotContain(OriginalTacticalCommandCodec.ChangeAuthorityCommandType,
            OriginalTacticalCommandCoverage.Unhandled());
        Assert.Equal("ChangeAuthority",
            OriginalTacticalCommandCatalog.NameOf(0x0420));
    }

    /// <summary>
    /// The body the handler reads is the one the client actually sends.
    /// </summary>
    [Fact]
    public void The_authored_body_round_trips_through_the_decoder()
    {
        Assert.True(OriginalTacticalCommandCodec.TryDecodeChangeAuthorityCommand(
            Body(2, 2113929483u, 2113929484u), out var command));
        Assert.Equal(2u, command.TargetId);
        Assert.Equal([2113929483u, 2113929484u], command.UnitIds);
    }

    /// <summary>
    /// A hostile fleet is not a change of command, and the viewer's own unit
    /// cannot be delegated to itself.
    /// </summary>
    /// <summary>
    /// A friendly formation is a real target shape, but re-parenting into it is
    /// not implemented, and the client is told exactly that.
    /// </summary>
    [Fact]
    public async Task It_names_reparenting_as_unimplemented()
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog);
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), ct);
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Body(2113929482u, 2113929483u), key, 2), ct);
        Assert.Contains("AUTHORITY_REPARENT_NOT_IMPLEMENTED", result.ResponseMetadata ?? string.Empty);
    }

    [Theory]
    [InlineData(2113929477u, "AUTHORITY_UNIT_NOT_FRIENDLY")]
    [InlineData(2u, "AUTHORITY_UNIT_IS_OWN")]
    public async Task It_refuses_what_it_cannot_bind(uint unit, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog);
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), ct);
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Body(2, unit), key, 2), ct);
        Assert.Contains(expected, result.ResponseMetadata ?? string.Empty);
    }

    /// <summary>
    /// The command names the formation it delegates to. A target that is not in
    /// the field at all is refused; a friendly formation that is not the viewer's
    /// own unit is refused as unimplemented rather than half-applied, because
    /// re-parenting would have to change the unit's catalog outfit.
    /// </summary>
    [Fact]
    public async Task It_refuses_a_formation_it_cannot_bind_to()
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog);
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), ct);
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Body(999, 2113929483u), key, 2), ct);
        Assert.Contains("AUTHORITY_TARGET_NOT_FRIENDLY", result.ResponseMetadata ?? string.Empty);
    }
}
