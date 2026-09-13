using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// The eight formations 隊列変更 chooses between, as the client names them.
/// </summary>
/// <remarks>
/// Recovered by hovering the sub-panel 隊列変更 opens and reading the client's own
/// tooltips - no command was issued to obtain them
/// (evidence/palette-named-by-the-client-v405.md).
/// </remarks>
public sealed class OriginalFleetFormationCatalogTests
{
    [Fact]
    public void The_panel_has_eight_named_formations_in_its_own_order()
    {
        Assert.Equal(8, OriginalFleetFormationCatalog.Count);
        Assert.Equal("防御", OriginalFleetFormationCatalog.NameOf(0));
        Assert.Equal("紡錘", OriginalFleetFormationCatalog.NameOf(1));
        Assert.Equal("艦種１", OriginalFleetFormationCatalog.NameOf(2));
        Assert.Equal("艦種２", OriginalFleetFormationCatalog.NameOf(3));
        Assert.Equal("混成１", OriginalFleetFormationCatalog.NameOf(4));
        Assert.Equal("混成２", OriginalFleetFormationCatalog.NameOf(5));
        Assert.Equal("散開", OriginalFleetFormationCatalog.NameOf(6));
        // The live 隊列変更 this lane captured carried kind = 7.
        Assert.Equal("三列", OriginalFleetFormationCatalog.NameOf(7));
    }

    [Fact]
    public void A_value_the_panel_cannot_produce_is_not_a_formation()
    {
        Assert.Null(OriginalFleetFormationCatalog.NameOf(8));
        Assert.Null(OriginalFleetFormationCatalog.NameOf(255));
        Assert.False(OriginalFleetFormationCatalog.IsKnown(8));
        Assert.True(OriginalFleetFormationCatalog.IsKnown(7));
    }
}
