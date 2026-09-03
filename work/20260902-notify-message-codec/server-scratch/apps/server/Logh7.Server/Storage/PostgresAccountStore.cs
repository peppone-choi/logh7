using Logh7.Server.Authority;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Security;
using Npgsql;
using NpgsqlTypes;
using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Logh7.Server.Storage;

public sealed class PostgresAccountStore : IAccountStore
{
    private readonly NpgsqlDataSource _dataSource;

    public PostgresAccountStore(NpgsqlDataSource dataSource)
    {
        _dataSource = dataSource ?? throw new ArgumentNullException(nameof(dataSource));
    }

    public async Task<AccountRecord> ProvisionAsync(
        AccountProvision provision,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(provision);
        if (!LoginNamePolicy.TryNormalize(
                provision.NormalizedLogin.Select(character => (ushort)character).ToArray(),
                out var normalized) ||
            !string.Equals(normalized, provision.NormalizedLogin, StringComparison.Ordinal))
        {
            throw new ArgumentException("LOGIN_NAME_POLICY_REJECTED", nameof(provision));
        }

        var accountId = Guid.NewGuid();
        var stateHash = AuthorityStateHash.EmptyAccount(accountId);
        const string sql = """
            INSERT INTO account(
                account_id, normalized_login, password_hash, password_salt,
                argon_memory_kib, argon_iterations, argon_parallelism,
                status, authority_version, authority_state_hash)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 0, $9)
            """;
        try
        {
            await using var command = _dataSource.CreateCommand(sql);
            command.Parameters.AddWithValue(accountId);
            command.Parameters.AddWithValue(normalized);
            command.Parameters.AddWithValue(NpgsqlDbType.Bytea, provision.Password.Hash);
            command.Parameters.AddWithValue(NpgsqlDbType.Bytea, provision.Password.Salt);
            command.Parameters.AddWithValue(provision.Password.MemoryKiB);
            command.Parameters.AddWithValue(provision.Password.Iterations);
            command.Parameters.AddWithValue(provision.Password.Parallelism);
            command.Parameters.AddWithValue(ToDatabaseStatus(provision.Status));
            command.Parameters.AddWithValue(stateHash);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }
        catch (PostgresException exception) when (exception.SqlState == PostgresErrorCodes.UniqueViolation)
        {
            throw new AccountAlreadyExistsException();
        }

        return new AccountRecord(
            accountId,
            normalized,
            ClonePassword(provision.Password),
            provision.Status,
            0,
            stateHash);
    }

    public async Task<AccountRecord?> FindAccountAsync(
        string normalizedLogin,
        CancellationToken cancellationToken)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(normalizedLogin);
        const string sql = """
            SELECT account_id, normalized_login, password_hash, password_salt,
                   argon_memory_kib, argon_iterations, argon_parallelism,
                   status, authority_version, authority_state_hash
            FROM account
            WHERE normalized_login = $1
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(normalizedLogin);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new AccountRecord(
            reader.GetGuid(0),
            reader.GetString(1),
            new PasswordHashRecord(
                reader.GetFieldValue<byte[]>(3),
                reader.GetFieldValue<byte[]>(2),
                reader.GetInt32(4),
                reader.GetInt32(5),
                reader.GetInt32(6)),
            FromDatabaseStatus(reader.GetString(7)),
            reader.GetInt64(8),
            reader.GetString(9).Trim());
    }

    public async Task<int> CountCharactersAsync(
        Guid accountId,
        CancellationToken cancellationToken)
    {
        await using var command = _dataSource.CreateCommand(
            "SELECT count(*) FROM character WHERE account_id = $1");
        command.Parameters.AddWithValue(accountId);
        var count = (long)(await command.ExecuteScalarAsync(cancellationToken) ?? 0L);
        return checked((int)count);
    }

    public async Task<IReadOnlyList<CharacterReadRecord>> ListCharactersAsync(
        Guid accountId,
        CancellationToken cancellationToken)
    {
        const string sql = """
            SELECT character_id, slot, faction, blood, sex,
                   last_name, first_name, flagship_name, face, ability_values, rank
            FROM character
            WHERE account_id = $1
            ORDER BY slot
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var characters = new List<CharacterReadRecord>();
        while (await reader.ReadAsync(cancellationToken))
        {
            characters.Add(new CharacterReadRecord(
                reader.GetInt64(0),
                reader.GetInt16(1),
                reader.GetInt16(2),
                reader.GetInt16(3),
                reader.GetInt16(4),
                reader.GetString(5),
                reader.GetString(6),
                reader.GetString(7),
                reader.GetInt32(8),
                reader.GetFieldValue<short[]>(9),
                reader.GetInt16(10)));
        }

        return characters;
    }

    public async Task<CharacterCreateStoreResult> CreateCharacterAsync(
        Guid accountId,
        CharacterCreateWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.AbilityValues.Length != 8)
        {
            throw new ArgumentException("CHARACTER_ABILITY_COUNT", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            var value = await account.ExecuteScalarAsync(cancellationToken);
            if (value is null)
            {
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND");
            }

            currentVersion = (long)value;
        }

        await using (var existing = new NpgsqlCommand(
            "SELECT character_id, authority_version FROM character WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            existing.Parameters.AddWithValue(accountId);
            existing.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await existing.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                var result = new CharacterCreateStoreResult(
                    reader.GetInt64(0), false, reader.GetInt64(1));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        short slot;
        await using (var count = new NpgsqlCommand(
            "SELECT count(*) FROM character WHERE account_id = $1",
            connection,
            transaction))
        {
            count.Parameters.AddWithValue(accountId);
            var characterCount = (long)(await count.ExecuteScalarAsync(cancellationToken) ?? 0L);
            if (characterCount >= 2)
            {
                throw new InvalidOperationException("CHARACTER_SLOT_LIMIT");
            }

            slot = checked((short)characterCount);
        }

        var nextVersion = checked(currentVersion + 1);
        long characterId;
        const string insertCharacter = """
            INSERT INTO character(
                account_id, slot, request_fingerprint, payload_hash,
                faction, blood, sex, last_name, first_name, flagship_name,
                face, ability_values, authority_version)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
            RETURNING character_id
            """;
        await using (var insert = new NpgsqlCommand(insertCharacter, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(slot);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(write.PayloadHash);
            insert.Parameters.AddWithValue(write.Faction);
            insert.Parameters.AddWithValue(write.Blood);
            insert.Parameters.AddWithValue(write.Sex);
            insert.Parameters.AddWithValue(write.LastName);
            insert.Parameters.AddWithValue(write.FirstName);
            insert.Parameters.AddWithValue(write.FlagshipName);
            insert.Parameters.AddWithValue(write.Face);
            insert.Parameters.AddWithValue(
                NpgsqlDbType.Array | NpgsqlDbType.Smallint,
                write.AbilityValues);
            insert.Parameters.AddWithValue(nextVersion);
            characterId = (long)(await insert.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("CHARACTER_INSERT_NO_ID"));
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId,
            slot,
            write.Faction,
            write.Blood,
            write.Sex,
            write.LastName,
            write.FirstName,
            write.FlagshipName,
            write.Face,
            abilityValues = write.AbilityValues
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'CharacterCreated', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterCreated(
            accountId, nextVersion, characterId, write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CharacterCreateStoreResult(characterId, true, nextVersion);
    }

    public async Task<IReadOnlyList<CharacterCardRecord>> ListCharacterCardsAsync(
        Guid accountId,
        CancellationToken cancellationToken)
    {
        const string sql = "SELECT character_id, card_id, appointed_by_character_id, authority_version FROM original_character_card WHERE account_id = $1 ORDER BY character_id";
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        var rows = new List<CharacterCardRecord>();
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        while (await reader.ReadAsync(cancellationToken))
        {
            rows.Add(new CharacterCardRecord(reader.GetInt64(0), reader.GetInt32(1), reader.GetInt64(2), reader.GetInt64(3)));
        }

        return rows;
    }

    public async Task<CardAppointmentStoreResult> AppointCardAsync(
        Guid accountId,
        CardAppointmentWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')) ||
            write.CharacterId <= 0 ||
            write.TargetCharacterId <= 0 ||
            write.CardId is <= 0 or > 65535)
        {
            throw new ArgumentException("CARD_APPOINTMENT_WRITE", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT character_id, card_id, target_character_id, authority_version FROM original_card_appointment WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId ||
                    reader.GetInt32(1) != write.CardId ||
                    reader.GetInt64(2) != write.TargetCharacterId)
                {
                    throw new InvalidOperationException("CARD_APPOINTMENT_REPLAY_MISMATCH");
                }

                var result = new CardAppointmentStoreResult(
                    write.TargetCharacterId,
                    write.CardId,
                    false,
                    reader.GetInt64(3));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        foreach (var id in new[] { write.CharacterId, write.TargetCharacterId })
        {
            await using var exists = new NpgsqlCommand(
                "SELECT 1 FROM character WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
                connection,
                transaction);
            exists.Parameters.AddWithValue(accountId);
            exists.Parameters.AddWithValue(id);
            if (await exists.ExecuteScalarAsync(cancellationToken) is null)
            {
                throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            }
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var insert = new NpgsqlCommand(
            "INSERT INTO original_card_appointment(account_id, character_id, card_id, target_character_id, request_fingerprint, authority_version) VALUES ($1, $2, $3, $4, $5, $6)",
            connection,
            transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.CharacterId);
            insert.Parameters.AddWithValue(write.CardId);
            insert.Parameters.AddWithValue(write.TargetCharacterId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(nextVersion);
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var upsert = new NpgsqlCommand(
            "INSERT INTO original_character_card(account_id, character_id, card_id, appointed_by_character_id, authority_version) VALUES ($1, $2, $3, $4, $5) ON CONFLICT (account_id, character_id) DO UPDATE SET card_id = EXCLUDED.card_id, appointed_by_character_id = EXCLUDED.appointed_by_character_id, authority_version = EXCLUDED.authority_version, updated_at = transaction_timestamp()",
            connection,
            transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue(write.TargetCharacterId);
            upsert.Parameters.AddWithValue(write.CardId);
            upsert.Parameters.AddWithValue(write.CharacterId);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            cardId = write.CardId,
            targetCharacterId = write.TargetCharacterId,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'character', $2, 'CharacterCardAppointed', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.TargetCharacterId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterCardAppointed(
            accountId,
            nextVersion,
            write.CharacterId,
            write.CardId,
            write.TargetCharacterId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CardAppointmentStoreResult(write.TargetCharacterId, write.CardId, true, nextVersion);
    }

    // 罷免 (0x0708): the inverse of AppointCardAsync — remove the target's current appointment (original_character_card
    // row) and record the dismissal for replay + audit. The target reverts to their statically-served base card.
    public async Task<CardDismissalStoreResult> DismissCardAsync(
        Guid accountId,
        CardDismissalWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')) ||
            write.CharacterId <= 0 ||
            write.TargetCharacterId <= 0 ||
            write.CardId is <= 0 or > 65535)
        {
            throw new ArgumentException("CARD_DISMISSAL_WRITE", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT character_id, card_id, target_character_id, authority_version FROM original_card_dismissal_command WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId ||
                    reader.GetInt32(1) != write.CardId ||
                    reader.GetInt64(2) != write.TargetCharacterId)
                {
                    throw new InvalidOperationException("CARD_DISMISSAL_REPLAY_MISMATCH");
                }

                var result = new CardDismissalStoreResult(
                    write.TargetCharacterId,
                    write.CardId,
                    false,
                    reader.GetInt64(3));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        await using (var held = new NpgsqlCommand(
            "SELECT card_id FROM original_character_card WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            held.Parameters.AddWithValue(accountId);
            held.Parameters.AddWithValue(write.TargetCharacterId);
            var current = await held.ExecuteScalarAsync(cancellationToken);
            if (current is null)
            {
                throw new InvalidOperationException("CARD_APPOINTMENT_NOT_FOUND");
            }

            if ((int)current != write.CardId)
            {
                throw new InvalidOperationException("CARD_APPOINTMENT_CARD_MISMATCH");
            }
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var delete = new NpgsqlCommand(
            "DELETE FROM original_character_card WHERE account_id = $1 AND character_id = $2",
            connection,
            transaction))
        {
            delete.Parameters.AddWithValue(accountId);
            delete.Parameters.AddWithValue(write.TargetCharacterId);
            if (await delete.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("CARD_APPOINTMENT_NOT_FOUND");
            }
        }

        await using (var insert = new NpgsqlCommand(
            "INSERT INTO original_card_dismissal_command(account_id, character_id, card_id, target_character_id, request_fingerprint, authority_version) VALUES ($1, $2, $3, $4, $5, $6)",
            connection,
            transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.CharacterId);
            insert.Parameters.AddWithValue(write.CardId);
            insert.Parameters.AddWithValue(write.TargetCharacterId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(nextVersion);
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            cardId = write.CardId,
            targetCharacterId = write.TargetCharacterId,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'character', $2, 'CharacterCardDismissed', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.TargetCharacterId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterCardDismissed(
            accountId,
            nextVersion,
            write.CharacterId,
            write.CardId,
            write.TargetCharacterId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CardDismissalStoreResult(write.TargetCharacterId, write.CardId, true, nextVersion);
    }

    // 辞任 (0x0709): the character resigns from the post they currently hold. The resulting state is card 0 = 個人,
    // the ORIGINAL's own "holds no post" value (constmsg group 3 row 0; the client renders 「皇宮 ： 個人」 with an
    // empty command grid). defaultCardId is the authored card a character holds when no appointment row exists.
    public async Task<CardResignationStoreResult> ResignCardAsync(
        Guid accountId,
        CardResignationWrite write,
        int defaultCardId,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')) ||
            write.CharacterId <= 0 ||
            write.SourceCardId is <= 0 or > 65535)
        {
            throw new ArgumentException("CARD_RESIGNATION_WRITE", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT character_id, source_card_id, authority_version FROM original_card_resignation_command WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId ||
                    reader.GetInt32(1) != write.SourceCardId)
                {
                    throw new InvalidOperationException("CARD_RESIGNATION_REPLAY_MISMATCH");
                }

                var result = new CardResignationStoreResult(
                    write.CharacterId,
                    write.SourceCardId,
                    false,
                    reader.GetInt64(2));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        await using (var exists = new NpgsqlCommand(
            "SELECT 1 FROM character WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            exists.Parameters.AddWithValue(accountId);
            exists.Parameters.AddWithValue(write.CharacterId);
            if (await exists.ExecuteScalarAsync(cancellationToken) is null)
            {
                throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            }
        }

        // The card the character actually holds right now: the appointment row when one exists, otherwise the
        // authored default. It must match the post the client asked to resign from.
        int heldCardId;
        await using (var held = new NpgsqlCommand(
            "SELECT card_id FROM original_character_card WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            held.Parameters.AddWithValue(accountId);
            held.Parameters.AddWithValue(write.CharacterId);
            heldCardId = await held.ExecuteScalarAsync(cancellationToken) is int value ? value : defaultCardId;
        }

        if (heldCardId == 0)
        {
            throw new InvalidOperationException("CARD_ALREADY_RESIGNED");
        }

        if (heldCardId != write.SourceCardId)
        {
            throw new InvalidOperationException("CARD_RESIGNATION_CARD_MISMATCH");
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var upsert = new NpgsqlCommand(
            "INSERT INTO original_character_card(account_id, character_id, card_id, appointed_by_character_id, authority_version) VALUES ($1, $2, 0, $2, $3) ON CONFLICT (account_id, character_id) DO UPDATE SET card_id = 0, appointed_by_character_id = EXCLUDED.appointed_by_character_id, authority_version = EXCLUDED.authority_version, updated_at = transaction_timestamp()",
            connection,
            transaction))
        {
            upsert.Parameters.AddWithValue(accountId);
            upsert.Parameters.AddWithValue(write.CharacterId);
            upsert.Parameters.AddWithValue(nextVersion);
            await upsert.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var insert = new NpgsqlCommand(
            "INSERT INTO original_card_resignation_command(account_id, character_id, source_card_id, request_fingerprint, authority_version) VALUES ($1, $2, $3, $4, $5)",
            connection,
            transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.CharacterId);
            insert.Parameters.AddWithValue(write.SourceCardId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(nextVersion);
            await insert.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            sourceCardId = write.SourceCardId,
            resultingCardId = 0,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'character', $2, 'CharacterCardResigned', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.CharacterId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterCardResigned(
            accountId,
            nextVersion,
            write.CharacterId,
            write.SourceCardId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CardResignationStoreResult(write.CharacterId, write.SourceCardId, true, nextVersion);
    }

    public async Task<CharacterRankUpStoreResult> PromoteCharacterAsync(
        Guid accountId,
        CharacterRankUpWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')) ||
            write.CharacterId <= 0 ||
            (write.EventType == "CharacterDemoted"
                ? (write.ExpectedRank < 1 || write.PromotedRank != write.ExpectedRank + 1)
                : (write.ExpectedRank <= 1 || write.PromotedRank != write.ExpectedRank - 1)))
        {
            throw new ArgumentException("CHARACTER_RANK_UP_WRITE", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        var demotion = write.EventType == "CharacterDemoted";
        await using (var replay = new NpgsqlCommand(
            demotion
                ? "SELECT character_id, source_rank, demoted_rank, authority_version FROM character_rank_down_command WHERE account_id = $1 AND request_fingerprint = $2"
                : "SELECT character_id, source_rank, promoted_rank, authority_version FROM character_rank_command WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId ||
                    reader.GetInt16(1) != write.ExpectedRank ||
                    reader.GetInt16(2) != write.PromotedRank)
                {
                    throw new InvalidOperationException("CHARACTER_RANK_UP_REPLAY_MISMATCH");
                }

                var result = new CharacterRankUpStoreResult(
                    write.CharacterId,
                    write.PromotedRank,
                    false,
                    reader.GetInt64(3));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        short currentRank;
        await using (var character = new NpgsqlCommand(
            "SELECT rank FROM character WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            character.Parameters.AddWithValue(accountId);
            character.Parameters.AddWithValue(write.CharacterId);
            var value = await character.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            currentRank = (short)value;
        }

        if (currentRank != write.ExpectedRank)
        {
            throw new InvalidOperationException("CHARACTER_RANK_CONFLICT");
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var update = new NpgsqlCommand(
            "UPDATE character SET rank = $3, authority_version = $4 WHERE account_id = $1 AND character_id = $2 AND rank = $5",
            connection,
            transaction))
        {
            update.Parameters.AddWithValue(accountId);
            update.Parameters.AddWithValue(write.CharacterId);
            update.Parameters.AddWithValue(write.PromotedRank);
            update.Parameters.AddWithValue(nextVersion);
            update.Parameters.AddWithValue(write.ExpectedRank);
            if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("CHARACTER_RANK_UPDATE_FAILED");
            }
        }

        await using (var command = new NpgsqlCommand(
            demotion
                ? "INSERT INTO character_rank_down_command(account_id, character_id, actor_character_id, request_fingerprint, source_rank, demoted_rank, authority_version) VALUES ($1, $2, $7, $3, $4, $5, $6)"
                : "INSERT INTO character_rank_command(account_id, character_id, request_fingerprint, source_rank, promoted_rank, authority_version) VALUES ($1, $2, $3, $4, $5, $6)",
            connection,
            transaction))
        {
            command.Parameters.AddWithValue(accountId);
            command.Parameters.AddWithValue(write.CharacterId);
            command.Parameters.AddWithValue(write.RequestFingerprint);
            command.Parameters.AddWithValue(write.ExpectedRank);
            command.Parameters.AddWithValue(write.PromotedRank);
            command.Parameters.AddWithValue(nextVersion);
            if (demotion)
            {
                command.Parameters.AddWithValue(write.ActorCharacterId);
            }

            await command.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            sourceRank = write.ExpectedRank,
            promotedRank = write.PromotedRank,
            actorCharacterId = write.ActorCharacterId,
            requestFingerprint = write.RequestFingerprint
        });
        if (write.EventType is not ("CharacterRankPromoted" or "CharacterSpeciallyPromoted" or "CharacterDemoted"))
        {
            throw new ArgumentException("CHARACTER_RANK_UP_EVENT_TYPE", nameof(write));
        }

        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'character', $2, '" + write.EventType + "', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.CharacterId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterRankPromoted(
            accountId,
            nextVersion,
            write.CharacterId,
            write.ExpectedRank,
            write.PromotedRank,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CharacterRankUpStoreResult(
            write.CharacterId,
            write.PromotedRank,
            true,
            nextVersion);
    }

    public async Task<CharacterDeleteStoreResult> DeleteCharacterAsync(
        Guid accountId,
        CharacterDeleteWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')) ||
            write.CharacterId <= 0 ||
            write.SessionId == 0)
        {
            throw new ArgumentException("CHARACTER_DELETE_WRITE", nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT character_id, source_slot, session_id, authority_version FROM character_delete_command WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(0) != write.CharacterId ||
                    reader.GetInt64(2) != write.SessionId)
                {
                    throw new InvalidOperationException("CHARACTER_DELETE_REPLAY_MISMATCH");
                }

                var result = new CharacterDeleteStoreResult(
                    write.CharacterId,
                    reader.GetInt16(1),
                    false,
                    reader.GetInt64(3));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        short sourceSlot;
        await using (var character = new NpgsqlCommand(
            "SELECT slot FROM character WHERE account_id = $1 AND character_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            character.Parameters.AddWithValue(accountId);
            character.Parameters.AddWithValue(write.CharacterId);
            var value = await character.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            sourceSlot = (short)value;
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var deleteRanks = new NpgsqlCommand(
            "DELETE FROM character_rank_command WHERE account_id = $1 AND character_id = $2",
            connection,
            transaction))
        {
            deleteRanks.Parameters.AddWithValue(accountId);
            deleteRanks.Parameters.AddWithValue(write.CharacterId);
            await deleteRanks.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var delete = new NpgsqlCommand(
            "DELETE FROM character WHERE account_id = $1 AND character_id = $2",
            connection,
            transaction))
        {
            delete.Parameters.AddWithValue(accountId);
            delete.Parameters.AddWithValue(write.CharacterId);
            if (await delete.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("CHARACTER_DELETE_FAILED");
            }
        }

        await using (var compact = new NpgsqlCommand(
            "UPDATE character SET slot = slot - 1, authority_version = $3 WHERE account_id = $1 AND slot > $2",
            connection,
            transaction))
        {
            compact.Parameters.AddWithValue(accountId);
            compact.Parameters.AddWithValue(sourceSlot);
            compact.Parameters.AddWithValue(nextVersion);
            await compact.ExecuteNonQueryAsync(cancellationToken);
        }

        await using (var command = new NpgsqlCommand(
            "INSERT INTO character_delete_command(account_id, character_id, request_fingerprint, source_slot, session_id, authority_version) VALUES ($1, $2, $3, $4, $5, $6)",
            connection,
            transaction))
        {
            command.Parameters.AddWithValue(accountId);
            command.Parameters.AddWithValue(write.CharacterId);
            command.Parameters.AddWithValue(write.RequestFingerprint);
            command.Parameters.AddWithValue(sourceSlot);
            command.Parameters.AddWithValue((long)write.SessionId);
            command.Parameters.AddWithValue(nextVersion);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            sourceSlot,
            write.SessionId,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'CharacterDeleted', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.CharacterDeleted(
            accountId,
            nextVersion,
            write.CharacterId,
            sourceSlot,
            write.SessionId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new CharacterDeleteStoreResult(
            write.CharacterId,
            sourceSlot,
            true,
            nextVersion);
    }

    public async Task<OriginalMailSendStoreResult> SendOriginalMailAsync(
        Guid accountId,
        OriginalMailSendWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ValidateOriginalMailWrite(write);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT mail_id, sender_character_id, recipient_character_id, title, body, authority_version FROM original_mail_message WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(1) != write.SenderCharacterId ||
                    reader.GetInt64(2) != write.RecipientCharacterId ||
                    !StringComparer.Ordinal.Equals(reader.GetString(3), write.Title) ||
                    !StringComparer.Ordinal.Equals(reader.GetString(4), write.Body))
                {
                    throw new InvalidOperationException("MAIL_SEND_REPLAY_MISMATCH");
                }

                var result = new OriginalMailSendStoreResult(
                    reader.GetInt64(0), false, reader.GetInt64(5));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        var expectedCharacterCount = write.SenderCharacterId == write.RecipientCharacterId ? 1L : 2L;
        await using (var characters = new NpgsqlCommand(
            "SELECT count(*) FROM character WHERE account_id = $1 AND character_id = ANY($2)",
            connection,
            transaction))
        {
            characters.Parameters.AddWithValue(accountId);
            characters.Parameters.AddWithValue(
                NpgsqlDbType.Array | NpgsqlDbType.Bigint,
                new[] { write.SenderCharacterId, write.RecipientCharacterId }.Distinct().ToArray());
            var characterCount = (long)(await characters.ExecuteScalarAsync(cancellationToken) ?? 0L);
            if (characterCount != expectedCharacterCount)
            {
                throw new InvalidOperationException("MAIL_CHARACTER_NOT_FOUND");
            }
        }

        var nextVersion = checked(currentVersion + 1);
        long mailId;
        const string insertMail = """
            INSERT INTO original_mail_message(
                account_id, request_fingerprint, sender_character_id,
                recipient_character_id, title, body, authority_version)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING mail_id
            """;
        await using (var insert = new NpgsqlCommand(insertMail, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(write.SenderCharacterId);
            insert.Parameters.AddWithValue(write.RecipientCharacterId);
            insert.Parameters.AddWithValue(write.Title);
            insert.Parameters.AddWithValue(write.Body);
            insert.Parameters.AddWithValue(nextVersion);
            mailId = (long)(await insert.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("MAIL_INSERT_NO_ID"));
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            mailId,
            senderCharacterId = write.SenderCharacterId,
            recipientCharacterId = write.RecipientCharacterId,
            write.Title,
            write.Body,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalMailSent', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalMailSent(
            accountId,
            nextVersion,
            mailId,
            write.SenderCharacterId,
            write.RecipientCharacterId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalMailSendStoreResult(mailId, true, nextVersion);
    }

    public async Task<IReadOnlyList<OriginalMailRecord>> ListOriginalMailAsync(
        Guid accountId,
        CancellationToken cancellationToken)
    {
        const string sql = """
            SELECT mail_id, sender_character_id, recipient_character_id,
                   title, body, authority_version, sent_at, is_read, read_at,
                   sender_deleted, recipient_deleted
            FROM original_mail_message
            WHERE account_id = $1
            ORDER BY mail_id
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var messages = new List<OriginalMailRecord>();
        while (await reader.ReadAsync(cancellationToken))
        {
            messages.Add(new OriginalMailRecord(
                reader.GetInt64(0),
                reader.GetInt64(1),
                reader.GetInt64(2),
                reader.GetString(3),
                reader.GetString(4),
                reader.GetInt64(5),
                reader.GetFieldValue<DateTimeOffset>(6),
                reader.GetBoolean(7),
                reader.IsDBNull(8) ? null : reader.GetFieldValue<DateTimeOffset>(8),
                reader.GetBoolean(9),
                reader.GetBoolean(10)));
        }

        return messages;
    }

    public async Task<OriginalMessengerMessageStoreResult> SaveOriginalMessengerMessageAsync(
        Guid accountId,
        OriginalMessengerMessageWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ValidateOriginalMessengerMessageWrite(write);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            "SELECT message_id, sender_character_id, recipient_character_id, message_text, wire_payload, authority_version FROM original_messenger_message WHERE sender_account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                if (reader.GetInt64(1) != write.SenderCharacterId ||
                    reader.GetInt64(2) != write.RecipientCharacterId ||
                    !StringComparer.Ordinal.Equals(reader.GetString(3), write.Message) ||
                    !reader.GetFieldValue<byte[]>(4).AsSpan().SequenceEqual(write.WirePayload))
                {
                    throw new InvalidOperationException("MESSENGER_MESSAGE_REPLAY_MISMATCH");
                }

                var replayed = new OriginalMessengerMessageStoreResult(
                    reader.GetInt64(0), false, reader.GetInt64(5));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return replayed;
            }
        }

        await using (var sender = new NpgsqlCommand(
            "SELECT 1 FROM character WHERE account_id = $1 AND character_id = $2",
            connection,
            transaction))
        {
            sender.Parameters.AddWithValue(accountId);
            sender.Parameters.AddWithValue(write.SenderCharacterId);
            if (await sender.ExecuteScalarAsync(cancellationToken) is null)
            {
                throw new InvalidOperationException("MESSENGER_SENDER_NOT_OWNED");
            }
        }

        var nextVersion = checked(currentVersion + 1);
        long messageId;
        const string insertMessage = """
            INSERT INTO original_messenger_message(
                sender_account_id, request_fingerprint, sender_character_id,
                recipient_character_id, message_text, wire_payload, authority_version)
            VALUES ($1, $2, $3, $4, $5, $6, $7)
            RETURNING message_id
            """;
        await using (var insert = new NpgsqlCommand(insertMessage, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(write.SenderCharacterId);
            insert.Parameters.AddWithValue(write.RecipientCharacterId);
            insert.Parameters.AddWithValue(write.Message);
            insert.Parameters.AddWithValue(NpgsqlDbType.Bytea, write.WirePayload);
            insert.Parameters.AddWithValue(nextVersion);
            messageId = (long)(await insert.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("MESSENGER_MESSAGE_INSERT_NO_ID"));
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            messageId,
            senderCharacterId = write.SenderCharacterId,
            recipientCharacterId = write.RecipientCharacterId,
            write.Message,
            wirePayloadBase64 = Convert.ToBase64String(write.WirePayload),
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalMessengerMessageSent', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalMessengerMessageSent(
            accountId,
            nextVersion,
            messageId,
            write.SenderCharacterId,
            write.RecipientCharacterId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalMessengerMessageStoreResult(messageId, true, nextVersion);
    }

    public async Task<IReadOnlyList<OriginalMessengerMessageRecord>> ListOriginalMessengerMessagesAsync(
        Guid accountId,
        long viewerCharacterId,
        long peerCharacterId,
        CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(viewerCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(peerCharacterId);
        await using (var viewer = _dataSource.CreateCommand(
            "SELECT 1 FROM character WHERE account_id = $1 AND character_id = $2"))
        {
            viewer.Parameters.AddWithValue(accountId);
            viewer.Parameters.AddWithValue(viewerCharacterId);
            if (await viewer.ExecuteScalarAsync(cancellationToken) is null)
            {
                throw new InvalidOperationException("MESSENGER_VIEWER_NOT_OWNED");
            }
        }

        const string sql = """
            SELECT message_id, sender_account_id, sender_character_id,
                   recipient_character_id, message_text, wire_payload,
                   request_fingerprint, authority_version, sent_at
            FROM original_messenger_message
            WHERE (sender_character_id = $1 AND recipient_character_id = $2)
               OR (sender_character_id = $2 AND recipient_character_id = $1)
            ORDER BY message_id
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(viewerCharacterId);
        command.Parameters.AddWithValue(peerCharacterId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var messages = new List<OriginalMessengerMessageRecord>();
        while (await reader.ReadAsync(cancellationToken))
        {
            messages.Add(new OriginalMessengerMessageRecord(
                reader.GetInt64(0),
                reader.GetGuid(1),
                reader.GetInt64(2),
                reader.GetInt64(3),
                reader.GetString(4),
                reader.GetFieldValue<byte[]>(5),
                reader.GetString(6).Trim(),
                reader.GetInt64(7),
                reader.GetFieldValue<DateTimeOffset>(8)));
        }

        return messages;
    }

    public async Task<OriginalGridUnitRecord?> FindOriginalGridUnitAsync(
        Guid accountId,
        long characterId,
        uint unitId,
        CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfZero(unitId);
        const string sql = """
            SELECT character_id, unit_id, authority_card_id,
                   current_cell_id, authority_version
            FROM original_grid_unit
            WHERE account_id = $1 AND character_id = $2 AND unit_id = $3
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        command.Parameters.AddWithValue(characterId);
        command.Parameters.AddWithValue((long)unitId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        return await reader.ReadAsync(cancellationToken)
            ? ReadOriginalGridUnit(reader)
            : null;
    }

    public async Task<OriginalMoveGridStoreResult> MoveOriginalGridUnitAsync(
        Guid accountId,
        OriginalMoveGridWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ValidateOriginalMoveGridWrite(write);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var replay = new NpgsqlCommand(
            """
            SELECT character_id, unit_id, authority_card_id,
                   expected_current_cell_id, source_cell_id, destination_cell_id,
                   action, authority_version
            FROM original_grid_move_command
            WHERE account_id = $1 AND request_fingerprint = $2
            """,
            connection,
            transaction))
        {
            replay.Parameters.AddWithValue(accountId);
            replay.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await replay.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                var isSameCommand =
                    reader.GetInt64(0) == write.CharacterId &&
                    checked((uint)reader.GetInt64(1)) == write.UnitId &&
                    checked((ushort)reader.GetInt32(2)) == write.AuthorityCardId &&
                    checked((uint)reader.GetInt64(3)) == write.ExpectedCurrentCellId &&
                    checked((uint)reader.GetInt64(4)) == write.SourceCellId &&
                    checked((uint)reader.GetInt64(5)) == write.DestinationCellId &&
                    checked((ushort)reader.GetInt32(6)) == write.Action;
                var replayVersion = reader.GetInt64(7);
                await reader.DisposeAsync();
                if (!isSameCommand)
                {
                    await transaction.CommitAsync(cancellationToken);
                    return RejectOriginalMoveGrid(
                        currentVersion,
                        "MOVE_GRID_FINGERPRINT_CONFLICT");
                }

                var historicalState = new OriginalGridUnitRecord(
                    write.CharacterId,
                    write.UnitId,
                    write.AuthorityCardId,
                    write.DestinationCellId,
                    replayVersion);
                await transaction.CommitAsync(cancellationToken);
                return new OriginalMoveGridStoreResult(
                    OriginalMoveGridStoreStatus.Replayed,
                    historicalState,
                    replayVersion,
                    null);
            }
        }

        OriginalGridUnitRecord? unit = null;
        await using (var selectUnit = new NpgsqlCommand(
            """
            SELECT character_id, unit_id, authority_card_id,
                   current_cell_id, authority_version
            FROM original_grid_unit
            WHERE account_id = $1 AND unit_id = $2
            FOR UPDATE
            """,
            connection,
            transaction))
        {
            selectUnit.Parameters.AddWithValue(accountId);
            selectUnit.Parameters.AddWithValue((long)write.UnitId);
            await using var reader = await selectUnit.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                unit = ReadOriginalGridUnit(reader);
            }
        }

        if (unit is null || unit.CharacterId != write.CharacterId)
        {
            await transaction.CommitAsync(cancellationToken);
            return RejectOriginalMoveGrid(
                currentVersion,
                "MOVE_GRID_UNIT_NOT_OWNED");
        }

        if (write.ExpectedCurrentCellId != unit.CurrentCellId)
        {
            await transaction.CommitAsync(cancellationToken);
            return RejectOriginalMoveGrid(
                currentVersion,
                "MOVE_GRID_SOURCE_STALE",
                unit);
        }

        var decision = OriginalMoveGridAuthority.Transition(
            new OriginalMoveGridAuthorityState(
                unit.UnitId,
                unit.AuthorityCardId,
                unit.CurrentCellId),
            new OriginalMoveGridAuthorityCommand(
                write.UnitId,
                write.AuthorityCardId,
                write.SourceCellId,
                write.DestinationCellId,
                write.Action));
        if (decision.Status != OriginalMoveGridAuthorityStatus.Allowed)
        {
            await transaction.CommitAsync(cancellationToken);
            return RejectOriginalMoveGrid(
                currentVersion,
                decision.ErrorCode ?? "MOVE_GRID_REJECTED",
                unit);
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var updateUnit = new NpgsqlCommand(
            """
            UPDATE original_grid_unit
            SET current_cell_id = $4,
                authority_version = $5,
                updated_at = transaction_timestamp()
            WHERE account_id = $1
              AND unit_id = $2
              AND character_id = $3
              AND current_cell_id = $6
              AND authority_version = $7
            """,
            connection,
            transaction))
        {
            updateUnit.Parameters.AddWithValue(accountId);
            updateUnit.Parameters.AddWithValue((long)write.UnitId);
            updateUnit.Parameters.AddWithValue(write.CharacterId);
            updateUnit.Parameters.AddWithValue((long)write.DestinationCellId);
            updateUnit.Parameters.AddWithValue(nextVersion);
            updateUnit.Parameters.AddWithValue((long)write.ExpectedCurrentCellId);
            updateUnit.Parameters.AddWithValue(unit.AuthorityVersion);
            if (await updateUnit.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                await transaction.RollbackAsync(cancellationToken);
                return RejectOriginalMoveGrid(
                    currentVersion,
                    "MOVE_GRID_SOURCE_STALE");
            }
        }

        const string insertCommand = """
            INSERT INTO original_grid_move_command(
                account_id, request_fingerprint, character_id, unit_id,
                authority_card_id, expected_current_cell_id, source_cell_id,
                destination_cell_id, action, outcome, authority_version)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, 'moved', $10)
            """;
        await using (var command = new NpgsqlCommand(insertCommand, connection, transaction))
        {
            command.Parameters.AddWithValue(accountId);
            command.Parameters.AddWithValue(write.RequestFingerprint);
            command.Parameters.AddWithValue(write.CharacterId);
            command.Parameters.AddWithValue((long)write.UnitId);
            command.Parameters.AddWithValue((int)write.AuthorityCardId);
            command.Parameters.AddWithValue((long)write.ExpectedCurrentCellId);
            command.Parameters.AddWithValue((long)write.SourceCellId);
            command.Parameters.AddWithValue((long)write.DestinationCellId);
            command.Parameters.AddWithValue((int)write.Action);
            command.Parameters.AddWithValue(nextVersion);
            await command.ExecuteNonQueryAsync(cancellationToken);
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            unitId = write.UnitId,
            authorityCardId = write.AuthorityCardId,
            expectedCurrentCellId = write.ExpectedCurrentCellId,
            sourceCellId = write.SourceCellId,
            destinationCellId = write.DestinationCellId,
            action = write.Action,
            outcome = "moved",
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'original-grid-unit', $2, 'OriginalGridUnitMoved', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.UnitId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = OriginalGridUnitMovedStateHash(
            accountId,
            nextVersion,
            write);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1 AND authority_version = $4",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            updateAccount.Parameters.AddWithValue(currentVersion);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        var movedUnit = unit with
        {
            CurrentCellId = write.DestinationCellId,
            AuthorityVersion = nextVersion
        };
        return new OriginalMoveGridStoreResult(
            OriginalMoveGridStoreStatus.Moved,
            movedUnit,
            nextVersion,
            null);
    }

    public async Task<OriginalMailReadStoreResult> MarkOriginalMailReadAsync(
        Guid accountId,
        long characterId,
        long mailId,
        CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(mailId);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        bool isRead;
        long mailAuthorityVersion;
        await using (var mail = new NpgsqlCommand(
            "SELECT sender_character_id, recipient_character_id, is_read, authority_version FROM original_mail_message WHERE account_id = $1 AND mail_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            mail.Parameters.AddWithValue(accountId);
            mail.Parameters.AddWithValue(mailId);
            await using var reader = await mail.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken) ||
                (reader.GetInt64(0) != characterId && reader.GetInt64(1) != characterId))
            {
                throw new InvalidOperationException("MAIL_NOT_FOUND");
            }

            isRead = reader.GetBoolean(2);
            mailAuthorityVersion = reader.GetInt64(3);
        }

        if (isRead)
        {
            await transaction.CommitAsync(cancellationToken);
            return new OriginalMailReadStoreResult(mailId, false, mailAuthorityVersion);
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var updateMail = new NpgsqlCommand(
            "UPDATE original_mail_message SET is_read = true, read_at = transaction_timestamp(), authority_version = $3 WHERE account_id = $1 AND mail_id = $2 AND NOT is_read",
            connection,
            transaction))
        {
            updateMail.Parameters.AddWithValue(accountId);
            updateMail.Parameters.AddWithValue(mailId);
            updateMail.Parameters.AddWithValue(nextVersion);
            if (await updateMail.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("MAIL_READ_UPDATE_CONFLICT");
            }
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            mailId,
            characterId
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalMailRead', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalMailRead(
            accountId,
            nextVersion,
            mailId,
            characterId);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalMailReadStoreResult(mailId, true, nextVersion);
    }

    public async Task<OriginalMailDeleteStoreResult> DeleteOriginalMailAsync(
        Guid accountId,
        long characterId,
        long mailId,
        byte box,
        CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(mailId);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(box, (byte)1);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        bool alreadyDeleted;
        long mailAuthorityVersion;
        await using (var mail = new NpgsqlCommand(
            "SELECT sender_character_id, recipient_character_id, sender_deleted, recipient_deleted, authority_version FROM original_mail_message WHERE account_id = $1 AND mail_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            mail.Parameters.AddWithValue(accountId);
            mail.Parameters.AddWithValue(mailId);
            await using var reader = await mail.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken) ||
                (box == 0
                    ? reader.GetInt64(0) != characterId
                    : reader.GetInt64(1) != characterId))
            {
                throw new InvalidOperationException("MAIL_NOT_FOUND");
            }

            alreadyDeleted = box == 0 ? reader.GetBoolean(2) : reader.GetBoolean(3);
            mailAuthorityVersion = reader.GetInt64(4);
        }

        if (alreadyDeleted)
        {
            await transaction.CommitAsync(cancellationToken);
            return new OriginalMailDeleteStoreResult(mailId, false, mailAuthorityVersion);
        }

        var nextVersion = checked(currentVersion + 1);
        var updateSql = box == 0
            ? "UPDATE original_mail_message SET sender_deleted = true, authority_version = $3 WHERE account_id = $1 AND mail_id = $2 AND NOT sender_deleted"
            : "UPDATE original_mail_message SET recipient_deleted = true, authority_version = $3 WHERE account_id = $1 AND mail_id = $2 AND NOT recipient_deleted";
        await using (var updateMail = new NpgsqlCommand(updateSql, connection, transaction))
        {
            updateMail.Parameters.AddWithValue(accountId);
            updateMail.Parameters.AddWithValue(mailId);
            updateMail.Parameters.AddWithValue(nextVersion);
            if (await updateMail.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("MAIL_DELETE_UPDATE_CONFLICT");
            }
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            mailId,
            characterId,
            box
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalMailDeleted', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalMailDeleted(
            accountId,
            nextVersion,
            mailId,
            characterId,
            box);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalMailDeleteStoreResult(mailId, true, nextVersion);
    }

    public async Task<OriginalOrderSuggestReplyStoreResult> SaveOriginalOrderSuggestReplyAsync(
        Guid accountId,
        OriginalOrderSuggestReplyWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ArgumentException.ThrowIfNullOrWhiteSpace(write.RequestFingerprint);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.CharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.CardId);
        ArgumentOutOfRangeException.ThrowIfGreaterThan(write.ReplyValue, (byte)2);
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException(
                "ORDER_SUGGEST_REPLY_FINGERPRINT",
                nameof(write));
        }

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            currentVersion = (long)(await account.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND"));
        }

        await using (var character = new NpgsqlCommand(
            "SELECT 1 FROM character WHERE account_id = $1 AND character_id = $2",
            connection,
            transaction))
        {
            character.Parameters.AddWithValue(accountId);
            character.Parameters.AddWithValue(write.CharacterId);
            if (await character.ExecuteScalarAsync(cancellationToken) is null)
            {
                throw new InvalidOperationException("CHARACTER_NOT_FOUND");
            }
        }

        byte? existingReply = null;
        long existingVersion = 0;
        await using (var existing = new NpgsqlCommand(
            "SELECT reply_value, authority_version FROM original_order_suggest_reply WHERE account_id = $1 AND character_id = $2 AND card_id = $3 FOR UPDATE",
            connection,
            transaction))
        {
            existing.Parameters.AddWithValue(accountId);
            existing.Parameters.AddWithValue(write.CharacterId);
            existing.Parameters.AddWithValue(write.CardId);
            await using var reader = await existing.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                existingReply = checked((byte)reader.GetInt16(0));
                existingVersion = reader.GetInt64(1);
            }
        }

        if (existingReply is not null)
        {
            if (existingReply.Value != write.ReplyValue)
            {
                throw new InvalidOperationException("ORDER_SUGGEST_REPLY_ALREADY_DECIDED");
            }

            await transaction.CommitAsync(cancellationToken);
            return new OriginalOrderSuggestReplyStoreResult(
                write.CharacterId,
                write.CardId,
                write.ReplyValue,
                false,
                existingVersion);
        }

        var nextVersion = checked(currentVersion + 1);
        await using (var insertReply = new NpgsqlCommand(
            "INSERT INTO original_order_suggest_reply(account_id, character_id, card_id, reply_value, request_fingerprint, authority_version) VALUES ($1, $2, $3, $4, $5, $6)",
            connection,
            transaction))
        {
            insertReply.Parameters.AddWithValue(accountId);
            insertReply.Parameters.AddWithValue(write.CharacterId);
            insertReply.Parameters.AddWithValue(write.CardId);
            insertReply.Parameters.AddWithValue(checked((short)write.ReplyValue));
            insertReply.Parameters.AddWithValue(write.RequestFingerprint);
            insertReply.Parameters.AddWithValue(nextVersion);
            if (await insertReply.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ORDER_SUGGEST_REPLY_INSERT_CONFLICT");
            }
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            characterId = write.CharacterId,
            cardId = write.CardId,
            replyValue = write.ReplyValue,
            requestFingerprint = write.RequestFingerprint
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'character', $2, 'OriginalOrderSuggestReplied', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(write.CharacterId.ToString());
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalOrderSuggestReplied(
            accountId,
            nextVersion,
            write.CharacterId,
            write.CardId,
            write.ReplyValue,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalOrderSuggestReplyStoreResult(
            write.CharacterId,
            write.CardId,
            write.ReplyValue,
            true,
            nextVersion);
    }

    public async Task<OriginalOrderSuggestReplyRecord?> FindOriginalOrderSuggestReplyAsync(
        Guid accountId,
        long characterId,
        int cardId,
        CancellationToken cancellationToken)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(characterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(cardId);
        const string sql = "SELECT reply_value, request_fingerprint, authority_version, responded_at FROM original_order_suggest_reply WHERE account_id = $1 AND character_id = $2 AND card_id = $3";
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        command.Parameters.AddWithValue(characterId);
        command.Parameters.AddWithValue(cardId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new OriginalOrderSuggestReplyRecord(
            characterId,
            cardId,
            checked((byte)reader.GetInt16(0)),
            reader.GetString(1).Trim(),
            reader.GetInt64(2),
            reader.GetFieldValue<DateTimeOffset>(3));
    }

    public async Task<OriginalCharacterLotteryEntryStoreResult> EnterOriginalCharacterLotteryAsync(
        Guid accountId,
        OriginalCharacterLotteryEntryWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ValidateOriginalLotteryWrite(write);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            var value = await account.ExecuteScalarAsync(cancellationToken);
            if (value is null)
            {
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND");
            }

            currentVersion = (long)value;
        }

        await using (var existing = new NpgsqlCommand(
            "SELECT entry_id, authority_version FROM original_character_lottery_entry WHERE account_id = $1 AND request_fingerprint = $2",
            connection,
            transaction))
        {
            existing.Parameters.AddWithValue(accountId);
            existing.Parameters.AddWithValue(write.RequestFingerprint);
            await using var reader = await existing.ExecuteReaderAsync(cancellationToken);
            if (await reader.ReadAsync(cancellationToken))
            {
                var result = new OriginalCharacterLotteryEntryStoreResult(
                    reader.GetInt64(0), false, reader.GetInt64(1));
                await reader.DisposeAsync();
                await transaction.CommitAsync(cancellationToken);
                return result;
            }
        }

        await using (var pending = new NpgsqlCommand(
            "SELECT 1 FROM original_character_lottery_entry WHERE account_id = $1 AND status = 'pending'",
            connection,
            transaction))
        {
            pending.Parameters.AddWithValue(accountId);
            if (await pending.ExecuteScalarAsync(cancellationToken) is not null)
            {
                throw new InvalidOperationException("ORIGINAL_LOTTERY_ENTRY_ALREADY_PENDING");
            }
        }

        var nextVersion = checked(currentVersion + 1);
        long entryId;
        const string insertEntry = """
            INSERT INTO original_character_lottery_entry(
                account_id, request_fingerprint, candidate_character_ids,
                status, authority_version)
            VALUES ($1, $2, $3, 'pending', $4)
            RETURNING entry_id
            """;
        await using (var insert = new NpgsqlCommand(insertEntry, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(write.RequestFingerprint);
            insert.Parameters.AddWithValue(
                NpgsqlDbType.Array | NpgsqlDbType.Bigint,
                write.CandidateCharacterIds.Select(value => (long)value).ToArray());
            insert.Parameters.AddWithValue(nextVersion);
            entryId = (long)(await insert.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("ORIGINAL_LOTTERY_INSERT_NO_ID"));
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            entryId,
            requestFingerprint = write.RequestFingerprint,
            candidateCharacterIds = write.CandidateCharacterIds,
            status = "pending"
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalCharacterLotteryEntered', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalCharacterLotteryEntered(
            accountId,
            nextVersion,
            entryId,
            write.RequestFingerprint);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalCharacterLotteryEntryStoreResult(entryId, true, nextVersion);
    }

    public async Task<OriginalCharacterLotteryEntryRecord?> FindPendingOriginalCharacterLotteryAsync(
        Guid accountId,
        CancellationToken cancellationToken)
    {
        const string sql = """
            SELECT entry_id, request_fingerprint, candidate_character_ids,
                   status, result_character_id, authority_version, submitted_at
            FROM original_character_lottery_entry
            WHERE account_id = $1 AND status = 'pending'
            """;
        await using var command = _dataSource.CreateCommand(sql);
        command.Parameters.AddWithValue(accountId);
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        if (!await reader.ReadAsync(cancellationToken))
        {
            return null;
        }

        return new OriginalCharacterLotteryEntryRecord(
            reader.GetInt64(0),
            reader.GetString(1).Trim(),
            reader.GetFieldValue<long[]>(2).Select(value => checked((uint)value)).ToArray(),
            FromDatabaseLotteryStatus(reader.GetString(3)),
            reader.IsDBNull(4) ? null : checked((uint)reader.GetInt64(4)),
            reader.GetInt64(5),
            reader.GetFieldValue<DateTimeOffset>(6));
    }

    public async Task<OriginalCharacterLotteryAwardStoreResult> AwardOriginalCharacterLotteryAsync(
        Guid accountId,
        OriginalCharacterLotteryAwardWrite write,
        CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(write);
        ValidateOriginalLotteryAwardWrite(write);

        await using var connection = await _dataSource.OpenConnectionAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        long currentVersion;
        await using (var account = new NpgsqlCommand(
            "SELECT authority_version FROM account WHERE account_id = $1 FOR UPDATE",
            connection,
            transaction))
        {
            account.Parameters.AddWithValue(accountId);
            var value = await account.ExecuteScalarAsync(cancellationToken);
            if (value is null)
            {
                throw new InvalidOperationException("ACCOUNT_NOT_FOUND");
            }

            currentVersion = (long)value;
        }

        uint[] candidateCharacterIds;
        string status;
        uint? persistedResultCandidateId;
        long entryAuthorityVersion;
        await using (var entry = new NpgsqlCommand(
            "SELECT candidate_character_ids, status, result_character_id, authority_version FROM original_character_lottery_entry WHERE account_id = $1 AND entry_id = $2 FOR UPDATE",
            connection,
            transaction))
        {
            entry.Parameters.AddWithValue(accountId);
            entry.Parameters.AddWithValue(write.EntryId);
            await using var reader = await entry.ExecuteReaderAsync(cancellationToken);
            if (!await reader.ReadAsync(cancellationToken))
            {
                throw new InvalidOperationException("ORIGINAL_LOTTERY_ENTRY_NOT_FOUND");
            }

            candidateCharacterIds = reader.GetFieldValue<long[]>(0)
                .Select(value => checked((uint)value))
                .ToArray();
            status = reader.GetString(1);
            persistedResultCandidateId = reader.IsDBNull(2)
                ? null
                : checked((uint)reader.GetInt64(2));
            entryAuthorityVersion = reader.GetInt64(3);
        }

        if (status == "awarded")
        {
            var resultCandidateId = persistedResultCandidateId ??
                throw new InvalidDataException("ORIGINAL_LOTTERY_AWARDED_RESULT_MISSING");
            var requestFingerprint =
                OriginalCharacterLotteryAwardIdentity.CharacterRequestFingerprint(
                    write.EntryId,
                    resultCandidateId);
            long characterId;
            await using (var existingCharacter = new NpgsqlCommand(
                "SELECT character_id FROM character WHERE account_id = $1 AND request_fingerprint = $2",
                connection,
                transaction))
            {
                existingCharacter.Parameters.AddWithValue(accountId);
                existingCharacter.Parameters.AddWithValue(requestFingerprint);
                characterId = (long)(await existingCharacter.ExecuteScalarAsync(cancellationToken) ??
                    throw new InvalidDataException("ORIGINAL_LOTTERY_AWARDED_CHARACTER_MISSING"));
            }

            await transaction.CommitAsync(cancellationToken);
            return new OriginalCharacterLotteryAwardStoreResult(
                write.EntryId,
                resultCandidateId,
                characterId,
                false,
                entryAuthorityVersion);
        }

        if (status != "pending")
        {
            throw new InvalidDataException("ORIGINAL_LOTTERY_STATUS_INVALID");
        }

        if (!candidateCharacterIds.Contains(write.ResultCandidateCharacterId))
        {
            throw new InvalidOperationException("ORIGINAL_LOTTERY_RESULT_NOT_CANDIDATE");
        }

        var expectedFingerprint =
            OriginalCharacterLotteryAwardIdentity.CharacterRequestFingerprint(
                write.EntryId,
                write.ResultCandidateCharacterId);
        if (!StringComparer.Ordinal.Equals(
                write.Character.RequestFingerprint,
                expectedFingerprint))
        {
            throw new InvalidOperationException("ORIGINAL_LOTTERY_CHARACTER_FINGERPRINT_MISMATCH");
        }

        short slot;
        await using (var count = new NpgsqlCommand(
            "SELECT count(*) FROM character WHERE account_id = $1",
            connection,
            transaction))
        {
            count.Parameters.AddWithValue(accountId);
            var characterCount = (long)(await count.ExecuteScalarAsync(cancellationToken) ?? 0L);
            if (characterCount >= 2)
            {
                throw new InvalidOperationException("CHARACTER_SLOT_LIMIT");
            }

            slot = checked((short)characterCount);
        }

        var nextVersion = checked(currentVersion + 1);
        long characterIdCreated;
        const string insertCharacter = """
            INSERT INTO character(
                account_id, slot, request_fingerprint, payload_hash,
                faction, blood, sex, last_name, first_name, flagship_name,
                face, ability_values, authority_version)
            VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
            RETURNING character_id
            """;
        await using (var insert = new NpgsqlCommand(insertCharacter, connection, transaction))
        {
            insert.Parameters.AddWithValue(accountId);
            insert.Parameters.AddWithValue(slot);
            insert.Parameters.AddWithValue(write.Character.RequestFingerprint);
            insert.Parameters.AddWithValue(write.Character.PayloadHash);
            insert.Parameters.AddWithValue(write.Character.Faction);
            insert.Parameters.AddWithValue(write.Character.Blood);
            insert.Parameters.AddWithValue(write.Character.Sex);
            insert.Parameters.AddWithValue(write.Character.LastName);
            insert.Parameters.AddWithValue(write.Character.FirstName);
            insert.Parameters.AddWithValue(write.Character.FlagshipName);
            insert.Parameters.AddWithValue(write.Character.Face);
            insert.Parameters.AddWithValue(
                NpgsqlDbType.Array | NpgsqlDbType.Smallint,
                write.Character.AbilityValues);
            insert.Parameters.AddWithValue(nextVersion);
            characterIdCreated = (long)(await insert.ExecuteScalarAsync(cancellationToken) ??
                throw new InvalidOperationException("CHARACTER_INSERT_NO_ID"));
        }

        await using (var updateEntry = new NpgsqlCommand(
            "UPDATE original_character_lottery_entry SET status = 'awarded', result_character_id = $3, authority_version = $4 WHERE account_id = $1 AND entry_id = $2 AND status = 'pending'",
            connection,
            transaction))
        {
            updateEntry.Parameters.AddWithValue(accountId);
            updateEntry.Parameters.AddWithValue(write.EntryId);
            updateEntry.Parameters.AddWithValue((long)write.ResultCandidateCharacterId);
            updateEntry.Parameters.AddWithValue(nextVersion);
            if (await updateEntry.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ORIGINAL_LOTTERY_AWARD_UPDATE_FAILED");
            }
        }

        var eventPayload = JsonSerializer.Serialize(new
        {
            entryId = write.EntryId,
            resultCandidateCharacterId = write.ResultCandidateCharacterId,
            characterId = characterIdCreated,
            provenance = write.Provenance,
            slot,
            write.Character.Faction,
            write.Character.Blood,
            write.Character.Sex,
            write.Character.LastName,
            write.Character.FirstName,
            write.Character.FlagshipName,
            write.Character.Face,
            abilityValues = write.Character.AbilityValues
        });
        await using (var insertEvent = new NpgsqlCommand(
            "INSERT INTO domain_event(account_id, aggregate_type, aggregate_id, event_type, payload, authority_version) VALUES ($1, 'account', $2, 'OriginalCharacterLotteryAwarded', $3::jsonb, $4)",
            connection,
            transaction))
        {
            insertEvent.Parameters.AddWithValue(accountId);
            insertEvent.Parameters.AddWithValue(accountId.ToString("D"));
            insertEvent.Parameters.AddWithValue(eventPayload);
            insertEvent.Parameters.AddWithValue(nextVersion);
            await insertEvent.ExecuteNonQueryAsync(cancellationToken);
        }

        var stateHash = AuthorityStateHash.OriginalCharacterLotteryAwarded(
            accountId,
            nextVersion,
            write.EntryId,
            write.ResultCandidateCharacterId,
            characterIdCreated);
        await using (var updateAccount = new NpgsqlCommand(
            "UPDATE account SET authority_version = $2, authority_state_hash = $3, updated_at = transaction_timestamp() WHERE account_id = $1",
            connection,
            transaction))
        {
            updateAccount.Parameters.AddWithValue(accountId);
            updateAccount.Parameters.AddWithValue(nextVersion);
            updateAccount.Parameters.AddWithValue(stateHash);
            if (await updateAccount.ExecuteNonQueryAsync(cancellationToken) != 1)
            {
                throw new InvalidOperationException("ACCOUNT_VERSION_UPDATE_FAILED");
            }
        }

        await transaction.CommitAsync(cancellationToken);
        return new OriginalCharacterLotteryAwardStoreResult(
            write.EntryId,
            write.ResultCandidateCharacterId,
            characterIdCreated,
            true,
            nextVersion);
    }

    private static OriginalGridUnitRecord ReadOriginalGridUnit(NpgsqlDataReader reader) =>
        new(
            reader.GetInt64(0),
            checked((uint)reader.GetInt64(1)),
            checked((ushort)reader.GetInt32(2)),
            checked((uint)reader.GetInt64(3)),
            reader.GetInt64(4));

    private static OriginalMoveGridStoreResult RejectOriginalMoveGrid(
        long authorityVersion,
        string errorCode,
        OriginalGridUnitRecord? unit = null) =>
        new(
            OriginalMoveGridStoreStatus.Rejected,
            unit,
            authorityVersion,
            errorCode);

    private static void ValidateOriginalMoveGridWrite(OriginalMoveGridWrite write)
    {
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("ORIGINAL_MOVE_GRID_FINGERPRINT", nameof(write));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.CharacterId);
        ArgumentOutOfRangeException.ThrowIfZero(write.UnitId);
        ArgumentOutOfRangeException.ThrowIfZero(write.AuthorityCardId);
        ArgumentOutOfRangeException.ThrowIfZero(write.ExpectedCurrentCellId);
        ArgumentOutOfRangeException.ThrowIfZero(write.SourceCellId);
        ArgumentOutOfRangeException.ThrowIfZero(write.DestinationCellId);
        ArgumentOutOfRangeException.ThrowIfZero(write.Action);
    }

    private static string OriginalGridUnitMovedStateHash(
        Guid accountId,
        long authorityVersion,
        OriginalMoveGridWrite write)
    {
        var canonicalJson = FormattableString.Invariant(
            $"{{\"accountId\":\"{accountId:D}\",\"authorityVersion\":{authorityVersion},\"characterId\":{write.CharacterId},\"unitId\":{write.UnitId},\"sourceCellId\":{write.SourceCellId},\"destinationCellId\":{write.DestinationCellId},\"requestFingerprint\":\"{write.RequestFingerprint}\"}}");
        var prefix = "logh7-authority-state/v1\n"u8;
        var jsonBytes = Encoding.UTF8.GetBytes(canonicalJson);
        var input = new byte[prefix.Length + jsonBytes.Length];
        prefix.CopyTo(input);
        jsonBytes.CopyTo(input, prefix.Length);
        try
        {
            return Convert.ToHexStringLower(SHA256.HashData(input));
        }
        finally
        {
            CryptographicOperations.ZeroMemory(input);
        }
    }

    private static void ValidateOriginalMailWrite(OriginalMailSendWrite write)
    {
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("ORIGINAL_MAIL_FINGERPRINT", nameof(write));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.SenderCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.RecipientCharacterId);
        if (string.IsNullOrWhiteSpace(write.Title) ||
            write.Title.Length > 63 ||
            write.Title.Contains('\0'))
        {
            throw new ArgumentException("ORIGINAL_MAIL_TITLE", nameof(write));
        }

        if (string.IsNullOrWhiteSpace(write.Body) ||
            write.Body.Length > 2047 ||
            write.Body.Contains('\0'))
        {
            throw new ArgumentException("ORIGINAL_MAIL_BODY", nameof(write));
        }
    }

    private static void ValidateOriginalMessengerMessageWrite(
        OriginalMessengerMessageWrite write)
    {
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("ORIGINAL_MESSENGER_FINGERPRINT", nameof(write));
        }

        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.SenderCharacterId);
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.RecipientCharacterId);
        if (string.IsNullOrWhiteSpace(write.Message) ||
            write.Message.Length > 511 ||
            write.Message.Contains('\0'))
        {
            throw new ArgumentException("ORIGINAL_MESSENGER_MESSAGE", nameof(write));
        }

        ArgumentNullException.ThrowIfNull(write.WirePayload);
        if (write.WirePayload.Length is < sizeof(ushort) or > 0x52c ||
            BinaryPrimitives.ReadUInt16BigEndian(write.WirePayload) != 0x0f0f)
        {
            throw new ArgumentException("ORIGINAL_MESSENGER_WIRE_PAYLOAD", nameof(write));
        }
    }

    private static void ValidateOriginalLotteryWrite(OriginalCharacterLotteryEntryWrite write)
    {
        if (write.RequestFingerprint.Length != 64 ||
            write.RequestFingerprint.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("ORIGINAL_LOTTERY_FINGERPRINT", nameof(write));
        }

        if (write.CandidateCharacterIds.Length is 0 or > 5 ||
            write.CandidateCharacterIds.Any(value => value == 0) ||
            write.CandidateCharacterIds.Distinct().Count() != write.CandidateCharacterIds.Length)
        {
            throw new ArgumentException("ORIGINAL_LOTTERY_CANDIDATES", nameof(write));
        }
    }

    private static void ValidateOriginalLotteryAwardWrite(OriginalCharacterLotteryAwardWrite write)
    {
        ArgumentOutOfRangeException.ThrowIfNegativeOrZero(write.EntryId);
        ArgumentOutOfRangeException.ThrowIfZero(write.ResultCandidateCharacterId);
        ArgumentException.ThrowIfNullOrWhiteSpace(write.Provenance);
        ArgumentNullException.ThrowIfNull(write.Character);
        if (write.Character.AbilityValues.Length != 8)
        {
            throw new ArgumentException("CHARACTER_ABILITY_COUNT", nameof(write));
        }

        if (write.Character.PayloadHash.Length != 64 ||
            write.Character.PayloadHash.Any(character =>
                character is not (>= '0' and <= '9') and not (>= 'a' and <= 'f')))
        {
            throw new ArgumentException("ORIGINAL_LOTTERY_PAYLOAD_HASH", nameof(write));
        }
    }

    private static PasswordHashRecord ClonePassword(PasswordHashRecord password) =>
        new(
            password.Salt.ToArray(),
            password.Hash.ToArray(),
            password.MemoryKiB,
            password.Iterations,
            password.Parallelism);

    private static string ToDatabaseStatus(AccountStatus status) => status switch
    {
        AccountStatus.Active => "active",
        AccountStatus.Suspended => "suspended",
        _ => throw new ArgumentOutOfRangeException(nameof(status))
    };

    private static AccountStatus FromDatabaseStatus(string status) => status switch
    {
        "active" => AccountStatus.Active,
        "suspended" => AccountStatus.Suspended,
        _ => throw new InvalidDataException("ACCOUNT_STATUS_INVALID")
    };

    private static OriginalCharacterLotteryEntryStatus FromDatabaseLotteryStatus(string status) =>
        status switch
        {
            "pending" => OriginalCharacterLotteryEntryStatus.Pending,
            "awarded" => OriginalCharacterLotteryEntryStatus.Awarded,
            _ => throw new InvalidDataException("ORIGINAL_LOTTERY_STATUS_INVALID")
        };
}
