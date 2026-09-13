using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// Recovered bodies for commands this authority does not yet handle. Every shape
/// comes from the client's own per-command logger, and two of them are checked
/// against frames the shipped client actually sent.
/// </summary>
/// <remarks>
/// A decoder is not a handler. These are here so the remaining work is a matter
/// of deciding what each command *does*, with its wire shape already settled and
/// tested - and so that a captured body can be read the moment a live press
/// produces one.
/// </remarks>
public sealed class OriginalUnhandledCommandShapeTests
{
    /// <summary>
    /// ORIGINAL_OBSERVED. 陸戦解除 as the shipped client sent it on 2026-09-10,
    /// captured off the not-implemented refusal, which keeps the body.
    /// </summary>
    [Fact]
    public void The_captured_evacuate_troops_body_decodes()
    {
        const string captured = "0410" + "00000000" + "00000000" + "00000002" + "01" + "00000002";

        Assert.True(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(captured), 0x0410, out var command));
        Assert.Equal(0u, command.Time);
        Assert.Equal(2u, command.Order);
        Assert.Equal([2u], command.UnitIds);
        // The same body on another type is not this command.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString(captured), 0x0418, out _));
    }

    /// <summary>
    /// ORIGINAL_OBSERVED. 所属変更 as the shipped client sent it on 2026-09-10 from
    /// palette r3c2, with the ally in the unit list and the player's own unit as
    /// the target.
    /// </summary>
    [Fact]
    public void The_captured_change_authority_body_decodes()
    {
        const string captured = "0420" + "3B627631" + "00000000" + "00000002" + "01" +
            "7E00010A" + "00000002";

        Assert.True(OriginalTacticalCommandCodec.TryDecodeUnitListWithTarget(
            Convert.FromHexString(captured), 0x0420, out var units, out var target));
        Assert.Equal(0x3B627631u, units.Time);
        Assert.Equal(0u, units.Wait);
        Assert.Equal(2u, units.Order);
        Assert.Equal([0x7E00010Au], units.UnitIds);   // 2113929482, the allied picket
        Assert.Equal(2u, target);                     // the player's own unit
    }

    /// <summary>
    /// 陸戦 (0x040F, <c>skill</c>) and <c>MoveTroop</c> (0x0416, <c>area</c>) share
    /// the 「header, unit list, one byte」 body.
    /// </summary>
    [Theory]
    [InlineData((ushort)0x040F)]
    [InlineData((ushort)0x0416)]
    public void The_unit_list_with_one_byte_body_round_trips(ushort type)
    {
        var body = Convert.FromHexString(
            type.ToString("X4") + "00000009" + "00000003" + "00000002" + "02" +
            "00000002" + "00000005" + "07");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeUnitListWithByte(
            body, type, out var units, out var value));
        Assert.Equal(9u, units.Time);
        Assert.Equal(3u, units.Wait);
        Assert.Equal([2u, 5u], units.UnitIds);
        Assert.Equal(7, value);
        // One byte short, and the neighbouring type, are both refused.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeUnitListWithByte(
            body.AsSpan(0, body.Length - 1), type, out _, out _));
        Assert.False(OriginalTacticalCommandCodec.TryDecodeUnitListWithByte(
            body, (ushort)(type + 1), out _, out _));
    }

    /// <summary>
    /// <c>AttackTroop</c> (0x0417) shares 所属変更's 「header, unit list, one target」
    /// body.
    /// </summary>
    [Fact]
    public void The_attack_troop_body_round_trips()
    {
        var body = Convert.FromHexString(
            "0417" + "00000001" + "00000000" + "00000002" + "01" + "00000002" + "7E000106");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeUnitListWithTarget(
            body, 0x0417, out var units, out var target));
        Assert.Equal([2u], units.UnitIds);
        Assert.Equal(0x7E000106u, target);
    }

    /// <summary>
    /// 空戦 (0x040E) shares 攻撃's body - the client's two loggers print the same
    /// field list, with <c>skill</c> where 攻撃 has <c>kind</c>.
    /// </summary>
    [Fact]
    public void The_air_battle_body_is_the_attack_body()
    {
        var body = Convert.FromHexString(
            "040E" + "00000004" + "00000000" + "00000002" + "01" + "00000002" + "03" + "7E000106");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeAttackShipCommand(body, 0x040E, out var command));
        Assert.Equal(4u, command.Time);
        Assert.Equal([2u], command.UnitIds);
        Assert.Equal(3, command.Kind);          // the logger calls it skill
        Assert.Equal(0x7E000106u, command.TargetId);
        // ...and it is not mistaken for 攻撃 itself.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeAttackShipCommand(body, out _));
    }

    /// <summary>
    /// <c>EncourageBase</c> (0x041D) and <c>StopBase</c> (0x041E) carry only a base.
    /// </summary>
    [Theory]
    [InlineData((ushort)0x041D)]
    [InlineData((ushort)0x041E)]
    public void The_base_only_body_round_trips(ushort type)
    {
        var body = Convert.FromHexString(
            type.ToString("X4") + "0000000B" + "00000000" + "00000002" + "00000001");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeBaseCommand(
            body, type, out var time, out var wait, out var order, out var baseId));
        Assert.Equal(11u, time);
        Assert.Equal(0u, wait);
        Assert.Equal(2u, order);
        Assert.Equal(1u, baseId);
        Assert.False(OriginalTacticalCommandCodec.TryDecodeBaseCommand(
            body.AsSpan(0, body.Length - 1), type, out _, out _, out _, out _));
    }

    /// <summary>
    /// 緊急補給 (0x0422) carries a base and a unit - the same two-word tail 修理 and
    /// 補給 use, so the recovered support decoder reads it.
    /// </summary>
    [Fact]
    public void The_emergency_supply_body_is_the_support_body()
    {
        var body = Convert.FromHexString(
            "0422" + "0000000C" + "00000000" + "00000002" + "00000001" + "00000002");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeSupportCommand(body, 0x0422, out var command));
        Assert.Equal(12u, command.Time);
        Assert.Equal(1u, command.Unit);        // the base
        Assert.Equal(2u, command.TargetId);    // the unit being made suppliable
    }

    /// <summary>
    /// This test used to name the types that had a recovered shape and no handler,
    /// so a decoder could never be mistaken for an implementation. There are none
    /// left: every type in the client's own table is handled, so the assertion is
    /// now its own inverse.
    /// </summary>
    [Fact]
    public void Every_recovered_shape_now_has_a_handler()
    {
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            Assert.True(OriginalTacticalCommandCatalog.IsTacticalCommand(type));
            Assert.True(OriginalTacticalCommandCoverage.IsHandled(type),
                $"0x{type:X4} has a recovered shape but no handler");
        }
    }
}
