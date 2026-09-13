using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// The client's own tactical command table, recovered from its per-command
/// loggers. Its value is that it is checked, not assumed: the types this
/// authority already recovered from captures must land on their own names.
/// </summary>
public sealed class OriginalTacticalCommandCatalogTests
{
    [Theory]
    // Every type this authority implemented from a capture, long before the
    // table existed. If the logger block's order were wrong, these would not line up.
    [InlineData(OriginalTacticalCommandCodec.MoveShipCommandType, "MoveShip")]
    [InlineData(OriginalTacticalCommandCodec.TurnShipCommandType, "TurnShip")]
    [InlineData(OriginalTacticalCommandCodec.ReverseShipCommandType, "ReverseShip")]
    [InlineData(OriginalTacticalCommandCodec.WarpCommandType, "WarpShip")]
    [InlineData(OriginalTacticalCommandCodec.AttackShipCommandType, "AttackShip")]
    [InlineData(OriginalTacticalCommandCodec.ShootShipCommandType, "ShootShip")]
    [InlineData(OriginalEncourageFlagshipCodec.CommandType, "EncourageFlagship")]
    [InlineData(OriginalTacticalCommandCodec.StopCommandType, "Stop")]
    [InlineData(OriginalTacticalControlCodec.CommandType, "Control")]
    // ...and the two the mission sub-panel work separated.
    [InlineData(OriginalSuggestionCodec.CommandType, "Suggestion")]
    [InlineData(OriginalTacticalCommandCodec.MissionCommandType, "Mission")]
    public void The_table_agrees_with_every_independently_recovered_type(ushort type, string name) =>
        Assert.Equal(name, OriginalTacticalCommandCatalog.NameOf(type));

    [Fact]
    public void The_table_covers_the_clients_whole_tactical_block_and_nothing_else()
    {
        Assert.Equal(0x0400, OriginalTacticalCommandCatalog.FirstType);
        Assert.Equal(0x0422, OriginalTacticalCommandCatalog.LastType);
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            Assert.True(OriginalTacticalCommandCatalog.IsTacticalCommand(type));
            Assert.False(string.IsNullOrWhiteSpace(OriginalTacticalCommandCatalog.NameOf(type)));
        }
        Assert.Null(OriginalTacticalCommandCatalog.NameOf(0x03FF));
        Assert.Null(OriginalTacticalCommandCatalog.NameOf(0x0423));
        // 0x0423..0x042F are the Notify* answers, not commands the client sends.
        Assert.False(OriginalTacticalCommandCatalog.IsTacticalCommand(
            OriginalTacticalCommandCodec.MovedShipNotificationType));
    }

    /// <summary>
    /// The reason the table exists. A command the client can send but this
    /// authority has not implemented must be refused visibly; before the table,
    /// the graceful band stopped at 0x040F and every type above it - 任務 0x0421
    /// among them - was treated as a protocol violation and dropped the player's
    /// connection mid-battle.
    /// </summary>
    [Theory]
    [InlineData((ushort)0x040E)] // 空戦 AirBattle
    [InlineData((ushort)0x0411)] // 態勢変更 ChangeMode
    [InlineData((ushort)0x0419)] // 要塞砲 ShootFortress
    [InlineData((ushort)0x0420)] // 所属変更 ChangeAuthority
    [InlineData((ushort)0x0422)] // 緊急補給 EmergencySupply
    public void A_command_the_client_can_send_is_never_a_protocol_violation(ushort type) =>
        Assert.True(OriginalTacticalCommandCatalog.IsTacticalCommand(type));

    /// <summary>
    /// Which commands the shipped client can actually put on the wire. The
    /// dispatcher's selector table is the authority on that, and the two types
    /// this lane has captured live must line up with their arms.
    /// </summary>
    [Theory]
    [InlineData(OriginalTacticalCommandCodec.MissionCommandType, (byte)127)]     // captured live
    [InlineData((ushort)0x0420, (byte)126)]                                      // captured live
    [InlineData(OriginalTacticalCommandCodec.ParallelMoveShipCommandType, (byte)51)] // captured live
    [InlineData(OriginalTacticalCommandCodec.MoveShipCommandType, (byte)49)]
    [InlineData(OriginalTacticalCommandCodec.ShootShipCommandType, (byte)53)]
    [InlineData(OriginalTacticalControlCodec.CommandType, (byte)54)]
    [InlineData(OriginalSuggestionCodec.CommandType, (byte)128)]
    public void The_selector_table_says_which_commands_the_client_can_send(ushort type, byte selector)
    {
        Assert.Equal(selector, OriginalTacticalCommandCatalog.SelectorOf(type));
        Assert.True(OriginalTacticalCommandCatalog.CanBeSentByClient(type));
    }

    /// <summary>
    /// Four catalogued commands have no selector arm at all, so this build cannot
    /// send them. They are still answered rather than dropped - but no live
    /// receipt for them is possible, and none must be claimed.
    /// </summary>
    [Theory]
    [InlineData((ushort)0x0415)] // StopFleet
    [InlineData((ushort)0x0416)] // MoveTroop
    [InlineData((ushort)0x0417)] // AttackTroop
    [InlineData((ushort)0x0418)] // StopTroop
    public void Four_catalogued_commands_have_no_way_to_be_sent(ushort type)
    {
        Assert.True(OriginalTacticalCommandCatalog.IsTacticalCommand(type));
        Assert.False(OriginalTacticalCommandCatalog.CanBeSentByClient(type));
        Assert.Equal(0, OriginalTacticalCommandCatalog.SelectorOf(type));
    }

    /// <summary>A selector is claimed for every other catalogued command.</summary>
    [Fact]
    public void Thirty_one_of_the_thirty_five_commands_have_an_arm()
    {
        var sendable = 0;
        for (var type = OriginalTacticalCommandCatalog.FirstType;
             type <= OriginalTacticalCommandCatalog.LastType;
             type++)
        {
            if (OriginalTacticalCommandCatalog.CanBeSentByClient(type)) sendable++;
        }
        Assert.Equal(31, sendable);
        Assert.Equal(0, OriginalTacticalCommandCatalog.SelectorOf(0x0423));
    }
}
