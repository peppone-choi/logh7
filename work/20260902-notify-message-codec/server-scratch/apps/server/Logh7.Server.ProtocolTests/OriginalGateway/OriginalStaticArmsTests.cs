using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalStaticArmsTests
{
    [Fact]
    public async Task DefaultSessionNoLongerSendsAnUnusableAllZeroArmsTable()
    {
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key);
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0310"), key, 1), TestContext.Current.CancellationToken);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        for (var row = 0; row < 27; row++)
        {
            var bins = Enumerable.Range(0, 8).Select(bin =>
                BinaryPrimitives.ReadInt16BigEndian(decoded.Payload!.AsSpan(6 + row * 16 + bin * 2))).ToArray();
            Assert.Contains(bins, value => value > 0);
            Assert.All(bins, value => Assert.InRange(value, (short)0, (short)100));
        }
    }

    [Fact]
    public void ApprovedTemporaryRangesKeepGunsCloseAndMissilesLongerThanBeams()
    {
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse([0x03, 0x10], out var frame));
        int LastPositive(int row) => Enumerable.Range(0, 8).Where(bin =>
            BinaryPrimitives.ReadInt16BigEndian(frame.AsSpan(6 + row * 16 + bin * 2)) > 0).DefaultIfEmpty(-1).Max();
        Assert.True(LastPositive(8) >= 0);
        Assert.True(LastPositive(8) < LastPositive(4));
        Assert.True(LastPositive(4) < LastPositive(12));
    }

    // Synthetic bit-pattern fixtures test transport, not recovered combat balance.
    private static short[][] Rows() => Enumerable.Range(0, 27).Select(_ => new short[8]).ToArray();

    [Fact]
    public void SerializesAllTwentySevenRowsWithoutCountIdsOrNativeActiveFlags()
    {
        var rows = Rows();
        rows[0] = [0x0102, -1, short.MinValue, short.MaxValue, 0, 1, 256, -256];
        rows[1][0] = 0x1234;
        rows[26][7] = 0x5678;
        var frame = new OriginalStaticArmsTable(rows).EncodeResponse();

        Assert.Equal(438, frame.Length);
        Assert.Equal("0000000003110102FFFF80007FFF000000010100FF001234",
            Convert.ToHexString(frame.AsSpan(0, 24)));
        Assert.Equal("5678", Convert.ToHexString(frame.AsSpan(436)));
        Assert.All(frame.AsSpan(24, 412).ToArray(), value => Assert.Equal((byte)0, value));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(26)]
    [InlineData(28)]
    public void RejectsRowCountsThatWouldShiftOrUnderrunTheFixedNativeReader(int count) =>
        Assert.Throws<ArgumentException>(() => new OriginalStaticArmsTable(
            Enumerable.Range(0, count).Select(_ => new short[8]).ToArray()));

    [Theory]
    [InlineData(0, 0)]
    [InlineData(1, 7)]
    [InlineData(26, 9)]
    public void RejectsAnyRowWithTheWrongNumberOfDistanceBins(int row, int count)
    {
        var rows = Rows();
        rows[row] = new short[count];
        Assert.Throws<ArgumentException>(() => new OriginalStaticArmsTable(rows));
    }

    [Fact]
    public void RejectsNullTableAndNullRowsWithoutInventingData()
    {
        Assert.Throws<ArgumentNullException>(() => new OriginalStaticArmsTable(null!));
        var rows = Rows();
        rows[17] = null!;
        Assert.Throws<ArgumentException>(() => new OriginalStaticArmsTable(rows));
    }

    [Fact]
    public void LaterCallerMutationsCannotChangeAnotherClientsStaticTable()
    {
        var rows = Rows();
        rows[1][3] = 0x2345;
        var table = new OriginalStaticArmsTable(rows);
        rows[1][3] = 0;
        rows[1] = new short[8];
        var frame = table.EncodeResponse();
        Assert.Equal("2345", Convert.ToHexString(frame.AsSpan(28, 2)));
        frame[28] = 0;
        Assert.Equal("2345", Convert.ToHexString(table.EncodeResponse().AsSpan(28, 2)));
    }

    [Fact]
    public void BootstrapUsesSuppliedArmsInsteadOfSilentlySendingZeros()
    {
        var rows = Rows();
        rows[12][7] = 0x3456;
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("0310"), out var frame, staticArms: new(rows)));
        Assert.Equal((ushort)0x3456, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(212)));
    }

    [Fact]
    public async Task EncryptedSessionQueryTransmitsTheConfiguredTable()
    {
        var rows = Rows();
        rows[26][7] = 0x4567;
        var key = Enumerable.Range(1, 16).Select(i => (byte)i).ToArray();
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, key, staticArms: new(rows));
        var result = await session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0310"), key, 1), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.Equal(438, decoded.Payload!.Length);
        Assert.Equal("0311", Convert.ToHexString(decoded.Payload.AsSpan(4, 2)));
        Assert.Equal("4567", Convert.ToHexString(decoded.Payload.AsSpan(436)));
    }
}
