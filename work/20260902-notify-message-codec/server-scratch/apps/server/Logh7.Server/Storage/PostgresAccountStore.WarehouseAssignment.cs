using System.Security.Cryptography;
using System.Text;
using Logh7.Server.OriginalGateway;
using Npgsql;

namespace Logh7.Server.Storage;

public sealed record OriginalWarehouseAssignmentResult(
    OriginalWarehouseTransferResult Transfer, OriginalCommandPointState Points);

public sealed partial class PostgresAccountStore
{
    // Original messages_com_0.dat offset0x2D74 explicitly says 160MCP.
    public const uint OriginalWarehouseAssignmentCost = 160;

    // Storage operation only. Native handler must derive stock deltas/identity
    // and validate the selected outfit Kind; request result stocks are untrusted.
    public async Task<OriginalWarehouseAssignmentResult> AssignOriginalWarehouseAsync(
        Guid accountId, OriginalWarehouseTransfer write, OriginalCommandPointPolicy policy,
        DateTimeOffset now, bool inTactics, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentNullException.ThrowIfNull(policy);
        if (write.Source.OutfitId != 0 || write.Destination.OutfitId == 0 ||
            write.Source.BaseId != write.Destination.BaseId)
            throw new ArgumentException("ASSIGNMENT_WAREHOUSE_ENDPOINTS_INVALID", nameof(write));
        write = write with { Lines = write.Lines?.ToArray()! };
        var fingerprint = "original-assignment-v1:" + write.RequestId.ToString("N");
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var transfer = await new PostgresWarehouseStore(_dataSource).TransferInTransactionAsync(
            transaction, accountId, write, cancellationToken);
        if (!transfer.Applied)
        {
            // A generic warehouse receipt must not masquerade as a paid command.
            await using var receipt = new NpgsqlCommand("""
                SELECT count(*) FROM original_command_point_charge
                WHERE account_id=$1 AND request_fingerprint=$2 AND character_id=$3
                    AND pool=1 AND cost=160 AND authority_version=$4
                """, connection, transaction);
            receipt.Parameters.AddWithValue(accountId);
            receipt.Parameters.AddWithValue(fingerprint);
            receipt.Parameters.AddWithValue(write.CharacterId);
            receipt.Parameters.AddWithValue(transfer.AuthorityVersion);
            if ((long)(await receipt.ExecuteScalarAsync(cancellationToken))! != 1)
                throw new InvalidOperationException("ASSIGNMENT_REPLAY_RECEIPT_MISMATCH");
            var current = await ReadPointsAsync(connection, transaction, accountId, write.CharacterId, cancellationToken);
            await using var versionQuery = new NpgsqlCommand(
                "SELECT authority_version FROM account WHERE account_id=$1", connection, transaction);
            versionQuery.Parameters.AddWithValue(accountId);
            var currentVersion = (long)(await versionQuery.ExecuteScalarAsync(cancellationToken))!;
            await transaction.CommitAsync(cancellationToken);
            return new(transfer, new(current.Political, current.Military, 0, 0, currentVersion, false));
        }
        var points = await ApplyCommandPointChargeAsync(connection, transaction, accountId,
            new(write.CharacterId, OriginalCommandPointPool.Military, OriginalWarehouseAssignmentCost, fingerprint),
            policy, now, inTactics, transfer.AuthorityVersion, cancellationToken, emitDomainEvent: false);
        if (!points.Applied) throw new InvalidOperationException("ASSIGNMENT_CHARGE_ALREADY_REPLAYED");
        // One action / one authority version: enrich the existing warehouse
        // event with the charge, rather than inserting a second version event.
        await using (var command = new NpgsqlCommand("""
            UPDATE domain_event SET event_type='OriginalWarehouseAssigned',
                payload=payload || jsonb_build_object('commandPoints',$3::jsonb)
            WHERE account_id=$1 AND authority_version=$2 AND event_type='OriginalWarehouseTransferred'
            """, connection, transaction))
        {
            command.Parameters.AddWithValue(accountId);
            command.Parameters.AddWithValue(transfer.AuthorityVersion);
            command.Parameters.AddWithValue(points.Payload);
            if (await command.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("ASSIGNMENT_EVENT_MISSING");
        }
        await using (var command = new NpgsqlCommand(
            "SELECT authority_state_hash FROM account WHERE account_id=$1", connection, transaction))
        {
            command.Parameters.AddWithValue(accountId);
            var stockHash = ((string)(await command.ExecuteScalarAsync(cancellationToken))!).Trim();
            var hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
                "original-assignment-v1\n" + stockHash + "\n" + points.Payload)));
            await using var update = new NpgsqlCommand(
                "UPDATE account SET authority_state_hash=$2 WHERE account_id=$1", connection, transaction);
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(hash);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
                throw new InvalidOperationException("ASSIGNMENT_ACCOUNT_MISSING");
        }
        await transaction.CommitAsync(cancellationToken);
        return new(transfer, points);
    }
}
