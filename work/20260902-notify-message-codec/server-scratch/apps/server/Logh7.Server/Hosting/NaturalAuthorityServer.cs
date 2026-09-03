using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;

namespace Logh7.Server.Hosting;

public sealed record NaturalAuthorityServerOptions(
    IPAddress BindAddress,
    int Port,
    IPAddress AdvertiseAddress,
    ushort AdvertisePort,
    string ReceiptPath,
    byte[]? ServerOutboundKey = null,
    IPAddress? SessionBindAddress = null,
    IPAddress? SessionAdvertiseAddress = null,
    string? ServerNotice = null);

public sealed class NaturalAuthorityServer : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly NaturalAuthorityServerOptions _options;
    private readonly OriginalLoginAuthority _loginAuthority;
    private readonly HandoffRegistry _handoffs;
    private readonly IAccountStore _store;
    private readonly MetadataOnlyGatewayReceipt _receipt;
    private readonly TcpListener _listener;
    private TcpListener? _sessionListener;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly List<Task> _connections = [];
    private readonly object _connectionsGate = new();

    private StreamWriter? _writer;
    private readonly List<Task> _acceptTasks = [];
    private int _connectionOrdinal;
    private bool _stopped;

    public NaturalAuthorityServer(
        NaturalAuthorityServerOptions options,
        OriginalLoginAuthority loginAuthority,
        HandoffRegistry handoffs,
        IAccountStore store,
        MetadataOnlyGatewayReceipt receipt)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentOutOfRangeException.ThrowIfNegative(options.Port);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(options.Port, ushort.MaxValue);
        ArgumentException.ThrowIfNullOrWhiteSpace(options.ReceiptPath);
        if (options.ServerOutboundKey is { Length: not OriginalClientCipherHandshake.SessionKeyLength })
        {
            throw new ArgumentException("ORIGINAL_SESSION_KEY_LENGTH", nameof(options));
        }

        _options = options with { ServerOutboundKey = options.ServerOutboundKey?.ToArray() };
        _loginAuthority = loginAuthority ?? throw new ArgumentNullException(nameof(loginAuthority));
        _handoffs = handoffs ?? throw new ArgumentNullException(nameof(handoffs));
        _store = store ?? throw new ArgumentNullException(nameof(store));
        _receipt = receipt ?? throw new ArgumentNullException(nameof(receipt));
        _listener = new TcpListener(options.BindAddress, options.Port);
    }

    public async Task<IPEndPoint> StartAsync(CancellationToken cancellationToken)
    {
        if (_acceptTasks.Count != 0)
        {
            throw new InvalidOperationException("NATURAL_AUTHORITY_SERVER_ALREADY_STARTED");
        }

        var receiptPath = Path.GetFullPath(_options.ReceiptPath);
        Directory.CreateDirectory(Path.GetDirectoryName(receiptPath)!);
        _writer = new StreamWriter(
            new FileStream(receiptPath, FileMode.CreateNew, FileAccess.Write, FileShare.Read),
            new UTF8Encoding(false))
        {
            AutoFlush = true
        };
        _listener.Start();
        var endpoint = (IPEndPoint)_listener.LocalEndpoint;
        IPEndPoint? sessionEndpoint = null;
        if (_options.SessionBindAddress is not null &&
            !_options.SessionBindAddress.Equals(endpoint.Address))
        {
            _sessionListener = new TcpListener(_options.SessionBindAddress, endpoint.Port);
            _sessionListener.Start();
            sessionEndpoint = (IPEndPoint)_sessionListener.LocalEndpoint;
        }
        await WriteAsync(new
        {
            timestampUtc = DateTimeOffset.UtcNow,
            eventName = "listener-ready",
            bindAddress = endpoint.Address.ToString(),
            port = endpoint.Port,
            advertiseAddress = _options.AdvertiseAddress.ToString(),
            advertisePort = _options.AdvertisePort,
            sessionBindAddress = sessionEndpoint?.Address.ToString(),
            sessionAdvertiseAddress = _options.SessionAdvertiseAddress?.ToString()
        }, cancellationToken);
        _acceptTasks.Add(AcceptLoopAsync(_listener, _stop.Token));
        if (_sessionListener is not null)
        {
            _acceptTasks.Add(AcceptLoopAsync(_sessionListener, _stop.Token));
        }
        return endpoint;
    }

    public async Task StopAsync(CancellationToken cancellationToken)
    {
        if (_stopped)
        {
            return;
        }

        _stopped = true;
        _stop.Cancel();
        _listener.Stop();
        _sessionListener?.Stop();
        if (_acceptTasks.Count != 0)
        {
            await Task.WhenAll(_acceptTasks).WaitAsync(cancellationToken);
        }

        Task[] connections;
        lock (_connectionsGate)
        {
            connections = [.. _connections];
        }

        await Task.WhenAll(connections).WaitAsync(cancellationToken);
        if (_writer is not null)
        {
            await WriteAsync(new
            {
                timestampUtc = DateTimeOffset.UtcNow,
                eventName = "listener-stopped"
            }, cancellationToken);
            await _writer.DisposeAsync();
            _writer = null;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync(CancellationToken.None);
        _stop.Dispose();
        _writeLock.Dispose();
    }

    private async Task AcceptLoopAsync(TcpListener listener, CancellationToken cancellationToken)
    {
        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                var client = await listener.AcceptTcpClientAsync(cancellationToken);
                var id = Interlocked.Increment(ref _connectionOrdinal);
                var task = HandleConnectionAsync(client, id, cancellationToken);
                lock (_connectionsGate)
                {
                    _connections.Add(task);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (SocketException) when (cancellationToken.IsCancellationRequested)
        {
        }
    }

    private async Task HandleConnectionAsync(
        TcpClient client,
        int connectionId,
        CancellationToken cancellationToken)
    {
        using (client)
        {
            await WriteAsync(new
            {
                timestampUtc = DateTimeOffset.UtcNow,
                eventName = "connection-accepted",
                connectionId
            }, cancellationToken);
            var key = _options.ServerOutboundKey?.ToArray() ??
                      RandomNumberGenerator.GetBytes(OriginalClientCipherHandshake.SessionKeyLength);
            var session = new NaturalAuthoritySession(
                key,
                ToOriginalIpv4Field(_options.AdvertiseAddress),
                ToOriginalIpv4Field(
                    _options.SessionAdvertiseAddress ?? _options.AdvertiseAddress),
                _options.AdvertisePort,
                _loginAuthority,
                _handoffs,
                _store,
                _receipt,
                _options.ServerNotice);
            await using var stream = client.GetStream();
            try
            {
                while (!cancellationToken.IsCancellationRequested)
                {
                    var prefix = new byte[sizeof(ushort)];
                    if (!await ReadPrefixOrEofAsync(stream, prefix, cancellationToken))
                    {
                        break;
                    }

                    var bodyLength = BinaryPrimitives.ReadUInt16BigEndian(prefix);
                    if (bodyLength < sizeof(ushort) ||
                        bodyLength > OriginalClientTransportFrameParser.ConfirmedStaticMaximumBodyLength)
                    {
                        await WriteAsync(new
                        {
                            timestampUtc = DateTimeOffset.UtcNow,
                            eventName = "frame-rejected",
                            connectionId,
                            bodyLength,
                            errorCode = "original.transport.body-length"
                        }, cancellationToken);
                        break;
                    }

                    var body = new byte[bodyLength];
                    await stream.ReadExactlyAsync(body, cancellationToken);
                    var control = BinaryPrimitives.ReadUInt16BigEndian(body);
                    var stateBefore = session.State;
                    var result = await session.ProcessAsync(
                        control,
                        body.AsMemory(sizeof(ushort)),
                        cancellationToken);
                    await WriteAsync(new
                    {
                        timestampUtc = DateTimeOffset.UtcNow,
                        eventName = "frame-processed",
                        connectionId,
                        outerControl = control,
                        payloadLength = bodyLength - sizeof(ushort),
                        stateBefore = stateBefore.ToString(),
                        stateAfter = session.State.ToString(),
                        status = result.Status.ToString(),
                        result.ErrorCode,
                        result.ObservedApplicationType,
                        result.OriginalLoginInputShape,
                        result.RejectedApplicationPayloadHex,
                        result.ResponseMetadata,
                        // DIAGNOSTIC (condition 11 probes): raw application request bytes, ONLY for frames
                        // received in the world state (SessionServerReady). Every credential-bearing frame
                        // (0x7000 login, 0x2000 lobby login, 0x0200 session login) arrives in an earlier
                        // state, so no secret can reach the receipt through this field.
                        requestPayloadHex = stateBefore == NaturalAuthoritySessionState.SessionServerReady
                            ? Convert.ToHexString(body.AsSpan(sizeof(ushort), bodyLength - sizeof(ushort)))
                            : null,
                        result.ResponseOuterControl,
                        responsePayloadLength = result.ResponsePayload?.Length,
                        responsesBeforePrimaryPayloadLengths = result.ResponsesBeforePrimary?.Select(
                            response => response.Payload.Length).ToArray(),
                        additionalResponsePayloadLengths = result.AdditionalResponses?.Select(
                            response => response.Payload.Length).ToArray()
                    }, cancellationToken);
                    if (result.Status != NaturalAuthoritySessionStatus.Success)
                    {
                        break;
                    }

                    var responseFrames = new List<byte[]>();
                    if (result.ResponsesBeforePrimary is not null)
                    {
                        foreach (var preceding in result.ResponsesBeforePrimary)
                        {
                            responseFrames.Add(OriginalClientTransportFrameWriter.Encode(
                                preceding.TransportPrefix,
                                preceding.OuterControl,
                                preceding.Payload));
                        }
                    }

                    if (result.ResponseOuterControl is ushort responseControl)
                    {
                        responseFrames.Add(OriginalClientTransportFrameWriter.Encode(
                            result.ResponseTransportPrefix ?? [],
                            responseControl,
                            result.ResponsePayload!));
                    }

                    if (responseFrames.Count > 0)
                    {
                        var responseBatch = OriginalClientTransportFrameWriter.EncodeBatch(
                            responseFrames.ToArray());
                        await stream.WriteAsync(responseBatch, cancellationToken);
                        await stream.FlushAsync(cancellationToken);
                    }


                    if (result.AdditionalResponses is not null)
                    {
                        foreach (var additional in result.AdditionalResponses)
                        {
                            var response = OriginalClientTransportFrameWriter.Encode(
                                additional.TransportPrefix,
                                additional.OuterControl,
                                additional.Payload);
                            await stream.WriteAsync(response, cancellationToken);
                            await stream.FlushAsync(cancellationToken);
                        }
                    }
                }
            }
            catch (EndOfStreamException)
            {
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }
            catch (IOException) when (cancellationToken.IsCancellationRequested)
            {
            }
            finally
            {
                CryptographicOperations.ZeroMemory(key);
                await WriteAsync(new
                {
                    timestampUtc = DateTimeOffset.UtcNow,
                    eventName = "connection-closed",
                    connectionId,
                    finalState = session.State.ToString()
                }, CancellationToken.None);
            }
        }
    }

    private async Task WriteAsync(object value, CancellationToken cancellationToken)
    {
        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            await _writer!.WriteLineAsync(
                JsonSerializer.Serialize(value, JsonOptions).AsMemory(),
                cancellationToken);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private static async Task<bool> ReadPrefixOrEofAsync(
        Stream stream,
        Memory<byte> prefix,
        CancellationToken cancellationToken)
    {
        var read = await stream.ReadAsync(prefix, cancellationToken);
        if (read == 0)
        {
            return false;
        }

        if (read < prefix.Length)
        {
            await stream.ReadExactlyAsync(prefix[read..], cancellationToken);
        }

        return true;
    }

    private static uint ToOriginalIpv4Field(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        if (bytes.Length != sizeof(uint))
        {
            throw new ArgumentException("ORIGINAL_IPV4_REQUIRED", nameof(address));
        }

        return BinaryPrimitives.ReadUInt32LittleEndian(bytes);
    }
}
