using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// ORIGINAL_OBSERVED 2026-09-09: the client's own ワープ航行 confirmation states
/// 「通常はコマンドポイント320MCP消費」. The charge therefore belongs to the warp
/// itself and must commit with it, so a player can never be billed for a jump
/// that did not happen nor jump without paying.
/// </summary>
public sealed class OriginalWarpCommandPointPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_warp_spends_its_military_points_in_the_move_transaction_and_only_once()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unit = checked((uint)character);

        // A historical replay record is not the current supply authority.
        // Preserve already-consumed stocks through both the move and its retry.
        await using (var stock = data.CreateCommand("UPDATE original_grid_unit SET supplies=23 WHERE account_id=$1 AND unit_id=$2"))
        {
            stock.Parameters.AddWithValue(owner);
            stock.Parameters.AddWithValue((long)unit);
            Assert.Equal(1,await stock.ExecuteNonQueryAsync(ct));
        }
        var moved = await store.MoveOriginalGridUnitAsync(owner, Write(1, character, unit), ct);

        Assert.Equal(OriginalMoveGridStoreStatus.Moved, moved.Status);
        Assert.Equal(102u, moved.Unit!.CurrentCellId);
        Assert.Equal(23u,moved.Unit.Supplies);
        Assert.Equal((1600u, 1280u), await BalancesAsync(data, character, ct));
        // The charge is on the ledger under the move's own fingerprint, and the
        // move's single event carries the cost. One action, one version.
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM original_command_point_charge WHERE cost = 320", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE authority_version = " +
            moved.AuthorityVersion.ToString(System.Globalization.CultureInfo.InvariantCulture), ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE payload->>'commandPointCost' = '320'", ct));

        var replayed = await store.MoveOriginalGridUnitAsync(owner, Write(1, character, unit), ct);

        Assert.Equal(OriginalMoveGridStoreStatus.Replayed, replayed.Status);
        Assert.Equal((1600u, 1280u), await BalancesAsync(data, character, ct));
        Assert.Equal(23u,(await store.FindOriginalGridUnitAsync(owner,character,unit,ct))!.Supplies);
    }

    /// <summary>
    /// A refused charge must leave the unit where it was. Rolling the move back
    /// but keeping the deduction, or the reverse, would both be worse than
    /// refusing the command.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_warp_the_player_cannot_pay_for_moves_nothing_and_spends_nothing()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unit = checked((uint)character);
        // Own pool empty and the other pool short of the deficit-only 2:1 cover.
        await using (var drain = data.CreateCommand("UPDATE character SET pcp = 100, mcp = 0"))
            await drain.ExecuteNonQueryAsync(ct);

        var refused = await store.MoveOriginalGridUnitAsync(owner, Write(2, character, unit), ct);

        Assert.Equal(OriginalMoveGridStoreStatus.Rejected, refused.Status);
        Assert.Equal("MOVE_GRID_COMMAND_POINTS_INSUFFICIENT", refused.ErrorCode);
        Assert.Equal((100u, 0u), await BalancesAsync(data, character, ct));
        Assert.Equal(101u, (await store.FindOriginalGridUnitAsync(owner, character, unit, ct))!.CurrentCellId);
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
    }

    /// <summary>
    /// The deficit is covered from the political pool at the authored ratio,
    /// and only the deficit: a move that the military pool nearly covers must
    /// not empty the political one.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_partly_covered_warp_substitutes_only_the_deficit()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unit = checked((uint)character);
        await using (var drain = data.CreateCommand("UPDATE character SET pcp = 1000, mcp = 300"))
            await drain.ExecuteNonQueryAsync(ct);

        var moved = await store.MoveOriginalGridUnitAsync(owner, Write(3, character, unit), ct);

        Assert.Equal(OriginalMoveGridStoreStatus.Moved, moved.Status);
        // 300 of 320 直接, the remaining 20 covered at 2:1 = 40 political.
        Assert.Equal((960u, 0u), await BalancesAsync(data, character, ct));
    }

    private static OriginalMoveGridWrite Write(int intent, long character, uint unit) =>
        new(intent.ToString("x64"), character, unit, OriginalAuthoredPlayableCatalog.AuthorityCardId,
            101, 101, 102, OriginalMoveGridAuthority.MinimalWorldWarpAction)
        {
            Points = new(OriginalCommandPointPool.Military,
                OriginalMoveGridAuthority.WarpMilitaryPointCost,
                OriginalCommandPointPolicy.LoadDefault(), DateTimeOffset.UnixEpoch, false),
        };

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
            VALUES($1,'warp_points',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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
        var schema = "warp_points_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var data = NpgsqlDataSource.Create(
            new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema }.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            TestContext.Current.CancellationToken);
        return data;
    }
}
