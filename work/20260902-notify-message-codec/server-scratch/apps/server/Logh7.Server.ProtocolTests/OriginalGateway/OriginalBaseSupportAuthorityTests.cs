using System.Buffers.Binary;
using System.Text.Json.Nodes;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalBaseSupportAuthorityTests
{
    [Theory]
    [InlineData(0.5f, true)]
    [InlineData(1f, false)]
    [InlineData(2f, false)]
    [InlineData(0.5f, false, true)]
    [InlineData(0.5f, false, false, 0u)]
    [InlineData(0.5f, false, false, 999u)]
    public async Task Repair_ship_requires_its_original_service_distance(float distance, bool accepted, bool busy = false,
        uint actor = 2)
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var repair = catalog.ProjectFleetUnit(2113929483, 101)!.Ship;
        var battles = new OriginalTacticalBattleRegistry();
        var gameClock = new OriginalGameClock(TimeProvider.System);
        var session = OriginalPlayerCombatTests.Session(battles, 2, 2, catalog, gameClock: gameClock);
        var own = OriginalSystemSceneCodec.CreateTacticalBattlefield(2, 2, catalog.Resolve(101)).Records[0];
        OriginalWarpSessionClockTests.SetField(session, "_tacticalUnitShip",
            own with { X = repair.X + distance, Y = repair.Y, Z = repair.Z });
        var key = new byte[16];
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 1), ct);
        using (await battles.LockAsync(101, 100, ct))
            battles.GetEncounter(101, 100).RecordUnitDamage(2, new(25, 10));
        var performer = battles.NpcSnapshot(101, 2113929483);
        Assert.NotNull(performer);
        var observer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, observer.Writer, 999);
        battles.MarkProjectedParticipants(101, 100, observer.Writer, Guid.Empty, [2, performer.Unit.Id]);
        if (busy) battles.RecordExecuting(performer.Unit.Id, performer.ShipGeneration, gameClock.Tick + 1800);
        var request = new byte[22];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0413);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10), actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14), 2113929483);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18), 2);
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 2), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        Assert.Contains(actor != 2 ? "SUPPORT_ACTOR_NOT_CONTROLLED" : accepted ? "tactical-repair-accepted" : busy ? "SUPPORT_VESSEL_EXECUTING" : "SUPPORT_TARGET_OUT_OF_RANGE",
            answer.ResponseMetadata ?? "");
        Assert.Equal(new OriginalTacticalDamageState(accepted ? (ushort)10 : (ushort)25, 10),
            battles.GetEncounter(101, 100).GetUnitDamage(2));
        Assert.Equal(accepted || busy, battles.IsExecuting(performer.Unit.Id, performer.ShipGeneration, gameClock.Tick));
        if (accepted)
        {
            var echo = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0).Payload!;
            Assert.Equal((ushort)0x0413, BinaryPrimitives.ReadUInt16BigEndian(echo.AsSpan(4)));
            Assert.Equal(1800u, BinaryPrimitives.ReadUInt32BigEndian(echo.AsSpan(10)));
            var started = BinaryPrimitives.ReadUInt32BigEndian(echo.AsSpan(6));
            Assert.True(battles.IsExecuting(performer.Unit.Id, performer.ShipGeneration, started + 1799));
            Assert.False(battles.IsExecuting(performer.Unit.Id, performer.ShipGeneration, started + 1800));
            Assert.Equal(request.AsSpan(10).ToArray(), echo.AsSpan(14).ToArray());
            var frames = new List<byte[]>();
            while (observer.Reader.TryRead(out var batch))
                frames.AddRange(batch.Frames.Select(f => f.ToArray()));
            Assert.Contains(frames, frame => frame.SequenceEqual(echo));
            var updated = Assert.Single(frames, frame => frame.Length >= 40 &&
                BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)) == 0x0325);
            Assert.Equal(2u, BinaryPrimitives.ReadUInt32BigEndian(updated.AsSpan(8)));
            Assert.Equal((ushort)10, BinaryPrimitives.ReadUInt16BigEndian(updated.AsSpan(34)));
            Assert.Equal((ushort)10, BinaryPrimitives.ReadUInt16BigEndian(updated.AsSpan(36)));
        }
        else Assert.False(observer.Reader.TryRead(out _));
    }

    [Theory]
    [InlineData(2, 3, "SUPPLY_BASE_NOT_FRIENDLY")]
    [InlineData(3, 2, "SUPPLY_BASE_NOT_FRIENDLY")]
    [InlineData(2, 2, "SUPPLY_BASE_NOT_GRANTED")]
    [InlineData(3, 3, "SUPPLY_BASE_NOT_GRANTED")]
    public async Task Fleet_supply_checks_base_faction_before_emergency_grant(
        byte playerPower, byte basePower, string reason)
    {
        var ct = TestContext.Current.CancellationToken;
        var json = JsonNode.Parse(File.ReadAllText(Path.Combine(
            AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        json["templates"]![0]!["baseInformation"]![0]!["power"] = basePower;
        var session = OriginalPlayerCombatTests.Session(new OriginalTacticalBattleRegistry(),
            2, playerPower, OriginalBattlefieldCatalog.Parse(json.ToJsonString()));
        var key = new byte[16];
        Assert.Equal(NaturalAuthoritySessionStatus.Success, (await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 1), ct)).Status);
        var request = new byte[22];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0414);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10), 2);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14), 1);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18), 2);
        var result = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 2), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
        Assert.Contains(reason, result.ResponseMetadata ?? "");
    }

    [Theory]
    [InlineData(0u,2,"BASE_SUPPORT_ACTOR_NOT_CONTROLLED")]
    [InlineData(999u,2,"BASE_SUPPORT_ACTOR_NOT_CONTROLLED")]
    [InlineData(2u,3,"BASE_SUPPORT_BASE_NOT_FRIENDLY")]
    public async Task Invalid_base_supply_is_refused_before_any_stock_operation(uint actor, byte basePower,string reason)
    {
        var ct = TestContext.Current.CancellationToken;
        var json = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json")))!;
        json["templates"]![0]!["baseInformation"]![0]!["power"] = basePower;
        var session = OriginalPlayerCombatTests.Session(new OriginalTacticalBattleRegistry(),2,2,
            OriginalBattlefieldCatalog.Parse(json.ToJsonString()));
        // This fixture throws on any stock access. The real encrypted dispatcher
        // must reject before attempting the supply store or applying an effect.
        var key = new byte[16];
        Assert.Equal(NaturalAuthoritySessionStatus.Success,(await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),ct)).Status);
        var request = new byte[23];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x041C);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14),1);
        request[18]=1;
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19),2);
        var result = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        Assert.Contains(reason,result.ResponseMetadata ?? "");
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(999u)]
    [InlineData(2u)]
    public async Task Base_repair_binds_actor_to_session_and_preserves_destroyed_hulls(uint actor)
    {
        var ct = TestContext.Current.CancellationToken;
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles,2,2);
        var key = new byte[16];
        Assert.Equal(NaturalAuthoritySessionStatus.Success,(await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,1),ct)).Status);
        using(await battles.LockAsync(101,100,ct))
            battles.GetEncounter(101,100).RecordUnitDamage(2,new(25,10));
        var observer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101,100,observer.Writer,999);
        battles.MarkProjectedParticipants(101,100,observer.Writer,Guid.Empty,[2]);
        var request = new byte[23];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x041B);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14),1);
        request[18]=1;
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19),2);
        var result = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        Assert.Equal(new OriginalTacticalDamageState(actor==2 ? (ushort)10 : (ushort)25,10),
            battles.GetEncounter(101,100).GetUnitDamage(2));
        Assert.Contains(actor==2 ? "tactical-base-repair-accepted" : "BASE_SUPPORT_ACTOR_NOT_CONTROLLED",
            result.ResponseMetadata ?? "");
        if (actor != 2) Assert.False(observer.Reader.TryRead(out _));
        else
        {
            Assert.True(observer.Reader.TryRead(out var batch));
            var frame = batch.Frames.Last().Span;
            Assert.Equal((ushort)0x0325,BinaryPrimitives.ReadUInt16BigEndian(frame[4..]));
            Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(frame[8..]));
            Assert.Equal((ushort)10,BinaryPrimitives.ReadUInt16BigEndian(frame[34..]));
            Assert.Equal((ushort)10,BinaryPrimitives.ReadUInt16BigEndian(frame[36..]));
        }
    }

    [Theory]
    [InlineData(2,3)]
    [InlineData(3,2)]
    [InlineData(2,2)]
    [InlineData(3,3)]
    public async Task Enemy_base_cannot_be_used_for_repairs(byte playerPower, byte basePower)
    {
        var json = JsonNode.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json")))!;
        json["templates"]![0]!["baseInformation"]![0]!["power"] = basePower;
        var catalog = OriginalBattlefieldCatalog.Parse(json.ToJsonString());
        var battles = new OriginalTacticalBattleRegistry();
        var session = OriginalPlayerCombatTests.Session(battles,2,playerPower,catalog);
        var key = new byte[16];
        var entered = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(
            Convert.FromHexString("0F02"),key,1),TestContext.Current.CancellationToken);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,entered.Status);
        if(playerPower != basePower)
            using(await battles.LockAsync(101,100,TestContext.Current.CancellationToken))
                battles.GetEncounter(101,100).RecordUnitDamage(2,new(25,0));
        byte[] request = new byte[23];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x041B);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),2);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14),1);
        request[18]=1;
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19),2);
        var result = await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),
            TestContext.Current.CancellationToken);
        Assert.Equal(new OriginalTacticalDamageState(playerPower != basePower ? (ushort)25 : (ushort)0,0),
            battles.GetEncounter(101,100).GetUnitDamage(2));
        Assert.Contains(playerPower != basePower ? "BASE_SUPPORT_BASE_NOT_FRIENDLY" : "REPAIR_NOTHING_DAMAGED",
            result.ResponseMetadata ?? "");
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status); // visible refusal, no disconnect
    }
}
