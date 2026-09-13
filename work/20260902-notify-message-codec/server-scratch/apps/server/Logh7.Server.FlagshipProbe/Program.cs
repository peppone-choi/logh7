using System.Text.Json;
using Logh7.Server.Storage;
using Npgsql;

// Dedicated disposable PostgreSQL cluster only; never accepts a live DB connection.
await using var source = NpgsqlDataSource.Create(
    "Host=127.0.0.1;Port=55433;Database=postgres;Username=flagship_probe;SSL Mode=Disable;Timeout=5");
var cancellation = CancellationToken.None;
var migrations = PostgresMigrationRunner.MigrationDirectory;
foreach (var path in Directory.GetFiles(migrations, "*.sql").Order(StringComparer.Ordinal))
{
    if (Path.GetFileName(path).StartsWith("0016_", StringComparison.Ordinal)) continue;
    await PostgresMigrationRunner.ApplyAsync(source, path, cancellation);
}
var accountId = Guid.NewGuid();
await using (var seed = source.CreateCommand("""
    INSERT INTO account(account_id, normalized_login, password_hash, password_salt,
        argon_memory_kib, argon_iterations, argon_parallelism, status,
        authority_version, authority_state_hash)
    VALUES ($1, 'flagship_probe', decode(repeat('00',32),'hex'), decode(repeat('00',16),'hex'),
        1, 1, 1, 'active', 1, repeat('0',64));
    """))
{
    seed.Parameters.AddWithValue(accountId);
    await seed.ExecuteNonQueryAsync(cancellation);
}
await using (var seed = source.CreateCommand("""
    INSERT INTO character(account_id, slot, request_fingerprint, payload_hash,
        faction, blood, sex, last_name, first_name, flagship_name, face, ability_values, authority_version)
    VALUES ($1, 0, repeat('a',64), repeat('a',64), 0, 0, 0, 'Legacy', 'Unknown', '', 0,
        ARRAY[1,2,3,4,5,6,7,8]::smallint[], 1)
    """))
{
    seed.Parameters.AddWithValue(accountId);
    await seed.ExecuteNonQueryAsync(cancellation);
}
await PostgresMigrationRunner.ApplyAllAsync(source, migrations, cancellation);
var store = new PostgresAccountStore(source);
var legacy = (await store.ListCharactersAsync(accountId, cancellation)).Single();
Check(legacy.FlagshipKind is null && legacy.FlagshipType is null, "LEGACY_UNKNOWN");

var write = new CharacterCreateWrite(new string('b', 64), new string('b', 64),
    1, 0, 0, "Selected", "Ship", "Probe", 1, [1,2,3,4,5,6,7,8], 255, 65535);
var created = await store.CreateCharacterAsync(accountId, write, cancellation);
Check(created.Created, "CREATED");
var replay = await store.CreateCharacterAsync(accountId, write, cancellation);
Check(!replay.Created && replay.CharacterId == created.CharacterId &&
      replay.AuthorityVersion == created.AuthorityVersion, "IDEMPOTENT_REPLAY");

// New data source/store forces a read back from PostgreSQL, not an in-memory DTO.
await using var reconnected = NpgsqlDataSource.Create(source.ConnectionString);
var rows = await new PostgresAccountStore(reconnected).ListCharactersAsync(accountId, cancellation);
var loaded = rows.Single(row => row.CharacterId == created.CharacterId);
Check(loaded.FlagshipType == 255 && loaded.FlagshipKind == 65535 &&
      loaded.FlagshipName == "Probe" && loaded.Rank == 20, "RECONNECTED_READ");
Check(rows.Single(row => row.CharacterId == legacy.CharacterId).FlagshipKind is null,
    "LEGACY_STAYS_UNKNOWN");
await using (var readEvent = source.CreateCommand(
    "SELECT payload->>'FlagshipType', payload->>'FlagshipKind' FROM domain_event WHERE account_id=$1 AND event_type='CharacterCreated'"))
{
    readEvent.Parameters.AddWithValue(accountId);
    await using var reader = await readEvent.ExecuteReaderAsync(cancellation);
    Check(await reader.ReadAsync(cancellation) && reader.GetString(0) == "255" &&
          reader.GetString(1) == "65535", "EVENT_PRESERVES_SELECTION");
    Check(!await reader.ReadAsync(cancellation), "ONE_CREATE_EVENT");
}
var rejectedPartial = false;
try
{
    await using var invalid = source.CreateCommand(
        "UPDATE character SET flagship_kind=NULL WHERE character_id=$1");
    invalid.Parameters.AddWithValue(created.CharacterId);
    await invalid.ExecuteNonQueryAsync(cancellation);
}
catch (PostgresException error) when (error.SqlState == PostgresErrorCodes.CheckViolation)
{
    rejectedPartial = true;
}
Check(rejectedPartial, "PARTIAL_SELECTION_REJECTED");
Console.WriteLine(JsonSerializer.Serialize(new {
    status = "FLAGSHIP_POSTGRES_ROUNDTRIP_PASS", legacyUnknown = true,
    persistedType = loaded.FlagshipType, persistedKind = loaded.FlagshipKind,
    idempotent = true, eventVerified = true, pairConstraintVerified = true,
    liveGameDatabaseTouched = false, restoredOriginalModelVerified = false
}));

static void Check(bool passed, string boundary)
{
    if (!passed) throw new InvalidOperationException("FLAGSHIP_PROBE_FAILED:" + boundary);
}
