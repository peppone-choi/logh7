using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using Logh7.Server.Hosting;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalConnectionPumpTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(3)]
    public async Task NotificationArrivesWhileFrameIsIdleOrPartiallyReceived(int prefixBytes)
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            async (body, ct) => { await pair.Server.GetStream().WriteAsync(body, ct); return false; },
            async (bytes, ct) => await pair.Server.GetStream().WriteAsync(bytes, ct), stop.Token);
        byte[] request = [0, 4, 0, 0x30, 0xAB, 0xCD];
        await pair.Client.GetStream().WriteAsync(request.AsMemory(0, prefixBytes), stop.Token);
        await queue.Writer.WriteAsync(new byte[] { 0x71, 0x72 }, stop.Token);
        byte[] push = new byte[2];
        var received = pair.Client.GetStream().ReadExactlyAsync(push, stop.Token).AsTask();
        Assert.Same(received, await Task.WhenAny(received, pump));
        await received;
        Assert.Equal(new byte[] { 0x71, 0x72 }, push);
        await pair.Client.GetStream().WriteAsync(request.AsMemory(prefixBytes), stop.Token);
        byte[] reply = new byte[4];
        await pair.Client.GetStream().ReadExactlyAsync(reply, stop.Token);
        Assert.Equal(new byte[] { 0, 0x30, 0xAB, 0xCD }, reply);
        await pump;
    }

    [Fact]
    public async Task MultipleFramesRemainReadableAfterNotificationSourceCloses()
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        queue.Writer.Complete();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var seen = new List<byte[]>();
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            (body, ct) => { seen.Add(body); return Task.FromResult(seen.Count < 2); },
            (_, _) => throw new InvalidOperationException(), stop.Token);
        await pair.Client.GetStream().WriteAsync(new byte[] { 0, 2, 0, 0x10, 0, 2, 0, 0x20 }, stop.Token);
        await pump.WaitAsync(stop.Token);
        Assert.Equal(2, seen.Count);
        Assert.Equal(new byte[] { 0, 0x10 }, seen[0]);
        Assert.Equal(new byte[] { 0, 0x20 }, seen[1]);
    }

    [Fact]
    public async Task CallbacksAreSerializedWhileFrameCallbackAwaits()
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var order = new List<int>();
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            async (_, ct) => { order.Add(1); entered.SetResult(); await release.Task.WaitAsync(ct); order.Add(2); return true; },
            async (_, ct) => { order.Add(3); await pair.Server.GetStream().WriteAsync(new byte[] { 7 }, ct); }, stop.Token);
        await pair.Client.GetStream().WriteAsync(new byte[] { 0, 2, 0, 0x30 }, stop.Token);
        Assert.Same(entered.Task, await Task.WhenAny(entered.Task, pump));
        await queue.Writer.WriteAsync(new byte[] { 7 }, stop.Token);
        release.SetResult();
        var response = new byte[1];
        await pair.Client.GetStream().ReadExactlyAsync(response, stop.Token);
        Assert.Equal(new[] { 1, 2, 3 }, order);
        pair.Client.Client.Shutdown(SocketShutdown.Send);
        await pump.WaitAsync(stop.Token);
    }

    [Fact]
    public async Task PartialFrameEofIsNotAnIdleWait()
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            (_, _) => throw new InvalidOperationException(), (_, _) => Task.CompletedTask, stop.Token);
        await pair.Client.GetStream().WriteAsync(new byte[] { 0, 4, 0 }, stop.Token);
        pair.Client.Client.Shutdown(SocketShutdown.Send);
        await Assert.ThrowsAsync<EndOfStreamException>(() => pump.WaitAsync(stop.Token));
    }

    [Fact]
    public async Task CancelStopsPendingFrameAndNotificationWaits()
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource();
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            (_, _) => throw new InvalidOperationException(), (_, _) => Task.CompletedTask, stop.Token);
        await pair.Client.GetStream().WriteAsync(new byte[] { 0 }, TestContext.Current.CancellationToken);
        stop.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            pump.WaitAsync(TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(0xF001)]
    public async Task ExistingFrameLengthLimitSurvivesPumpIntegration(ushort length)
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            (_, _) => throw new InvalidOperationException(), (_, _) => Task.CompletedTask, stop.Token);
        await pair.Client.GetStream().WriteAsync(new byte[] { (byte)(length >> 8), (byte)length }, stop.Token);
        var exception = await Assert.ThrowsAsync<OriginalFrameLengthException>(() => pump.WaitAsync(stop.Token));
        Assert.Equal(length, exception.BodyLength);
    }

    [Fact]
    public async Task NotificationFailureRetiresPendingReadAndPropagates()
    {
        await using var pair = await SocketPair.Create();
        var queue = Channel.CreateUnbounded<byte[]>();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var failure = new IOException("Test write failure");
        var pump = OriginalConnectionPump.RunAsync(pair.Server.GetStream(), queue.Reader,
            (_, _) => throw new InvalidOperationException(), (_, _) => throw failure, stop.Token);
        await queue.Writer.WriteAsync(new byte[] { 7 }, stop.Token);
        Assert.Same(failure, await Assert.ThrowsAsync<IOException>(() => pump.WaitAsync(stop.Token)));
    }

    private sealed class SocketPair : IAsyncDisposable
    {
        public TcpClient Client { get; } = new();
        public TcpClient Server { get; private set; } = null!;
        public static async Task<SocketPair> Create()
        {
            var pair = new SocketPair();
            var listener = new TcpListener(IPAddress.Loopback, 0);
            listener.Start();
            try
            {
                await pair.Client.ConnectAsync((IPEndPoint)listener.LocalEndpoint);
                pair.Server = await listener.AcceptTcpClientAsync();
                return pair;
            }
            finally { listener.Stop(); }
        }
        public ValueTask DisposeAsync() { Client.Dispose(); Server.Dispose(); return ValueTask.CompletedTask; }
    }
}
