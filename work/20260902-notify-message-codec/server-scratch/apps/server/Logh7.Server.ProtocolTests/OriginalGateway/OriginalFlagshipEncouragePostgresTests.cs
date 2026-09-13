using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 鼓舞 (0x0409 CommandEncourageFlagship). Morale falls with hulls destroyed and
/// is restored by this command; the morale write, its command-point charge and
/// its history row commit together, or none of them do.
/// </summary>
public sealed class OriginalFlagshipEncouragePostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Encouraging_a_shaken_fleet_restores_morale_and_spends_points_once()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand("UPDATE original_grid_unit SET morale=40"))
            await stage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var encouraged = await store.EncourageOwnOriginalFlagshipAsync(owner, Write(1, before), ct);

        Assert.Equal(OriginalFlagshipEncourageStatus.Encouraged, encouraged.Status);
        Assert.Equal((byte)100, encouraged.Unit!.Morale);
        Assert.Equal(encouraged.Unit, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((1600u, 1560u), await BalancesAsync(data, character, ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type = 'OriginalFlagshipEncouraged'", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE payload->>'commandPointCost' = '40'", ct));
        // One player action stays one authority version.
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE authority_version = " +
            encouraged.AuthorityVersion.ToString(System.Globalization.CultureInfo.InvariantCulture), ct));

        var replayed = await store.EncourageOwnOriginalFlagshipAsync(owner, Write(1, before), ct);

        Assert.Equal(OriginalFlagshipEncourageStatus.Replayed, replayed.Status);
        Assert.Equal((1600u, 1560u), await BalancesAsync(data, character, ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("UPDATE original_grid_unit SET morale=100", "FLAGSHIP_ENCOURAGE_MORALE_FULL")]
    [InlineData("UPDATE original_grid_unit SET morale=10,destroyed=100,damaged=100",
        "FLAGSHIP_ENCOURAGE_UNIT_DESTROYED")]
    public async Task A_refused_encouragement_changes_nothing_at_all(string setup, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(setup)) await stage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var refused = await store.EncourageOwnOriginalFlagshipAsync(owner, Write(2, before), ct);

        Assert.Equal(OriginalFlagshipEncourageStatus.Rejected, refused.Status);
        Assert.Equal(expected, refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal(0, await CountAsync(data,
            "SELECT count(*) FROM original_flagship_encourage_command", ct));
    }

    /// <summary>
    /// A player who cannot pay keeps their low morale and their points; the
    /// charge and the morale write are the same transaction.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task An_encouragement_the_player_cannot_pay_for_raises_nothing()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET morale=40;UPDATE character SET pcp=0,mcp=0"))
            await stage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var refused = await store.EncourageOwnOriginalFlagshipAsync(owner, Write(3, before), ct);

        Assert.Equal(OriginalFlagshipEncourageStatus.Rejected, refused.Status);
        Assert.Equal("FLAGSHIP_ENCOURAGE_COMMAND_POINTS_INSUFFICIENT", refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((0u, 0u), await BalancesAsync(data, character, ct));
    }

    /// <summary>
    /// Losses are what morale answers to: destroyed hulls lower it in proportion
    /// to the complement, and only the newly destroyed ones count.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Destroyed_hulls_lower_morale_and_only_the_new_ones_count()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        var first = await store.SaveOriginalUnitDamageAsync(owner,
            new OriginalUnitDamageWrite(character, unitId, 101, 0, 100, 30, 30), ct);
        Assert.Equal((byte)70, first.Unit.Morale);

        var second = await store.SaveOriginalUnitDamageAsync(owner,
            new OriginalUnitDamageWrite(character, unitId, 101, 0, 100, 45, 40), ct);

        // 10 further hulls destroyed out of 100 = ten more points, not forty.
        Assert.Equal((byte)60, second.Unit.Morale);
        var stored = await store.FindOriginalGridUnitAsync(owner, character, unitId, ct);
        Assert.Equal((byte)60, stored!.Morale);
    }

    /// <summary>
    /// Damage without a new loss leaves morale where it was.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Damage_without_a_new_loss_leaves_morale_alone()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);

        var damaged = await store.SaveOriginalUnitDamageAsync(owner,
            new OriginalUnitDamageWrite(character, unitId, 101, 0, 100, 55, 0), ct);

        Assert.Equal((byte)100, damaged.Unit.Morale);
    }

    /// <summary>
    /// The whole command over the real session and wire: a shaken flagship is
    /// encouraged, the client gets its own body back, and the scene record the
    /// authority serves carries the restored morale.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task The_wire_command_restores_morale_and_reports_the_new_state()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET morale=30,current_cell_id=102"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);

        var request = OriginalEncourageFlagshipCodec.Encode(
            new(0, unitId, unitId, unitId)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        Assert.Contains("flagship-encourage-accepted;unit=", answer.ResponseMetadata);
        Assert.Contains("morale=100;mcp-cost=40", answer.ResponseMetadata);
        var frame = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0).Payload!;
        Assert.Equal((ushort)0x0409, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        var stored = await store.FindOriginalGridUnitAsync(owner, character, unitId, ct);
        Assert.Equal((byte)100, stored!.Morale);
    }

    /// <summary>
    /// A body that names a unit this session does not command is refused on
    /// screen instead of executing against the player's own flagship.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_foreign_unit_is_refused_visibly()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET morale=30,current_cell_id=102"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);

        var request = OriginalEncourageFlagshipCodec.Encode(
            new(0, unitId, unitId + 7000, unitId)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);

        Assert.Contains("FLAGSHIP_ENCOURAGE_UNIT_NOT_CONTROLLED", answer.ResponseMetadata);
        var stored = await store.FindOriginalGridUnitAsync(owner, character, unitId, ct);
        Assert.Equal((byte)30, stored!.Morale);
    }

    /// <summary>
    /// The client arms its tactical command UI from 0x0F1F: FUN_004C1B20 writes
    /// world+0x357E8C = 2 for state 1 and 0 otherwise, and the battle sequence
    /// reads that word. The one the world bootstrap sends is buried in twenty-odd
    /// pushed frames and was measured as not taking on the live client, so the
    /// first clock tick inside an active tactical field re-asserts it - once.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task The_first_clock_tick_in_an_active_tactical_field_reasserts_the_tactics_notify()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand("UPDATE original_grid_unit SET current_cell_id=101"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: new OriginalTacticalBattleRegistry());
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 101u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);

        var first = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0300"), key, 3), ct);
        var second = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0300"), key, 4), ct);

        Assert.Contains("tactics-notify-reasserted=1", first.ResponseMetadata);
        Assert.Single(first.AdditionalResponses!);
        // The body is the client's own shape: one state byte then the grid.
        var notify = OriginalClientInnerFrameCodec.Decode(
            first.AdditionalResponses![0].Payload, key, 0).Payload!;
        Assert.Equal((ushort)0x0f1f, BinaryPrimitives.ReadUInt16BigEndian(notify.AsSpan(4)));
        Assert.Equal((byte)1, notify[6]);
        Assert.Equal(101u, BinaryPrimitives.ReadUInt32BigEndian(notify.AsSpan(7)));
        // Once per entry into the field, not on every tick.
        Assert.DoesNotContain("tactics-notify-reasserted", second.ResponseMetadata ?? string.Empty);
        Assert.True(second.AdditionalResponses is null || second.AdditionalResponses.Count == 0);
    }

    [Fact]
    public void The_codec_round_trips_the_captured_body()
    {
        // ORIGINAL_OBSERVED 2026-09-09.
        var captured = Convert.FromHexString("040908C1A738000000020000000200000002");

        Assert.True(OriginalEncourageFlagshipCodec.TryDecode(captured, out var command));
        Assert.Equal((0x08C1A738u, 2u, 2u, 2u),
            (command.Time, command.Wait, command.Id, command.Unit));
        Assert.Equal(captured, OriginalEncourageFlagshipCodec.Encode(command).AsSpan(4).ToArray());
        Assert.False(OriginalEncourageFlagshipCodec.TryDecode(captured.AsSpan(0, 17), out _));
    }

    private static OriginalFlagshipEncourageWrite Write(int intent, OriginalGridUnitRecord unit) =>
        new(intent.ToString("x64"), unit.CharacterId, unit.UnitId, unit.AuthorityVersion,
            unit.ShipGeneration, NaturalAuthoritySession.FlagshipMoraleMaximum,
            new(OriginalCommandPointPool.Military, NaturalAuthoritySession.FlagshipEncouragePointCost,
                OriginalCommandPointPolicy.LoadDefault(), DateTimeOffset.UnixEpoch, false));

    private static async Task<(uint Political, uint Military)> BalancesAsync(NpgsqlDataSource data,
        long character, CancellationToken ct)
    {
        await using var read = data.CreateCommand("SELECT pcp, mcp FROM character WHERE character_id = $1");
        read.Parameters.AddWithValue(character);
        await using var reader = await read.ExecuteReaderAsync(ct);
        Assert.True(await reader.ReadAsync(ct));
        return ((uint)reader.GetInt64(0), (uint)reader.GetInt64(1));
    }

    private static async Task<long> CountAsync(NpgsqlDataSource data, string sql, CancellationToken ct)
    {
        await using var read = data.CreateCommand(sql);
        return (long)(await read.ExecuteScalarAsync(ct))!;
    }

    private static async Task<(Guid Owner, long Character)> SeedAsync(NpgsqlDataSource data)
    {
        var ct = TestContext.Current.CancellationToken;
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'encourage',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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
        var schema = "flagship_encourage_" + Guid.NewGuid().ToString("N");
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
