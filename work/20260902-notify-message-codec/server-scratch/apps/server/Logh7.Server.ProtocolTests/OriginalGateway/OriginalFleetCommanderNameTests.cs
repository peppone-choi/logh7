using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// Every authored fleet in the field can be named by the client.
/// </summary>
/// <remarks>
/// The tactical scene carries no unit name: the ship record holds a character id
/// and 0x0337 answers with ids alone, so the client draws a fleet's label from its
/// own character table. Before this, only the viewer's own fleet and the primary
/// NPC had a character record, and every other fleet went unlabelled - which is
/// what the live field showed (evidence/service-window-and-live-repair-v401.md).
/// </remarks>
public sealed class OriginalFleetCommanderNameTests
{
    private static OriginalBattlefieldCatalog Catalog() =>
        OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));

    [Fact]
    public void Every_authored_fleet_states_a_commander()
    {
        var catalog = Catalog();
        foreach (var grid in new uint[] { 101, 102 })
        {
            var fleets = catalog.Resolve(grid).Fleets ?? [];
            Assert.NotEmpty(fleets);
            foreach (var fleet in fleets)
            {
                Assert.False(string.IsNullOrWhiteSpace(fleet.Commander));
            }
        }
    }

    /// <summary>
    /// The client asks for the character by the id the ship record carries, so the
    /// name has to hang off the fleet's own id, not the ship's.
    /// </summary>
    [Fact]
    public void The_ship_record_points_at_its_fleet_for_the_name()
    {
        var catalog = Catalog();
        foreach (var fleet in catalog.Resolve(102).Fleets ?? [])
        {
            foreach (var participant in fleet.Project(102))
            {
                Assert.Equal(fleet.Id, participant.Ship.Character);
            }
        }
    }

    /// <summary>
    /// The catalog can find a fleet by its outfit id, which is how the persisted
    /// restore path recovers the commander name for a saved row.
    /// </summary>
    [Fact]
    public void An_outfit_id_resolves_to_its_fleet()
    {
        var catalog = Catalog();
        var picket = catalog.FindOutfit(2113929574u);
        Assert.NotNull(picket);
        Assert.False(string.IsNullOrWhiteSpace(picket!.Commander));
        Assert.Null(catalog.FindOutfit(1u));
    }

    /// <summary>
    /// A live session serves that character record when the client asks for it.
    /// </summary>
    [Fact]
    public async Task The_authority_answers_a_character_query_for_an_authored_fleet()
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = Catalog();
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog);
        await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), key, 1), ct);

        // 0x0322 RequestInformationCharacter: the 2-byte type then the id, big-endian.
        // 0x7E000166 is 2113929574, the allied picket's fleet - the id that fleet's
        // ship records carry as Character.
        var query = Convert.FromHexString("0322" + "7E000166");
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(query, key, 2), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
    }
}
