using System.Security.Cryptography;
using System.Text.Json;
using Logh7.Server.Storage;
using Npgsql;

// Hard-bound to a newly created disposable database; cannot target the game DB.
const string connection = "Host=127.0.0.1;Port=55433;Database=unit_base_full_schema_v1;Username=logh7;Pooling=false;SSL Mode=Disable;Timeout=5;Command Timeout=15";
if (args.Length != 1 || File.Exists(args[0])) return 2;
using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(90));
var ct = timeout.Token;
var checks = new List<string>();
var migrations = new List<object>();
string status = "FAILED";
string? error = null;
await using var source = NpgsqlDataSource.Create(connection);
void Check(bool value, string name)
{
    if (!value) throw new InvalidOperationException(name);
    checks.Add(name);
}
async Task Execute(string sql, params object[] values)
{
    await using var command = source.CreateCommand(sql);
    foreach (var value in values) command.Parameters.AddWithValue(value);
    await command.ExecuteNonQueryAsync(ct);
}
async Task<(Guid Account, long Character)> Seed(string login)
{
    var account = Guid.NewGuid();
    await Execute("""
        INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
            argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
        VALUES ($1,$2,decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),1,1,1,'active',1,repeat('0',64));
        """, account, login);
    await using var insert = source.CreateCommand("""
        INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,
            faction,blood,sex,last_name,first_name,flagship_name,face,ability_values,authority_version)
        VALUES ($1,0,repeat('a',64),repeat('a',64),0,0,0,'Storage','Probe','',0,
            ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id;
        """);
    insert.Parameters.AddWithValue(account);
    return (account, (long)(await insert.ExecuteScalarAsync(ct))!);
}
OriginalMoveGridWrite Move((Guid Account, long Character) actor, char fingerprint) =>
    new(new string(fingerprint,64), actor.Character,2,39,101,101,102,43);
async Task<Snapshot> State(Guid account)
{
    await using var command = source.CreateCommand("""
        SELECT u.base_id,u.current_cell_id,u.authority_version,a.authority_version,
          (SELECT count(*) FROM original_grid_move_command WHERE account_id=$1),
          (SELECT count(*) FROM domain_event WHERE account_id=$1),a.authority_state_hash
        FROM original_grid_unit u JOIN account a USING(account_id) WHERE u.account_id=$1;
        """);
    command.Parameters.AddWithValue(account);
    await using var reader = await command.ExecuteReaderAsync(ct);
    if (!await reader.ReadAsync(ct)) throw new InvalidOperationException("SNAPSHOT_MISSING");
    return new(reader.GetInt64(0),reader.GetInt64(1),reader.GetInt64(2),reader.GetInt64(3),
        reader.GetInt64(4),reader.GetInt64(5),reader.GetString(6));
}
try
{
    await using (var guard = source.CreateCommand(
        "SELECT current_database(),(SELECT count(*) FROM pg_tables WHERE schemaname='public');"))
    await using (var reader = await guard.ExecuteReaderAsync(ct))
    {
        await reader.ReadAsync(ct);
        Check(reader.GetString(0) == "unit_base_full_schema_v1" && reader.GetInt64(1) == 0,
            "FRESH_DISPOSABLE_DATABASE_ONLY");
    }
    var paths = Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql").Order(StringComparer.Ordinal).ToArray();
    Check(paths.Any(path=>Path.GetFileName(path).StartsWith("0017_",StringComparison.Ordinal)),
        "ASSOCIATION_MIGRATION_INCLUDED");
    foreach (var path in paths)
    {
        await PostgresMigrationRunner.ApplyAsync(source,path,ct);
        migrations.Add(new { file=Path.GetFileName(path),sha256=Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(path,ct))) });
    }
    // Applying the migration runner twice must not repeat the Base backfill.
    var first = await Seed("base_store_first");
    var rollback = await Seed("base_store_rollback");
    var other = await Seed("base_store_other");
    var store = new PostgresAccountStore(source);
    var initial = await store.FindOriginalGridUnitAsync(first.Account,first.Character,2,ct);
    Check(initial is {BaseId:1,CurrentCellId:101}, "STORE_READS_TRIGGER_SEEDED_BASE");
    await Execute("UPDATE original_grid_unit SET base_id=7 WHERE account_id=$1;", first.Account);
    await PostgresMigrationRunner.ApplyAllAsync(source,PostgresMigrationRunner.MigrationDirectory,ct);
    Check((await store.FindOriginalGridUnitAsync(first.Account,first.Character,2,ct))?.BaseId == 7,
        "MIGRATION_REAPPLY_DOES_NOT_RESET_ASSOCIATION");

    var write = Move(first,'a');
    var moved = await store.MoveOriginalGridUnitAsync(first.Account,write,ct);
    Check(moved.Status == OriginalMoveGridStoreStatus.Moved &&
        moved.Unit is {BaseId:0,CurrentCellId:102,AuthorityVersion:2} && moved.AuthorityVersion == 2,
        "REAL_STORE_MOVE_RETURNS_CLEARED_BASE");
    var afterMove = await State(first.Account);
    Check(afterMove is {Base:0,Cell:102,UnitVersion:2,AccountVersion:2,Commands:1,Events:1},
        "GRID_BASE_VERSIONS_HISTORY_COMMITTED_TOGETHER");
    await using (var fresh = NpgsqlDataSource.Create(connection))
    {
        var loaded = await new PostgresAccountStore(fresh).FindOriginalGridUnitAsync(first.Account,first.Character,2,ct);
        Check(loaded is {BaseId:0,CurrentCellId:102,AuthorityVersion:2},
            "NEW_SOURCE_STORE_AND_CONNECTION_RESTORE_BASE");
    }
    await using (var ev = source.CreateCommand(
        "SELECT payload->>'sourceBaseId',payload->>'destinationBaseId' FROM domain_event WHERE account_id=$1 AND event_type='OriginalGridUnitMoved';"))
    {
        ev.Parameters.AddWithValue(first.Account);
        await using var reader = await ev.ExecuteReaderAsync(ct);
        Check(await reader.ReadAsync(ct) && reader.GetString(0) == "7" && reader.GetString(1) == "0",
            "MOVE_EVENT_RECORDS_SOURCE_AND_DESTINATION_BASE");
    }
    // Simulate a later association: replay must return the historical result, not this value.
    await Execute("UPDATE original_grid_unit SET base_id=9 WHERE account_id=$1;",first.Account);
    var beforeReplay = await State(first.Account);
    var replay = await store.MoveOriginalGridUnitAsync(first.Account,write,ct);
    Check(replay.Status == OriginalMoveGridStoreStatus.Replayed &&
        replay.Unit is {BaseId:0,CurrentCellId:102,AuthorityVersion:2}, "REPLAY_RETURNS_HISTORICAL_BASE");
    Check(await State(first.Account) == beforeReplay, "REPLAY_HAS_NO_WRITE_OR_DUPLICATE_EVENT");
    var conflict = await store.MoveOriginalGridUnitAsync(first.Account,write with {DestinationCellId=999},ct);
    Check(conflict.Status == OriginalMoveGridStoreStatus.Rejected &&
        conflict.ErrorCode == "MOVE_GRID_FINGERPRINT_CONFLICT" && await State(first.Account) == beforeReplay,
        "CONFLICT_REJECTED_WITHOUT_MUTATION");
    var stale = await store.MoveOriginalGridUnitAsync(first.Account,Move(first,'b'),ct);
    Check(stale.Status == OriginalMoveGridStoreStatus.Rejected &&
        stale.ErrorCode == "MOVE_GRID_SOURCE_STALE" && await State(first.Account) == beforeReplay,
        "STALE_SOURCE_REJECTED_WITHOUT_MUTATION");
    Check(await store.FindOriginalGridUnitAsync(other.Account,first.Character,2,ct) is null,
        "CROSS_ACCOUNT_READ_REJECTED");
    var beforeOther = await State(other.Account);
    var denied = await store.MoveOriginalGridUnitAsync(other.Account,write with {RequestFingerprint=new string('c',64)},ct);
    Check(denied.Status == OriginalMoveGridStoreStatus.Rejected && await State(other.Account) == beforeOther,
        "CROSS_ACCOUNT_MOVE_REJECTED_WITHOUT_MUTATION");

    await Execute("UPDATE original_grid_unit SET base_id=7 WHERE account_id=$1;",rollback.Account);
    var beforeFailure = await State(rollback.Account);
    await Execute($"""
        CREATE FUNCTION probe_reject_move_event() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'storage probe intentional late failure' USING ERRCODE='P0001'; END; $$;
        CREATE TRIGGER probe_late_failure BEFORE INSERT ON domain_event
        FOR EACH ROW WHEN (NEW.account_id='{rollback.Account:D}'::uuid)
        EXECUTE FUNCTION probe_reject_move_event();
        """);
    var failedAsExpected = false;
    try { await store.MoveOriginalGridUnitAsync(rollback.Account,Move(rollback,'d'),ct); }
    catch (PostgresException ex) when (ex.SqlState == "P0001") { failedAsExpected = true; }
    Check(failedAsExpected, "LATE_FAILURE_REACHED_AFTER_UNIT_AND_HISTORY_WRITES");
    Check(await State(rollback.Account) == beforeFailure,
        "LATE_FAILURE_ROLLS_BACK_BASE_GRID_VERSIONS_HISTORY_AND_EVENT");
    status = "POSTGRES_ACCOUNT_STORE_BASE_PASS";
}
catch (Exception ex) { error = ex.ToString(); }
await File.WriteAllTextAsync(args[0],JsonSerializer.Serialize(new
{
    status,error,checks,migrations,database="unit_base_full_schema_v1",port=55433,
    gameDatabaseTouched=false,gameInputs=0,utc=DateTime.UtcNow,
    serverAssemblySha256=Convert.ToHexString(SHA256.HashData(
        await File.ReadAllBytesAsync(typeof(PostgresAccountStore).Assembly.Location))),
},new JsonSerializerOptions{WriteIndented=true}));
return status == "POSTGRES_ACCOUNT_STORE_BASE_PASS" ? 0 : 1;

internal sealed record Snapshot(long Base,long Cell,long UnitVersion,long AccountVersion,long Commands,long Events,string Hash);
