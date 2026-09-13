using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// Real-database behaviour of the user-approved authored command-point economy.
/// These tests exercise the trusted storage primitive; they do not claim a
/// native command, a client request or an original cost rule beyond the
/// manual-sourced 160-point complete-repair cost.
/// </summary>
public sealed class OriginalCommandPointChargePostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    private static readonly OriginalCommandPointPolicy Policy = OriginalCommandPointPolicy.LoadDefault();
    private static readonly DateTimeOffset Now = new(2026, 9, 9, 12, 0, 0, TimeSpan.Zero);

    private static async Task<(NpgsqlDataSource Data, PostgresAccountStore Store, Guid Owner, long Character)>
        OpenAsync(string label, CancellationToken cancellationToken)
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = label + "_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(cancellationToken);
        var builder = new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema };
        var data = NpgsqlDataSource.Create(builder.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            cancellationToken);
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'points_charge',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(cancellationToken);
        }
        var store = new PostgresAccountStore(data);
        var created = await store.CreateCharacterAsync(owner, new(new string('1', 64), new string('2', 64),
            2, 0, 0, "Last", "First", "指揮艦", 5, [1, 2, 3, 4, 5, 6, 7, 8]), cancellationToken);
        return (data, store, owner, created.CharacterId);
    }

    private static async Task<(uint Pcp, uint Mcp, long DirectPolitical, long DirectMilitary,
        long SubstitutePolitical, long SubstituteMilitary)> ReadAsync(NpgsqlDataSource data, long character,
        CancellationToken cancellationToken)
    {
        await using var select = data.CreateCommand("""
            SELECT pcp,mcp,pcp_spent_direct,mcp_spent_direct,pcp_spent_substitute,mcp_spent_substitute
            FROM character WHERE character_id=$1
            """);
        select.Parameters.AddWithValue(character);
        await using var reader = await select.ExecuteReaderAsync(cancellationToken);
        Assert.True(await reader.ReadAsync(cancellationToken));
        return ((uint)reader.GetInt64(0), (uint)reader.GetInt64(1), reader.GetInt64(2), reader.GetInt64(3),
            reader.GetInt64(4), reader.GetInt64(5));
    }

    private static string Fingerprint(char value) => new(value, 64);

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_repeated_command_identity_charges_once_and_returns_the_first_outcome()
    {
        var ct = TestContext.Current.CancellationToken;
        var (data, store, owner, character) = await OpenAsync("points_once", ct);
        await using var _ = data;
        var write = new OriginalCommandPointWrite(character, OriginalCommandPointPool.Military, 160, Fingerprint('a'));
        var first = await store.ChargeCommandPointsAsync(owner, write, Policy, Now, inTactics: false, ct);
        Assert.True(first.Applied);
        Assert.Equal(160u, first.Direct);
        Assert.Equal(0u, first.Substitute);
        Assert.Equal(Policy.InitialMilitary - 160, first.Military);
        Assert.Equal(Policy.InitialPolitical, first.Political);

        var replay = await store.ChargeCommandPointsAsync(owner, write, Policy, Now, inTactics: false, ct);
        Assert.False(replay.Applied);
        Assert.Equal(first.Military, replay.Military);
        Assert.Equal(first.AuthorityVersion, replay.AuthorityVersion);

        var stored = await ReadAsync(data, character, ct);
        Assert.Equal(Policy.InitialMilitary - 160, stored.Mcp);
        Assert.Equal(160, stored.DirectMilitary);
        Assert.Equal(0, stored.SubstituteMilitary);
        Assert.Equal(0, stored.SubstitutePolitical);

        await using var count = data.CreateCommand("SELECT count(*) FROM original_command_point_charge");
        Assert.Equal(1L, (long)(await count.ExecuteScalarAsync(ct))!);
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task The_same_fingerprint_with_a_different_meaning_is_refused()
    {
        var ct = TestContext.Current.CancellationToken;
        var (data, store, owner, character) = await OpenAsync("points_conflict", ct);
        await using var _ = data;
        await store.ChargeCommandPointsAsync(owner,
            new(character, OriginalCommandPointPool.Military, 160, Fingerprint('b')), Policy, Now, false, ct);
        var conflict = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.ChargeCommandPointsAsync(owner,
                new(character, OriginalCommandPointPool.Military, 320, Fingerprint('b')), Policy, Now, false, ct));
        Assert.Equal("COMMAND_POINTS_REPLAY_CONFLICT", conflict.Message);
        var stored = await ReadAsync(data, character, ct);
        Assert.Equal(Policy.InitialMilitary - 160, stored.Mcp);
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Only_the_deficit_is_taken_from_the_other_pool_and_stays_separately_counted()
    {
        var ct = TestContext.Current.CancellationToken;
        var (data, store, owner, character) = await OpenAsync("points_deficit", ct);
        await using var _ = data;
        // Test-owned fixture stock, not a production credit API.
        await using (var seed = data.CreateCommand(
            "UPDATE character SET mcp=100,points_accrued_at=$2 WHERE character_id=$1"))
        {
            seed.Parameters.AddWithValue(character);
            seed.Parameters.AddWithValue(Now);
            Assert.Equal(1, await seed.ExecuteNonQueryAsync(ct));
        }
        var charged = await store.ChargeCommandPointsAsync(owner,
            new(character, OriginalCommandPointPool.Military, 160, Fingerprint('c')), Policy, Now, false, ct);
        Assert.True(charged.Applied);
        Assert.Equal(100u, charged.Direct);
        Assert.Equal(120u, charged.Substitute);
        Assert.Equal(0u, charged.Military);
        Assert.Equal(Policy.InitialPolitical - 120, charged.Political);

        var stored = await ReadAsync(data, character, ct);
        Assert.Equal(100, stored.DirectMilitary);
        Assert.Equal(120, stored.SubstitutePolitical);
        Assert.Equal(0, stored.DirectPolitical);
        Assert.Equal(0, stored.SubstituteMilitary);
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task An_unaffordable_command_leaves_the_balances_and_the_authority_version_untouched()
    {
        var ct = TestContext.Current.CancellationToken;
        var (data, store, owner, character) = await OpenAsync("points_poor", ct);
        await using var _ = data;
        await using (var seed = data.CreateCommand(
            "UPDATE character SET pcp=119,mcp=100,points_accrued_at=$2 WHERE character_id=$1"))
        {
            seed.Parameters.AddWithValue(character);
            seed.Parameters.AddWithValue(Now);
            Assert.Equal(1, await seed.ExecuteNonQueryAsync(ct));
        }
        long before;
        await using (var version = data.CreateCommand("SELECT authority_version FROM account WHERE account_id=$1"))
        {
            version.Parameters.AddWithValue(owner);
            before = (long)(await version.ExecuteScalarAsync(ct))!;
        }
        var failure = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.ChargeCommandPointsAsync(owner,
                new(character, OriginalCommandPointPool.Military, 160, Fingerprint('d')), Policy, Now, false, ct));
        Assert.Equal("COMMAND_POINTS_INSUFFICIENT", failure.Message);
        var stored = await ReadAsync(data, character, ct);
        Assert.Equal(119u, stored.Pcp);
        Assert.Equal(100u, stored.Mcp);
        await using (var version = data.CreateCommand("SELECT authority_version FROM account WHERE account_id=$1"))
        {
            version.Parameters.AddWithValue(owner);
            Assert.Equal(before, (long)(await version.ExecuteScalarAsync(ct))!);
        }
        await using var count = data.CreateCommand("SELECT count(*) FROM original_command_point_charge");
        Assert.Equal(0L, (long)(await count.ExecuteScalarAsync(ct))!);
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Regeneration_persists_outside_tactics_and_never_advances_the_authority_version()
    {
        var ct = TestContext.Current.CancellationToken;
        var (data, store, owner, character) = await OpenAsync("points_regen", ct);
        await using var _ = data;
        await using (var seed = data.CreateCommand(
            "UPDATE character SET pcp=100,mcp=100,points_accrued_at=$2 WHERE character_id=$1"))
        {
            seed.Parameters.AddWithValue(character);
            seed.Parameters.AddWithValue(Now);
            Assert.Equal(1, await seed.ExecuteNonQueryAsync(ct));
        }
        long before;
        await using (var version = data.CreateCommand("SELECT authority_version FROM account WHERE account_id=$1"))
        {
            version.Parameters.AddWithValue(owner);
            before = (long)(await version.ExecuteScalarAsync(ct))!;
        }
        var inBattle = await store.AccrueCommandPointsAsync(owner, character, Policy,
            Now + TimeSpan.FromMinutes(10), inTactics: true, ct);
        Assert.Equal(100u, inBattle.Political);
        Assert.Equal(100u, inBattle.Military);

        var settled = await store.AccrueCommandPointsAsync(owner, character, Policy,
            Now + TimeSpan.FromMinutes(15), inTactics: false, ct);
        Assert.Equal(260u, settled.Political);
        Assert.Equal(260u, settled.Military);

        var stored = await ReadAsync(data, character, ct);
        Assert.Equal(260u, stored.Pcp);
        Assert.Equal(260u, stored.Mcp);
        await using (var version = data.CreateCommand("SELECT authority_version FROM account WHERE account_id=$1"))
        {
            version.Parameters.AddWithValue(owner);
            Assert.Equal(before, (long)(await version.ExecuteScalarAsync(ct))!);
        }
    }
}
