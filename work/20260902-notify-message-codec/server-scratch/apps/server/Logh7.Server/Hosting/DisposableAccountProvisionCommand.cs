using System.Text.Json;
using Logh7.Server.Security;
using Logh7.Server.Storage;
using Npgsql;

namespace Logh7.Server.Hosting;

public static class DisposableAccountProvisionCommand
{
    public static async Task<int> RunAsync(
        ProvisionDisposableAccountCommand command,
        TextWriter standardOutput,
        TextWriter standardError,
        CancellationToken cancellationToken)
    {
        var connectionString = Environment.GetEnvironmentVariable(
            NaturalAuthorityServerCommand.ConnectionEnvironmentName);
        if (string.IsNullOrWhiteSpace(connectionString))
        {
            await standardError.WriteLineAsync("server.database.connection-missing");
            return 3;
        }

        try
        {
            await using var dataSource = NpgsqlDataSource.Create(connectionString);
            await PostgresMigrationRunner.ApplyAllAsync(
                dataSource,
                PostgresMigrationRunner.MigrationDirectory,
                cancellationToken);
            var provisioner = new DisposableAccountProvisioner(
                new Argon2PasswordHasher(),
                new WindowsDpapiCredentialProtector(),
                TimeProvider.System);
            await provisioner.ProvisionAsync(
                new PostgresAccountStore(dataSource),
                command.SecretPath,
                command.ReceiptPath,
                cancellationToken);
            await standardOutput.WriteLineAsync(JsonSerializer.Serialize(new
            {
                eventName = "account-provisioned",
                receiptPath = Path.GetFullPath(command.ReceiptPath),
                secretProtected = true
            }));
            return 0;
        }
        catch (PostgresException exception)
        {
            await standardError.WriteLineAsync($"server.database.postgres:{exception.SqlState}");
            return 4;
        }
        catch (NpgsqlException)
        {
            await standardError.WriteLineAsync("server.database.unavailable");
            return 4;
        }
        catch (IOException exception)
        {
            await standardError.WriteLineAsync(exception.Message);
            return 5;
        }
    }
}
