using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// The six missions, as the client names them.
/// </summary>
public sealed class OriginalMissionCatalogTests
{
    [Fact]
    public void The_panel_names_six_missions_in_its_own_order()
    {
        Assert.Equal(6, OriginalMissionCatalog.Count);
        Assert.Equal("遊撃", OriginalMissionCatalog.NameOf(0));
        Assert.Equal("防衛", OriginalMissionCatalog.NameOf(1));
        Assert.Equal("占領", OriginalMissionCatalog.NameOf(2));
        Assert.Equal("迎撃", OriginalMissionCatalog.NameOf(3));
        Assert.Equal("偵察", OriginalMissionCatalog.NameOf(4));
        Assert.Equal("撤退", OriginalMissionCatalog.NameOf(5));
    }

    /// <summary>
    /// The static reading and the client's own panel agree on the same six values,
    /// and on which one carries no target.
    /// </summary>
    [Fact]
    public void The_catalogue_agrees_with_the_recovered_wire_range()
    {
        Assert.Equal(OriginalTacticalCommandCodec.HighestMission,
            OriginalMissionCatalog.Count - 1);
        // Mission 5 is the one whose branch skips the target pick - and it is 撤退.
        Assert.Equal("撤退",
            OriginalMissionCatalog.NameOf(OriginalTacticalCommandCodec.MissionWithoutTarget));
        Assert.Null(OriginalMissionCatalog.NameOf(6));
        Assert.False(OriginalMissionCatalog.IsKnown(6));
    }
}
