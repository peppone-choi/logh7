using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalGameClockTests
{
    [Fact]
    public void Response_time_encodes_the_supplied_unsigned_tick_in_network_order()
    {
        // Input_ResponseTime 004AA250 reads one u32; 004C5A30 anchors to it.
        Assert.Equal("00000000030101020304", Convert.ToHexString(
            OriginalWorldBootstrapCodec.EncodeResponseTime(0x01020304)));
    }

    [Theory]
    [InlineData(0, 0)]
    [InlineData(41, 0)]
    [InlineData(42, 1)]
    [InlineData(125, 3)]
    [InlineData(1000, 24)]
    [InlineData(12345, 296)]
    public void Elapsed_monotonic_time_uses_the_original_24_hz_rate(long milliseconds, uint expected)
    {
        var time = new ClockTestTimeProvider();
        var clock = new OriginalGameClock(time);
        time.AdvanceMilliseconds(milliseconds);

        Assert.Equal(expected, clock.Tick);
    }

    [Fact]
    public void Repeated_responses_do_not_reset_the_shared_clock_epoch()
    {
        var time = new ClockTestTimeProvider();
        var clock = new OriginalGameClock(time);
        time.AdvanceMilliseconds(1000);
        var firstConnectionResponse = OriginalWorldBootstrapCodec.EncodeResponseTime(clock.Tick);
        time.AdvanceMilliseconds(1000);
        var nextConnectionResponse = OriginalWorldBootstrapCodec.EncodeResponseTime(clock.Tick);

        Assert.Equal("00000000030100000018", Convert.ToHexString(firstConnectionResponse));
        Assert.Equal("00000000030100000030", Convert.ToHexString(nextConnectionResponse));
    }

    [Fact]
    public void Wall_clock_adjustments_do_not_change_simulation_time()
    {
        var time = new ClockTestTimeProvider();
        var clock = new OriginalGameClock(time);
        time.AdvanceMilliseconds(1000);
        time.UtcNow = time.UtcNow.AddDays(-2);
        Assert.Equal(24u, clock.Tick);
        time.UtcNow = time.UtcNow.AddDays(4);
        time.AdvanceMilliseconds(1000);
        Assert.Equal(48u, clock.Tick);
    }

    [Fact]
    public void Timeless_bootstrap_cannot_answer_request_time()
    {
        // A static response resets the original 24 Hz clock on every sync.
        Assert.False(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("0300"), out var response));
        Assert.Empty(response);
    }

    private sealed class ClockTestTimeProvider : TimeProvider
    {
        private long _timestamp = 9_000_000_000;
        public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UnixEpoch;
        public override long TimestampFrequency => 1_000_000;
        public override long GetTimestamp() => _timestamp;
        public override DateTimeOffset GetUtcNow() => UtcNow;
        public void AdvanceMilliseconds(long milliseconds) => _timestamp += milliseconds * 1000;
    }
}
