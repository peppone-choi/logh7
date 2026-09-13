using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalWarehousePostgresTests
{
    public static bool HasTestDatabase => !string.IsNullOrWhiteSpace(
        Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB"));

    private static CancellationToken Token => TestContext.Current.CancellationToken;
    private static readonly OriginalWarehouseKey Source = new(1, 0);
    private static readonly OriginalWarehouseKey Destination = new(1, 1);
    private static readonly OriginalWarehouseKey OtherDestination = new(2, 1);
    private static readonly OriginalStockKey Ship = new(OriginalStockKind.ShipUnits, 7);
    private static readonly OriginalStockKey Boats = new(OriginalStockKind.ShipBoats, 7);
    private static readonly OriginalStockKey Troops = new(OriginalStockKind.Troops, 9, 2);
    private static readonly OriginalStockKey Supplies = new(OriginalStockKind.Supplies);

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Assignment_late_event_failure_rolls_back_real_charge_and_allows_retry()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET pcp=0,mcp=500,points_accrued_at=now() WHERE character_id=$1", f.OwnerCharacter);
        await f.ExecuteAsync("""
            CREATE FUNCTION fail_paid_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.event_type='OriginalWarehouseAssigned' THEN
                    IF NOT EXISTS(SELECT 1 FROM original_command_point_charge
                        WHERE account_id=NEW.account_id AND authority_version=NEW.authority_version AND cost=160) THEN
                        RAISE EXCEPTION 'TEST_CHARGE_NOT_REACHED';
                    END IF;
                    RAISE EXCEPTION 'TEST_AFTER_REAL_ASSIGNMENT_CHARGE';
                END IF;
                RETURN NEW;
            END $$;
            CREATE TRIGGER assignment_late_failure BEFORE UPDATE ON domain_event
                FOR EACH ROW EXECUTE FUNCTION fail_paid_assignment();
            """);
        var before = await f.StateAsync();
        var store = new PostgresAccountStore(f.Data);
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        var policy = OriginalCommandPointPolicy.LoadDefault();
        var error = await Assert.ThrowsAsync<PostgresException>(() =>
            store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token));
        Assert.Equal("TEST_AFTER_REAL_ASSIGNMENT_CHARGE", error.MessageText);
        Assert.Equal(before, await f.StateAsync());
        Assert.Equal(500, await f.ScalarAsync("SELECT mcp FROM character WHERE character_id=" + f.OwnerCharacter));
        Assert.Equal(0, await f.ScalarAsync("SELECT count(*) FROM original_command_point_charge"));
        Assert.Equal(0, await f.ScalarAsync("SELECT mcp_spent_direct FROM character WHERE character_id=" + f.OwnerCharacter));
        await f.ExecuteAsync("ALTER TABLE domain_event DISABLE TRIGGER assignment_late_failure");
        var retry = await store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token);
        Assert.True(retry.Transfer.Applied);
        Assert.Equal(340u, retry.Points.Military);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Assignment_uses_deficit_only_substitution_and_direct_growth_accounting()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET pcp=200,mcp=100,points_accrued_at=now() WHERE character_id=$1", f.OwnerCharacter);
        var result = await new PostgresAccountStore(f.Data).AssignOriginalWarehouseAsync(f.Owner,
            f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2)),
            OriginalCommandPointPolicy.LoadDefault(), DateTimeOffset.UtcNow, true, Token);
        Assert.Equal(0u, result.Points.Military);
        Assert.Equal(80u, result.Points.Political);
        Assert.Equal(100u, result.Points.Direct);
        Assert.Equal(120u, result.Points.Substitute);
        Assert.Equal(100, await f.ScalarAsync("SELECT mcp_spent_direct FROM character WHERE character_id=" + f.OwnerCharacter));
        Assert.Equal(0, await f.ScalarAsync("SELECT pcp_spent_direct FROM character WHERE character_id=" + f.OwnerCharacter));
        Assert.Equal(120, await f.ScalarAsync("SELECT pcp_spent_substitute FROM character WHERE character_id=" + f.OwnerCharacter));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Assignment_charges_real_policy_once_and_replay_returns_current_balances()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET pcp=0,mcp=500,points_accrued_at=now() WHERE character_id=$1", f.OwnerCharacter);
        var store = new PostgresAccountStore(f.Data);
        var policy = OriginalCommandPointPolicy.LoadDefault();
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        var result = await store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token);
        Assert.True(result.Transfer.Applied);
        Assert.Equal(340u, result.Points.Military);
        Assert.Equal(160u, result.Points.Direct);
        Assert.Equal(1, await f.ScalarAsync("SELECT count(*) FROM domain_event WHERE event_type='OriginalWarehouseAssigned' AND payload->'commandPoints'->>'cost'='160'"));
        await store.AssignOriginalWarehouseAsync(f.Owner,
            f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 1)), policy, DateTimeOffset.UtcNow, true, Token);
        var beforeReplay = await f.StateAsync();
        var replay = await store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token);
        Assert.False(replay.Transfer.Applied);
        Assert.Equal(180u, replay.Points.Military);
        Assert.Equal(2, replay.Points.AuthorityVersion);
        Assert.Equal(beforeReplay, await f.StateAsync());
        Assert.Equal(2, await f.ScalarAsync("SELECT count(*) FROM original_command_point_charge"));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Assignment_insufficient_points_rolls_back_stock_and_unpaid_receipt_is_rejected()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET pcp=0,mcp=0,points_accrued_at=now() WHERE character_id=$1", f.OwnerCharacter);
        var store = new PostgresAccountStore(f.Data);
        var policy = OriginalCommandPointPolicy.LoadDefault();
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        var before = await f.StateAsync();
        await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token));
        Assert.Equal(before, await f.StateAsync());
        Assert.Equal(0, await f.ScalarAsync("SELECT count(*) FROM original_command_point_charge"));
        await new PostgresWarehouseStore(f.Data).TransferAsync(f.Owner, write, Token);
        var afterGeneric = await f.StateAsync();
        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token));
        Assert.Equal("ASSIGNMENT_REPLAY_RECEIPT_MISMATCH", error.Message);
        Assert.Equal(afterGeneric, await f.StateAsync());
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Concurrent_assignment_replays_charge_once_and_changed_stock_conflicts()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET pcp=0,mcp=500,points_accrued_at=now() WHERE character_id=$1", f.OwnerCharacter);
        var store = new PostgresAccountStore(f.Data);
        var policy = OriginalCommandPointPolicy.LoadDefault();
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ =>
            store.AssignOriginalWarehouseAsync(f.Owner, write, policy, DateTimeOffset.UtcNow, true, Token)));
        Assert.Single(results, r => r.Transfer.Applied);
        Assert.All(results, r => Assert.Equal(340u, r.Points.Military));
        Assert.Equal(1, await f.ScalarAsync("SELECT count(*) FROM original_command_point_charge"));
        var before = await f.StateAsync();
        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            store.AssignOriginalWarehouseAsync(f.Owner, write with { Lines = [new(Ship, 1)] },
                policy, DateTimeOffset.UtcNow, true, Token));
        Assert.Equal("WAREHOUSE_REQUEST_CONFLICT", error.Message);
        Assert.Equal(before, await f.StateAsync());
        Assert.Equal(340, await f.ScalarAsync("SELECT mcp FROM character WHERE character_id=" + f.OwnerCharacter));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Caller_transaction_owns_stock_receipt_and_authority_commit(bool commit)
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        var before = await f.StateAsync();
        var store = new PostgresWarehouseStore(f.Data);
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        await using (var connection = await f.Data.OpenConnectionAsync(Token))
        await using (var transaction = await connection.BeginTransactionAsync(Token))
        {
            var result = await store.TransferInTransactionAsync(transaction, f.Owner, write, Token);
            Assert.True(result.Applied);
            // A separate connection must still see the old committed state.
            Assert.Equal(before, await f.StateAsync());
            if (commit) await transaction.CommitAsync(Token);
            else await transaction.RollbackAsync(Token);
        }
        if (!commit) Assert.Equal(before, await f.StateAsync());
        else
        {
            Assert.Equal(3, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token)).Balances[Ship]);
            Assert.Equal(2, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Destination, Token)).Balances[Ship]);
            Assert.Equal(1, await f.ScalarAsync("SELECT count(*) FROM original_warehouse_transfer_request"));
        }
        // A rolled-back attempt must be retryable; a committed one must replay.
        Assert.Equal(!commit, (await store.TransferAsync(f.Owner, write, Token)).Applied);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Late_sql_failure_rolls_back_stock_and_same_transaction_point_debit()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.ExecuteAsync("UPDATE character SET mcp=200 WHERE character_id=$1", f.OwnerCharacter);
        var before = await f.StateAsync();
        await using (var connection = await f.Data.OpenConnectionAsync(Token))
        await using (var transaction = await connection.BeginTransactionAsync(Token))
        {
            await new PostgresWarehouseStore(f.Data).TransferInTransactionAsync(transaction, f.Owner,
                f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2)), Token);
            // Exercise transaction composition, not the native point policy handler.
            await using var debit = new NpgsqlCommand("UPDATE character SET mcp=mcp-160 WHERE character_id=$1", connection, transaction);
            debit.Parameters.AddWithValue(f.OwnerCharacter);
            Assert.Equal(1, await debit.ExecuteNonQueryAsync(Token));
            await using var failure = new NpgsqlCommand("SELECT 1/0", connection, transaction);
            var error = await Assert.ThrowsAsync<PostgresException>(() => failure.ExecuteScalarAsync(Token));
            Assert.Equal("22012", error.SqlState);
            await transaction.RollbackAsync(Token);
        }
        Assert.Equal(before, await f.StateAsync());
        Assert.Equal(200, await f.ScalarAsync("SELECT mcp FROM character WHERE character_id=" + f.OwnerCharacter));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Mixed_direction_exchange_conserves_stock_and_replays_once()
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Destination, Troops, 9);
        var store = new PostgresWarehouseStore(f.Data);
        var write = f.Transfer(f.OwnerCharacter, new(Ship, 2), new(Troops, -3));
        Assert.True((await store.TransferAsync(f.Owner, write, Token)).Applied);
        var source = await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token);
        var destination = await store.ReadAsync(f.Owner, f.OwnerCharacter, Destination, Token);
        Assert.Equal(3, source.Balances[Ship]);
        Assert.Equal(3, source.Balances[Troops]);
        Assert.Equal(2, destination.Balances[Ship]);
        Assert.Equal(6, destination.Balances[Troops]);
        var before = await f.StateAsync();
        Assert.False((await store.TransferAsync(f.Owner, write, Token)).Applied);
        Assert.Equal(before, await f.StateAsync());
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Reverse_leg_failure_rolls_back_both_directions(bool overflow)
    {
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Destination, Troops, overflow ? 3 : 1);
        if (overflow) await f.SeedAsync(Source, Troops, 65535);
        var before = await f.StateAsync();
        var error = await Assert.ThrowsAsync<InvalidOperationException>(() =>
            new PostgresWarehouseStore(f.Data).TransferAsync(f.Owner,
                f.Transfer(f.OwnerCharacter, new(Ship, 2), new(Troops, -3)), Token));
        Assert.Equal(overflow ? "WAREHOUSE_STOCK_OVERFLOW" : "WAREHOUSE_INSUFFICIENT_STOCK", error.Message);
        Assert.Equal(before, await f.StateAsync());
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Shared_stock_is_durable_and_competing_accounts_cannot_spend_the_last_unit_twice()
    {
        // Catches account-partitioned inventories, lost durable writes, and unlocked stock debits.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Source, Boats, 11);
        await f.SeedAsync(Source, Troops, 13);
        await f.SeedAsync(Source, Supplies, 17);
        await f.SeedAsync(Source, new(OriginalStockKind.Food), 19);
        await f.SeedAsync(Source, new(OriginalStockKind.Mineral), 23);
        var store = new PostgresWarehouseStore(f.Data);

        var first = await store.TransferAsync(f.Owner, f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2)), Token);
        Assert.True(first.Applied);
        Assert.Equal(1L, first.SourceVersion);
        Assert.Equal(1L, first.DestinationVersion);
        Assert.Equal(1L, first.AuthorityVersion);
        var source = await store.ReadAsync(f.Other, f.OtherCharacter, Source, Token);
        Assert.Equal(3L, source.Balances[Ship]);
        Assert.Equal(2L, (await store.ReadAsync(f.Other, f.OtherCharacter, Destination, Token)).Balances[Ship]);
        Assert.Equal(11L, source.Balances[Boats]);
        Assert.Equal(13L, source.Balances[Troops]);
        Assert.Equal(17L, source.Balances[Supplies]);
        Assert.Equal(19L, source.Balances[new(OriginalStockKind.Food)]);
        Assert.Equal(23L, source.Balances[new(OriginalStockKind.Mineral)]);
        await using (var reopened = NpgsqlDataSource.Create(f.ConnectionString))
        {
            var persisted = await new PostgresWarehouseStore(reopened).ReadAsync(f.Owner, f.OwnerCharacter, Source, Token);
            Assert.Equal(1L, persisted.Version);
            Assert.Equal(3L, persisted.Balances[Ship]);
        }

        await store.TransferAsync(f.Owner, f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2)), Token);
        var requests = new[]
        {
            (f.Owner, f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 1))),
            (f.Other, f.Transfer(f.OtherCharacter, new OriginalStockTransferLine(Ship, 1)) with { Destination = OtherDestination })
        };
        var errors = await Task.WhenAll(requests.Select(async request =>
            await Record.ExceptionAsync(async () =>
                Assert.True((await store.TransferAsync(request.Item1, request.Item2, Token)).Applied))));
        Assert.Single(errors, error => error is null);
        Assert.Equal("WAREHOUSE_INSUFFICIENT_STOCK", Assert.IsType<InvalidOperationException>(Assert.Single(errors, error => error is not null)).Message);
        Assert.Equal(0L, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token)).Balances.GetValueOrDefault(Ship));
        Assert.Equal(5L, await f.ScalarAsync("SELECT sum(quantity)::bigint FROM original_warehouse_balance WHERE stock_kind=0"));
        Assert.Equal(3L, await f.ScalarAsync("SELECT count(*) FROM original_warehouse_transfer_request"));
        Assert.Equal(3L, await f.ScalarAsync("SELECT count(*) FROM domain_event WHERE event_type='OriginalWarehouseTransferred'"));
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Concurrent_replays_apply_once_and_canonical_replays_never_rewind_newer_stock()
    {
        // Catches duplicate debit/event commits, order-sensitive fingerprints, and replay overwrites.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Source, Supplies, 10);
        var store = new PostgresWarehouseStore(f.Data);
        var write = f.Transfer(f.OwnerCharacter, new(Ship, 2), new(Supplies, 3));
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => store.TransferAsync(f.Owner, write, Token)));
        Assert.Single(results, result => result.Applied);
        Assert.All(results, result =>
        {
            Assert.Equal(1L, result.SourceVersion);
            Assert.Equal(1L, result.DestinationVersion);
            Assert.Equal(1L, result.AuthorityVersion);
        });
        Assert.Equal(1L, await f.ScalarAsync("SELECT count(*) FROM original_warehouse_transfer_request"));
        Assert.Equal(1L, await f.ScalarAsync("SELECT count(*) FROM domain_event WHERE event_type='OriginalWarehouseTransferred'"));
        await store.TransferAsync(f.Owner, f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 1)), Token);
        var beforeReplay = await f.StateAsync();
        Assert.False((await store.TransferAsync(f.Owner, write with { Lines = write.Lines.Reverse().ToArray() }, Token)).Applied);
        Assert.Equal(beforeReplay, await f.StateAsync());
        var conflict = await Assert.ThrowsAsync<InvalidOperationException>(() => store.TransferAsync(f.Owner,
            write with { Lines = new[] { new OriginalStockTransferLine(Ship, 1), new OriginalStockTransferLine(Supplies, 3) } }, Token));
        Assert.Equal("WAREHOUSE_REQUEST_CONFLICT", conflict.Message);
        Assert.Equal(beforeReplay, await f.StateAsync());
        Assert.Equal(2L, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token)).Balances[Ship]);
        Assert.Equal(3L, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Destination, Token)).Balances[Ship]);
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Access_checks_and_invalid_lines_reject_without_creating_or_mutating_rows()
    {
        // Catches missing ownership/write-grant checks and malformed transfers reaching persistence.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        var store = new PostgresWarehouseStore(f.Data);
        await f.ExecuteAsync("UPDATE original_warehouse_access SET can_write=false WHERE account_id=$1", f.Other);
        var before = await f.StateAsync();
        Assert.Equal(5L, (await store.ReadAsync(f.Other, f.OtherCharacter, Source, Token)).Balances[Ship]);
        await DeniedAsync(() => store.ReadAsync(f.Outsider, f.OutsiderCharacter, Source, Token));
        await DeniedAsync(() => store.ReadAsync(f.Owner, f.OtherCharacter, Source, Token));
        await DeniedAsync(() => store.TransferAsync(f.Outsider, f.Transfer(f.OutsiderCharacter, new OriginalStockTransferLine(Ship, 1)), Token));
        await DeniedAsync(() => store.TransferAsync(f.Owner, f.Transfer(f.OtherCharacter, new OriginalStockTransferLine(Ship, 1)), Token));
        await DeniedAsync(() => store.TransferAsync(f.Other, f.Transfer(f.OtherCharacter, new OriginalStockTransferLine(Ship, 1)), Token));
        await DeniedAsync(() => store.TransferAsync(f.Owner,
            f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 1)) with { Destination = new(987, 654) }, Token));
        Assert.Equal(before, await f.StateAsync());

        OriginalStockTransferLine[][] invalidLines =
        [
            [],
            [new(Ship, 1), new(Ship, 2)],
            [new(Ship, 0)],
            [new(Ship, -256)],
            [new(Ship, long.MinValue)],
            [new(new((OriginalStockKind)99), 1)],
            [new(new(OriginalStockKind.Supplies, 1), 1)],
            [new(new(OriginalStockKind.Food, 1), 1)],
            [new(new(OriginalStockKind.Mineral, 1), 1)],
            [new(new(OriginalStockKind.ShipUnits, 7, 1), 1)],
            [new(new(OriginalStockKind.ShipBoats, 7, 1), 1)],
            [new(new(OriginalStockKind.Supplies, 0, 1), 1)]
        ];
        foreach (var lines in invalidLines)
            await Assert.ThrowsAnyAsync<ArgumentException>(() => store.TransferAsync(f.Owner, f.Transfer(f.OwnerCharacter, lines), Token));
        Assert.Equal(before, await f.StateAsync());
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Insufficient_later_line_and_destination_capacity_overflow_leave_every_row_unchanged()
    {
        // Catches partial multi-line updates and quantity bounds being applied only to incoming deltas.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Source, Supplies, 1);
        var store = new PostgresWarehouseStore(f.Data);
        var before = await f.StateAsync();
        var insufficient = await Assert.ThrowsAsync<InvalidOperationException>(() => store.TransferAsync(f.Owner,
            f.Transfer(f.OwnerCharacter, new(Ship, 2), new(Supplies, 2)), Token));
        Assert.Equal("WAREHOUSE_INSUFFICIENT_STOCK", insufficient.Message);
        Assert.Equal(before, await f.StateAsync());

        (OriginalStockKey Stock, long Maximum)[] limits =
        [
            (Ship, 255), (Boats, 65535), (Troops, 65535),
            (Supplies, 4294967295), (new(OriginalStockKind.Food), 4294967295),
            (new(OriginalStockKind.Mineral), 4294967295)
        ];
        foreach (var (stock, maximum) in limits)
        {
            await f.SeedAsync(Source, stock, 1);
            await f.SeedAsync(Destination, stock, maximum);
            var beforeOverflow = await f.StateAsync();
            var overflow = await Assert.ThrowsAsync<InvalidOperationException>(() => store.TransferAsync(f.Owner,
                f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(stock, 1)), Token));
            Assert.Equal("WAREHOUSE_STOCK_OVERFLOW", overflow.Message);
            Assert.Equal(beforeOverflow, await f.StateAsync());
        }
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Event_insert_failure_rolls_back_balances_versions_authority_and_request_receipt()
    {
        // Catches committing any debit, credit, version, or receipt outside the event transaction.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        var store = new PostgresWarehouseStore(f.Data);
        await f.ExecuteAsync("ALTER TABLE domain_event ADD CONSTRAINT reject_warehouse_event CHECK(event_type <> 'OriginalWarehouseTransferred') NOT VALID");
        var before = await f.StateAsync();
        var write = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2));
        var error = await Assert.ThrowsAsync<PostgresException>(() => store.TransferAsync(f.Owner, write, Token));
        Assert.Equal(PostgresErrorCodes.CheckViolation, error.SqlState);
        Assert.Equal(before, await f.StateAsync());
        Assert.Equal(5L, (await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token)).Balances[Ship]);
        Assert.Empty((await store.ReadAsync(f.Owner, f.OwnerCharacter, Destination, Token)).Balances);
        // The failed transaction must not consume its request id.
        await f.ExecuteAsync("ALTER TABLE domain_event DROP CONSTRAINT reject_warehouse_event");
        Assert.True((await store.TransferAsync(f.Owner, write, Token)).Applied);
        Assert.Equal(1L, await f.ScalarAsync("SELECT count(*) FROM original_warehouse_transfer_request"));
        Assert.Equal(1L, await f.ScalarAsync("SELECT count(*) FROM domain_event WHERE event_type='OriginalWarehouseTransferred'"));
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Opposite_direction_transfers_across_accounts_complete_without_deadlock_and_conserve_stock()
    {
        // Catches acquiring warehouse locks in caller-dependent source/destination order.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 1);
        await f.SeedAsync(Destination, Ship, 1);
        var store = new PostgresWarehouseStore(f.Data);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(Token);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        var forward = f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 1));
        var reverse = f.Transfer(f.OtherCharacter, new OriginalStockTransferLine(Ship, 1)) with
        {
            Source = Destination,
            Destination = Source
        };
        var results = await Task.WhenAll(
            store.TransferAsync(f.Owner, forward, deadline.Token),
            store.TransferAsync(f.Other, reverse, deadline.Token)).WaitAsync(TimeSpan.FromSeconds(10), Token);
        Assert.All(results, result => Assert.True(result.Applied));
        var source = await store.ReadAsync(f.Owner, f.OwnerCharacter, Source, Token);
        var destination = await store.ReadAsync(f.Other, f.OtherCharacter, Destination, Token);
        Assert.Equal(1L, source.Balances[Ship]);
        Assert.Equal(1L, destination.Balances[Ship]);
        Assert.Equal(2L, source.Version);
        Assert.Equal(2L, destination.Version);
        Assert.Equal(2L, await f.ScalarAsync("SELECT sum(quantity)::bigint FROM original_warehouse_balance"));
        Assert.Equal(2L, await f.ScalarAsync("SELECT count(*) FROM original_warehouse_transfer_request"));
        Assert.Equal(2L, await f.ScalarAsync("SELECT count(*) FROM domain_event WHERE event_type='OriginalWarehouseTransferred'"));
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Full_ship_and_troop_group_capacity_rejects_new_groups_but_allows_existing_group_increments()
    {
        // Catches allowing a new projected group past capacity or treating an existing group as a new one.
        await using var f = await Fixture.CreateAsync();
        for (ushort item = 1; item <= 99; item++)
            await f.SeedAsync(Destination, new(OriginalStockKind.ShipUnits, item), 1);
        for (ushort item = 1; item <= 24; item++)
            await f.SeedAsync(Destination, new(OriginalStockKind.Troops, item), 1);
        var existingShip = new OriginalStockKey(OriginalStockKind.ShipUnits, 1);
        var newShip = new OriginalStockKey(OriginalStockKind.ShipUnits, 100);
        var existingTroop = new OriginalStockKey(OriginalStockKind.Troops, 1);
        var newTroop = new OriginalStockKey(OriginalStockKind.Troops, 25);
        foreach (var stock in new[] { existingShip, newShip, existingTroop, newTroop })
            await f.SeedAsync(Source, stock, 1);
        var store = new PostgresWarehouseStore(f.Data);
        var before = await f.StateAsync();
        foreach (var stock in new[] { newShip, newTroop })
        {
            var rejected = await Assert.ThrowsAsync<InvalidOperationException>(() => store.TransferAsync(f.Owner,
                f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(stock, 1)), Token));
            Assert.Equal("WAREHOUSE_CAPACITY_EXCEEDED", rejected.Message);
            Assert.Equal(before, await f.StateAsync());
        }
        Assert.True((await store.TransferAsync(f.Owner, f.Transfer(f.OwnerCharacter,
            new(existingShip, 1), new(existingTroop, 1)), Token)).Applied);
        var destination = await store.ReadAsync(f.Owner, f.OwnerCharacter, Destination, Token);
        Assert.Equal(2L, destination.Balances[existingShip]);
        Assert.Equal(2L, destination.Balances[existingTroop]);
        Assert.Equal(99, destination.Balances.Count(pair => pair.Key.Kind == OriginalStockKind.ShipUnits && pair.Value > 0));
        Assert.Equal(24, destination.Balances.Count(pair => pair.Key.Kind == OriginalStockKind.Troops && pair.Value > 0));
    }

    [Fact(Skip = "Requires isolated PostgreSQL set by LOGH7_RETURN_BASE_TEST_DB", SkipUnless = nameof(HasTestDatabase))]
    public async Task Encrypted_warehouse_queries_project_durable_shared_stock_and_enforce_stored_grants_without_writes()
    {
        // Catches session-authored stock, stale per-session inventories, broken wire projection, and missing grant checks.
        // Session authentication/world entry is fixture setup; the encrypted request and PostgreSQL reads are real.
        await using var f = await Fixture.CreateAsync();
        await f.SeedAsync(Source, Ship, 5);
        await f.SeedAsync(Source, Boats, 11);
        await f.SeedAsync(Source, Troops, 13);
        await f.SeedAsync(Source, Supplies, 17);
        await f.SeedAsync(Source, new(OriginalStockKind.Food), 19);
        await f.SeedAsync(Source, new(OriginalStockKind.Mineral), 23);
        var key = new byte[OriginalClientCipherHandshake.SessionKeyLength];

        NaturalAuthoritySession NewSession(NpgsqlDataSource data, Guid account, long characterId)
        {
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
                store: new PostgresAccountStore(data));
            OriginalWarpSessionClockTests.SetField(session, "_accountId", account);
            OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", checked((uint)characterId));
            return session;
        }

        Task<NaturalAuthoritySessionResult> QueryAsync(NaturalAuthoritySession session, OriginalWarehouseKey warehouse)
        {
            var application = OriginalWarehouseCodec.EncodeRequest(new(warehouse.BaseId, warehouse.OutfitId)).AsSpan(4).ToArray();
            return session.ProcessAsync(0x0030, OriginalClientInnerFrameCodec.Encode(application, key, 1), Token);
        }

        OriginalWarehouseResponse DecodeResponse(NaturalAuthoritySessionResult result, OriginalWarehouseKey warehouse)
        {
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
            Assert.Equal((ushort)0x0326, result.ObservedApplicationType);
            Assert.NotNull(result.ResponsePayload);
            var decoded = OriginalClientInnerFrameCodec.Decode(result.ResponsePayload, key, 0);
            Assert.Equal(OriginalClientInnerFrameStatus.Success, decoded.Status);
            Assert.True(OriginalWarehouseCodec.TryDecodeResponse(decoded.Payload!.AsSpan(4), out var response));
            Assert.Equal(warehouse.BaseId, response.BaseId);
            Assert.Equal(warehouse.OutfitId, response.OutfitId);
            // NEW_DESIGN index zero: warehouse version is not an observed original index meaning.
            Assert.Equal(0u, response.Index);
            return response;
        }

        var beforeInitialRead = await f.StateAsync();
        var initial = DecodeResponse(await QueryAsync(NewSession(f.Data, f.Owner, f.OwnerCharacter), Source), Source);
        Assert.Equal(new OriginalWarehouseShip(7, 5, 11), Assert.Single(initial.Ships));
        Assert.Equal(beforeInitialRead, await f.StateAsync());

        await new PostgresWarehouseStore(f.Data).TransferAsync(f.Owner,
            f.Transfer(f.OwnerCharacter, new OriginalStockTransferLine(Ship, 2)), Token);
        var afterTransfer = await f.StateAsync();
        await using var reopened = NpgsqlDataSource.Create(f.ConnectionString);
        var source = DecodeResponse(await QueryAsync(NewSession(reopened, f.Other, f.OtherCharacter), Source), Source);
        var destination = DecodeResponse(await QueryAsync(NewSession(reopened, f.Owner, f.OwnerCharacter), Destination), Destination);
        Assert.Equal(new OriginalWarehouseShip(7, 3, 11), Assert.Single(source.Ships));
        Assert.Equal(new OriginalWarehouseTroop(9, 2, 13), Assert.Single(source.Troops));
        Assert.Equal(17u, source.Supplies);
        Assert.Equal(19u, source.Food);
        Assert.Equal(23u, source.Mineral);
        Assert.Equal(new OriginalWarehouseShip(7, 2, 0), Assert.Single(destination.Ships));
        Assert.Empty(destination.Troops);
        Assert.Equal(0u, destination.Supplies);
        Assert.Equal(0u, destination.Food);
        Assert.Equal(0u, destination.Mineral);
        Assert.Equal(afterTransfer, await f.StateAsync());

        var deniedAccount = await QueryAsync(NewSession(reopened, f.Outsider, f.OutsiderCharacter), Source);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, deniedAccount.Status);
        Assert.Equal("original.warehouse.access-denied", deniedAccount.ErrorCode);
        Assert.Null(deniedAccount.ResponsePayload);
        Assert.Equal(afterTransfer, await f.StateAsync());

        await f.ExecuteAsync("DELETE FROM original_warehouse_access WHERE account_id=$1 AND character_id=$2", f.Owner, f.OwnerCharacter);
        var afterGrantRevocation = await f.StateAsync();
        var deniedCharacter = await QueryAsync(NewSession(reopened, f.Owner, f.OwnerCharacter), Source);
        Assert.Equal(NaturalAuthoritySessionStatus.Invalid, deniedCharacter.Status);
        Assert.Equal("original.warehouse.access-denied", deniedCharacter.ErrorCode);
        Assert.Null(deniedCharacter.ResponsePayload);
        Assert.Equal(afterGrantRevocation, await f.StateAsync());
    }

    private static async Task DeniedAsync(Func<Task> action)
    {
        var denied = await Assert.ThrowsAsync<InvalidOperationException>(action);
        Assert.Equal("WAREHOUSE_ACCESS_DENIED", denied.Message);
    }

    private sealed class Fixture(NpgsqlDataSource data, string connectionString) : IAsyncDisposable
    {
        public NpgsqlDataSource Data { get; } = data;
        public string ConnectionString { get; } = connectionString;
        public Guid Owner { get; } = Guid.NewGuid();
        public Guid Other { get; } = Guid.NewGuid();
        public Guid Outsider { get; } = Guid.NewGuid();
        public long OwnerCharacter { get; private set; }
        public long OtherCharacter { get; private set; }
        public long OutsiderCharacter { get; private set; }

        public static async Task<Fixture> CreateAsync()
        {
            var original = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
            var schema = "warehouse_test_" + Guid.NewGuid().ToString("N");
            // A new random test-owned schema only. Never delete or seed production data.
            await using (var admin = NpgsqlDataSource.Create(original))
            await using (var command = admin.CreateCommand("CREATE SCHEMA " + schema))
                await command.ExecuteNonQueryAsync(Token);
            var connectionString = new NpgsqlConnectionStringBuilder(original) { SearchPath = schema }.ConnectionString;
            var fixture = new Fixture(NpgsqlDataSource.Create(connectionString), connectionString);
            await PostgresMigrationRunner.ApplyAllAsync(fixture.Data, PostgresMigrationRunner.MigrationDirectory, Token);
            await PostgresMigrationRunner.ApplyAllAsync(fixture.Data, PostgresMigrationRunner.MigrationDirectory, Token);
            fixture.OwnerCharacter = await fixture.SeedCharacterAsync(fixture.Owner, "warehouse_owner");
            fixture.OtherCharacter = await fixture.SeedCharacterAsync(fixture.Other, "warehouse_other");
            fixture.OutsiderCharacter = await fixture.SeedCharacterAsync(fixture.Outsider, "warehouse_outsider");
            foreach (var key in new[] { Source, Destination, OtherDestination })
            {
                await fixture.ExecuteAsync("INSERT INTO original_warehouse(base_id,outfit_id) VALUES($1,$2)", (long)key.BaseId, (long)key.OutfitId);
                await fixture.ExecuteAsync("""
                    INSERT INTO original_warehouse_access(base_id,outfit_id,account_id,character_id,can_write)
                    VALUES($1,$2,$3,$4,true),($1,$2,$5,$6,true)
                    """, (long)key.BaseId, (long)key.OutfitId, fixture.Owner, fixture.OwnerCharacter, fixture.Other, fixture.OtherCharacter);
            }
            return fixture;
        }

        public OriginalWarehouseTransfer Transfer(long characterId, params OriginalStockTransferLine[] lines) =>
            new(Guid.NewGuid(), characterId, Source, Destination, lines);

        public Task SeedAsync(OriginalWarehouseKey key, OriginalStockKey stock, long quantity) => ExecuteAsync("""
            INSERT INTO original_warehouse_balance(base_id,outfit_id,stock_kind,item_kind,grade,quantity)
            VALUES($1,$2,$3,$4,$5,$6)
            ON CONFLICT(base_id,outfit_id,stock_kind,item_kind,grade) DO UPDATE SET quantity=EXCLUDED.quantity
            """, (long)key.BaseId, (long)key.OutfitId, (short)stock.Kind, (int)stock.ItemKind, (short)stock.Grade, quantity);

        public async Task ExecuteAsync(string sql, params object[] parameters)
        {
            await using var command = Data.CreateCommand(sql);
            foreach (var parameter in parameters) command.Parameters.AddWithValue(parameter);
            await command.ExecuteNonQueryAsync(Token);
        }

        public async Task<long> ScalarAsync(string sql, params object[] parameters)
        {
            await using var command = Data.CreateCommand(sql);
            foreach (var parameter in parameters) command.Parameters.AddWithValue(parameter);
            return (long)(await command.ExecuteScalarAsync(Token))!;
        }

        public async Task<string> StateAsync()
        {
            await using var command = Data.CreateCommand("""
                SELECT jsonb_build_object(
                  'warehouses',(SELECT jsonb_agg(to_jsonb(w) ORDER BY base_id,outfit_id) FROM original_warehouse w),
                  'balances',(SELECT jsonb_agg(to_jsonb(b) ORDER BY base_id,outfit_id,stock_kind,item_kind,grade) FROM original_warehouse_balance b),
                  'grants',(SELECT jsonb_agg(to_jsonb(g) ORDER BY base_id,outfit_id,account_id,character_id) FROM original_warehouse_access g),
                  'receipts',(SELECT jsonb_agg(to_jsonb(r) ORDER BY account_id,request_id) FROM original_warehouse_transfer_request r),
                  'events',(SELECT jsonb_agg(to_jsonb(e) ORDER BY to_jsonb(e)::text) FROM domain_event e),
                  'accounts',(SELECT jsonb_agg(to_jsonb(a) ORDER BY account_id) FROM account a),
                  'characters',(SELECT jsonb_agg(to_jsonb(c) ORDER BY character_id) FROM character c)
                )::text
                """);
            return (string)(await command.ExecuteScalarAsync(Token))!;
        }

        private async Task<long> SeedCharacterAsync(Guid account, string login)
        {
            await ExecuteAsync("""
                INSERT INTO account(account_id, normalized_login, password_hash, password_salt,
                    argon_memory_kib, argon_iterations, argon_parallelism, status, authority_version, authority_state_hash)
                VALUES($1,$2,decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',0,repeat('0',64))
                """, account, login);
            return await ScalarAsync("""
                INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                    last_name,first_name,flagship_name,face,ability_values,authority_version)
                VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Flag',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
                RETURNING character_id
                """, account);
        }

        public ValueTask DisposeAsync() => Data.DisposeAsync();
    }
}
