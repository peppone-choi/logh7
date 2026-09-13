using System.Text.Json;
using Logh7.Server.Authority;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Security;
using Logh7.Server.Storage;
using Npgsql;

namespace Logh7.Server.Hosting;

public static class NaturalAuthorityServerCommand
{
    public const string ConnectionEnvironmentName = "LOGH7_DB_CONNECTION";
    public const string ServerNoticeEnvironmentName = "LOGH7_SERVER_NOTICE";

    public static async Task<int> RunAsync(
        ServeOriginalCommand command,
        TextWriter standardOutput,
        TextWriter standardError,
        CancellationToken cancellationToken)
    {
        var connectionString = Environment.GetEnvironmentVariable(ConnectionEnvironmentName);
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
            var store = new PostgresAccountStore(dataSource);
            var time = TimeProvider.System;
            var handoffs = new HandoffRegistry(time, TimeSpan.FromSeconds(60));
            var receipt = new MetadataOnlyGatewayReceipt(time);
            var accountAuthority = new AccountAuthority(store, new Argon2PasswordHasher());
            var loginAuthority = new OriginalLoginAuthority(accountAuthority, handoffs, receipt);
            var serverNotice = Environment.GetEnvironmentVariable(ServerNoticeEnvironmentName);
            await using var server = new NaturalAuthorityServer(
                new NaturalAuthorityServerOptions(
                    command.BindAddress,
                    command.Port,
                    command.AdvertiseAddress,
                    checked((ushort)command.Port),
                    command.ReceiptPath,
                    SessionBindAddress: command.SessionBindAddress,
                    SessionAdvertiseAddress: command.SessionAdvertiseAddress,
                    ServerNotice: serverNotice),
                loginAuthority,
                handoffs,
                store,
                receipt);
            var endpoint = await server.StartAsync(cancellationToken);
            await standardOutput.WriteLineAsync(JsonSerializer.Serialize(new
            {
                eventName = "natural-authority-ready",
                bindAddress = endpoint.Address.ToString(),
                port = endpoint.Port,
                receiptPath = Path.GetFullPath(command.ReceiptPath)
            }));
            try
            {
                await Task.Delay(Timeout.InfiniteTimeSpan, cancellationToken);
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
            }

            await server.StopAsync(CancellationToken.None);
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
        catch (IOException)
        {
            await standardError.WriteLineAsync("server.receipt-or-listener.io-failure");
            return 5;
        }
    }
}
