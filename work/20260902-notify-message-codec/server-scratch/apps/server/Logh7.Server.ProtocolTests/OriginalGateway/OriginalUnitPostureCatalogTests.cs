using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// The four postures 態勢変更 offers, as the client names them.
/// </summary>
public sealed class OriginalUnitPostureCatalogTests
{
    [Fact]
    public void The_panel_offers_four_postures_in_its_own_order()
    {
        Assert.Equal(["駐留", "航行", "戦闘", "碇泊"], OriginalUnitPostureCatalog.PanelOrder);
    }

    /// <summary>
    /// Only the two whose wire value row 21 states are addressable by value; the
    /// other two are recorded as existing, not guessed at.
    /// </summary>
    [Fact]
    public void Only_the_two_observed_values_are_named()
    {
        Assert.Equal("駐留", OriginalUnitPostureCatalog.NameOf(OriginalUnitPostureCatalog.Garrison));
        Assert.Equal("碇泊", OriginalUnitPostureCatalog.NameOf(OriginalUnitPostureCatalog.Anchored));
        Assert.Equal((byte)4, OriginalUnitPostureCatalog.Garrison);
        Assert.Equal((byte)5, OriginalUnitPostureCatalog.Anchored);
        Assert.Null(OriginalUnitPostureCatalog.NameOf(0));
        Assert.Null(OriginalUnitPostureCatalog.NameOf(6));
        Assert.Equal("惑星／要塞軌道上に碇泊",
            OriginalUnitPostureCatalog.MeaningOf(OriginalUnitPostureCatalog.Anchored));
    }
}
