using System.Security.Cryptography;
using Npgsql;

namespace Logh7.Server.Storage;

public static class PostgresMigrationRunner
{
    public static string MigrationDirectory =>
        Path.Combine(AppContext.BaseDirectory, "migrations");

    public static string NaturalAuthorityD02MigrationPath =>
        Path.Combine(MigrationDirectory, "0001_natural_authority_d02.sql");

    public static async Task ApplyAllAsync(
        NpgsqlDataSource dataSource,
        string migrationDirectory,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(dataSource);
        ArgumentException.ThrowIfNullOrWhiteSpace(migrationDirectory);
        foreach (var migrationPath in Directory.GetFiles(migrationDirectory, "*.sql")
                     .Order(StringComparer.Ordinal))
        {
            await ApplyAsync(dataSource, migrationPath, cancellationToken);
        }
    }

    public static async Task ApplyAsync(
        NpgsqlDataSource dataSource,
        string migrationPath,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(dataSource);
        ArgumentException.ThrowIfNullOrWhiteSpace(migrationPath);
        var bytes = await File.ReadAllBytesAsync(migrationPath, cancellationToken);
        var sql = System.Text.Encoding.UTF8.GetString(bytes);
        var hash = Convert.ToHexStringLower(SHA256.HashData(bytes));
        var version = Path.GetFileNameWithoutExtension(migrationPath);
        if (string.IsNullOrWhiteSpace(version))
        {
            throw new InvalidOperationException("MIGRATION_VERSION_MISSING");
        }

        await using var connection = await dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using (var advisory = new NpgsqlCommand(
            "SELECT pg_advisory_xact_lock(71070001)",
            connection,
            transaction))
        {
            await advisory.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var bootstrap = new NpgsqlCommand(
            "CREATE TABLE IF NOT EXISTS schema_migration (version text PRIMARY KEY, sha256 char(64) NOT NULL, applied_at timestamptz NOT NULL DEFAULT transaction_timestamp())",
            connection,
            transaction))
        {
            await bootstrap.ExecuteNonQueryAsync(cancellationToken);
        }

        string? existingHash;
        await using (var select = new NpgsqlCommand(
            "SELECT sha256 FROM schema_migration WHERE version = $1",
            connection,
            transaction))
        {
            select.Parameters.AddWithValue(version);
            existingHash = (string?)await select.ExecuteScalarAsync(cancellationToken);
        }

        if (existingHash is not null)
        {
            if (!string.Equals(existingHash.Trim(), hash, StringComparison.Ordinal))
            {
                throw new InvalidOperationException("MIGRATION_HASH_MISMATCH");
            }

            await transaction.CommitAsync(cancellationToken);
            return;
        }

        await using (var migrate = new NpgsqlCommand(sql, connection, transaction))
        {
            await migrate.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var insert = new NpgsqlCommand(
            "INSERT INTO schema_migration(version, sha256) VALUES ($1, $2)",
            connection,
            transaction))
        {
            insert.Parameters.AddWithValue(version);
            insert.Parameters.AddWithValue(hash);
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }

        await transaction.CommitAsync(cancellationToken);
    }
}
