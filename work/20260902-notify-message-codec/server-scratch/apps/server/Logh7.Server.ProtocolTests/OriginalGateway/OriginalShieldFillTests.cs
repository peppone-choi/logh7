using System.Buffers.Binary;
using System.Reflection;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalShieldFillTests
{
    [Fact]
    public void CodecMatchesNativeUnitSixU32AndSixU16Layout()
    {
        var frame = OriginalTacticalShieldCodec.Encode([
            new(0x11223344, [0x01020304, 0x05060708, 0x090A0B0C, 0x0D0E0F10, 0x11121314, 0x15161718],
                [0x191A, 0x1B1C, 0x1D1E, 0x1F20, 0x2122, 0x2324])]);
        Assert.Equal("0000000003410001112233440102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F2021222324",
            Convert.ToHexString(frame));
    }

    [Fact]
    public void InitialTimingMatchesExistingStaticPeriodNotAbsoluteClockOrFillAmount()
    {
        var initial = OriginalTacticalShieldCodec.CreateAuthoredInitialState(37);
        Assert.Equal(37u, initial.UnitId);
        Assert.Equal(new uint[] { 100, 100, 100, 100, 100, 100 }, initial.NextTime);
        Assert.Equal(new ushort[] { 0, 0, 0, 0, 0, 0 }, initial.ShieldStep);
        var table = OriginalWorldBootstrapCodec.EncodeStaticPowerDistribution();
        // 6-byte envelope, move[11] f32, warp[2] u8, sensor[4] f32.
        for (var index = 0; index < 99; index++)
            Assert.Equal(initial.NextTime[0], BinaryPrimitives.ReadUInt32BigEndian(table.AsSpan(68 + index * 4)));
    }

    [Theory]
    [InlineData(5, 6)]
    [InlineData(7, 6)]
    [InlineData(6, 5)]
    [InlineData(6, 7)]
    public void CodecRejectsVectorsThatWouldShiftSubsequentFields(int timeCount, int stepCount) =>
        Assert.Throws<ArgumentException>(() => OriginalTacticalShieldCodec.Encode([
            new(1, new uint[timeCount], new ushort[stepCount])]));

    [Fact]
    public void ProjectionDoesNotLeakUnknownOrDuplicateRequestedUnits()
    {
        var available = new[] { OriginalTacticalShieldCodec.CreateAuthoredInitialState(2),
            OriginalTacticalShieldCodec.CreateAuthoredInitialState(0x7F000001) };
        Assert.Equal(2u, Assert.Single(OriginalTacticalShieldCodec.Project([2, 2, 99], available)).UnitId);
        Assert.Empty(OriginalTacticalShieldCodec.Project([], available));
        Assert.Empty(OriginalTacticalShieldCodec.Project([99], available));
    }

    [Fact]
    public void CodecEnforcesNativeSixHundredRecordLimit()
    {
        var record = OriginalTacticalShieldCodec.CreateAuthoredInitialState(1);
        Assert.Equal(24008, OriginalTacticalShieldCodec.Encode(Enumerable.Repeat(record, 600).ToArray()).Length);
        Assert.Throws<ArgumentOutOfRangeException>(() => OriginalTacticalShieldCodec.Encode(
            Enumerable.Repeat(record, 601).ToArray()));
        Assert.Equal("0000000003410000", Convert.ToHexString(OriginalTacticalShieldCodec.Encode([])));
    }

    [Fact]
    public async Task RequestedShipsReceiveShieldTimingRecordsInsteadOfAnEmptyTable()
    {
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        OriginalWarpSessionClockTests.SetField(session, "_createdCharacter",
            new OriginalCreateCharacterCommand(2, 2, 2, 0, 0, "Self", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flagship", 0, []));
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("03400002000000027F000001"), key, 1), TestContext.Current.CancellationToken);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        var frame = decoded.Payload!;
        Assert.Equal((ushort)0x341, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal((ushort)2, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
        Assert.Equal(88, frame.Length); // 6-byte envelope + u16 count + 2 * 40.
        Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(8)));
        Assert.Equal(0x7F000001u, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(48)));
    }

    [Fact]
    public void BootstrapSuppliesTimingForEveryShipItWillImport()
    {
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, new byte[16]);
        var bootstrap = typeof(NaturalAuthoritySession).GetMethod("EncodeTacticalSceneBootstrapFrames",
            BindingFlags.Instance | BindingFlags.NonPublic)!
            .CreateDelegate<Func<IReadOnlyList<OriginalTacticalParticipantSnapshot>?, IReadOnlyList<byte[]>>>(session)(null);
        var frame = Assert.Single(bootstrap, frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x341);
        Assert.Equal((ushort)2, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
    }
}
