using System.Buffers.Binary;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalPlayerUnitIdentityPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;
    private static CancellationToken Token => TestContext.Current.CancellationToken;

    [Fact(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    public async Task Every_account_and_character_slot_can_enter_with_a_distinct_owned_unit()
    {
        await using var f = await Fixture.Create();
        await PostgresMigrationRunner.ApplyAllAsync(f.Data, PostgresMigrationRunner.MigrationDirectory, Token);
        var first = await f.Account("unit_first");
        var second = await f.Account("unit_second");
        var characters = new[] { (first, await f.Character(first, 0)), (first, await f.Character(first, 1)),
            (second, await f.Character(second, 0)) };
        var units = new List<uint>();
        foreach (var (account, character) in characters)
        {
            var unit = await new PostgresAccountStore(f.Data).FindOriginalGridUnitAsync(account, character, checked((uint)character), Token);
            Assert.NotNull(unit); // Old trigger always2 fails for character1 and skips a second slot.
            Assert.Equal(character, unit.CharacterId);
            units.Add(unit.UnitId);
            Assert.Null(await new PostgresAccountStore(f.Data).FindOriginalGridUnitAsync(
                account == first ? second : first, character, unit.UnitId, Token));
            var key = new byte[16];
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
                store: new PostgresAccountStore(f.Data));
            OriginalWarpSessionClockTests.SetField(session, "_accountId", account);
            OriginalWarpSessionClockTests.SetField(session, "_lobbySelectionValue", checked((ushort)character));
            var result = await session.ProcessAsync(0x30,
                OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), Token);
            Assert.Equal(NaturalAuthoritySessionStatus.Success, result.Status);
            var frames = result.AdditionalResponses!.Select(p =>
                OriginalClientInnerFrameCodec.Decode(p.Payload, key, 0).Payload!).ToArray();
            var projection = Assert.Single(frames, p => BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(4)) == 0x325);
            Assert.Equal(unit.UnitId, BinaryPrimitives.ReadUInt32BigEndian(projection.AsSpan(8)));
        }
        Assert.Equal(3, units.Distinct().Count());
    }

    [Fact(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    public async Task Legacy_identity_migration_preserves_injury_history_and_backfills_the_previously_skipped_slot()
    {
        await using var f = await Fixture.Create();
        foreach (var file in Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql")
            .Where(p => string.CompareOrdinal(Path.GetFileName(p), "0021") < 0).Order(StringComparer.Ordinal))
            await PostgresMigrationRunner.ApplyAsync(f.Data, file, Token);
        var owner = await f.Account("legacy_identity");
        var first = await f.Character(owner, 0);
        var second = await f.Character(owner, 1);
        var defeat = Guid.NewGuid();
        await f.Sql("UPDATE original_grid_unit SET current_cell_id=102,base_id=2,damaged=100,destroyed=100,injury_return_id=$2,injury_return_request_hash=repeat('a',64) WHERE account_id=$1", owner, defeat);
        await f.Sql("INSERT INTO domain_event(account_id,aggregate_type,aggregate_id,event_type,payload,authority_version) VALUES($1,'original-grid-unit','2','IdentityTestHistory','{\"unitId\":2}'::jsonb,1)", owner);
        var history = await f.Text("SELECT to_jsonb(e)::text FROM domain_event e WHERE event_type='IdentityTestHistory'");
        await PostgresMigrationRunner.ApplyAllAsync(f.Data, PostgresMigrationRunner.MigrationDirectory, Token);
        await PostgresMigrationRunner.ApplyAllAsync(f.Data, PostgresMigrationRunner.MigrationDirectory, Token);
        var store = new PostgresAccountStore(f.Data);
        var firstUnit = await store.FindOriginalGridUnitAsync(owner, first, checked((uint)first), Token);
        Assert.NotNull(firstUnit);
        Assert.Equal((102u,2u,(ushort)100,(ushort)100,defeat,1L),
            (firstUnit.CurrentCellId,firstUnit.BaseId,firstUnit.Damaged,firstUnit.Destroyed,firstUnit.InjuryReturnId,firstUnit.AuthorityVersion));
        Assert.NotNull(await store.FindOriginalGridUnitAsync(owner, second, checked((uint)second), Token));
        Assert.Equal(history, await f.Text("SELECT to_jsonb(e)::text FROM domain_event e WHERE event_type='IdentityTestHistory'"));
        Assert.Equal(new string('a',64), await f.Text("SELECT injury_return_request_hash::text FROM original_grid_unit WHERE injury_return_id IS NOT NULL"));
    }

    [Fact(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    public async Task Two_database_backed_players_remain_distinct_targets_in_one_battle()
    {
        await using var f = await Fixture.Create();
        await PostgresMigrationRunner.ApplyAllAsync(f.Data, PostgresMigrationRunner.MigrationDirectory, Token);
        var empire = await f.Account("pvp_empire");
        var alliance = await f.Account("pvp_alliance");
        var first = checked((uint)await f.Character(empire,0));
        var second = checked((uint)await f.Character(alliance,0));
        await f.Sql("UPDATE character SET faction=3 WHERE account_id=$1",alliance);
        var battles = new OriginalTacticalBattleRegistry();
        var key = new byte[16];
        async Task<NaturalAuthoritySession> Enter(Guid account)
        {
            var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
                store:new PostgresAccountStore(f.Data),battles:battles);
            OriginalWarpSessionClockTests.SetField(session,"_accountId",account);
            foreach(var (hex,seq) in new[]{("0205",1u),("0F02",2u)})
                Assert.Equal(NaturalAuthoritySessionStatus.Success,(await session.ProcessAsync(0x30,
                    OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(hex),key,seq),Token)).Status);
            return session;
        }
        var actor = await Enter(empire);
        await Enter(alliance);
        Assert.Equal(new[]{first,second},battles.OtherParticipants(101,0).Select(p=>p.Unit.Id).ToArray());
        var shot = "040600000000000000000000000201"+first.ToString("X8")+"0001"+second.ToString("X8");
        var overlapping = await actor.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(shot),key,3),Token);
        Assert.StartsWith("command-reject=TACTICAL_TARGET_OUTSIDE_WEAPON_ARC",overlapping.ResponseMetadata);
        Assert.Equal((ushort)0,battles.GetEncounter(101,100).GetUnitDamage(second).Damaged);
        // Default world entry currently overlaps both players. Move through the
        // actual command path before testing target identity and durable damage.
        var move=OriginalTacticalCommandCodec.EncodeMoveShipCommand(new(0,0,first,
            [new(first,0,-10,0,0)],1,0,[new(-13,0,0)]));
        var moved=await actor.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(move.AsSpan(4).ToArray(),key,4),Token);
        Assert.Contains("tactical-move-ship-accepted",moved.ResponseMetadata);
        var result=await actor.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString(shot),key,5),Token);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,result.Status);
        var hit = Assert.Single(result.AdditionalResponses!.Select(p=>
            OriginalClientInnerFrameCodec.Decode(p.Payload,key,0).Payload!),
            p=>BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(4))==0x426);
        Assert.Equal(second,BinaryPrimitives.ReadUInt32BigEndian(hit.AsSpan(16)));
        Assert.Equal((ushort)25,BinaryPrimitives.ReadUInt16BigEndian(hit.AsSpan(20)));
        Assert.Equal((ushort)0,battles.GetEncounter(101,100).GetUnitDamage(first).Damaged);
        Assert.Equal((ushort)25,battles.GetEncounter(101,100).GetUnitDamage(second).Damaged);
    }

    [Fact(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    public async Task Unknown_existing_unit_mapping_aborts_migration_without_renumbering_or_history_writes()
    {
        await using var f = await Fixture.Create();
        foreach (var file in Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql")
            .Where(p=>string.CompareOrdinal(Path.GetFileName(p),"0021")<0).Order(StringComparer.Ordinal))
            await PostgresMigrationRunner.ApplyAsync(f.Data,file,Token);
        var owner=await f.Account("custom_unit");
        await f.Character(owner,0);
        await f.Sql("UPDATE original_grid_unit SET unit_id=99 WHERE account_id=$1",owner);
        var before=await f.Text("SELECT to_jsonb(u)::text FROM original_grid_unit u");
        var error=await Assert.ThrowsAsync<PostgresException>(()=>PostgresMigrationRunner.ApplyAllAsync(
            f.Data,PostgresMigrationRunner.MigrationDirectory,Token));
        Assert.Contains("PLAYER_UNIT_NONLEGACY_MAPPING_REQUIRES_REVIEW",error.Message);
        Assert.Equal(before,await f.Text("SELECT to_jsonb(u)::text FROM original_grid_unit u"));
        Assert.Equal("0",await f.Text("SELECT count(*)::text FROM schema_migration WHERE version LIKE '0021%'"));
        Assert.Equal("0",await f.Text("SELECT count(*)::text FROM information_schema.tables WHERE table_schema=current_schema() AND table_name='original_player_unit_identity_migration'"));
    }

    [Theory(Skip="Requires isolated PostgreSQL", SkipUnless=nameof(HasTestDatabase))]
    [InlineData(2130706433L)]
    [InlineData(2130706434L)]
    [InlineData(4294967296L)]
    public async Task Reserved_or_out_of_range_character_ids_cannot_enter_player_namespace(long reserved)
    {
        await using var f=await Fixture.Create();
        foreach(var file in Directory.GetFiles(PostgresMigrationRunner.MigrationDirectory,"*.sql")
            .Where(p=>string.CompareOrdinal(Path.GetFileName(p),"0021")<0).Order(StringComparer.Ordinal))
            await PostgresMigrationRunner.ApplyAsync(f.Data,file,Token);
        var owner=await f.Account("reserved_legacy");
        await f.Sql("""
            INSERT INTO character(character_id,account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            OVERRIDING SYSTEM VALUE VALUES($1,$2,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
            """,reserved,owner);
        var before=await f.Text("SELECT to_jsonb(u)::text FROM original_grid_unit u");
        await Assert.ThrowsAsync<PostgresException>(()=>PostgresMigrationRunner.ApplyAllAsync(
            f.Data,PostgresMigrationRunner.MigrationDirectory,Token));
        Assert.Equal(before,await f.Text("SELECT to_jsonb(u)::text FROM original_grid_unit u"));
        Assert.Equal("0",await f.Text("SELECT count(*)::text FROM schema_migration WHERE version LIKE '0021%'"));

        await using var fresh=await Fixture.Create();
        await PostgresMigrationRunner.ApplyAllAsync(fresh.Data,PostgresMigrationRunner.MigrationDirectory,Token);
        var freshOwner=await fresh.Account("reserved_new");
        await Assert.ThrowsAsync<PostgresException>(()=>fresh.Sql("""
            INSERT INTO character(character_id,account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            OVERRIDING SYSTEM VALUE VALUES($1,$2,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1)
            """,reserved,freshOwner));
        Assert.Equal("0",await fresh.Text("SELECT count(*)::text FROM character"));
        Assert.Equal("0",await fresh.Text("SELECT count(*)::text FROM original_grid_unit"));
    }

    private sealed class Fixture(NpgsqlDataSource data) : IAsyncDisposable
    {
        public NpgsqlDataSource Data => data;
        public static async Task<Fixture> Create()
        {
            var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
            var schema = "unit_identity_" + Guid.NewGuid().ToString("N");
            await using (var admin = NpgsqlDataSource.Create(connection))
            await using (var command = admin.CreateCommand("CREATE SCHEMA " + schema))
                await command.ExecuteNonQueryAsync(Token);
            return new(NpgsqlDataSource.Create(new NpgsqlConnectionStringBuilder(connection) { SearchPath=schema }.ConnectionString));
        }
        public async Task<Guid> Account(string name)
        {
            var id = Guid.NewGuid();
            await Sql("""
                INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                  argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
                VALUES($1,$2,decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
                """, id, name);
            return id;
        }
        public async Task<long> Character(Guid account,short slot)
        {
            await using var command = data.CreateCommand("""
                INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                  last_name,first_name,flagship_name,face,ability_values,authority_version,flagship_type,flagship_kind)
                VALUES($1,$2,$3,repeat('b',64),2,0,0,'Pilot','First','Ship',5,ARRAY[1,2,3,4,5,6,7,8]::smallint[],1,0,0)
                RETURNING character_id
                """);
            command.Parameters.AddWithValue(account); command.Parameters.AddWithValue(slot);
            command.Parameters.AddWithValue(slot.ToString().PadLeft(64,'0'));
            return (long)(await command.ExecuteScalarAsync(Token))!;
        }
        public async Task Sql(string sql,params object[] values)
        {
            await using var command=data.CreateCommand(sql);
            foreach(var value in values)command.Parameters.AddWithValue(value);
            await command.ExecuteNonQueryAsync(Token);
        }
        public async Task<string> Text(string sql)
        {
            await using var command=data.CreateCommand(sql);
            return (string)(await command.ExecuteScalarAsync(Token))!;
        }
        public ValueTask DisposeAsync()=>data.DisposeAsync();
    }
}
