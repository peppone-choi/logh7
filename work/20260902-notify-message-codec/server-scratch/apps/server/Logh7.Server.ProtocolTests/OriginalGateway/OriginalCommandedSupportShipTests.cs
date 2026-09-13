using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// The support vessels are served with the player's own character, so the
/// client's selection band can offer them to its own selectability test.
/// </summary>
/// <remarks>
/// EXPERIMENT. The band picks with mask 0x10581 and that mask resolves to a demand
/// for node flag 0x0400, which the player's unit carries and no authored allied
/// ship does (evidence/band-mask-solved-v395.md). The player's ship record and an
/// authored fleet's come from the same builder, so the only difference this
/// authority controls is the record's Character word. These tests pin the change;
/// whether it actually sets 0x0400 is a live question to be answered by re-reading
/// the hit list, not by this suite.
/// </remarks>
public sealed class OriginalCommandedSupportShipTests
{
    [Fact]
    public async Task The_support_vessels_are_served_with_the_players_character()
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog);
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), ct);

        // Ask for the two support vessels and the ordinary picket by id.
        var query = Convert.FromHexString("033A0003" + "7E00010B" + "7E00010C" + "7E00010A");
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(query, key, 2), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(decoded.Payload!.AsSpan(4), out var ships));
        var records = ships.Records;

        var repair = Assert.Single(records, s => s.Id == 2113929483u);
        var supply = Assert.Single(records, s => s.Id == 2113929484u);
        // Served as units of the character the player is logged in as...
        Assert.Equal(2u, repair.Character);
        Assert.Equal(2u, supply.Character);

        // ...while an ordinary allied picket keeps its own outfit.
        var picket = Assert.Single(records, s => s.Id == 2113929482u);
        Assert.NotEqual(2u, picket.Character);
    }

    /// <summary>
    /// Only the two authored roles are treated this way - the rule is the
    /// battlefield's stated role, never a guess from power or proximity.
    /// </summary>
    [Fact]
    public void Only_role_carrying_outfits_qualify()
    {
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        Assert.True(catalog.OutfitCarriesRole(101, 2113929575u, "repair"));
        Assert.True(catalog.OutfitCarriesRole(101, 2113929576u, "supply"));
        // The allied picket is the player's own power and carries no role.
        Assert.False(catalog.OutfitCarriesRole(101, 2113929574u, "repair"));
        Assert.False(catalog.OutfitCarriesRole(101, 2113929574u, "supply"));
    }

}
