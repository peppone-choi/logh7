using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 態勢変更 (0x0411) and 出撃 (0x0412) - the tactical palette's own posture
/// commands. The strategy card's 0x0B06 route already performed the posture
/// change and recorded in its own comment that the tactical route was missing.
/// </summary>
public sealed class OriginalTacticalModeTests
{
    /// <summary>
    /// 態勢変更's body is the client's own: <c>_INF:CommandChangeMode#</c>
    /// (0x00497150) prints <c>time / wait / id / unit[n] / kind / target_base</c> -
    /// the 攻撃 shape with a base id where the target unit sits.
    /// </summary>
    [Fact]
    public void The_change_mode_body_is_the_shape_the_clients_own_logger_names()
    {
        var command = new OriginalTacticalChangeModeCommand(0x11223344, 7, 2, [2u, 3u], 5, 0x7F000001);

        var body = OriginalTacticalCommandCodec.EncodeChangeModeCommand(command)
            .AsSpan(OriginalLoginCodec.MessageCodeSize).ToArray();

        Assert.Equal(
            "0411" + "11223344" + "00000007" + "00000002" + "02" + "00000002" + "00000003" +
            "05" + "7F000001",
            Convert.ToHexString(body));
        Assert.True(OriginalTacticalCommandCodec.TryDecodeChangeModeCommand(body, out var decoded));
        Assert.Equal(command.Time, decoded.Time);
        Assert.Equal(command.Order, decoded.Order);
        Assert.Equal([2u, 3u], decoded.UnitIds);
        Assert.Equal(5, decoded.Kind);
        Assert.Equal(0x7F000001u, decoded.TargetBase);
        Assert.False(OriginalTacticalCommandCodec.TryDecodeChangeModeCommand(
            body.AsSpan(0, body.Length - 1), out _));
    }

    /// <summary>
    /// 出撃 carries the bare 「header plus unit list」 body - the same field list as
    /// 撤退 - so the recovered warp decoder reads it, and only on its own type.
    /// </summary>
    [Fact]
    public void The_sortie_body_is_the_one_it_shares_with_retreat()
    {
        const string sortie = "0412" + "000000000000000000000005" + "01" + "00000002";

        Assert.True(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(sortie),
            OriginalTacticalCommandCodec.SortieCommandType, out var command));
        Assert.Equal(5u, command.Order);
        Assert.Equal([2u], command.UnitIds);
        Assert.False(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(sortie), out _));
    }

    /// <summary>
    /// Both take their schedule from the client's own tooltip: constmsg group 0
    /// rows 20 and 21, 実行待機時間48G秒 / 実行所要時間240G秒. The 240 is a recovered
    /// number, so it is enforced as occupancy rather than left at zero.
    /// </summary>
    [Fact]
    public void Both_carry_the_original_schedule_including_a_real_duration()
    {
        foreach (var type in new[]
                 {
                     OriginalTacticalCommandCodec.ChangeModeCommandType,
                     OriginalTacticalCommandCodec.SortieCommandType,
                 })
        {
            Assert.Equal(48u, OriginalTacticalCommandTiming.WaitTicks(type));
            Assert.Equal(240u, OriginalTacticalCommandTiming.DurationTicks(type));
        }
        Assert.Equal("態勢変更", OriginalTacticalCommandTiming.For(
            OriginalTacticalCommandCodec.ChangeModeCommandType)!.Value.Name);
        Assert.Equal("出撃", OriginalTacticalCommandTiming.For(
            OriginalTacticalCommandCodec.SortieCommandType)!.Value.Name);
    }

    /// <summary>Both are commands the shipped client can actually send.</summary>
    [Fact]
    public void Both_are_reachable_from_the_palette()
    {
        Assert.Equal(55, OriginalTacticalCommandCatalog.SelectorOf(
            OriginalTacticalCommandCodec.ChangeModeCommandType));
        Assert.Equal(58, OriginalTacticalCommandCatalog.SelectorOf(
            OriginalTacticalCommandCodec.SortieCommandType));
        Assert.Equal("ChangeMode", OriginalTacticalCommandCatalog.NameOf(
            OriginalTacticalCommandCodec.ChangeModeCommandType));
        Assert.Equal("Sortie", OriginalTacticalCommandCatalog.NameOf(
            OriginalTacticalCommandCodec.SortieCommandType));
    }
}
