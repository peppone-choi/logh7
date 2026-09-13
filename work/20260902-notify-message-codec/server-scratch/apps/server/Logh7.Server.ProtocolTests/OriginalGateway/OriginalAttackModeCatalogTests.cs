using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 攻撃's three modes, as the client names them.
/// </summary>
public sealed class OriginalAttackModeCatalogTests
{
    /// <summary>
    /// The sub-panel's cell order is 一斉 / 連続 / 停止, and the recovered wire kinds
    /// for those UI modes are 1 / 2 / 0.
    /// </summary>
    [Fact]
    public void The_panels_modes_map_to_the_recovered_wire_kinds()
    {
        Assert.Equal("一斉攻撃", OriginalAttackModeCatalog.NameOf(OriginalAttackModeCatalog.Salvo));
        Assert.Equal("連続攻撃", OriginalAttackModeCatalog.NameOf(OriginalAttackModeCatalog.Continuous));
        Assert.Equal("攻撃停止", OriginalAttackModeCatalog.NameOf(OriginalAttackModeCatalog.CeaseFire));
        Assert.Equal((byte)1, OriginalAttackModeCatalog.Salvo);
        Assert.Equal((byte)2, OriginalAttackModeCatalog.Continuous);
        Assert.Equal((byte)0, OriginalAttackModeCatalog.CeaseFire);
    }

    /// <summary>
    /// 射撃's own sub-panel names the three weapon families in the order the
    /// recovered 0/1/2 selection already used.
    /// </summary>
    [Fact]
    public void The_shoot_panel_names_the_three_weapon_families()
    {
        Assert.Equal("ビーム兵装", OriginalTacticalCommandAuthority.ShotArmsName(0));
        Assert.Equal("ガン兵装", OriginalTacticalCommandAuthority.ShotArmsName(1));
        Assert.Equal("ミサイル兵装", OriginalTacticalCommandAuthority.ShotArmsName(2));
        Assert.Null(OriginalTacticalCommandAuthority.ShotArmsName(3));
    }

    /// <summary>
    /// The trade each mode states is carried too, because it is the constraint a
    /// future damage or cadence model has to satisfy.
    /// </summary>
    [Fact]
    public void Each_mode_carries_the_trade_the_client_states()
    {
        Assert.Equal("破壊力大、連続攻撃性低し", OriginalAttackModeCatalog.TradeOf(1));
        Assert.Equal("破壊力小、連続攻撃性高し", OriginalAttackModeCatalog.TradeOf(2));
        Assert.Equal("攻撃を停止", OriginalAttackModeCatalog.TradeOf(0));
        Assert.Null(OriginalAttackModeCatalog.TradeOf(3));
        Assert.False(OriginalAttackModeCatalog.IsKnown(3));
    }
}
