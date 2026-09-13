using System.Threading.Channels;
using System.Buffers.Binary;
using Logh7.Server.Compatibility;

namespace Logh7.Server.Hosting;

public static class OriginalConnectionPump
{
    // One actor owns callbacks and therefore cipher sequence allocation/writes.
    // A notification never cancels/restarts a partially consumed network frame.
    public static async Task RunAsync<TNotification>(Stream stream, ChannelReader<TNotification> notifications,
        Func<byte[], CancellationToken, Task<bool>> processFrame,
        Func<TNotification, CancellationToken, Task> sendNotification, CancellationToken cancellationToken)
    {
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        var token = lifetime.Token;
        Task<byte[]?> frame = ReadFrameAsync(stream, token);
        Task<bool>? ready = notifications.WaitToReadAsync(token).AsTask();
        var preferNotification = false;
        try
        {
            while (true)
            {
                token.ThrowIfCancellationRequested();
                if (ready is null) await frame;
                else await Task.WhenAny(frame, ready);
                if (ready is not null && ready.IsCompleted && (!frame.IsCompleted || preferNotification))
                {
                    if (!await ready) ready = null;
                    else
                    {
                        if (notifications.TryRead(out var notification))
                            await sendNotification(notification, token);
                        ready = notifications.WaitToReadAsync(token).AsTask();
                    }
                    preferNotification = false;
                }
                else
                {
                    var body = await frame;
                    if (body is null || !await processFrame(body, token)) return;
                    frame = ReadFrameAsync(stream, token);
                    preferNotification = true;
                }
            }
        }
        finally
        {
            // Also retire waits on EOF, rejection and callback failure, not just
            // server shutdown. Do not leave a detached read using this stream.
            await lifetime.CancelAsync();
            try { await frame; } catch { /* Already observed, or cancelled on exit. */ }
            if (ready is not null)
                try { await ready; } catch { /* Already observed, or cancelled on exit. */ }
        }
    }

    private static async Task<byte[]?> ReadFrameAsync(Stream stream, CancellationToken cancellationToken)
    {
        var prefix = new byte[2];
        var read = await stream.ReadAsync(prefix, cancellationToken);
        if (read == 0) return null;
        if (read < 2) await stream.ReadExactlyAsync(prefix.AsMemory(read), cancellationToken);
        var length = BinaryPrimitives.ReadUInt16BigEndian(prefix);
        if (length < 2 || length > OriginalClientTransportFrameParser.ConfirmedStaticMaximumBodyLength)
            throw new OriginalFrameLengthException(length);
        var body = new byte[length];
        await stream.ReadExactlyAsync(body, cancellationToken);
        return body;
    }
}

public sealed class OriginalFrameLengthException(ushort bodyLength) : IOException("original.transport.body-length")
{
    public ushort BodyLength { get; } = bodyLength;
}
