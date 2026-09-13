using System.Globalization;
using System.Buffers.Binary;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 補給 (0x0414). constmsg group 0 row 26 is 「補給艦による補給を行う 注）旗艦の左
/// 実行待機時間48G秒 実行所要時間1800G秒」. The refill and its history row commit
/// together, and no point cost is charged: the manual's 160 belongs to 完全修復.
/// </summary>
public sealed class OriginalTacticalSupplyPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    private const uint SupplyVessel = 2113929483u;

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(2113929473u, false, false, null, 0.5f, false)]
    [InlineData(2113929475u, false, false, "SUPPORT_TARGET_NOT_FRIENDLY", 0.5f, false)]
    [InlineData(2113929473u, true, false, "SUPPLY_FLEET_UNAVAILABLE", 0.5f, false)]
    [InlineData(2113929473u, false, true, "TACTICAL_ACTOR_DESTROYED", 0.5f, false)]
    [InlineData(2113929473u, false, false, "SUPPORT_TARGET_OUT_OF_RANGE", 1f, false)]
    [InlineData(2113929473u, false, false, "SUPPORT_TARGET_OUT_OF_RANGE", 2f, false)]
    [InlineData(2113929473u, false, false, "SUPPORT_TARGET_OUT_OF_RANGE", 1.1f, true)]
    [InlineData(2113929473u, false, false, "SUPPORT_ACTOR_NOT_CONTROLLED", 0.5f, false, 0u)]
    [InlineData(2113929473u, false, false, "SUPPORT_ACTOR_NOT_CONTROLLED", 0.5f, false, 999u)]
    public async Task Fleet_supply_wire_request_updates_only_eligible_allied_ordinary_units(
        uint targetId, bool stale, bool destroyed, string? refusal, float distance, bool vertical, uint? actor = null)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var store = new PostgresAccountStore(data);
        var fleets = new PostgresFleetUnitStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var target = catalog.ProjectFleetUnit(targetId, 101)!;
        var service = catalog.ProjectFleetUnit(2113929484, 101)!.Ship;
        await fleets.EnsureCreatedAsync(new(targetId, target.Unit.Outfit, target.Unit.Kind,
            target.Power, target.Camp, 101, catalog.ShipComplement(targetId, 300),
            destroyed ? (ushort)300 : (ushort)25, destroyed ? (ushort)300 : (ushort)10,
            service.X + (vertical ? 0 : distance), service.Y,
            service.Z + (vertical ? distance : 0), target.Ship.Direction,
            target.Unit.Cruising, Supplies: 23), ct);
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,
            key, catalog: catalog, store: store, battles: battles);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);
        if (stale)
        {
            await using var concurrent = data.CreateCommand(
                "UPDATE original_fleet_unit SET revision=revision+1 WHERE unit_id=$1");
            concurrent.Parameters.AddWithValue((long)targetId);
            await concurrent.ExecuteNonQueryAsync(ct);
        }
        var before = (await fleets.ReadGridAsync(101, ct)).Single(row => row.UnitId == targetId);
        var observer = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var subscription = battles.Subscribe(101, 100, observer.Writer, 999,
            primaryNpcKnown: false);
        var request = new byte[22];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x0414);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10), actor ?? unitId);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14), 2113929484);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18), targetId);
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        if (refusal is not null)
        {
            Assert.Contains(refusal, answer.ResponseMetadata);
            Assert.Equal(before, (await fleets.ReadGridAsync(101, ct)).Single(row => row.UnitId == targetId));
            Assert.Equal(23u, battles.NpcSnapshot(101, targetId)!.Unit.Supplies);
            Assert.False(observer.Reader.TryRead(out _));
            return;
        }
        Assert.Contains("tactical-fleet-supply-accepted", answer.ResponseMetadata);
        Assert.Equal(before with { Supplies = 100, Revision = before.Revision + 1 },
            (await fleets.ReadGridAsync(101, ct)).Single(row => row.UnitId == targetId));
        Assert.Equal(100u, battles.NpcSnapshot(101, targetId)!.Unit.Supplies);
        Assert.True(observer.Reader.TryRead(out var notification));
        var serviceStart = notification.Frames.Last().Span;
        Assert.Equal((ushort)0x0414, BinaryPrimitives.ReadUInt16BigEndian(serviceStart[4..]));
        Assert.Equal(1800u, BinaryPrimitives.ReadUInt32BigEndian(serviceStart[10..]));
        Assert.Equal(targetId, BinaryPrimitives.ReadUInt32BigEndian(serviceStart[22..]));
        Assert.True(observer.Reader.TryRead(out notification));
        var observerUpdate = notification.Frames.Last().Span;
        Assert.Equal((ushort)0x0325, BinaryPrimitives.ReadUInt16BigEndian(observerUpdate[4..]));
        Assert.Equal(targetId, BinaryPrimitives.ReadUInt32BigEndian(observerUpdate[8..]));
        Assert.Equal(100u, BinaryPrimitives.ReadUInt32BigEndian(observerUpdate[38..]));
        Assert.Equal((ushort)25, BinaryPrimitives.ReadUInt16BigEndian(observerUpdate[34..]));
        Assert.Equal((ushort)10, BinaryPrimitives.ReadUInt16BigEndian(observerUpdate[36..]));
        // A new session and registry restore the durable supplied state rather
        // than the catalog's spawn value or the old process's snapshot.
        var restartedBattles = new OriginalTacticalBattleRegistry();
        var reconnect = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,
            key, catalog: catalog, store: new PostgresAccountStore(data), battles: restartedBattles);
        OriginalWarpSessionClockTests.SetField(reconnect, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(reconnect, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(reconnect, "_worldGridUnitId", unitId);
        await reconnect.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await reconnect.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);
        Assert.Equal(100u, restartedBattles.NpcSnapshot(101, targetId)!.Unit.Supplies);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false, false, true)]
    [InlineData(true, false, false)]
    [InlineData(false, true, false)]
    public async Task Ordinary_supply_preserves_other_state_and_refuses_stale_or_destroyed_units(
        bool stale, bool destroyed, bool accepted)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var fleetStore = new PostgresFleetUnitStore(data);
        var initial = new OriginalFleetUnitRecord(2113929217, 2113929312, 56, 2, 0, 101,
            300, destroyed ? (ushort)300 : (ushort)25, destroyed ? (ushort)300 : (ushort)10,
            1.5f, 2.5f, 0, 0.25f, 9, Supplies: 23);
        await fleetStore.EnsureCreatedAsync(initial, ct);
        var before = Assert.Single(await fleetStore.ReadGridAsync(101, ct));
        var supplied = await fleetStore.SupplyOrdinaryUnitAsync(
            stale ? before with { Revision = before.Revision + 1 } : before, ct);
        Assert.Equal(accepted, supplied);
        var after = Assert.Single(await fleetStore.ReadGridAsync(101, ct));
        Assert.Equal(accepted ? before with { Supplies = 100, Revision = before.Revision + 1 } : before, after);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Base_supply_wire_request_refills_persisted_unit_and_returns_command_echo()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET supplies=23,current_cell_id=102"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var supplyBattles = new OriginalTacticalBattleRegistry();
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: supplyBattles);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);
        var supplyObserver = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var supplySubscription = supplyBattles.Subscribe(102, 100, supplyObserver.Writer, 999);
        supplyBattles.MarkProjectedParticipants(102, 100, supplyObserver.Writer, Guid.Empty, [unitId]);
        var request = new byte[23];
        BinaryPrimitives.WriteUInt16BigEndian(request, 0x041C);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10), unitId);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14), 2);
        request[18] = 1;
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19), unitId);
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        Assert.Contains("tactical-supply-accepted", answer.ResponseMetadata);
        var decoded = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
        Assert.Equal((ushort)0x041C, BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4)));
        Assert.NotNull(answer.AdditionalResponses);
        Assert.NotEmpty(answer.AdditionalResponses);
        var update = OriginalClientInnerFrameCodec.Decode(
            Assert.Single(answer.AdditionalResponses).Payload, key, 0);
        Assert.Equal(OriginalClientInnerFrameStatus.Success, update.Status);
        var unitFrame = update.Payload!;
        Assert.Equal((ushort)0x0325, BinaryPrimitives.ReadUInt16BigEndian(unitFrame.AsSpan(4)));
        Assert.Equal((ushort)1, BinaryPrimitives.ReadUInt16BigEndian(unitFrame.AsSpan(6)));
        Assert.Equal(unitId, BinaryPrimitives.ReadUInt32BigEndian(unitFrame.AsSpan(8)));
        // Four-byte message prefix, type/count, then supplies at record offset30.
        Assert.Equal(100u, BinaryPrimitives.ReadUInt32BigEndian(unitFrame.AsSpan(38)));
        Assert.True(supplyObserver.Reader.TryRead(out var supplyNotification));
        Assert.Equal(unitFrame, supplyNotification.Frames.Last().ToArray());
        Assert.Equal(100u, (await store.FindOriginalGridUnitAsync(owner, character, unitId, ct))!.Supplies);
        Assert.Equal(1, await CountAsync(data, "SELECT count(*) FROM original_tactical_supply_command", ct));
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData((ushort)0)]
    [InlineData((ushort)100)]
    public async Task A_supply_refills_the_unit_once_and_charges_nothing(ushort laterDestroyed)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var spend = data.CreateCommand("UPDATE original_grid_unit SET supplies=0"))
            await spend.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal(0u, before.Supplies);

        var supplied = await store.SupplyOwnOriginalUnitAsync(owner, Write(1, before, unitId), ct);

        Assert.Equal(OriginalTacticalSupplyStatus.Supplied, supplied.Status);
        Assert.Equal(100u, supplied.Unit!.Supplies);
        Assert.Equal(supplied.Unit, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        // No point charge: the original states no price for this command.
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type = 'OriginalTacticalUnitSupplied'", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM original_tactical_supply_command", ct));

        // The same request twice is one refill: the replay answers with the
        // current row rather than repeating the write.
        // Model later gameplay consuming supplies. A historical result of100
        // must not roll that newer state back when the request is retransmitted.
        await using (var consume = data.CreateCommand(
            "UPDATE original_grid_unit SET supplies=23,damaged=$3,destroyed=$3 WHERE account_id=$1 AND unit_id=$2"))
        {
            consume.Parameters.AddWithValue(owner);
            consume.Parameters.AddWithValue((long)unitId);
            consume.Parameters.AddWithValue((int)laterDestroyed);
            await consume.ExecuteNonQueryAsync(ct);
        }
        var replayed = await store.SupplyOwnOriginalUnitAsync(owner, Write(1, before, unitId), ct);
        Assert.Equal(OriginalTacticalSupplyStatus.Replayed, replayed.Status);
        Assert.Equal(23u, replayed.Unit!.Supplies);
        Assert.Equal(laterDestroyed, replayed.Unit.Destroyed);
        Assert.Equal(laterDestroyed, replayed.Unit.Damaged);
        Assert.Equal(replayed.Unit, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal(23u, (await store.FindOriginalGridUnitAsync(owner, character, unitId, ct))!.Supplies);
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type = 'OriginalTacticalUnitSupplied'", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM original_tactical_supply_command", ct));
    }

    /// <summary>A full unit needs nothing, and a refused supply leaves no trace.</summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_unit_that_is_already_full_is_refused_and_nothing_is_written()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal(100u, before.Supplies);

        var refused = await store.SupplyOwnOriginalUnitAsync(owner, Write(2, before, unitId), ct);

        Assert.Equal(OriginalTacticalSupplyStatus.Rejected, refused.Status);
        Assert.Equal("TACTICAL_SUPPLY_ALREADY_FULL", refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal(0, await CountAsync(data,
            "SELECT count(*) FROM original_tactical_supply_command", ct));
    }

    /// <summary>A unit destroyed before completion must not receive a refill.</summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Destroyed_unit_cannot_be_refilled_by_storage_primitive()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET supplies=23,unit_number=100,damaged=100,destroyed=100"))
            await stage.ExecuteNonQueryAsync(ct);
        var before = (await store.FindOriginalGridUnitAsync(owner, character, unitId, ct))!;
        var balances = await BalancesAsync(data, character, ct);
        var refused = await store.SupplyOwnOriginalUnitAsync(owner, Write(7, before, unitId), ct);
        Assert.Equal(OriginalTacticalSupplyStatus.Rejected, refused.Status);
        Assert.Equal("TACTICAL_SUPPLY_UNIT_DESTROYED", refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal(balances, await BalancesAsync(data, character, ct));
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_tactical_supply_command", ct));
    }

    /// <summary>A stale expectation never writes: another session moved first.</summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_stale_expectation_is_refused()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var spend = data.CreateCommand("UPDATE original_grid_unit SET supplies=0"))
            await spend.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var refused = await store.SupplyOwnOriginalUnitAsync(owner,
            new OriginalTacticalSupplyWrite(Fingerprint(9), character, unitId, SupplyVessel,
                before.AuthorityVersion + 5, before.ShipGeneration), ct);

        Assert.Equal(OriginalTacticalSupplyStatus.Rejected, refused.Status);
        Assert.Equal("TACTICAL_SUPPLY_SOURCE_STALE", refused.ErrorCode);
        Assert.Equal(0u, (await store.FindOriginalGridUnitAsync(owner, character, unitId, ct))!.Supplies);
    }

    private static OriginalTacticalSupplyWrite Write(int seed, OriginalGridUnitRecord unit, uint unitId) =>
        new(Fingerprint(seed), unit.CharacterId, unitId, SupplyVessel,
            unit.AuthorityVersion, unit.ShipGeneration);

    private static string Fingerprint(int seed) =>
        string.Concat(Enumerable.Repeat((seed % 10).ToString(CultureInfo.InvariantCulture), 64));

    private static async Task<int> CountAsync(NpgsqlDataSource data, string sql, CancellationToken ct)
    {
        await using var command = data.CreateCommand(sql);
        return (int)(long)(await command.ExecuteScalarAsync(ct))!;
    }

    private static async Task<(uint Pcp, uint Mcp)> BalancesAsync(
        NpgsqlDataSource data, long character, CancellationToken ct)
    {
        await using var command = data.CreateCommand(
            "SELECT pcp,mcp FROM character WHERE character_id = $1");
        command.Parameters.AddWithValue(character);
        await using var reader = await command.ExecuteReaderAsync(ct);
        Assert.True(await reader.ReadAsync(ct));
        return ((uint)reader.GetInt64(0), (uint)reader.GetInt64(1));
    }

    private static async Task<(Guid Owner, long Character)> SeedAsync(NpgsqlDataSource data)
    {
        var ct = TestContext.Current.CancellationToken;
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'supply',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(ct);
        }
        await using var character = data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """);
        character.Parameters.AddWithValue(owner);
        return (owner, (long)(await character.ExecuteScalarAsync(ct))!);
    }

    private static async Task<NpgsqlDataSource> CreateAsync()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "tactical_supply_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var data = NpgsqlDataSource.Create(
            new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema }.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            CancellationToken.None);
        return data;
    }
}
