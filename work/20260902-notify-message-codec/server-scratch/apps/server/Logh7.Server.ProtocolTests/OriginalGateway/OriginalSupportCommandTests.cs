using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 修理 (0x0413) and 補給 (0x0414). Both rows of constmsg group 0 name the vessel
/// that performs the command - 「工作艦による修理を行う 注）旗艦の右」 and
/// 「補給艦による補給を行う 注）旗艦の左」 - so the battlefield had to carry one before
/// either command could mean anything.
/// </summary>
public sealed class OriginalSupportCommandTests
{
    /// <summary>
    /// One body serves both: the client's two loggers print the identical layout
    /// at identical offsets, `time / wait / id / unit|transport_unit / target`.
    /// </summary>
    [Theory]
    [InlineData(OriginalTacticalCommandCodec.RepairFleetCommandType, "0413")]
    [InlineData(OriginalTacticalCommandCodec.SupplyFleetCommandType, "0414")]
    public void The_body_is_the_shape_the_clients_own_loggers_name(ushort type, string prefix)
    {
        var command = new OriginalTacticalSupportCommand(0x11223344, 7, 2, 0x7E000101, 2);

        var body = OriginalTacticalCommandCodec.EncodeSupportCommand(command, type)
            .AsSpan(OriginalLoginCodec.MessageCodeSize).ToArray();

        Assert.Equal(22, body.Length);
        Assert.Equal(prefix + "11223344" + "00000007" + "00000002" + "7E000101" + "00000002",
            Convert.ToHexString(body));
        Assert.True(OriginalTacticalCommandCodec.TryDecodeSupportCommand(body, type, out var decoded));
        Assert.Equal(command, decoded);
        // Not the other command's type, and not one byte short.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeSupportCommand(body,
            type == OriginalTacticalCommandCodec.RepairFleetCommandType
                ? OriginalTacticalCommandCodec.SupplyFleetCommandType
                : OriginalTacticalCommandCodec.RepairFleetCommandType, out _));
        Assert.False(OriginalTacticalCommandCodec.TryDecodeSupportCommand(
            body.AsSpan(0, 21), type, out _));
    }

    /// <summary>
    /// Rows 25 and 26: 実行待機時間48G秒 and a stated 実行所要時間1800G秒 - a recovered
    /// number, so it is enforced as real occupancy rather than left at zero.
    /// </summary>
    [Fact]
    public void Both_carry_the_original_schedule_including_the_1800_second_duration()
    {
        foreach (var type in new[]
                 {
                     OriginalTacticalCommandCodec.RepairFleetCommandType,
                     OriginalTacticalCommandCodec.SupplyFleetCommandType,
                 })
        {
            Assert.Equal(48u, OriginalTacticalCommandTiming.WaitTicks(type));
            Assert.Equal(1800u, OriginalTacticalCommandTiming.DurationTicks(type));
        }
        Assert.Equal(89, OriginalTacticalCommandCatalog.SelectorOf(
            OriginalTacticalCommandCodec.RepairFleetCommandType));
        Assert.Equal(90, OriginalTacticalCommandCatalog.SelectorOf(
            OriginalTacticalCommandCodec.SupplyFleetCommandType));
    }

    /// <summary>
    /// The battlefield now carries the two vessels the commands' own descriptions
    /// name, alongside the flagship's spawn and on the sides the commands' rows
    /// name - 工作艦 to starboard, 補給艦 to port - in both grids.
    /// </summary>
    /// <remarks>
    /// The placement is not cosmetic. The client rejects a support target that lies
    /// outside the vessel's own service range (FUN_004F1180's window test), and the
    /// original placement - 10 units astern and 1.5 abeam - put the flagship outside
    /// it, so no support command could complete however correctly it was driven. The
    /// client's own 修理 tooltip says where they belong: 「注) 旗艦の右」.
    /// See evidence/support-command-target-rules-v399.md.
    /// </remarks>
    [Fact]
    public void The_battlefield_carries_a_repair_vessel_and_a_supply_vessel()
    {
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));

        foreach (var grid in new uint[] { 101, 102 })
        {
            var fleets = catalog.Resolve(grid).Fleets ?? [];
            var repair = Assert.Single(fleets, f => f.Repairs);
            var supply = Assert.Single(fleets, f => f.Supplies);
            // Row 25 says 「注）旗艦の右」 and row 26 「注）旗艦の左」. Screen-right is
            // +y: the 補給艦 authored at y = +1.5 rendered to the flagship's right
            // and the 工作艦 at y = -1.5 to its left, so the original placement had
            // both on the wrong side. They are swapped here, and brought alongside.
            var repairSpawn = Assert.Single(repair.Ships).Spawn;
            var supplySpawn = Assert.Single(supply.Ships).Spawn;
            Assert.Equal(0.15f, repairSpawn.Y);
            Assert.Equal(-0.15f, supplySpawn.Y);
            Assert.Equal(-0.5f, repairSpawn.X);
            Assert.Equal(-0.5f, supplySpawn.X);
            // Inside the service radius the client enforces: the window read live
            // (evidence/service-window-v401.md) stores 1.0 for the bearings it
            // serves and 0 elsewhere, and the target is accepted only while its
            // distance is under that. 0.52 leaves room; 1.17 did not.
            Assert.True(MathF.Sqrt(repairSpawn.X * repairSpawn.X + repairSpawn.Y * repairSpawn.Y) < 1.0f);
            Assert.True(MathF.Sqrt(supplySpawn.X * supplySpawn.X + supplySpawn.Y * supplySpawn.Y) < 1.0f);
            Assert.Equal(2, repair.Power);
            Assert.Equal(2, supply.Power);
            // Support vessels hold: they must not start a fight of their own.
            Assert.True(repair.Defensive);
            Assert.True(supply.Defensive);
            Assert.True(catalog.OutfitCarriesRole(grid, repair.Id, "repair"));
            Assert.True(catalog.OutfitCarriesRole(grid, supply.Id, "supply"));
            Assert.False(catalog.OutfitCarriesRole(grid, repair.Id, "supply"));
            Assert.False(catalog.OutfitCarriesRole(grid, 0, "repair"));
        }
    }
}
