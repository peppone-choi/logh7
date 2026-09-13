using System.Collections.ObjectModel;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Npgsql;

namespace Logh7.Server.Storage;

// NEW_DESIGN storage model. Preserve independent original counters; there is
// no conversion here between ship groups, boat counts, casualties or crew.
public enum OriginalStockKind { ShipUnits, ShipBoats, Troops, Supplies, Food, Mineral }
public readonly record struct OriginalWarehouseKey(uint BaseId, uint OutfitId);
public readonly record struct OriginalStockKey(OriginalStockKind Kind, ushort ItemKind = 0, byte Grade = 0);
// Positive: source -> destination. Negative: destination -> source.
// Mixed lines form one atomic exchange, not two independently committed transfers.
public readonly record struct OriginalStockTransferLine(OriginalStockKey Stock, long Quantity);
public sealed record OriginalWarehouseTransfer(Guid RequestId, long CharacterId,
    OriginalWarehouseKey Source, OriginalWarehouseKey Destination, IReadOnlyList<OriginalStockTransferLine> Lines);
public sealed record OriginalWarehouseTransferResult(bool Applied, long SourceVersion,
    long DestinationVersion, long AuthorityVersion);
public sealed record OriginalWarehouseSnapshot(OriginalWarehouseKey Key, long Version,
    IReadOnlyDictionary<OriginalStockKey, long> Balances);

// This is a shared-stock transaction primitive, not the native 0903/0C02
// handler. Those handlers must derive grants, deltas and request identity from
// authenticated authority state. No public method grants access or seeds stock.
public sealed class PostgresWarehouseStore(NpgsqlDataSource dataSource)
{
    private readonly NpgsqlDataSource _dataSource = dataSource ?? throw new ArgumentNullException(nameof(dataSource));

    public async Task<OriginalWarehouseSnapshot> ReadAsync(Guid accountId, long characterId,
        OriginalWarehouseKey key, CancellationToken cancellationToken)
    {
        ValidateIdentity(accountId, characterId, key);
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        // One statement supplies a consistent header+balance+permission snapshot.
        await using var command = Command(connection, null, """
            SELECT w.version,b.stock_kind,b.item_kind,b.grade,b.quantity
            FROM original_warehouse w
            JOIN original_warehouse_access g USING(base_id,outfit_id)
            JOIN character c ON c.account_id=g.account_id AND c.character_id=g.character_id
            JOIN account a ON a.account_id=c.account_id AND a.status='active'
            LEFT JOIN original_warehouse_balance b ON b.base_id=w.base_id AND b.outfit_id=w.outfit_id AND b.quantity>0
            WHERE w.base_id=$1 AND w.outfit_id=$2 AND g.account_id=$3 AND g.character_id=$4
            ORDER BY b.stock_kind,b.item_kind,b.grade
            """, (long)key.BaseId, (long)key.OutfitId, accountId, characterId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken)) throw Denied();
        var version = reader.GetInt64(0);
        var balances = new Dictionary<OriginalStockKey, long>();
        do
        {
            if (!reader.IsDBNull(1))
                balances.Add(new((OriginalStockKind)reader.GetInt16(1),
                    checked((ushort)reader.GetInt32(2)), checked((byte)reader.GetInt16(3))), reader.GetInt64(4));
        } while (await reader.ReadAsync(cancellationToken));
        ValidateCapacity(balances);
        return new(key, version, new ReadOnlyDictionary<OriginalStockKey, long>(balances));
    }

    public async Task<OriginalWarehouseTransferResult> TransferAsync(Guid accountId,
        OriginalWarehouseTransfer write, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        // Freeze before opening a connection: caller mutation during the await
        // must not change the transfer which was submitted.
        write = write with { Lines = write.Lines?.ToArray()! };
        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var result = await TransferInTransactionAsync(transaction, accountId, write, cancellationToken);
        await transaction.CommitAsync(cancellationToken);
        return result;
    }

    /// <summary>
    /// Applies the transfer in the caller's transaction without committing it.
    /// Caller must roll back the entire transaction on any failure, including
    /// a later command-point or command-specific validation failure. Lock account
    /// before shared warehouses, and preserve the global warehouse key order.
    /// The result describes pending writes until the caller commits.
    /// </summary>
    public async Task<OriginalWarehouseTransferResult> TransferInTransactionAsync(
        NpgsqlTransaction transaction, Guid accountId, OriginalWarehouseTransfer write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(transaction);
        var connection = transaction.Connection ?? throw new ArgumentException("WAREHOUSE_TRANSACTION_INACTIVE", nameof(transaction));
        ArgumentNullException.ThrowIfNull(write);
        ValidateIdentity(accountId, write.CharacterId, write.Source);
        ValidateIdentity(accountId, write.CharacterId, write.Destination);
        if (write.RequestId == Guid.Empty || write.Source == write.Destination ||
            write.Lines is null || write.Lines.Count is < 1 or > 225)
            throw new ArgumentException("WAREHOUSE_TRANSFER_INVALID", nameof(write));
        // Freeze and canonicalize before any await; callers cannot change the
        // fingerprint and applied amounts by mutating their list concurrently.
        var lines = write.Lines.OrderBy(x => x.Stock.Kind).ThenBy(x => x.Stock.ItemKind).ThenBy(x => x.Stock.Grade).ToArray();
        var seen = new HashSet<OriginalStockKey>();
        foreach (var line in lines)
        {
            var max = Maximum(line.Stock);
            if (line.Quantity == 0 || line.Quantity < -max || line.Quantity > max || !seen.Add(line.Stock))
                throw new ArgumentException("WAREHOUSE_TRANSFER_INVALID", nameof(write));
        }
        var payload = JsonSerializer.Serialize(new
        {
            design = "shared-warehouse-transfer-v1", accountId, write.CharacterId,
            write.Source, write.Destination, lines
        });
        var fingerprint = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(payload)));

        long authorityVersion;
        string previousHash;
        // Account first matches character-deletion and other authority writes.
        await using (var account = Command(connection, transaction,
            "SELECT authority_version,authority_state_hash FROM account WHERE account_id=$1 AND status='active' FOR UPDATE", accountId))
        await using (var reader = await account.ExecuteReaderAsync(cancellationToken))
        {
            if (!await reader.ReadAsync(cancellationToken)) throw Denied();
            authorityVersion = reader.GetInt64(0);
            previousHash = reader.GetString(1).Trim();
        }

        // GLOBAL key order prevents A->B/B->A deadlocks across different accounts.
        var keys = new[] { write.Source, write.Destination }.OrderBy(k => k.BaseId).ThenBy(k => k.OutfitId).ToArray();
        var versions = new Dictionary<OriginalWarehouseKey, long>();
        foreach (var key in keys)
        {
            await using var warehouse = Command(connection, transaction, """
                SELECT w.version FROM original_warehouse w
                JOIN original_warehouse_access g USING(base_id,outfit_id)
                JOIN character c ON c.account_id=g.account_id AND c.character_id=g.character_id
                WHERE w.base_id=$1 AND w.outfit_id=$2 AND g.account_id=$3 AND g.character_id=$4 AND g.can_write
                FOR UPDATE OF w FOR SHARE OF g,c
                """, (long)key.BaseId, (long)key.OutfitId, accountId, write.CharacterId);
            var value = await warehouse.ExecuteScalarAsync(cancellationToken);
            if (value is null) throw Denied();
            versions.Add(key, (long)value);
        }
        await using (var replay = Command(connection, transaction, """
            SELECT character_id,request_fingerprint,source_version,destination_version,authority_version
            FROM original_warehouse_transfer_request WHERE account_id=$1 AND request_id=$2
            """, accountId, write.RequestId))
        await using (var reader = await replay.ExecuteReaderAsync(cancellationToken))
        {
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId || reader.GetString(1).Trim() != fingerprint)
                    throw new InvalidOperationException("WAREHOUSE_REQUEST_CONFLICT");
                var result = new OriginalWarehouseTransferResult(false, reader.GetInt64(2), reader.GetInt64(3), reader.GetInt64(4));
                await reader.DisposeAsync();
                return result; // Original receipt; never restore historical balances.
            }
        }

        var source = await Balances(connection, transaction, write.Source, cancellationToken);
        var destination = await Balances(connection, transaction, write.Destination, cancellationToken);
        // Validate every effect before issuing any stock writes.
        foreach (var line in lines)
        {
            var nextSource = source.GetValueOrDefault(line.Stock) - line.Quantity;
            var nextDestination = destination.GetValueOrDefault(line.Stock) + line.Quantity;
            if (nextSource < 0 || nextDestination < 0)
                throw new InvalidOperationException("WAREHOUSE_INSUFFICIENT_STOCK");
            if (nextSource > Maximum(line.Stock) || nextDestination > Maximum(line.Stock))
                throw new InvalidOperationException("WAREHOUSE_STOCK_OVERFLOW");
            source[line.Stock] = nextSource;
            destination[line.Stock] = nextDestination;
        }
        ValidateCapacity(source);
        ValidateCapacity(destination);
        var sourceVersion = checked(versions[write.Source] + 1);
        var destinationVersion = checked(versions[write.Destination] + 1);
        authorityVersion = checked(authorityVersion + 1);
        foreach (var line in lines)
        {
            await SetBalance(connection, transaction, write.Source, line.Stock, source[line.Stock], cancellationToken);
            await SetBalance(connection, transaction, write.Destination, line.Stock, destination[line.Stock], cancellationToken);
        }
        foreach (var (key, version) in new[] { (write.Source, sourceVersion), (write.Destination, destinationVersion) })
        {
            await using var update = Command(connection, transaction,
                "UPDATE original_warehouse SET version=$3 WHERE base_id=$1 AND outfit_id=$2",
                (long)key.BaseId, (long)key.OutfitId, version);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1) throw Denied();
        }
        var eventPayload = JsonSerializer.Serialize(new
        {
            write.RequestId, write.CharacterId, write.Source, write.Destination, lines,
            sourceVersion, destinationVersion, fingerprint
        });
        await using (var record = Command(connection, transaction, """
            INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version)
            VALUES($1,'warehouse',$2,'OriginalWarehouseTransferred',$3::jsonb,$4)
            """, accountId, write.RequestId.ToString("N"), eventPayload, authorityVersion))
            await record.ExecuteNonQueryAsync(cancellationToken);
        // NEW_DESIGN: chain the actor's prior authority hash to this committed
        // shared-stock event. Warehouse versions remain global, not per-account.
        var hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(
            "original-warehouse-transfer-v1\n" + previousHash + "\n" + eventPayload)));
        await using (var update = Command(connection, transaction,
            "UPDATE account SET authority_version=$2,authority_state_hash=$3,updated_at=transaction_timestamp() WHERE account_id=$1",
            accountId, authorityVersion, hash))
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1) throw Denied();
        await using (var receipt = Command(connection, transaction, """
            INSERT INTO original_warehouse_transfer_request
                (account_id,request_id,character_id,request_fingerprint,source_version,destination_version,authority_version)
            VALUES($1,$2,$3,$4,$5,$6,$7)
            """, accountId, write.RequestId, write.CharacterId, fingerprint, sourceVersion, destinationVersion, authorityVersion))
            await receipt.ExecuteNonQueryAsync(cancellationToken);
        return new(true, sourceVersion, destinationVersion, authorityVersion);
    }

    private static async Task<Dictionary<OriginalStockKey, long>> Balances(NpgsqlConnection connection,
        NpgsqlTransaction transaction, OriginalWarehouseKey key, CancellationToken cancellationToken)
    {
        await using var command = Command(connection, transaction, """
            SELECT stock_kind,item_kind,grade,quantity FROM original_warehouse_balance
            WHERE base_id=$1 AND outfit_id=$2 AND quantity>0
            """, (long)key.BaseId, (long)key.OutfitId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var result = new Dictionary<OriginalStockKey, long>();
        while (await reader.ReadAsync(cancellationToken))
            result.Add(new((OriginalStockKind)reader.GetInt16(0), checked((ushort)reader.GetInt32(1)),
                checked((byte)reader.GetInt16(2))), reader.GetInt64(3));
        return result;
    }

    private static async Task SetBalance(NpgsqlConnection connection, NpgsqlTransaction transaction,
        OriginalWarehouseKey warehouse, OriginalStockKey stock, long quantity, CancellationToken cancellationToken)
    {
        await using var command = Command(connection, transaction, """
            INSERT INTO original_warehouse_balance(base_id,outfit_id,stock_kind,item_kind,grade,quantity)
            VALUES($1,$2,$3,$4,$5,$6)
            ON CONFLICT(base_id,outfit_id,stock_kind,item_kind,grade) DO UPDATE SET quantity=EXCLUDED.quantity
            """, (long)warehouse.BaseId, (long)warehouse.OutfitId, (short)stock.Kind, (int)stock.ItemKind, (short)stock.Grade, quantity);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    private static long Maximum(OriginalStockKey key)
    {
        if (!Enum.IsDefined(key.Kind) || (key.Kind != OriginalStockKind.Troops && key.Grade != 0) ||
            (key.Kind >= OriginalStockKind.Supplies && key.ItemKind != 0))
            throw new ArgumentException("WAREHOUSE_STOCK_KEY_INVALID");
        return key.Kind switch
        {
            OriginalStockKind.ShipUnits => byte.MaxValue,
            OriginalStockKind.ShipBoats or OriginalStockKind.Troops => ushort.MaxValue,
            _ => uint.MaxValue
        };
    }

    private static void ValidateCapacity(IReadOnlyDictionary<OriginalStockKey, long> balances)
    {
        var positive = balances.Where(x => x.Value > 0).Select(x => x.Key).ToArray();
        if (positive.Where(k => k.Kind is OriginalStockKind.ShipUnits or OriginalStockKind.ShipBoats)
                .Select(k => k.ItemKind).Distinct().Count() > 99 ||
            positive.Count(k => k.Kind == OriginalStockKind.Troops) > 24)
            throw new InvalidOperationException("WAREHOUSE_CAPACITY_EXCEEDED");
    }

    private static void ValidateIdentity(Guid accountId, long characterId, OriginalWarehouseKey key)
    {
        if (accountId == Guid.Empty || characterId <= 0 || key.BaseId == 0)
            throw new ArgumentException("WAREHOUSE_IDENTITY_INVALID");
    }

    private static InvalidOperationException Denied() => new("WAREHOUSE_ACCESS_DENIED");

    private static NpgsqlCommand Command(NpgsqlConnection connection, NpgsqlTransaction? transaction,
        string sql, params object[] values)
    {
        var command = new NpgsqlCommand(sql, connection, transaction);
        foreach (var value in values) command.Parameters.AddWithValue(value);
        return command;
    }
}
