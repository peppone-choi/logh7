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
    string? ServerNotice = null,
    TimeSpan? BaseTravelDelay = null);

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
    private readonly OriginalBattlefieldCatalog _battlefieldCatalog;
    private readonly MetadataOnlyGatewayReceipt _receipt;
    // All login, lobby and world connections share one epoch.
    private readonly OriginalGameClock _gameClock = new(TimeProvider.System);
    private readonly OriginalTacticalBattleRegistry _battles = new();
    private readonly TcpListener _listener;
    private TcpListener? _sessionListener;
    private readonly CancellationTokenSource _stop = new();
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly List<Task> _connections = [];
    private readonly object _connectionsGate = new();

    private StreamWriter? _writer;
    private readonly List<Task> _acceptTasks = [];
    private Task? _npcTask;
    private Task? _baseTravelTask;
    private readonly OriginalBaseTravelNotifications _baseTravelNotifications = new();
    private int _connectionOrdinal;
    private bool _stopped;

    public NaturalAuthorityServer(
        NaturalAuthorityServerOptions options,
        OriginalLoginAuthority loginAuthority,
        HandoffRegistry handoffs,
        IAccountStore store,
        MetadataOnlyGatewayReceipt receipt,
        OriginalBattlefieldCatalog? battlefieldCatalog = null)
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
        _battlefieldCatalog = battlefieldCatalog ?? OriginalBattlefieldCatalog.LoadConfigured(
            Environment.GetEnvironmentVariable("LOGH7_BATTLEFIELD_CATALOG"));
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
        if (_store is IOriginalFleetUnitStoreProvider provider)
        {
            var grids = _battlefieldCatalog.AuthoredShips().Select(ship => ship.Grid)
                .Concat(await provider.FleetUnits.ReadOccupiedGridsAsync(cancellationToken))
                .Where(grid => grid != 0).Distinct().Order().ToArray();
            foreach (var grid in grids)
            {
                using var lease = await _battles.LockAsync(grid,
                    OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, cancellationToken);
                await OriginalFleetRosterRestorer.RestoreAsync(provider.FleetUnits, _battles,
                    _battlefieldCatalog, grid, OriginalAuthoredPlayableCatalog.TacticalArms,
                    (fleet, unit, character) => string.IsNullOrEmpty(fleet?.Commander) ? null :
                        OriginalWorldEntryCodec.EncodeCharacter(character, unit, 0,
                            OriginalAuthoredNpcProfiles.FleetCommander(fleet, fleet.Commander)),
                    cancellationToken);
            }
        }
        // Persisted UTC deadlines survive the process-local game-clock epoch.
        await ResolveDueBaseTravelAsync(cancellationToken);
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
        _npcTask = RunNpcLoopAsync(_stop.Token);
        _baseTravelTask = RunBaseTravelLoopAsync(_stop.Token);
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
        if (_npcTask is not null)
            await _npcTask.WaitAsync(cancellationToken);
        if (_baseTravelTask is not null)
            await _baseTravelTask.WaitAsync(cancellationToken);
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

    private async Task ResolveDueBaseTravelAsync(CancellationToken cancellationToken)
    {
        if (_store is not IOriginalBaseTravelStore travel) return;
        var now = _gameClock.Now;
        foreach (var pending in await travel.ReadDueOriginalBaseTravelAsync(now, 128, cancellationToken))
        {
            var result = await travel.CompleteOriginalBaseTravelAsync(pending.AccountId,
                pending.RequestFingerprint, now, cancellationToken, _battlefieldCatalog.HasFriendlyPublicPort);
            _baseTravelNotifications.Publish(pending.AccountId,result);
            if (result.Updated)
                await WriteAsync(new
                {
                    timestampUtc = now, eventName = "base-travel-resolved", result.Outcome,
                    unitId = result.Unit?.UnitId, result.AuthorityVersion,
                }, cancellationToken);
        }
    }

    private async Task RunBaseTravelLoopAsync(CancellationToken cancellationToken)
    {
        if (_store is not IOriginalBaseTravelStore) return;
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken))
                await ResolveDueBaseTravelAsync(cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
        catch (Exception exception)
        {
            await WriteAsync(new
            {
                timestampUtc = DateTimeOffset.UtcNow, eventName = "base-travel-fault",
                error = exception.GetType().Name,
            }, CancellationToken.None);
            // Do not silently advertise a functioning world with a dead scheduler.
            _stop.Cancel();
            _listener.Stop();
            _sessionListener?.Stop();
        }
    }

    private async Task RunNpcLoopAsync(CancellationToken cancellationToken)
    {
        // NEW DESIGN: four decisions/second, dated by the shared 24 Hz epoch.
        // No user input, per-connection timer or direct cipher write drives AI.
        using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(250));
        try
        {
            while (await timer.WaitForNextTickAsync(cancellationToken))
                foreach (var decision in await _battles.AdvanceNpcsAsync(_gameClock.Tick, cancellationToken))
                    await WriteAsync(new
                    {
                        timestampUtc = DateTimeOffset.UtcNow, eventName = "npc-ai",
                        design = "authored-temporary-v1", decision
                    }, cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { }
        catch (Exception exception)
        {
            await WriteAsync(new
            {
                timestampUtc = DateTimeOffset.UtcNow, eventName = "npc-ai-fault",
                error = exception.GetType().Name
            }, CancellationToken.None);
            // Do not silently leave a live-looking server without its simulation.
            _stop.Cancel();
            _listener.Stop();
            _sessionListener?.Stop();
        }
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
                _gameClock,
                _options.ServerNotice, battles: _battles, battlefieldCatalog: _battlefieldCatalog,
                baseTravelDelay: _options.BaseTravelDelay);
            await using var stream = client.GetStream();
            try
            {
                await OriginalConnectionPump.RunAsync(stream, session.PendingNotifications.Reader,
                    async (body, cancellationToken) =>
                {
                    var bodyLength = body.Length;
                    var control = BinaryPrimitives.ReadUInt16BigEndian(body);
                    var stateBefore = session.State;
                    var result = await session.ProcessAsync(
                        control,
                        body.AsMemory(sizeof(ushort)),
                        cancellationToken);
                    if (session.BaseTravelNotificationOwner is { } notificationOwner)
                        _baseTravelNotifications.Register(connectionId,notificationOwner,
                            session.PendingNotifications.Writer,client.Close);
                    else
                        _baseTravelNotifications.Remove(connectionId);
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
                        return false;
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
                    return true;
                }, async (notificationBatch, cancellationToken) =>
                {
                    // Cipher sequence allocation and all socket writes remain
                    // serialized with ProcessAsync and its response batches.
                    var pushes = notificationBatch.BaseTravel is { } travel
                        ? await session.ProjectCompletedBaseTravelAsync(travel.Owner,travel.Completion,cancellationToken)
                        : session.EncodeNotificationBatch(notificationBatch);
                    foreach (var push in pushes)
                    {
                        var frame = OriginalClientTransportFrameWriter.Encode(
                            push.TransportPrefix, push.OuterControl, push.Payload);
                        await stream.WriteAsync(frame, cancellationToken);
                    }
                    await stream.FlushAsync(cancellationToken);
                    await WriteAsync(new
                    {
                        timestampUtc = DateTimeOffset.UtcNow,
                        eventName = pushes.Count == 0 ? "authority-notification-stale" : "authority-notification-sent",
                        connectionId,
                        frameCount = pushes.Count
                    }, cancellationToken);
                }, cancellationToken);
            }
            catch (OriginalFrameLengthException exception)
            {
                await WriteAsync(new
                {
                    timestampUtc = DateTimeOffset.UtcNow,
                    eventName = "frame-rejected",
                    connectionId,
                    bodyLength = exception.BodyLength,
                    errorCode = "original.transport.body-length"
                }, cancellationToken);
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
            catch (IOException exception)
            {
                await WriteAsync(new
                {
                    timestampUtc = DateTimeOffset.UtcNow,
                    eventName = "connection-io-failed",
                    connectionId,
                    errorCode = exception.Message
                }, CancellationToken.None);
            }
            finally
            {
                _baseTravelNotifications.Remove(connectionId);
                session.CloseNotifications();
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
