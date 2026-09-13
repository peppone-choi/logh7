using System.Buffers.Binary;
using System.Reflection;
using System.Net;
using System.Net.Sockets;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.Hosting;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalSharedBattleTests
{
    private static readonly byte[] Key = new byte[16];
    private readonly BattleClock _clock = new();
    private readonly OriginalGameClock _gameClock;
    public OriginalSharedBattleTests() => _gameClock = new OriginalGameClock(_clock);
    private sealed class BattleClock : TimeProvider
    {
        private long _timestamp;
        public override long TimestampFrequency => 1000;
        public override long GetTimestamp() => _timestamp;
        public void Recharge() => _timestamp += 3000;
    }

    [Fact]
    public async Task SameGridWorldRefreshRequiresPreviouslyImportedActorToEnterAgain()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        var actor = KnownActor(battles);
        await Join(observer);
        await AttackActor(actor, 1);
        Assert.Equal(8, Drain(observer).Count);
        PrepareAlreadyInitializedObserver(observer);
        var refresh = await observer.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), Key, 2), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, refresh.Status);
        _clock.Recharge();
        await AttackActor(actor, 2);
        var frames = Drain(observer);
        Assert.Equal(new ushort[] { 0xB09, 0x323, 0x325, 0x33F, 0x341, 0x33B, 0xB0A, 0x426 },
            frames.Select(frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))));
        Assert.Equal((ushort)50, BinaryPrimitives.ReadUInt16BigEndian(frames[^1].AsSpan(20)));
    }

    [Fact]
    public async Task SameGridWorldRefreshInvalidatesAnOldPendingImportBatch()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        await Join(observer);
        await AttackActor(KnownActor(battles), 1);
        Assert.True(Inbox(observer).Reader.TryRead(out var old));
        PrepareAlreadyInitializedObserver(observer);
        var refresh = await observer.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), Key, 2), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, refresh.Status);
        Assert.Empty(BatchEncoder(observer)(old!));
    }

    [Fact]
    public async Task FirstBootstrapAlsoInvalidatesPreBootstrapObservation()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, Key,
            store: new SceneRosterStore(), battles: battles, catalog: CloseCombatCatalog());
        OriginalWarpSessionClockTests.SetField(observer, "_createdCharacter",
            new OriginalCreateCharacterCommand(2, 2, 2, 0, 0, "Self", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "SelfFlag", 0, []));
        var actor = KnownActor(battles);
        await Join(observer);
        await AttackActor(actor, 1);
        Assert.True(Inbox(observer).Reader.TryRead(out var old));
        var bootstrap = await observer.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), Key, 2), TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, bootstrap.Status);
        Assert.Empty(BatchEncoder(observer)(old!));
        _clock.Recharge();
        await AttackActor(actor, 2);
        var frames = Drain(observer);
        Assert.Equal((ushort)0xB09, BinaryPrimitives.ReadUInt16BigEndian(frames[0].AsSpan(4)));
        Assert.Equal((ushort)50, BinaryPrimitives.ReadUInt16BigEndian(frames[^1].AsSpan(20)));
    }

    [Fact]
    public async Task SceneRefreshWaitsForTheBattleMutationLease()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        await Join(observer);
        PrepareAlreadyInitializedObserver(observer);
        using var lease = await battles.LockAsync(101, 100, TestContext.Current.CancellationToken);
        var refresh = observer.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"), Key, 2), TestContext.Current.CancellationToken);
        Assert.False(refresh.IsCompleted);
        lease.Dispose();
        Assert.Equal(NaturalAuthoritySessionStatus.Success, (await refresh.WaitAsync(
            TimeSpan.FromSeconds(5), TestContext.Current.CancellationToken)).Status);
    }

    private sealed class SceneRosterStore : OriginalWarpSessionClockTests.UnusedStore
    {
        public override Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(Guid accountId,
            CancellationToken cancellationToken) => Task.FromResult<IReadOnlyList<CharacterReadRecord>>([
                new CharacterReadRecord(2, 0, 2, 0, 0, "Self", "Pilot", "SelfFlag", 5,
                    [1, 2, 3, 4, 5, 6, 7, 8], 20, 0, 0)]);
    }

    private NaturalAuthoritySession KnownActor(OriginalTacticalBattleRegistry battles)
    {
        var actor = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_worldCharacterId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_createdCharacter",
            new OriginalCreateCharacterCommand(3, 2, 2, 0, 0, "Other", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "OtherFlag", 0, []));
        return actor;
    }

    private static Task<NaturalAuthoritySessionResult> AttackActor(NaturalAuthoritySession actor, uint sequence) =>
        actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("040500000000000000000000000201000000030100000000"), Key, sequence),
            TestContext.Current.CancellationToken);

    private static void PrepareAlreadyInitializedObserver(NaturalAuthoritySession observer)
    {
        OriginalWarpSessionClockTests.SetField(observer, "_createdCharacter",
            new OriginalCreateCharacterCommand(2, 2, 2, 0, 0, "Self", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "SelfFlag", 0, []));
        // Fixture represents a completed initial bootstrap; the tested request is a refresh.
        var gate = (OriginalTacticalTransitionGate)typeof(NaturalAuthoritySession).GetField(
            "_tacticalTransitionGate", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(observer)!;
        gate.OnWorldInitializeRequest();
    }

    [Fact]
    public async Task UnknownActorEntryIncludesItsShieldTimingBeforeTheImportBoundary()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        var actor = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_worldCharacterId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_createdCharacter",
            new OriginalCreateCharacterCommand(3, 2, 2, 0, 0, "Other", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "OtherFlag", 0, []));
        await Join(observer);
        await actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("040500000000000000000000000201000000030100000000"), Key, 1),
            TestContext.Current.CancellationToken);
        var frames = Drain(observer);
        var shield = Assert.Single(frames, frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x341);
        Assert.Equal((ushort)1, BinaryPrimitives.ReadUInt16BigEndian(shield.AsSpan(6)));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(shield.AsSpan(8)));
        var endIndex = frames.FindIndex(frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0xB0A);
        Assert.True(frames.IndexOf(shield) < endIndex);
    }

    [Fact]
    public async Task OneBattleEventCannotLeaveOnlyAPrefixInTheObserverQueue()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Channel.CreateBounded<OriginalTacticalNotificationBatch>(1);
        var origin = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, observer.Writer);
        using var lease = await battles.LockAsync(101, 100, TestContext.Current.CancellationToken);
        battles.Publish(101, 100, origin.Writer, [new byte[] { 1 }, new byte[] { 2 }]);
        var delivered = new List<byte>();
        while (observer.Reader.TryRead(out var batch))
            foreach (var frame in batch.Frames) delivered.AddRange(frame.ToArray());
        Assert.Equal(new byte[] { 1, 2 }, delivered);
    }

    [Fact]
    public async Task UnknownActorIsImportedBeforeDamageWithoutReimportingViewerOrNpc()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        var actor = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_worldCharacterId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_createdCharacter",
            new OriginalCreateCharacterCommand(3, 2, 2, 0, 0, "Other", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "OtherFlag", 0, []));
        OriginalWarpSessionClockTests.SetField(actor, "_tacticalUnitShip",
            new OriginalTacticalUnitShipRecord(3, 73, 2, 3, -8, 4, 567, 0.75f, 0, 0, 0, 0, 0, 1));
        await Join(observer);
        var request = Convert.FromHexString("040500000000000000000000000201000000030100000000");
        await actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(request, Key, 1),
            TestContext.Current.CancellationToken);
        var frames = Drain(observer);
        Assert.Equal(new ushort[] { 0xB09, 0x323, 0x325, 0x33F, 0x341, 0x33B, 0xB0A, 0x426 },
            frames.Select(frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(frames[1].AsSpan(6)));
        Assert.True(frames[1].AsSpan().IndexOf(System.Text.Encoding.Unicode.GetBytes("OtherFlag")) >= 0);
        Assert.Equal((ushort)1, BinaryPrimitives.ReadUInt16BigEndian(frames[2].AsSpan(6)));
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(frames[2].AsSpan(8)));
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(frames[5].AsSpan(4), out var ships));
        Assert.Equal(3u, Assert.Single(ships.Records).Id);
        Assert.Equal(3u, ships.Records[0].Character);
        Assert.Equal(-8f, ships.Records[0].X);
        Assert.Equal(4f, ships.Records[0].Y);
        Assert.Equal(567f, ships.Records[0].Z);
        Assert.Equal(0.75f, ships.Records[0].Direction);
        Assert.Equal((byte)73, ships.Records[0].Morale);
        Assert.Equal((byte)2, ships.Records[0].Confusion);
        _clock.Recharge();
        await actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(request, Key, 2),
            TestContext.Current.CancellationToken);
        frames = Drain(observer);
        Assert.Equal((ushort)0x426, BinaryPrimitives.ReadUInt16BigEndian(Assert.Single(frames).AsSpan(4)));
    }

    [Fact]
    public async Task DamageFromTwoSessionsAccumulatesInOneBattle()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var first = Session(battles);
        var second = Session(battles);
        Assert.Equal((ushort)25, Damage(await Attack(first, 1)));
        _clock.Recharge();
        Assert.Equal((ushort)50, Damage(await Attack(second, 1)));
    }

    [Fact]
    public async Task JoinedObserverGetsDamageWithoutSubmittingAnotherInput()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles);
        var observer = Session(battles);
        await Join(observer);
        await Attack(actor, 1);
        var notification = Assert.Single(Drain(observer));
        Assert.Equal((ushort)0x0426, BinaryPrimitives.ReadUInt16BigEndian(notification.AsSpan(4)));
        Assert.Equal((ushort)25, BinaryPrimitives.ReadUInt16BigEndian(notification.AsSpan(20)));
        Assert.False(Inbox(actor).Reader.TryRead(out _)); // Actor already gets its normal response batch.
    }

    [Fact]
    public async Task LaterConnectionSeesExistingDamageWithoutRespawningEnemy()
    {
        var battles = new OriginalTacticalBattleRegistry();
        await Attack(Session(battles), 1);
        _clock.Recharge();
        await Attack(Session(battles), 1);
        _clock.Recharge();
        Assert.Equal((ushort)75, Damage(await Attack(Session(battles), 1)));
    }

    [Fact]
    public async Task MovingSubscriptionToAnotherGridStopsOldGridEvents()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        await Join(observer);
        OriginalWarpSessionClockTests.SetField(observer, "_worldGridCellId", 102u);
        await Join(observer, 2);
        await Attack(Session(battles), 1);
        Assert.False(Inbox(observer).Reader.TryRead(out _));
        // A third connection still shares the old grid's accumulated casualty.
        _clock.Recharge();
        Assert.Equal((ushort)50, Damage(await Attack(Session(battles), 1)));
    }

    [Fact]
    public async Task ParallelAttackSessionsDoNotLoseOrDuplicateCumulativeDamage()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var sessions = Enumerable.Range(0, 8).Select(_ => Session(battles)).ToArray();
        var results = await Task.WhenAll(sessions.Select(session =>
            Task.Run(() => Attack(session, 1), TestContext.Current.CancellationToken)));
        var damage = results.Where(r => r.AdditionalResponses is { Count: > 0 })
            .Select(Damage).Order().ToArray();
        Assert.Equal(new ushort[] { 25 }, damage);
        Assert.Equal(7, results.Count(r => r.ResponseMetadata?.StartsWith(
            "command-reject=TACTICAL_WEAPON_RECHARGING", StringComparison.Ordinal) == true));
        Assert.Equal((ushort)25, battles.GetEncounter(101, 100).EnemyDamage.Damaged);
    }

    private NaturalAuthoritySession Session(OriginalTacticalBattleRegistry battles)
        => OriginalWarpSessionClockTests.CreateWorldEnteredSession(_clock, Key, battles: battles,
            catalog: CloseCombatCatalog(), gameClock: _gameClock);

    // These tests exercise observer ordering/identity, not approach movement.
    // Keep their NPC within the real five-unit beam range; no production balance change.
    internal static OriginalBattlefieldCatalog CloseCombatCatalog()
    {
        var doc=System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json")))!;
        doc["templates"]![0]!["enemySpawn"]!["x"]=-7;
        return OriginalBattlefieldCatalog.Parse(doc.ToJsonString());
    }

    [Fact]
    public async Task FinalCasualtyPrecedesSharedCompletionAndLateJoinStaysCompleted()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles);
        var observer = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_createdCharacter",
            new OriginalCreateCharacterCommand(4, 2, 2, 0, 0, "Viewer", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "Flagship", 0, []));
        await Join(observer);
        for (uint i = 1; i <= 4; i++)
        {
            _clock.Recharge();
            await Attack(actor, i);
        }
        var frames = Drain(observer);
        Assert.Equal(new ushort[] { 0x426, 0x426, 0x426, 0x426, 0x317, 0xF1F },
            frames.Select(frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4))).ToArray());
        Assert.Equal((ushort)100, BinaryPrimitives.ReadUInt16BigEndian(frames[3].AsSpan(22)));
        var lateJoin = await Join(Session(battles));
        var decoded = OriginalClientInnerFrameCodec.Decode(lateJoin.ResponsePayload!, Key, 0);
        Assert.Equal("000000000317006500", Convert.ToHexString(decoded.Payload!));
    }

    [Fact]
    public async Task SeparateGridRetainsItsOwnEnemyCasualties()
    {
        var battles = new OriginalTacticalBattleRegistry();
        await Attack(Session(battles), 1);
        var other = Session(battles);
        // Grid102 is the authored quiet return destination; use another combat grid.
        OriginalWarpSessionClockTests.SetField(other, "_worldGridCellId", 103u);
        _clock.Recharge();
        Assert.Equal((ushort)25, Damage(await Attack(other, 1)));
        _clock.Recharge();
        Assert.Equal((ushort)50, Damage(await Attack(Session(battles), 1)));
    }

    [Fact]
    public async Task SlowObserverIsFailedInsteadOfBlockingBattleOrLosingEventsSilently()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Channel.CreateBounded<OriginalTacticalNotificationBatch>(1);
        var origin = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, observer.Writer);
        using var lease = await battles.LockAsync(101, 100, TestContext.Current.CancellationToken);
        battles.Publish(101, 100, origin.Writer, [new byte[] { 1 }, new byte[] { 2 }]);
        battles.Publish(101, 100, origin.Writer, [new byte[] { 3 }]);
        Assert.True(observer.Reader.TryRead(out var first));
        Assert.Equal(new byte[] { 1, 2 }, first!.Frames.SelectMany(frame => frame.ToArray()));
        await Assert.ThrowsAsync<IOException>(() => observer.Reader.WaitToReadAsync(TestContext.Current.CancellationToken).AsTask());
    }

    [Fact]
    public async Task ClosingSessionCompletesItsNotificationChannel()
    {
        var session = Session(new OriginalTacticalBattleRegistry());
        await Join(session);
        var close = typeof(NaturalAuthoritySession).GetMethod("CloseNotifications",
            BindingFlags.Instance | BindingFlags.NonPublic)!.CreateDelegate<Action>(session);
        close();
        Assert.False(await Inbox(session).Reader.WaitToReadAsync(TestContext.Current.CancellationToken));
    }

    [Fact]
    public async Task IdleObserverReceivesRealSessionCipherFramesOverTcp()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles);
        var observerKey = Enumerable.Repeat((byte)0x37, 16).ToArray();
        var observer = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, observerKey, battles: battles);
        await Join(observer, key: observerKey); // Session response consumes outbound sequence1.
        var encode = typeof(NaturalAuthoritySession).GetMethod("EncodeApplicationPush",
            BindingFlags.Instance | BindingFlags.NonPublic)!.CreateDelegate<PushEncoder>(observer);
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var client = new TcpClient();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            await client.ConnectAsync((IPEndPoint)listener.LocalEndpoint, stop.Token);
            using var server = await listener.AcceptTcpClientAsync(stop.Token);
            var pump = OriginalConnectionPump.RunAsync(server.GetStream(), Inbox(observer).Reader,
                (_, _) => throw new InvalidOperationException("Observer submits no input"),
                async (batch, ct) =>
                {
                    foreach (var raw in batch.Frames)
                    {
                        var push = encode(raw.Span);
                        await server.GetStream().WriteAsync(OriginalClientTransportFrameWriter.Encode(
                            push.TransportPrefix, push.OuterControl, push.Payload), ct);
                    }
                }, stop.Token);
            try
            {
                for (uint i = 1; i <= 2; i++)
                {
                    _clock.Recharge();
                    await Attack(actor, i);
                    var prefix = new byte[2];
                    await client.GetStream().ReadExactlyAsync(prefix, stop.Token);
                    var body = new byte[BinaryPrimitives.ReadUInt16BigEndian(prefix)];
                    await client.GetStream().ReadExactlyAsync(body, stop.Token);
                    Assert.Equal(new byte[4], body[..4]);
                    Assert.Equal((ushort)0x30, BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(4)));
                    var decoded = OriginalClientInnerFrameCodec.Decode(body.AsSpan(6), observerKey, i);
                    Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
                    Assert.Equal(i + 1, decoded.Sequence);
                    Assert.Equal((ushort)0x426, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
                    Assert.Equal(i == 1 ? (ushort)25 : (ushort)50,
                        BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload.AsSpan(20)));
                }
                client.Client.Shutdown(SocketShutdown.Send);
                await pump.WaitAsync(stop.Token);
            }
            finally
            {
                await stop.CancelAsync();
                try { await pump; } catch (OperationCanceledException) { }
            }
        }
        finally { listener.Stop(); }
    }

    private delegate NaturalAuthorityPush PushEncoder(ReadOnlySpan<byte> payload);

    [Fact]
    public async Task ActorEntryAndHitStayContiguousOnTcpWhileAnInboundQueryArrives()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var actor = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_worldCharacterId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_createdCharacter",
            new OriginalCreateCharacterCommand(3, 2, 2, 0, 0, "Other", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "OtherFlag", 0, []));
        var observerKey = Enumerable.Repeat((byte)0x37, 16).ToArray();
        var observer = OriginalWarpSessionClockTests.CreateWorldEnteredSession(
            TimeProvider.System, observerKey, battles: battles);
        await Join(observer, key: observerKey);
        var encode = BatchEncoder(observer);
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        using var client = new TcpClient();
        using var stop = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var firstSent = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var resume = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            await client.ConnectAsync((IPEndPoint)listener.LocalEndpoint, stop.Token);
            using var server = await listener.AcceptTcpClientAsync(stop.Token);
            var pump = OriginalConnectionPump.RunAsync(server.GetStream(), Inbox(observer).Reader,
                async (body, ct) =>
                {
                    var reply = await observer.ProcessAsync(BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(4)),
                        body.AsMemory(6), ct);
                    Assert.Equal(NaturalAuthoritySessionStatus.Success, reply.Status);
                    await server.GetStream().WriteAsync(OriginalClientTransportFrameWriter.Encode(
                        reply.ResponseTransportPrefix!, reply.ResponseOuterControl!.Value, reply.ResponsePayload!), ct);
                    return true;
                }, async (batch, ct) =>
                {
                    var pushes = encode(batch);
                    for (var index = 0; index < pushes.Count; index++)
                    {
                        var push = pushes[index];
                        await server.GetStream().WriteAsync(OriginalClientTransportFrameWriter.Encode(
                            push.TransportPrefix, push.OuterControl, push.Payload), ct);
                        if (index == 0)
                        {
                            firstSent.SetResult();
                            await resume.Task.WaitAsync(ct);
                        }
                    }
                }, stop.Token);
            try
            {
                await actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
                    Convert.FromHexString("040500000000000000000000000201000000030100000000"), Key, 1), stop.Token);
                await firstSent.Task.WaitAsync(stop.Token);
                await client.GetStream().WriteAsync(OriginalClientTransportFrameWriter.Encode(new byte[4], 0x30,
                    OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("03160065"), observerKey, 2)), stop.Token);
                resume.SetResult();
                var types = new List<ushort>();
                for (uint sequence = 2; sequence <= 10; sequence++)
                {
                    byte[] prefix = new byte[2];
                    await client.GetStream().ReadExactlyAsync(prefix, stop.Token);
                    var body = new byte[BinaryPrimitives.ReadUInt16BigEndian(prefix)];
                    await client.GetStream().ReadExactlyAsync(body, stop.Token);
                    var decoded = OriginalClientInnerFrameCodec.Decode(body.AsSpan(6), observerKey, sequence - 1);
                    Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
                    Assert.Equal(sequence, decoded.Sequence);
                    types.Add(BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
                }
                Assert.Equal(new ushort[] { 0xB09, 0x323, 0x325, 0x33F, 0x341, 0x33B, 0xB0A, 0x426, 0x317 }, types);
                client.Client.Shutdown(SocketShutdown.Send);
                await pump.WaitAsync(stop.Token);
            }
            finally
            {
                await stop.CancelAsync();
                try { await pump; } catch (OperationCanceledException) { }
            }
        }
        finally { listener.Stop(); }
    }

    [Fact]
    public async Task UnknownActorWithoutIdentityFailsObserverInsteadOfSendingAnUnusableHit()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        var actor = Session(battles);
        OriginalWarpSessionClockTests.SetField(actor, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(actor, "_worldCharacterId", 3u);
        await Join(observer);
        await actor.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("040500000000000000000000000201000000030100000000"), Key, 1),
            TestContext.Current.CancellationToken);
        Assert.Empty(Drain(observer));
        var failure = await Assert.ThrowsAsync<IOException>(() => Inbox(observer).Reader.WaitToReadAsync(
            TestContext.Current.CancellationToken).AsTask().WaitAsync(TimeSpan.FromSeconds(5),TestContext.Current.CancellationToken));
        Assert.Equal("TACTICAL_PARTICIPANT_UNAVAILABLE", failure.Message);
    }

    [Fact]
    public async Task OldQueuedBatchIsDiscardedEvenAfterLeavingAndReturningToTheSameGrid()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var observer = Session(battles);
        var actor = Session(battles);
        await Join(observer);
        await Attack(actor, 1);
        Assert.True(Inbox(observer).Reader.TryRead(out var old));
        OriginalWarpSessionClockTests.SetField(observer, "_worldGridCellId", 102u);
        await Join(observer, 2);
        OriginalWarpSessionClockTests.SetField(observer, "_worldGridCellId", 101u);
        await Join(observer, 3);
        var encode = BatchEncoder(observer);
        Assert.Empty(encode(old!));
        _clock.Recharge();
        await Attack(actor, 2);
        Assert.True(Inbox(observer).Reader.TryRead(out var current));
        var push = Assert.Single(encode(current!));
        var decoded = OriginalClientInnerFrameCodec.Decode(push.Payload, Key, 3);
        Assert.Equal(4u, decoded.Sequence); // Stale batch consumed no sequence.
        Assert.Equal((ushort)50, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(20)));
    }

    [Fact]
    public async Task PublishedBatchOwnsItsBytesAfterThePublisherChangesTheSource()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var queue = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var origin = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, queue.Writer);
        using var lease = await battles.LockAsync(101, 100, TestContext.Current.CancellationToken);
        byte[] source = [11, 12];
        battles.Publish(101, 100, origin.Writer, [source]);
        source[0] = 99;
        Assert.True(queue.Reader.TryRead(out var batch));
        Assert.Equal(new byte[] { 11, 12 }, Assert.Single(batch!.Frames).ToArray());
    }

    private static Func<OriginalTacticalNotificationBatch, IReadOnlyList<NaturalAuthorityPush>> BatchEncoder(
        NaturalAuthoritySession session) => typeof(NaturalAuthoritySession).GetMethod("EncodeNotificationBatch",
            BindingFlags.Instance | BindingFlags.NonPublic)!
            .CreateDelegate<Func<OriginalTacticalNotificationBatch, IReadOnlyList<NaturalAuthorityPush>>>(session);

    [Fact]
    public async Task DifferentOwnedUnitIdsStillAttackTheSameMapNpc()
    {
        var battles = new OriginalTacticalBattleRegistry();
        var first = Session(battles);
        var second = Session(battles);
        OriginalWarpSessionClockTests.SetField(second, "_worldGridUnitId", 3u);
        OriginalWarpSessionClockTests.SetField(second, "_worldCharacterId", 3u);
        OriginalWarpSessionClockTests.SetField(second, "_createdCharacter",
            new OriginalCreateCharacterCommand(3, 2, 2, 0, 0, "Other", "Pilot", 18, 1, 1,
                0, new byte[8], 0, 0, 0, 20, 0, 0, "OtherFlag", 0, []));
        Assert.Equal((ushort)25, Damage(await Attack(first, 1)));
        var request = Convert.FromHexString("040500000000000000000000000201000000020100000000");
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(15), 3);
        var result = await second.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, Key, 1), TestContext.Current.CancellationToken);
        Assert.Equal((ushort)50, Damage(result));
        var notification = Assert.Single(Drain(first), frame => BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x426);
        Assert.Equal(3u, BinaryPrimitives.ReadUInt32BigEndian(notification.AsSpan(10)));
    }

    private static Task<NaturalAuthoritySessionResult> Attack(NaturalAuthoritySession session, uint sequence)
        => session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("040500000000000000000000000201000000020100000000"), Key, sequence),
            TestContext.Current.CancellationToken);

    private static Task<NaturalAuthoritySessionResult> Join(NaturalAuthoritySession session, uint sequence = 1,
        byte[]? key = null)
        => session.ProcessAsync(0x30, OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("03160065"), key ?? Key, sequence), TestContext.Current.CancellationToken);

    private static ushort Damage(NaturalAuthoritySessionResult result)
    {
        var pushes = Assert.IsAssignableFrom<IReadOnlyList<NaturalAuthorityPush>>(result.AdditionalResponses);
        var decoded = OriginalClientInnerFrameCodec.Decode(pushes[0].Payload, Key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.Equal((ushort)0x0426, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
        return BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload.AsSpan(20));
    }

    private static List<byte[]> Drain(NaturalAuthoritySession session)
    {
        var frames = new List<byte[]>();
        while (Inbox(session).Reader.TryRead(out var batch))
            frames.AddRange(batch.Frames.Select(frame => frame.ToArray()));
        return frames;
    }

    private static Channel<OriginalTacticalNotificationBatch> Inbox(NaturalAuthoritySession session)
        => (Channel<OriginalTacticalNotificationBatch>)typeof(NaturalAuthoritySession).GetProperty("PendingNotifications",
            BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(session)!;
}
