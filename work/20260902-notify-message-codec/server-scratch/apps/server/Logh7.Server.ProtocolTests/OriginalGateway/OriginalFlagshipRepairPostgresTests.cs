using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using Logh7.Server.Authority;
using Logh7.Server.Hosting;
using Logh7.Server.Security;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 完全修復 (0x0C00). The manual prices the command at 160 points; the pool is
/// INFERRED. The repair, its charge and its history row commit together, and a
/// repair never resurrects a destroyed hull.
/// </summary>
public sealed class OriginalFlagshipRepairPostgresTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("accepted")]
    [InlineData("healthy-flagship")]
    [InlineData("escort-only")]
    [InlineData("event-failure")]
    [InlineData("revision=revision+1")]
    [InlineData("controller_ship_generation=controller_ship_generation+1")]
    [InlineData("controller_character_id=NULL,controller_unit_id=NULL,controller_ship_generation=NULL,autonomous=true")]
    [InlineData("supplies=0")]
    public async Task Fleet_repair_commits_together_or_rolls_back_on_last_stale_escort(string scenario)
    {
        var stale=scenario is not ("accepted" or "healthy-flagship" or "escort-only");
        var healthy=scenario=="healthy-flagship";
        var escortOnly=scenario=="escort-only";
        var eventFailure=scenario=="event-failure";
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var store=new PostgresAccountStore(data);
        await using var stage=data.CreateCommand("UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=2,supplies=100");
        await stage.ExecuteNonQueryAsync(ct);
        if(healthy)
        {
            await using var undamaged=data.CreateCommand("UPDATE original_grid_unit SET damaged=0,destroyed=0,supplies=37");
            await undamaged.ExecuteNonQueryAsync(ct);
        }
        var before=Assert.IsType<OriginalGridUnitRecord>(await store.FindOriginalGridUnitAsync(owner,actor,(uint)actor,ct));
        var fleet=new PostgresFleetUnitStore(data);
        var first=new OriginalFleetUnitRecord(900001,800001,57,2,0,before.CurrentCellId,100,40,10,
            0,0,0,0,1,actor,false,0,1,before.UnitId,before.ShipGeneration,100);
        var last=first with { UnitId=900002 };
        await fleet.EnsureCreatedAsync(first,ct);
        await fleet.EnsureCreatedAsync(last,ct);
        if(eventFailure)
        {
            await using var fail=data.CreateCommand("ALTER TABLE domain_event ADD CONSTRAINT fixture_repair_event_failure CHECK(event_type <> 'OriginalFlagshipRepaired')");
            await fail.ExecuteNonQueryAsync(ct);
        }
        else if(stale)
        {
            await using var change=data.CreateCommand("UPDATE original_fleet_unit SET "+scenario+" WHERE unit_id=900002");
            await change.ExecuteNonQueryAsync(ct);
        }
        var fleetBefore=await fleet.ReadGridAsync(before.CurrentCellId,ct);
        if(eventFailure)
        {
            var error=await Assert.ThrowsAsync<PostgresException>(()=>store.RepairOwnOriginalFlagshipAsync(owner,
                Write(77,before) with { Escorts=[first,last] },ct));
            Assert.Equal("23514",error.SqlState);
            Assert.Equal(before,await store.FindOriginalGridUnitAsync(owner,actor,before.UnitId,ct));
            Assert.Equal(fleetBefore,await fleet.ReadGridAsync(before.CurrentCellId,ct));
            Assert.Equal((1600u,1600u),await BalancesAsync(data,actor,ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_flagship_repair_command",ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM domain_event WHERE event_type='OriginalFlagshipRepaired'",ct));
            return;
        }
        var result=await store.RepairOwnOriginalFlagshipAsync(owner,Write(77,before) with { Escorts=[first,last], IncludeFlagship=!escortOnly },ct);
        Assert.Equal(stale ? OriginalFlagshipRepairStatus.Rejected : OriginalFlagshipRepairStatus.Repaired,result.Status);
        Assert.Equal(stale ? (1600u,1600u) : (1600u,1440u),await BalancesAsync(data,actor,ct));
        var current=await store.FindOriginalGridUnitAsync(owner,actor,before.UnitId,ct);
        Assert.Equal(healthy ? (ushort)0 : stale || escortOnly ? (ushort)60 : (ushort)25,current!.Damaged);
        Assert.Equal(healthy ? 37u : stale || escortOnly ? 100u : 0u,current.Supplies);
        var rows=await fleet.ReadGridAsync(before.CurrentCellId,ct);
        if(!stale)
        {
            Assert.NotNull(result.Escorts);
            Assert.Equal(rows.OrderBy(row=>row.UnitId),result.Escorts.OrderBy(row=>row.UnitId));
        }
        else Assert.Null(result.Escorts);
        if(stale) Assert.Equal(fleetBefore,rows);
        Assert.All(rows,row=> {
            Assert.Equal(stale ? (ushort)40 : (ushort)10,row.Damaged);
            Assert.Equal((ushort)10,row.Destroyed);
            if(!stale) Assert.Equal(0u,row.Supplies);
        });
        Assert.Equal(stale ? 0 : 1,await CountAsync(data,"SELECT count(*) FROM original_flagship_repair_command",ct));
        Assert.Equal(stale ? 0 : 1,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
        Assert.Equal(stale ? 0 : 1,await CountAsync(data,"SELECT count(*) FROM domain_event WHERE event_type='OriginalFlagshipRepaired'",ct));
        if(!stale)
        {
            var original=Write(77,before) with { Escorts=[first,last],IncludeFlagship=!escortOnly };
            var replay=await new PostgresAccountStore(data).RepairOwnOriginalFlagshipAsync(owner,original,ct);
            Assert.Equal(OriginalFlagshipRepairStatus.Replayed,replay.Status);
            foreach(var changed in new[]{ original with { Escorts=[first] }, original with { IncludeFlagship=escortOnly } })
            {
                var mismatch=await store.RepairOwnOriginalFlagshipAsync(owner,changed,ct);
                Assert.Equal(OriginalFlagshipRepairStatus.Rejected,mismatch.Status);
                Assert.Equal("FLEET_REPAIR_REPLAY_SELECTION_MISMATCH",mismatch.ErrorCode);
            }
            Assert.Equal((1600u,1440u),await BalancesAsync(data,actor,ct));
            Assert.Equal(rows,await fleet.ReadGridAsync(before.CurrentCellId,ct));
        }
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Wire_ordinary_promotion_can_repeat_after_demotion()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var stage=data.CreateCommand("UPDATE character SET rank=20");
        await stage.ExecuteNonQueryAsync(ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)actor);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)actor);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await using var balances=data.CreateCommand("UPDATE character SET pcp=1200,mcp=1100,points_accrued_at=now()");
        await balances.ExecuteNonQueryAsync(ct);
        var request=new byte[28];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x0704);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),uint.MaxValue);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(14),uint.MaxValue);
        request[18]=20;
        var first=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,first.Status);
        var response=OriginalClientInnerFrameCodec.Decode(first.ResponsePayload!,key,0).Payload!;
        Assert.Equal(1200u,BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(14)));
        Assert.Equal(1100u,BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(18)));
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=19",ct));
        await new PostgresAccountStore(data).PromoteCharacterAsync(owner,
            new CharacterRankUpWrite(new string('4',64),actor,19,20,"CharacterDemoted",actor),ct);
        var again=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,3),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,again.Status);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=19",ct));
        Assert.Equal(2,await CountAsync(data,"SELECT count(*) FROM domain_event WHERE event_type='CharacterRankPromoted'",ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("accepted")]
    [InlineData("insufficient")]
    [InlineData("write-failure")]
    public async Task Wire_demotion_reports_charge_and_rolls_back_failure(string scenario)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var stage=data.CreateCommand("UPDATE character SET rank=19");
        await stage.ExecuteNonQueryAsync(ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)actor);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)actor);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        if(scenario!="accepted")
        {
            await using var fault=data.CreateCommand(scenario=="insufficient"
                ? "UPDATE character SET pcp=0,mcp=0,points_accrued_at=now()"
                : "ALTER TABLE domain_event ADD CONSTRAINT fixture_no_demotion CHECK(event_type <> 'CharacterDemoted')");
            await fault.ExecuteNonQueryAsync(ct);
        }
        var request=new byte[36];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x0706);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)actor);
        request[18]=19;
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19),(uint)actor);
        var answer=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,answer.Status);
        if(scenario!="accepted")
        {
            Assert.DoesNotContain("-accepted",answer.ResponseMetadata ?? "");
            Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=19",ct));
            Assert.Equal(scenario=="insufficient" ? (0u,0u) : (1600u,1600u),await BalancesAsync(data,actor,ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
            return;
        }
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=20",ct));
        Assert.Equal((1600u,1440u),await BalancesAsync(data,actor,ct));
        var frame=OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!,key,0).Payload!;
        Assert.Equal(1440u,BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(18)));
        await new PostgresAccountStore(data).PromoteCharacterAsync(owner,
            new CharacterRankUpWrite(new string('5',64),actor,20,19),ct);
        var again=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,3),ct);
        Assert.Contains("rank-down-accepted",again.ResponseMetadata);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=20",ct));
        Assert.Equal((1600u,1280u),await BalancesAsync(data,actor,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Demotion_charges_actor_original160_mcp_and_preserves_target_balance()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var target=await SeedOtherCharacterAsync(data,owner,ct);
        await using var stage=data.CreateCommand("UPDATE character SET rank=19");
        await stage.ExecuteNonQueryAsync(ct);
        var store=new PostgresAccountStore(data);
        var command=new CharacterRankUpWrite(new string('6',64),target,19,20,"CharacterDemoted",actor,
            OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch);
        Assert.True((await store.PromoteCharacterAsync(owner,command,ct)).Updated);
        Assert.False((await new PostgresAccountStore(data).PromoteCharacterAsync(owner,command,ct)).Updated);
        Assert.Equal((1600u,1440u),await BalancesAsync(data,actor,ct));
        Assert.Equal((1600u,1600u),await BalancesAsync(data,target,ct));
        var characters=await store.ListCharactersAsync(owner,ct);
        Assert.Equal((short)19,characters.Single(c=>c.CharacterId==actor).Rank);
        Assert.Equal((short)20,characters.Single(c=>c.CharacterId==target).Rank);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Special_promotion_charges_actor_and_only_promotes_target_once()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var target=await SeedOtherCharacterAsync(data,owner,ct);
        await using var stage=data.CreateCommand("UPDATE character SET rank=20");
        await stage.ExecuteNonQueryAsync(ct);
        var write=new CharacterRankUpWrite(new string('7',64),target,20,19,
            "CharacterSpeciallyPromoted",actor,OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch);
        var store=new PostgresAccountStore(data);
        Assert.True((await store.PromoteCharacterAsync(owner,write,ct)).Updated);
        Assert.False((await new PostgresAccountStore(data).PromoteCharacterAsync(owner,write,ct)).Updated);
        var characters=await store.ListCharactersAsync(owner,ct);
        Assert.Equal((short)20,characters.Single(c=>c.CharacterId==actor).Rank);
        Assert.Equal((short)19,characters.Single(c=>c.CharacterId==target).Rank);
        Assert.Equal((1600u,1280u),await BalancesAsync(data,actor,ct));
        Assert.Equal((1600u,1600u),await BalancesAsync(data,target,ct));
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Rank_replay_rejects_changed_command_kind()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var stage=data.CreateCommand("UPDATE character SET rank=20");
        await stage.ExecuteNonQueryAsync(ct);
        var store=new PostgresAccountStore(data);
        var ordinary=new CharacterRankUpWrite(new string('f',64),actor,20,19);
        Assert.True((await store.PromoteCharacterAsync(owner,ordinary,ct)).Updated);
        Assert.False((await new PostgresAccountStore(data).PromoteCharacterAsync(owner,ordinary,ct)).Updated);
        var actorConflict=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.PromoteCharacterAsync(owner,
            ordinary with {ActorCharacterId=actor},ct));
        Assert.Equal("CHARACTER_RANK_UP_REPLAY_MISMATCH",actorConflict.Message);
        var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.PromoteCharacterAsync(owner,
            ordinary with {EventType="CharacterSpeciallyPromoted",ActorCharacterId=actor},ct));
        Assert.Equal("CHARACTER_RANK_UP_REPLAY_MISMATCH",error.Message);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,actor,ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("accepted")]
    [InlineData("insufficient")]
    [InlineData("write-failure")]
    public async Task Special_promotion_charges_original_320_mcp_with_rank_change(string scenario)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var stage=data.CreateCommand("UPDATE character SET rank=20");
        await stage.ExecuteNonQueryAsync(ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)actor);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)actor);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        if(scenario=="insufficient")
        {
            await using var empty=data.CreateCommand("UPDATE character SET pcp=0,mcp=0,points_accrued_at=now()");
            await empty.ExecuteNonQueryAsync(ct);
        }
        if(scenario=="write-failure")
        {
            await using var fail=data.CreateCommand("ALTER TABLE domain_event ADD CONSTRAINT fixture_fail_special CHECK(event_type <> 'CharacterSpeciallyPromoted')");
            await fail.ExecuteNonQueryAsync(ct);
        }
        var request=Convert.FromHexString("07050000000000000000000000000000000000000000140000000000000000000000");
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)actor);
        var answer=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        if(scenario!="accepted")
        {
            Assert.Equal(NaturalAuthoritySessionStatus.Success,answer.Status);
            Assert.DoesNotContain("special-rank-up-accepted",answer.ResponseMetadata ?? "");
            Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=20",ct));
            Assert.Equal(scenario=="insufficient" ? (0u,0u) : (1600u,1600u),await BalancesAsync(data,actor,ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM character_rank_command",ct));
            return;
        }
        Assert.Contains("special-rank-up-accepted",answer.ResponseMetadata);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=19",ct));
        Assert.Equal((1600u,1280u),await BalancesAsync(data,actor,ct));
        var response=OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!,key,0).Payload!;
        Assert.Equal(1280u,BinaryPrimitives.ReadUInt32BigEndian(response.AsSpan(18)));
        await new PostgresAccountStore(data).PromoteCharacterAsync(owner,
            new CharacterRankUpWrite(new string('8',64),actor,19,20,"CharacterDemoted",actor),ct);
        var repeated=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,3),ct);
        Assert.Contains("special-rank-up-accepted",repeated.ResponseMetadata);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM character WHERE rank=19",ct));
        Assert.Equal((1600u,800u),await BalancesAsync(data,actor,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Appointment_rechecks_changed_actor_post_before_charging()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var target=await SeedOtherCharacterAsync(data,owner,ct);
        var store=new PostgresAccountStore(data);
        var command=new CardAppointmentWrite(new string('e',64),actor,40,target,
            OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch,false,39,39);
        // Trusted route has resolved requiredpost39, but actor loses it before execution.
        await using var changed=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,41,$2,1)");
        changed.Parameters.AddWithValue(owner);changed.Parameters.AddWithValue(actor);
        await changed.ExecuteNonQueryAsync(ct);
        var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.AppointCardAsync(owner,command,ct));
        Assert.Equal("CARD_APPOINTMENT_AUTHORITY_MISMATCH",error.Message);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,actor,ct));
        Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_card_appointment",ct));
        Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
        Assert.DoesNotContain(await store.ListCharacterCardsAsync(owner,ct),c=>c.CharacterId==target);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Appointment_cannot_bypass_configured_appointer_by_direct_request()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var held=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,40,$2,1)");
        held.Parameters.AddWithValue(owner);held.Parameters.AddWithValue(actor);
        await held.ExecuteNonQueryAsync(ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)actor);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)actor);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var request=new byte[40];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x0707);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)actor);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(22),41);
        var answer=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.DoesNotContain("card-appointment-accepted",answer.ResponseMetadata ?? "");
        Assert.Contains(await new PostgresAccountStore(data).ListCharacterCardsAsync(owner,ct),c=>c.CharacterId==actor && c.CardId==40);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,actor,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Appointment_post_list_refreshes_after_actor_loses_post()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)actor);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)actor);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var request=new byte[OriginalSimpleCharacterRosterCodec.RequestMessageSize];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x1200);
        BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(6),0x12);
        async Task<int> PostCount(uint sequence)
        {
            var answer=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,sequence),ct);
            Assert.Equal(NaturalAuthoritySessionStatus.Success,answer.Status);
            return (answer.ResponsesBeforePrimary ?? []).Count(p=>
                BinaryPrimitives.ReadUInt16BigEndian(OriginalClientInnerFrameCodec.Decode(p.Payload,key,0).Payload!.AsSpan(4))==0x1208);
        }
        Assert.True(await PostCount(2)>0);
        await using var noPost=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,0,$2,1)");
        noPost.Parameters.AddWithValue(owner);noPost.Parameters.AddWithValue(actor);
        await noPost.ExecuteNonQueryAsync(ct);
        Assert.Equal(0,await PostCount(3));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Character_without_post_cannot_dismiss_another_character()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        var target=await SeedOtherCharacterAsync(data,owner,ct);
        await using var stage=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,0,$2,1),($1,$3,40,$2,1)");
        stage.Parameters.AddWithValue(owner);stage.Parameters.AddWithValue(actor);stage.Parameters.AddWithValue(target);
        await stage.ExecuteNonQueryAsync(ct);
        var store=new PostgresAccountStore(data);
        var error=await Assert.ThrowsAsync<InvalidOperationException>(()=>store.DismissCardAsync(owner,
            new CardDismissalWrite(new string('d',64),actor,40,target),ct));
        Assert.Equal("CARD_DISMISSAL_ACTOR_HAS_NO_POST",error.Message);
        Assert.Contains(await store.ListCharacterCardsAsync(owner,ct),c=>c.CharacterId==target && c.CardId==40);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,actor,ct));
        Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Character_without_a_post_cannot_appoint_itself_to_regain_authority()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        await using var noPost=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,0,$2,1)");
        noPost.Parameters.AddWithValue(owner);noPost.Parameters.AddWithValue(character);
        await noPost.ExecuteNonQueryAsync(ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var request=new byte[40];
        BinaryPrimitives.WriteUInt16BigEndian(request,0x0707);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(22),39);
        var response=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.DoesNotContain("card-appointment-accepted",response.ResponseMetadata ?? "");
        Assert.Contains(await new PostgresAccountStore(data).ListCharacterCardsAsync(owner,ct),c=>c.CharacterId==character && c.CardId==0);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,character,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Personnel_cost_is_charged_to_actor_not_target_and_replay_does_not_charge_again()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,actor)=await SeedAsync(data);
        await using var insert=data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,1,repeat('3',64),repeat('4',64),2,0,0,'Target','Second','Other',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """);
        insert.Parameters.AddWithValue(owner);
        var target=(long)(await insert.ExecuteScalarAsync(ct))!;
        var store=new PostgresAccountStore(data);
        var appointment=new CardAppointmentWrite(new string('b',64),actor,40,target,
            OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch);
        Assert.True((await store.AppointCardAsync(owner,appointment,ct)).Updated);
        Assert.False((await new PostgresAccountStore(data).AppointCardAsync(owner,appointment,ct)).Updated);
        Assert.Equal((1600u,1440u),await BalancesAsync(data,actor,ct));
        Assert.Equal((1600u,1600u),await BalancesAsync(data,target,ct));
        var dismissal=new CardDismissalWrite(new string('c',64),actor,40,target,
            OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch);
        Assert.True((await store.DismissCardAsync(owner,dismissal,ct)).Updated);
        Assert.False((await new PostgresAccountStore(data).DismissCardAsync(owner,dismissal,ct)).Updated);
        Assert.Equal((1600u,1280u),await BalancesAsync(data,actor,ct));
        Assert.Equal((1600u,1600u),await BalancesAsync(data,target,ct));
        Assert.Equal(2,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
        Assert.Equal(2,await CountAsync(data,"SELECT count(*) FROM domain_event WHERE event_type IN ('CharacterCardAppointed','CharacterCardDismissed') AND (payload->>'mcpCost')::integer=160",ct));
        Assert.Contains(await store.ListCharacterCardsAsync(owner,ct),c=>c.CharacterId==target && c.CardId==0);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0x0707,false)]
    [InlineData(0x0707,true)]
    [InlineData(0x0708,false)]
    [InlineData(0x0708,true)]
    public async Task Failed_personnel_command_preserves_post_and_points(int opcode,bool storageFailure)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        if(opcode==0x0708)
        {
            await using var held=data.CreateCommand("INSERT INTO original_character_card(account_id,character_id,card_id,appointed_by_character_id,authority_version) VALUES($1,$2,40,$2,1)");
            held.Parameters.AddWithValue(owner);held.Parameters.AddWithValue(character);
            await held.ExecuteNonQueryAsync(ct);
        }
        if(!storageFailure)
        {
            await using var empty=data.CreateCommand("UPDATE character SET pcp=0,mcp=0,points_accrued_at=now()");
            await empty.ExecuteNonQueryAsync(ct);
        }
        else
        {
            // Event insert happens after point and post writes in both commands.
            await using var fail=data.CreateCommand("ALTER TABLE domain_event ADD CONSTRAINT fixture_no_personnel CHECK(event_type NOT IN ('CharacterCardAppointed','CharacterCardDismissed'))");
            await fail.ExecuteNonQueryAsync(ct);
        }
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var request=new byte[40];
        BinaryPrimitives.WriteUInt16BigEndian(request,(ushort)opcode);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(22),40);
        var response=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,response.Status);
        Assert.DoesNotContain("-accepted",response.ResponseMetadata ?? "");
        Assert.Equal(opcode==0x0708 ? 1 : 0,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=40",ct));
        Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
        Assert.Equal(storageFailure ? (1600u,1600u) : (0u,0u),await BalancesAsync(data,character,ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0x0707,24,40u)]
    [InlineData(0x0707,25,40u)]
    [InlineData(0x0707,26,65536u)]
    [InlineData(0x0708,26,65536u)]
    public async Task Malformed_personnel_payload_is_rejected_without_exception_or_mutation(int opcode,int length,uint card)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var request=new byte[length];
        BinaryPrimitives.WriteUInt16BigEndian(request,(ushort)opcode);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)character);
        if(length>=26) BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(22),card);
        var response=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,2),ct);
        Assert.DoesNotContain("-accepted",response.ResponseMetadata ?? "");
        Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_character_card",ct));
        Assert.Equal((1600u,1600u),await BalancesAsync(data,character,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Wire_appointment_and_dismissal_repeat_after_the_opposite_command()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var target=await SeedOtherCharacterAsync(data,owner,ct);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_lobbySelectionValue",(ushort)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),ct);
        var request=new byte[40];
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)character);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18),(uint)target);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(22),40);
        uint sequence=3;
        for(var cycle=0;cycle<2;cycle++)
        {
            BinaryPrimitives.WriteUInt16BigEndian(request,0x0707);
            var appointed=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,sequence++),ct);
            Assert.Contains("card-appointment-accepted",appointed.ResponseMetadata);
            Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=40",ct));
            Assert.Equal((1600u,(uint)(1440-cycle*320)),await BalancesAsync(data,character,ct));
            var appointmentFrame=OriginalClientInnerFrameCodec.Decode(appointed.ResponsePayload!,key,0).Payload!;
            Assert.Equal((uint)(1440-cycle*320),BinaryPrimitives.ReadUInt32BigEndian(appointmentFrame.AsSpan(18)));
            BinaryPrimitives.WriteUInt16BigEndian(request,0x0708);
            var dismissed=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,sequence++),ct);
            Assert.Contains("card-dismissal-accepted",dismissed.ResponseMetadata);
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=40",ct));
            Assert.Equal((1600u,(uint)(1280-cycle*320)),await BalancesAsync(data,character,ct));
            var dismissalFrame=OriginalClientInnerFrameCodec.Decode(dismissed.ResponsePayload!,key,0).Payload!;
            Assert.Equal((uint)(1280-cycle*320),BinaryPrimitives.ReadUInt32BigEndian(dismissalFrame.AsSpan(18)));
            // Absence restores authored default39 on reconnect; dismissal must
            // persist the original no-post card0, just as resignation does.
            var freshCards=await new PostgresAccountStore(data).ListCharacterCardsAsync(owner,ct);
            Assert.Contains(freshCards,c=>c.CharacterId==target && c.CardId==0);
        }
        Assert.Equal(2,await CountAsync(data,"SELECT count(*) FROM original_card_dismissal_command",ct));
        var reconnect=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(reconnect,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(reconnect,"_lobbySelectionValue",(ushort)target);
        OriginalWarpSessionClockTests.SetField(reconnect,"_worldCharacterId",(uint)target);
        OriginalWarpSessionClockTests.SetField(reconnect,"_worldGridUnitId",(uint)target);
        var bootstrap=await reconnect.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        var packets=(bootstrap.ResponsesBeforePrimary ?? []).Select(p=>p.Payload)
            .Concat(bootstrap.ResponsePayload is {} primary ? new[]{primary} : Array.Empty<byte[]>())
            .Concat((bootstrap.AdditionalResponses ?? []).Select(p=>p.Payload));
        var actorFrames=packets.Select(p=>OriginalClientInnerFrameCodec.Decode(p,key,0).Payload!)
            .Where(p=>p.Length>=12 && BinaryPrimitives.ReadUInt16BigEndian(p.AsSpan(4))==0x0323 &&
                BinaryPrimitives.ReadUInt32BigEndian(p.AsSpan(6))==(uint)target).ToArray();
        var actorFrame=Assert.Single(actorFrames);
        // Packed0323 ends with card:u16,holder:u32,flag:u8.
        Assert.Equal((ushort)0,BinaryPrimitives.ReadUInt16BigEndian(actorFrame.AsSpan(actorFrame.Length-7)));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("accepted")]
    [InlineData("insufficient")]
    [InlineData("write-failure")]
    public async Task Wire_resignation_charges_original_eighty_mcp_and_persists_no_post(string scenario)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            store:new PostgresAccountStore(data));
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),ct);
        if(scenario=="insufficient")
        {
            await using var empty=data.CreateCommand("UPDATE character SET pcp=0,mcp=0,points_accrued_at=now()");
            await empty.ExecuteNonQueryAsync(ct);
        }
        if(scenario=="write-failure")
        {
            await using var fail=data.CreateCommand("ALTER TABLE original_card_resignation_command ADD CONSTRAINT fixture_fail_resignation CHECK(false)");
            await fail.ExecuteNonQueryAsync(ct);
        }
        // Original captured layout; forged client balances must not set authority balances.
        var request=Convert.FromHexString("07090000000000000000FFFFFFFFFFFFFFFF000000270000000000");
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6),(uint)character);
        var response=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,3),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,response.Status);
        if(scenario!="accepted")
        {
            Assert.DoesNotContain("card-resignation-accepted",response.ResponseMetadata ?? "");
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=0",ct));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
            Assert.Equal(scenario=="insufficient" ? (0u,0u) : (1600u,1600u),await BalancesAsync(data,character,ct));
            return;
        }
        Assert.Contains("card-resignation-accepted",response.ResponseMetadata);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=0",ct));
        Assert.Equal((1600u,1520u),await BalancesAsync(data,character,ct));
        var frame=OriginalClientInnerFrameCodec.Decode(response.ResponsePayload!,key,0).Payload!;
        Assert.Equal(1520u,BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(18)));
        var duplicate=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,4),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,duplicate.Status);
        Assert.Equal((1600u,1520u),await BalancesAsync(data,character,ct));
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
        var appointer=await SeedOtherCharacterAsync(data,owner,ct);
        await new PostgresAccountStore(data).AppointCardAsync(owner,
            new CardAppointmentWrite(new string('a',64),appointer,39,character),ct);
        var second=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,5),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,second.Status);
        Assert.Contains("card-resignation-accepted",second.ResponseMetadata);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_character_card WHERE card_id=0",ct));
        Assert.Equal((1600u,1440u),await BalancesAsync(data,character,ct));
        Assert.Equal(3,await CountAsync(data,"SELECT count(*) FROM original_command_point_charge",ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false,false)]
    [InlineData(true,false)]
    [InlineData(false,true)]
    public async Task Token_authenticated_tcp_client_receives_automatic_base_travel_completion(
        bool replaceShip,bool disconnectBeforeCompletion)
    {
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        var ct=timeout.Token;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var store=new PostgresAccountStore(data);
        var catalog=await TwoPortCatalogAsync(ct);
        var key=new byte[16];
        var receipt=new MetadataOnlyGatewayReceipt(TimeProvider.System);
        var handoffs=new HandoffRegistry(TimeProvider.System,TimeSpan.FromMinutes(1));
        // Fixture token issuance stands in for the prior login/lobby service;
        // this test authenticates the session token, not the password screen.
        var token=handoffs.Issue(owner,"repair");
        // Two outstanding tokens make account-only fallback ambiguous. Login
        // succeeds here only if the supplied wire token is actually accepted.
        handoffs.Issue(owner,"repair");
        var login=new OriginalLoginAuthority(new AccountAuthority(store,new Argon2PasswordHasher()),handoffs,receipt);
        var path=Path.Combine(Path.GetTempPath(),"base-travel-tcp-"+Guid.NewGuid().ToString("N")+".jsonl");
        await using var server=new NaturalAuthorityServer(new(IPAddress.Loopback,0,IPAddress.Loopback,47900,path,
            ServerOutboundKey:key,BaseTravelDelay:TimeSpan.FromSeconds(2)),login,handoffs,store,receipt,catalog);
        var endpoint=await server.StartAsync(ct);
        using var client=new TcpClient();
        await client.ConnectAsync(endpoint,ct);
        await using var stream=client.GetStream();
        var activeStream=stream;
        async Task Send(ushort control,byte[] payload) =>
            await activeStream.WriteAsync(OriginalClientTransportFrameWriter.Encode(control,payload),ct);
        async Task<byte[]> Receive()
        {
            var length=new byte[2];
            await activeStream.ReadExactlyAsync(length,ct);
            var body=new byte[BinaryPrimitives.ReadUInt16BigEndian(length)];
            await activeStream.ReadExactlyAsync(body,ct);
            return body;
        }
        async Task Handshake()
        {
        var bootstrap=new OriginalClientBlowfish("{A4C13748-0159-4c54-AEB3-1D68575761B3}"u8);
        var phase1=new byte[24];
        BinaryPrimitives.WriteUInt16BigEndian(phase1.AsSpan(2),16);
        BinaryPrimitives.WriteUInt32BigEndian(phase1.AsSpan(20),1);
        BinaryPrimitives.WriteUInt16BigEndian(phase1,0x1001); // zero key, length16, sequence1
        await Send(0x34,bootstrap.EncryptPadded(phase1));
        Assert.Equal((ushort)0x35,BinaryPrimitives.ReadUInt16BigEndian(await Receive()));
        var phase3=new byte[20];
        BinaryPrimitives.WriteUInt16BigEndian(phase3.AsSpan(2),16);
        BinaryPrimitives.WriteUInt16BigEndian(phase3,0x1000); // zero key, length16
        await Send(0x36,bootstrap.EncryptPadded(phase3));
        }
        await Handshake();
        uint outbound=0, inbound=0;
        var observedTypes=new List<ushort>();
        async Task Application(byte[] payload) => await Send(0x30,
            OriginalClientInnerFrameCodec.Encode(payload,key,++outbound));
        async Task<byte[]> Until(ushort type)
        {
            while(true)
            {
                var body=await Receive();
                var offset=BinaryPrimitives.ReadUInt16BigEndian(body)==0 ? 4 : 0;
                Assert.Equal((ushort)0x30,BinaryPrimitives.ReadUInt16BigEndian(body.AsSpan(offset)));
                var decoded=OriginalClientInnerFrameCodec.Decode(body.AsSpan(offset+2),key,inbound);
                Assert.Equal(OriginalClientInnerFrameStatus.Success,decoded.Status);
                inbound=decoded.Sequence;
                var receivedType=BinaryPrimitives.ReadUInt16BigEndian(decoded.Payload!.AsSpan(4));
                observedTypes.Add(receivedType);
                if(receivedType==type) return decoded.Payload!;
            }
        }
        var sessionLogin=Convert.FromHexString("02000000000000570000070072006500700061006900720000");
        BinaryPrimitives.WriteUInt32BigEndian(sessionLogin.AsSpan(2),token);
        await Application(sessionLogin);
        await Until(0x201);
        await Application(Convert.FromHexString("0205"));
        await Until(0x206);
        await Application(Convert.FromHexString("0F02"));
        await Until(0xf03);
        var move=Convert.FromHexString("0B000000000000000000000000000027FFFFFFFFFFFFFFFF000000020004");
        BinaryPrimitives.WriteUInt32BigEndian(move.AsSpan(10),(uint)character);
        await Application(move);
        var ack=await Until(0xb00);
        Assert.Equal(1440u,BinaryPrimitives.ReadUInt32BigEndian(ack.AsSpan(24)));
        Assert.Equal(1u,(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!.BaseId);
        observedTypes.Clear();
        if(disconnectBeforeCompletion)
        {
            client.Close();
            while((await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!.BaseId!=2)
                await Task.Delay(50,ct);
            // The server, not this test, completed the pending command offline.
            // Re-enter through a NEW authenticated connection and inspect actual
            // bootstrap wire state, not just the persisted row.
            using var reconnect=new TcpClient();
            await reconnect.ConnectAsync(endpoint,ct);
            await using var reconnectStream=reconnect.GetStream();
            activeStream=reconnectStream;
            outbound=0; inbound=0;
            await Handshake();
            BinaryPrimitives.WriteUInt32BigEndian(sessionLogin.AsSpan(2),handoffs.Issue(owner,"repair"));
            await Application(sessionLogin);
            await Until(0x201);
            await Application(Convert.FromHexString("0205"));
            await Until(0x206);
            var restored=await Until(0x325);
            Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(restored.AsSpan(8)));
            Assert.Equal((byte)0,restored[27]); // fixture has no troop rows
            Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(restored.AsSpan(28)));
            reconnect.Close();
        }
        else if(replaceShip)
        {
            // Model an independent replacement after admission, not a manual
            // cancellation. The real scheduler must detect and resolve it.
            await using var replacement=data.CreateCommand(
                "UPDATE original_grid_unit SET ship_generation=ship_generation+1");
            await replacement.ExecuteNonQueryAsync(ct);
        }
        // No further client requests, manual completion calls or due-time SQL.
        if(disconnectBeforeCompletion)
        {
            Assert.Equal(1,await CountAsync(data,
                "SELECT count(*) FROM domain_event WHERE event_type='OriginalBaseTravelResolved'",ct));
        }
        else if(replaceShip)
        {
            var cancelled=await Until(0x500);
            Assert.Equal((ushort)0xb00,BinaryPrimitives.ReadUInt16BigEndian(cancelled.AsSpan(6)));
            Assert.DoesNotContain((ushort)0xb0b,observedTypes);
        }
        else
        {
            var moved=await Until(0xb0b);
            Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(moved.AsSpan(10)));
            Assert.Equal(2u,BinaryPrimitives.ReadUInt32BigEndian(moved.AsSpan(14)));
        }
        Assert.Equal(replaceShip ? 1u : 2u,
            (await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!.BaseId);
        Assert.Equal((1600u,1440u),await BalancesAsync(data,character,ct));
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_base_travel_command WHERE outcome='"+
            (replaceShip ? "cancelled" : "completed")+"'",ct));
        client.Close();
        await server.StopAsync(ct);
        if(!disconnectBeforeCompletion)
            Assert.Contains("authority-notification-sent",await File.ReadAllTextAsync(path,ct));
    }

    private static async Task<OriginalBattlefieldCatalog> TwoPortCatalogAsync(CancellationToken ct)
    {
        var json=System.Text.Json.Nodes.JsonNode.Parse(await File.ReadAllTextAsync(
            Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json"),ct))!;
        json["staticBases"]![1]!["grid"]=101;
        var templates=json["templates"]!.AsArray();
        templates[0]!["spawnEnemy"]=false;
        var second=templates[1]!["baseInformation"]![0]!.DeepClone(); second["grid"]=101;
        templates[0]!["baseInformation"]!.AsArray().Add(second);
        templates[0]!["baseInstitutions"]!.AsArray().Add(templates[1]!["baseInstitutions"]![0]!.DeepClone());
        templates.RemoveAt(1);
        return OriginalBattlefieldCatalog.Parse(json.ToJsonString());
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("accepted")]
    [InlineData("actor")]
    [InlineData("card")]
    [InlineData("destination")]
    [InlineData("policy")]
    [InlineData("hostile")]
    [InlineData("cross-grid")]
    [InlineData("portless")]
    [InlineData("same-base")]
    [InlineData("mode")]
    public async Task Wire_base_travel_reserves_without_early_movement_and_completes_with_location_push(string scenario)
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var store=new PostgresAccountStore(data);
        var json=System.Text.Json.Nodes.JsonNode.Parse(await File.ReadAllTextAsync(
            Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json"),ct))!;
        json["staticBases"]![1]!["grid"]=101;
        var templates=json["templates"]!.AsArray();
        templates[0]!["spawnEnemy"]=false;
        var second=templates[1]!["baseInformation"]![0]!.DeepClone();
        second["grid"]=101;
        if(scenario=="hostile") second["power"]=3;
        templates[0]!["baseInformation"]!.AsArray().Add(second);
        templates[0]!["baseInstitutions"]!.AsArray().Add(templates[1]!["baseInstitutions"]![0]!.DeepClone());
        if(scenario=="portless")
            templates[0]!["baseInstitutions"]![1]!["institutions"]!.AsArray().RemoveAt(0);
        if(scenario=="cross-grid")
        {
            json["staticBases"]![1]!["grid"]=102;
            templates[0]!["baseInformation"]!.AsArray().RemoveAt(1);
            templates[0]!["baseInstitutions"]!.AsArray().RemoveAt(1);
        }
        else templates.RemoveAt(1);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,
            catalog:OriginalBattlefieldCatalog.Parse(json.ToJsonString()),store:store);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        if(scenario!="policy")
            OriginalWarpSessionClockTests.SetField(session,"_baseTravelDelay",TimeSpan.FromSeconds(10));
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),ct);
        var before=(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!;
        var request=Convert.FromHexString("0B000000000000000000000000000027FFFFFFFFFFFFFFFF000000020004");
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),(uint)character);
        if(scenario=="actor") BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(10),(uint)character+1);
        if(scenario=="card") BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(14),1);
        if(scenario=="destination") BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(24),99);
        if(scenario=="same-base") BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(24),1);
        if(scenario=="mode") BinaryPrimitives.WriteUInt16BigEndian(request.AsSpan(28),5);
        var accepted=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,3),ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success,accepted.Status);
        var ack=OriginalClientInnerFrameCodec.Decode(accepted.ResponsePayload!,key,0).Payload!;
        if(scenario!="accepted")
        {
            if(scenario is "hostile" or "cross-grid" or "portless" or "same-base")
                Assert.Contains("DESTINATION_UNAVAILABLE",accepted.ResponseMetadata);
            Assert.Equal((ushort)0x0500,BinaryPrimitives.ReadUInt16BigEndian(ack.AsSpan(4)));
            Assert.Equal((ushort)0x0b00,BinaryPrimitives.ReadUInt16BigEndian(ack.AsSpan(6)));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_base_travel_command",ct));
            Assert.Equal((1600u,1600u),await BalancesAsync(data,character,ct));
            Assert.Equal(before,await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct));
            return;
        }
        Assert.Equal((ushort)0x0b00,BinaryPrimitives.ReadUInt16BigEndian(ack.AsSpan(4)));
        Assert.Equal(1600u,BinaryPrimitives.ReadUInt32BigEndian(ack.AsSpan(20)));
        Assert.Equal(1440u,BinaryPrimitives.ReadUInt32BigEndian(ack.AsSpan(24)));
        Assert.Equal(before,await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct));
        await using var read=data.CreateCommand("SELECT request_fingerprint,due_at FROM original_base_travel_command");
        await using var reader=await read.ExecuteReaderAsync(ct);
        Assert.True(await reader.ReadAsync(ct));
        var fingerprint=reader.GetString(0);
        var due=new DateTimeOffset(reader.GetDateTime(1));
        await reader.DisposeAsync();
        var completion=await store.CompleteOriginalBaseTravelAsync(owner,fingerprint,due,ct);
        var pushes=await session.ProjectCompletedBaseTravelAsync(owner,completion,ct);
        Assert.Equal(2,pushes.Count);
        Assert.Equal(2u,(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!.BaseId);
        Assert.Equal((1600u,1440u),await BalancesAsync(data,character,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Cancellation_fails_only_the_matching_waiting_request_without_moving_the_client()
    {
        var ct=TestContext.Current.CancellationToken;
        await using var data=await CreateAsync();
        var(owner,character)=await SeedAsync(data);
        var store=new PostgresAccountStore(data);
        var key=new byte[16];
        var session=OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,key,store:store);
        OriginalWarpSessionClockTests.SetField(session,"_accountId",owner);
        OriginalWarpSessionClockTests.SetField(session,"_worldCharacterId",(uint)character);
        OriginalWarpSessionClockTests.SetField(session,"_worldGridUnitId",(uint)character);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),ct);
        var source=(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!;
        var write=new OriginalBaseTravelWrite(new string('5',64),source,source.CurrentCellId,2,
            DateTimeOffset.UnixEpoch.AddMinutes(20),new(OriginalCommandPointPool.Military,160,
                OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch,false));
        await store.ScheduleOriginalBaseTravelAsync(owner,write,ct);
        await using(var change=data.CreateCommand("UPDATE original_grid_unit SET ship_generation=ship_generation+1"))
            await change.ExecuteNonQueryAsync(ct);
        var cancelled=await store.CompleteOriginalBaseTravelAsync(owner,write.RequestFingerprint,write.DueAt,ct);
        Assert.Equal("cancelled",cancelled.Outcome);
        Assert.Equal(write.RequestFingerprint,cancelled.RequestFingerprint);
        OriginalWarpSessionClockTests.SetField(session,"_baseTravelRequestFingerprint",new string('4',64));
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(owner,cancelled,ct));
        OriginalWarpSessionClockTests.SetField(session,"_baseTravelRequestFingerprint",write.RequestFingerprint);
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(Guid.NewGuid(),cancelled,ct));
        var notice=Assert.Single(await session.ProjectCompletedBaseTravelAsync(owner,cancelled,ct));
        var frame=OriginalClientInnerFrameCodec.Decode(notice.Payload,key,0).Payload!;
        Assert.Equal((ushort)0x0500,BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal((ushort)0x0b00,BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(6)));
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(owner,cancelled,ct));
        Assert.Equal(source.BaseId,(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!.BaseId);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Completed_travel_projects_only_current_owned_state_and_never_a_pending_or_duplicate_trip()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key, store: store);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", (uint)character);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", source.UnitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", source.CurrentCellId);
        OriginalWarpSessionClockTests.SetField(session, "_persistedGridUnit", source);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"),key,1),ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"),key,2),ct);
        var write = new OriginalBaseTravelWrite(new string('6',64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, DateTimeOffset.UnixEpoch.AddMinutes(20),
            new(OriginalCommandPointPool.Military,160,OriginalCommandPointPolicy.LoadDefault(),DateTimeOffset.UnixEpoch,false));
        await store.ScheduleOriginalBaseTravelAsync(owner,write,ct);
        var pending = await store.CompleteOriginalBaseTravelAsync(owner,write.RequestFingerprint,DateTimeOffset.UnixEpoch,ct);
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(owner,pending,ct));
        var completed = await store.CompleteOriginalBaseTravelAsync(owner,write.RequestFingerprint,write.DueAt,ct);
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(Guid.NewGuid(),completed,ct));
        // Another request can refresh the server cache before the queued notice;
        // that is not evidence the client received its0B0B location update.
        OriginalWarpSessionClockTests.SetField(session,"_persistedGridUnit",completed.Unit!);
        var pushes = await session.ProjectCompletedBaseTravelAsync(owner,completed,ct);
        Assert.Equal(2,pushes.Count);
        var frame = OriginalClientInnerFrameCodec.Decode(pushes[0].Payload,key,0).Payload!;
        Assert.Equal((ushort)0x0b0b,BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(10)));
        Assert.Equal(write.DestinationBaseId,BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(14)));
        Assert.Equal((byte)1,frame[28]);
        Assert.Equal((uint)character,BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(29)));
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(owner,completed,ct));
        OriginalWarpSessionClockTests.SetField(session,"_persistedGridUnit",source);
        OriginalWarpSessionClockTests.SetField(session,"_lastBaseTravelProjectionVersion",0L);
        await using(var later=data.CreateCommand("UPDATE original_grid_unit SET base_id=7,authority_version=4"))
            await later.ExecuteNonQueryAsync(ct);
        Assert.Empty(await session.ProjectCompletedBaseTravelAsync(owner,completed,ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("missing")]
    [InlineData("cross-grid")]
    [InlineData("hostile")]
    [InlineData("portless")]
    [InlineData("faction-changed")]
    public async Task Server_start_cancels_travel_when_destination_becomes_unavailable(string scenario)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var admissionCatalog = await TwoPortCatalogAsync(ct);
        Assert.True(admissionCatalog.HasFriendlyPublicPort(101,2,2));
        var write = new OriginalBaseTravelWrite(new string('9',64), source, source.CurrentCellId,
            2, DateTimeOffset.UnixEpoch.AddMinutes(20),
            new(OriginalCommandPointPool.Military,160,OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch,false));
        Assert.Equal(OriginalBaseTravelStatus.Scheduled,
            (await store.ScheduleOriginalBaseTravelAsync(owner,write,ct)).Status);
        var json=System.Text.Json.Nodes.JsonNode.Parse(await File.ReadAllTextAsync(
            Path.Combine(AppContext.BaseDirectory,"battlefields","catalog.json"),ct))!;
        var templates=json["templates"]!.AsArray();
        templates[0]!["spawnEnemy"]=false;
        if(scenario=="missing")
        {
            json["staticBases"]!.AsArray().RemoveAt(1);
            templates.RemoveAt(1);
        }
        else if(scenario!="cross-grid")
        {
            json["staticBases"]![1]!["grid"]=101;
            var second=templates[1]!["baseInformation"]![0]!.DeepClone();
            second["grid"]=101;
            if(scenario=="hostile") second["power"]=3;
            templates[0]!["baseInformation"]!.AsArray().Add(second);
            templates[0]!["baseInstitutions"]!.AsArray().Add(templates[1]!["baseInstitutions"]![0]!.DeepClone());
            if(scenario=="portless")
                templates[0]!["baseInstitutions"]![1]!["institutions"]!.AsArray().RemoveAt(0);
            templates.RemoveAt(1);
        }
        if(scenario=="faction-changed")
        {
            await using var change=data.CreateCommand("UPDATE character SET faction=3 WHERE character_id=$1");
            change.Parameters.AddWithValue(character);
            await change.ExecuteNonQueryAsync(ct);
        }
        var catalog=OriginalBattlefieldCatalog.Parse(json.ToJsonString());
        var receipt = new MetadataOnlyGatewayReceipt(TimeProvider.System);
        var handoffs = new HandoffRegistry(TimeProvider.System,TimeSpan.FromMinutes(1));
        var login = new OriginalLoginAuthority(new AccountAuthority(store,new Argon2PasswordHasher()),
            handoffs,receipt);
        var path = Path.Combine(Path.GetTempPath(),"base-travel-missing-destination-"+Guid.NewGuid().ToString("N")+".jsonl");
        await using var server = new NaturalAuthorityServer(
            new(IPAddress.Loopback,0,IPAddress.Loopback,47900,path),login,handoffs,store,receipt,catalog);
        await server.StartAsync(ct);
        await server.StopAsync(ct);
        Assert.Equal(source,(await store.FindOriginalGridUnitAsync(owner,character,(uint)character,ct))!);
        Assert.Equal(1,await CountAsync(data,"SELECT count(*) FROM original_base_travel_command WHERE outcome='cancelled'",ct));
        Assert.Equal((1600u,1440u),await BalancesAsync(data,character,ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Server_start_and_periodic_loop_finish_persisted_travel_without_clients()
    {
        var ct = TestContext.Current.CancellationToken;
        var catalog = await TwoPortCatalogAsync(ct);
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var write = new OriginalBaseTravelWrite(new string('8', 64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, DateTimeOffset.UnixEpoch.AddMinutes(20),
            new(OriginalCommandPointPool.Military, 160, OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch, false));
        Assert.Equal(OriginalBaseTravelStatus.Scheduled,
            (await store.ScheduleOriginalBaseTravelAsync(owner, write, ct)).Status);
        NaturalAuthorityServer Server(string path)
        {
            var freshStore = new PostgresAccountStore(data);
            var receipt = new MetadataOnlyGatewayReceipt(TimeProvider.System);
            var handoffs = new HandoffRegistry(TimeProvider.System, TimeSpan.FromMinutes(1));
            var login = new OriginalLoginAuthority(new AccountAuthority(freshStore, new Argon2PasswordHasher()),
                handoffs, receipt);
            return new(new(IPAddress.Loopback, 0, IPAddress.Loopback, 47900, path),
                login, handoffs, freshStore, receipt, catalog);
        }
        var path = Path.Combine(Path.GetTempPath(), "base-travel-start-" + Guid.NewGuid().ToString("N") + ".jsonl");
        await using (var server = Server(path))
        {
            await server.StartAsync(ct);
            Assert.Equal(write.DestinationBaseId,
                (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!.BaseId);
            await server.StopAsync(ct);
        }
        Assert.DoesNotContain("connection-accepted", await File.ReadAllTextAsync(path, ct));
        source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        write = write with { RequestFingerprint = new string('7', 64), Source = source,
            DestinationBaseId = 1, DueAt = DateTimeOffset.UtcNow.AddHours(1) };
        Assert.Equal(OriginalBaseTravelStatus.Scheduled,
            (await store.ScheduleOriginalBaseTravelAsync(owner, write, ct)).Status);
        var restartPath = Path.Combine(Path.GetTempPath(), "base-travel-restart-" + Guid.NewGuid().ToString("N") + ".jsonl");
        await using (var restarted = Server(restartPath))
        {
            await restarted.StartAsync(ct);
            Assert.Equal(source.BaseId,
                (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!.BaseId);
            // Make fixture due after startup; only the running server may finish it.
            await using (var advance = data.CreateCommand("""
                UPDATE original_base_travel_command SET due_at=now()-interval '1 second' WHERE outcome='pending'
                """)) await advance.ExecuteNonQueryAsync(ct);
            var until = DateTime.UtcNow.AddSeconds(10);
            while (DateTime.UtcNow < until &&
                (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!.BaseId != 1)
                await Task.Delay(50, ct);
            Assert.Equal(1u, (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!.BaseId);
            await restarted.StopAsync(ct);
        }
        Assert.DoesNotContain("connection-accepted", await File.ReadAllTextAsync(restartPath, ct));
        Assert.Equal(2, await CountAsync(data,
            "SELECT count(*) FROM original_base_travel_command WHERE outcome='completed'", ct));
        Assert.Equal((1600u, 1280u), await BalancesAsync(data, character, ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("unchanged")]
    [InlineData("generation")]
    [InlineData("version")]
    [InlineData("grid")]
    [InlineData("base")]
    [InlineData("destroyed")]
    public async Task Base_travel_due_completion_is_once_only_and_never_overwrites_a_changed_ship(string change)
    {
        var changed = change != "unchanged";
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var due = DateTimeOffset.UnixEpoch.AddMinutes(20);
        var write = new OriginalBaseTravelWrite(new string('9', 64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, due,
            new(OriginalCommandPointPool.Military, 160, OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch, false));
        Assert.Equal(OriginalBaseTravelStatus.Scheduled,
            (await store.ScheduleOriginalBaseTravelAsync(owner, write, ct)).Status);
        var early = await store.CompleteOriginalBaseTravelAsync(owner, write.RequestFingerprint,
            due.AddTicks(-10), ct);
        Assert.Equal("pending", early.Outcome);
        Assert.False(early.Updated);
        Assert.Equal(source, await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct));
        if (changed)
        {
            var mutation = change switch
            {
                "generation" => "UPDATE original_grid_unit SET ship_generation=ship_generation+1",
                "version" => "UPDATE original_grid_unit SET authority_version=3; UPDATE account SET authority_version=3",
                "grid" => "UPDATE original_grid_unit SET current_cell_id=105",
                "base" => "UPDATE original_grid_unit SET base_id=9",
                "destroyed" => "UPDATE original_grid_unit SET damaged=unit_number,destroyed=unit_number",
                _ => throw new InvalidOperationException("Unknown fixture mutation"),
            };
            await using var move = data.CreateCommand(mutation);
            await move.ExecuteNonQueryAsync(ct);
        }
        var beforeDue = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        // Fresh store has no in-memory schedule. Competing workers must finish once.
        var results = await Task.WhenAll(
            new PostgresAccountStore(data).CompleteOriginalBaseTravelAsync(owner, write.RequestFingerprint, due, ct),
            new PostgresAccountStore(data).CompleteOriginalBaseTravelAsync(owner, write.RequestFingerprint, due, ct));
        Assert.Single(results, result => result.Updated);
        Assert.All(results, result => Assert.Equal(changed ? "cancelled" : "completed", result.Outcome));
        var current = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        Assert.Equal(changed ? beforeDue : source with { BaseId = write.DestinationBaseId, Mode = 4,
            AuthorityVersion = 3 }, current);
        Assert.Equal((1600u, 1440u), await BalancesAsync(data, character, ct));
        Assert.Equal(1, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type='OriginalBaseTravelResolved'", ct));
        // A retry after another unit update must report the current row, not replay movement.
        await using (var later = data.CreateCommand("UPDATE original_grid_unit SET base_id=7,authority_version=5"))
            await later.ExecuteNonQueryAsync(ct);
        var replay = await store.CompleteOriginalBaseTravelAsync(owner, write.RequestFingerprint, due.AddHours(1), ct);
        Assert.False(replay.Updated);
        Assert.Equal(7u, replay.Unit!.BaseId);
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Base_travel_admission_charges_once_and_replays_its_original_deadline()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var due = DateTimeOffset.UnixEpoch.AddMinutes(20); // Fixture, not original wait policy.
        var write = new OriginalBaseTravelWrite(new string('c', 64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, due,
            new(OriginalCommandPointPool.Military, 160, OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch, false));
        var simultaneous = await Task.WhenAll(
            store.ScheduleOriginalBaseTravelAsync(owner, write, ct),
            new PostgresAccountStore(data).ScheduleOriginalBaseTravelAsync(owner, write, ct));
        var admitted = Assert.Single(simultaneous, item => item.Status == OriginalBaseTravelStatus.Scheduled);
        Assert.Single(simultaneous, item => item.Status == OriginalBaseTravelStatus.Replayed);
        Assert.Equal(OriginalBaseTravelStatus.Scheduled, admitted.Status);
        Assert.Equal(due, admitted.DueAt);
        Assert.Equal((1600u, 1440u), await BalancesAsync(data, character, ct));
        Assert.Equal(source, await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct));
        // A new store and later candidate deadline must not reschedule/recharge.
        var replay = await new PostgresAccountStore(data).ScheduleOriginalBaseTravelAsync(owner,
            write with { DueAt = due.AddHours(1) }, ct);
        Assert.Equal(OriginalBaseTravelStatus.Replayed, replay.Status);
        Assert.Equal(due, replay.DueAt);
        Assert.Equal(admitted.AuthorityVersion, replay.AuthorityVersion);
        Assert.Equal((1600u, 1440u), await BalancesAsync(data, character, ct));
        var conflict = await store.ScheduleOriginalBaseTravelAsync(owner,
            write with { DestinationBaseId = 4 }, ct);
        Assert.Equal("BASE_TRAVEL_REPLAY_CONFLICT", conflict.ErrorCode);
        var pending = await store.ScheduleOriginalBaseTravelAsync(owner,
            write with { RequestFingerprint = new string('d', 64) }, ct);
        Assert.Equal("BASE_TRAVEL_ALREADY_PENDING", pending.ErrorCode);
        Assert.Equal(1, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal(1, await CountAsync(data, "SELECT count(*) FROM original_base_travel_command", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type='OriginalBaseTravelScheduled'", ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Base_travel_insufficient_points_leaves_no_reservation_or_charge()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        await using (var empty = data.CreateCommand("UPDATE character SET pcp=0,mcp=0"))
            await empty.ExecuteNonQueryAsync(ct);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var write = new OriginalBaseTravelWrite(new string('e', 64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, DateTimeOffset.UnixEpoch.AddMinutes(20),
            new(OriginalCommandPointPool.Military, 160, OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch, false));
        var result = await store.ScheduleOriginalBaseTravelAsync(owner, write, ct);
        Assert.Equal(OriginalBaseTravelStatus.Rejected, result.Status);
        Assert.Equal("BASE_TRAVEL_COMMAND_POINTS_INSUFFICIENT", result.ErrorCode);
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_base_travel_command", ct));
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal((0u, 0u), await BalancesAsync(data, character, ct));
        Assert.Equal(source, await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Base_travel_storage_failure_after_charge_rolls_back_the_whole_admission()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        // Force a real SQL failure at the reservation write, after the CP write.
        await using (var constraint = data.CreateCommand("""
            ALTER TABLE original_base_travel_command ADD CONSTRAINT fixture_refuse_admission CHECK(false)
            """)) await constraint.ExecuteNonQueryAsync(ct);
        var store = new PostgresAccountStore(data);
        var source = (await store.FindOriginalGridUnitAsync(owner, character, (uint)character, ct))!;
        var write = new OriginalBaseTravelWrite(new string('f', 64), source, source.CurrentCellId,
            source.BaseId == 2 ? 3u : 2u, DateTimeOffset.UnixEpoch.AddMinutes(20),
            new(OriginalCommandPointPool.Military, 160, OriginalCommandPointPolicy.LoadDefault(),
                DateTimeOffset.UnixEpoch, false));
        var error = await Assert.ThrowsAsync<PostgresException>(() =>
            store.ScheduleOriginalBaseTravelAsync(owner, write, ct));
        Assert.Equal("fixture_refuse_admission", error.ConstraintName);
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal(0, await CountAsync(data, "SELECT count(*) FROM original_base_travel_command", ct));
        Assert.Equal(0, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type='OriginalBaseTravelScheduled'", ct));
        Assert.Equal(1, await CountAsync(data, "SELECT authority_version FROM account", ct));
    }

    // A persisted deadline, not a process tick, is required for pending 0B00.
    // This schema test does not claim that admission charges or completion run.
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task Base_travel_deadline_survives_reconnection_and_only_one_pending_trip_is_allowed()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        await using (var exists = data.CreateCommand("SELECT to_regclass('original_base_travel_command') IS NOT NULL"))
            Assert.True((bool)(await exists.ExecuteScalarAsync(ct))!, "Pending base travel has no durable storage");
        const string insert = """
            INSERT INTO original_base_travel_command(account_id,request_fingerprint,character_id,unit_id,
                ship_generation,source_unit_version,grid_id,source_base_id,destination_base_id,
                accepted_at,due_at,authority_version)
            VALUES($1,$2,$3,$3,0,1,102,1,2,'2026-09-13T00:00:00Z','2026-09-13T00:20:00Z',2)
            """;
        async Task InsertAsync(string fingerprint)
        {
            await using var command = data.CreateCommand(insert);
            command.Parameters.AddWithValue(owner);
            command.Parameters.AddWithValue(fingerprint);
            command.Parameters.AddWithValue(character);
            await command.ExecuteNonQueryAsync(ct);
        }
        await InsertAsync(new string('a', 64));
        var duplicate = await Assert.ThrowsAsync<PostgresException>(() => InsertAsync(new string('b', 64)));
        Assert.Equal(PostgresErrorCodes.UniqueViolation, duplicate.SqlState);
        // A new pool/connection must see the original absolute deadline.
        await using (var reopened = NpgsqlDataSource.Create(data.ConnectionString))
        await using (var read = reopened.CreateCommand("SELECT due_at FROM original_base_travel_command"))
            Assert.Equal(new DateTime(2026, 9, 13, 0, 20, 0, DateTimeKind.Utc),
                (DateTime)(await read.ExecuteScalarAsync(ct))!);
        await using (var terminal = data.CreateCommand("""
            UPDATE original_base_travel_command SET outcome='completed',
                finished_at='2026-09-13T00:20:00Z',completion_authority_version=3
            """))
            await terminal.ExecuteNonQueryAsync(ct);
        await InsertAsync(new string('b', 64));
        var replayKey = await Assert.ThrowsAsync<PostgresException>(() => InsertAsync(new string('a', 64)));
        Assert.Equal(PostgresErrorCodes.UniqueViolation, replayKey.SqlState);
        Assert.Equal(2, await CountAsync(data, "SELECT count(*) FROM original_base_travel_command", ct));
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
    }

    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_repair_in_port_restores_surviving_hulls_spends_supplies_and_points_once()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var damage = data.CreateCommand(
            "UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=2,supplies=100"))
            await damage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var repaired = await store.RepairOwnOriginalFlagshipAsync(owner, Write(1, before), ct);

        Assert.Equal(OriginalFlagshipRepairStatus.Repaired, repaired.Status);
        // Only the surviving damaged hulls come back; the 25 destroyed stay.
        Assert.Equal(((ushort)25, (ushort)25, 0u), (repaired.Unit!.Damaged, repaired.Unit.Destroyed,
            repaired.Unit.Supplies));
        Assert.Equal(repaired.Unit, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((1600u, 1440u), await BalancesAsync(data, character, ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE event_type = 'OriginalFlagshipRepaired'", ct));
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE payload->>'commandPointCost' = '160'", ct));
        // One player action stays one authority version.
        Assert.Equal(1, await CountAsync(data,
            "SELECT count(*) FROM domain_event WHERE authority_version = " +
            repaired.AuthorityVersion.ToString(System.Globalization.CultureInfo.InvariantCulture), ct));

        var replayed = await store.RepairOwnOriginalFlagshipAsync(owner, Write(1, before), ct);

        Assert.Equal(OriginalFlagshipRepairStatus.Replayed, replayed.Status);
        Assert.Equal((1600u, 1440u), await BalancesAsync(data, character, ct));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData("UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=6,base_id=2,supplies=100",
        "FLAGSHIP_REPAIR_NOT_IN_PORT")]
    [InlineData("UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=0,supplies=100",
        "FLAGSHIP_REPAIR_NOT_IN_PORT")]
    [InlineData("UPDATE original_grid_unit SET damaged=25,destroyed=25,mode=4,base_id=2,supplies=100",
        "FLAGSHIP_REPAIR_NOTHING_DAMAGED")]
    [InlineData("UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=2,supplies=0",
        "FLAGSHIP_REPAIR_NO_SUPPLIES")]
    public async Task A_refused_repair_changes_nothing_at_all(string setup, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(setup)) await stage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var refused = await store.RepairOwnOriginalFlagshipAsync(owner, Write(2, before), ct);

        Assert.Equal(OriginalFlagshipRepairStatus.Rejected, refused.Status);
        Assert.Equal(expected, refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((1600u, 1600u), await BalancesAsync(data, character, ct));
        Assert.Equal(0, await CountAsync(data,
            "SELECT count(*) FROM original_command_point_charge", ct));
        Assert.Equal(0, await CountAsync(data,
            "SELECT count(*) FROM original_flagship_repair_command", ct));
    }

    /// <summary>
    /// A player who cannot pay keeps their damage and their points; the charge
    /// and the repair are the same transaction.
    /// </summary>
    [Fact(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    public async Task A_repair_the_player_cannot_pay_for_repairs_nothing()
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=2,supplies=100;" +
            "UPDATE character SET pcp=10,mcp=0"))
            await stage.ExecuteNonQueryAsync(ct);
        var before = Assert.IsType<OriginalGridUnitRecord>(
            await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));

        var refused = await store.RepairOwnOriginalFlagshipAsync(owner, Write(3, before), ct);

        Assert.Equal(OriginalFlagshipRepairStatus.Rejected, refused.Status);
        Assert.Equal("FLAGSHIP_REPAIR_COMMAND_POINTS_INSUFFICIENT", refused.ErrorCode);
        Assert.Equal(before, await store.FindOriginalGridUnitAsync(owner, character, unitId, ct));
        Assert.Equal((10u, 0u), await BalancesAsync(data, character, ct));
    }

    /// <summary>
    /// The whole command over the real session and wire: a docked, damaged
    /// flagship is repaired, the client is told, and the served unit record
    /// carries the new casualties.
    /// </summary>
    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false,true)]
    [InlineData(true,true)]
    [InlineData(true,false)]
    public async Task The_wire_command_repairs_and_reports_the_new_state(bool withEscort,bool includeFlagship)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand(
            "UPDATE original_grid_unit SET damaged=60,destroyed=25,mode=4,base_id=2,supplies=100,current_cell_id=102"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var registry=new OriginalTacticalBattleRegistry();
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: registry);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 102u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);

        var selected=new List<OriginalCompletenessRepairShip>{new(unitId,0,0)};
        if(!includeFlagship) selected.Clear();
        if(withEscort)
        {
            var own=(await store.FindOriginalGridUnitAsync(owner,character,unitId,ct))!;
            var fleetStore=new PostgresFleetUnitStore(data);
            var row=new OriginalFleetUnitRecord(2113929217,2113929312,56,2,0,102,300,40,10,
                0,0,0,0,10,character,false,0,1,unitId,own.ShipGeneration,100);
            await fleetStore.EnsureCreatedAsync(row,ct);
            var fleet=new OriginalBattlefieldFleet(row.OutfitId,2,0,0,0,[new(row.UnitId,56,new(0,0,0,0))]);
            var initial=Assert.Single(fleet.Project(102));
            using(await registry.LockAsync(102,100,ct))
            {
                registry.RegisterNpc(initial,OriginalSubordinateShipCatalog.Capabilities,OriginalAuthoredPlayableCatalog.TacticalArms);
                registry.BindFleetUnitPersistence(row,fleetStore,initial);
            }
            selected.Add(new(row.UnitId,uint.MaxValue,uint.MaxValue));
        }
        uint repairSequence=3;
        var observerQueue=System.Threading.Channels.Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        using var observer=registry.Subscribe(102,100,observerQueue.Writer,2,primaryNpcKnown:false);
        var uncontrolledStore=new PostgresFleetUnitStore(data);
        var uncontrolled=new OriginalFleetUnitRecord(2113929299,2113929399,56,3,0,102,300,40,10,
            0,0,0,0,10,null,true,0,1,null,null,100);
        await uncontrolledStore.EnsureCreatedAsync(uncontrolled,ct);
        foreach(var invalidSelection in new IReadOnlyList<OriginalCompletenessRepairShip>[] {
            [new(unitId,0,0),new(unitId,0,0)], [new(unitId,0,0),new(4000000000,0,0)],
            [new(unitId,0,0),new(uncontrolled.UnitId,0,0)] })
        {
            var invalidRequest=OriginalCompletenessRepairCodec.Encode(new(0,unitId,0,0,invalidSelection)).AsSpan(4).ToArray();
            var refused=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(invalidRequest,key,repairSequence++),ct);
            Assert.Contains("command-reject=",refused.ResponseMetadata);
            Assert.Equal((1600u,1600u),await BalancesAsync(data,character,ct));
            var unchanged=(await store.FindOriginalGridUnitAsync(owner,character,unitId,ct))!;
            Assert.Equal(((ushort)60,100u),(unchanged.Damaged,unchanged.Supplies));
            Assert.Equal(0,await CountAsync(data,"SELECT count(*) FROM original_flagship_repair_command",ct));
            Assert.Equal(uncontrolled,(await uncontrolledStore.ReadGridAsync(102,ct)).Single(row=>row.UnitId==uncontrolled.UnitId));
            Assert.False(observerQueue.Reader.TryRead(out _));
        }
        var request = OriginalCompletenessRepairCodec.Encode(
            new(0, unitId, 0, 0, selected)).AsSpan(4).ToArray();
        await using(var fault=data.CreateCommand("ALTER TABLE domain_event ADD CONSTRAINT fixture_wire_repair_failure CHECK(event_type <> 'OriginalFlagshipRepaired')"))
            await fault.ExecuteNonQueryAsync(ct);
        var failed=await session.ProcessAsync(0x30,OriginalClientInnerFrameCodec.Encode(request,key,repairSequence++),ct);
        Assert.Contains("command-reject=FLEET_REPAIR_STORAGE_FAILED",failed.ResponseMetadata);
        Assert.Equal((1600u,1600u),await BalancesAsync(data,character,ct));
        Assert.Equal((ushort)60,(await store.FindOriginalGridUnitAsync(owner,character,unitId,ct))!.Damaged);
        Assert.False(observerQueue.Reader.TryRead(out _));
        if(withEscort) Assert.Equal(100u,registry.NpcSnapshot(102,2113929217)!.Unit.Supplies);
        await using(var clearFault=data.CreateCommand("ALTER TABLE domain_event DROP CONSTRAINT fixture_wire_repair_failure"))
            await clearFault.ExecuteNonQueryAsync(ct);
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, repairSequence), ct);

        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        Assert.Contains("flagship-repair-accepted;unit=", answer.ResponseMetadata);
        Assert.Contains(includeFlagship ? "damaged=25;destroyed=25;supplies=0;mcp-cost=160"
            : "damaged=60;destroyed=25;supplies=100;mcp-cost=160", answer.ResponseMetadata);
        var frame = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0).Payload!;
        Assert.Equal((ushort)0x0c00, BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(4)));
        var stored = await store.FindOriginalGridUnitAsync(owner, character, unitId, ct);
        Assert.Equal(includeFlagship ? ((ushort)25, (ushort)25, 0u) : ((ushort)60,(ushort)25,100u),
            (stored!.Damaged, stored.Destroyed, stored.Supplies));
        Assert.True(OriginalCompletenessRepairCodec.TryDecode(frame.AsSpan(4),out var outcome));
        Assert.Equal(selected.Count,outcome.Ships.Count);
        Assert.Equal(selected.Select(row=>row.UnitId),outcome.Ships.Select(row=>row.UnitId));
        Assert.Equal((1600u,1440u),await BalancesAsync(data,character,ct));
        if(withEscort)
        {
            Assert.Equal(new OriginalCompletenessRepairShip(2113929217,10,0),outcome.Ships[includeFlagship ? 1 : 0]);
            var persisted=(await new PostgresFleetUnitStore(data).ReadGridAsync(102,ct)).Single(row=>row.UnitId==2113929217);
            Assert.Equal((ushort)10,persisted.Damaged);
            Assert.Equal(0u,persisted.Supplies);
            var projection=registry.NpcSnapshot(102,2113929217)!.Unit;
            Assert.Equal((ushort)10,projection.Damaged);
            Assert.Equal(0u,projection.Supplies);
            var expectedUpdate=OriginalWorldEntryCodec.EncodeUnits([projection]);
            var notified=false;
            while(observerQueue.Reader.TryRead(out var notification))
                notified|=notification.Frames.Any(bytes=>bytes.Span.SequenceEqual(expectedUpdate));
            Assert.True(notified,"Other grid subscriber must receive the repaired escort unit record");
        }
    }

    private static OriginalFlagshipRepairWrite Write(int intent, OriginalGridUnitRecord unit) =>
        new(intent.ToString("x64"), unit.CharacterId, unit.UnitId, unit.AuthorityVersion,
            unit.ShipGeneration,
            new(OriginalCommandPointPool.Military, NaturalAuthoritySession.FlagshipRepairPointCost,
                OriginalCommandPointPolicy.LoadDefault(), DateTimeOffset.UnixEpoch, false));

    private static async Task<(uint Political, uint Military)> BalancesAsync(NpgsqlDataSource data,
        long character, CancellationToken ct)
    {
        await using var read = data.CreateCommand("SELECT pcp, mcp FROM character WHERE character_id = $1");
        read.Parameters.AddWithValue(character);
        await using var reader = await read.ExecuteReaderAsync(ct);
        Assert.True(await reader.ReadAsync(ct));
        return ((uint)reader.GetInt64(0), (uint)reader.GetInt64(1));
    }

    private static async Task<long> CountAsync(NpgsqlDataSource data, string sql, CancellationToken ct)
    {
        await using var read = data.CreateCommand(sql);
        return (long)(await read.ExecuteScalarAsync(ct))!;
    }

    private static async Task<(Guid Owner, long Character)> SeedAsync(NpgsqlDataSource data)
    {
        var ct = TestContext.Current.CancellationToken;
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'repair',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
            """))
        {
            seed.Parameters.AddWithValue(owner);
            await seed.ExecuteNonQueryAsync(ct);
        }
        await using var character = data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,0,repeat('1',64),repeat('2',64),2,0,0,'Pilot','First','Ship',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """);
        character.Parameters.AddWithValue(owner);
        return (owner, (long)(await character.ExecuteScalarAsync(ct))!);
    }

    private static async Task<long> SeedOtherCharacterAsync(NpgsqlDataSource data,Guid owner,CancellationToken ct)
    {
        await using var insert=data.CreateCommand("""
            INSERT INTO character(account_id,slot,request_fingerprint,payload_hash,faction,blood,sex,
                last_name,first_name,flagship_name,face,ability_values,authority_version)
            VALUES($1,1,repeat('3',64),repeat('4',64),2,0,0,'Other','Second','OtherShip',5,
                ARRAY[1,2,3,4,5,6,7,8]::smallint[],1) RETURNING character_id
            """);
        insert.Parameters.AddWithValue(owner);
        return (long)(await insert.ExecuteScalarAsync(ct))!;
    }

    private static async Task<NpgsqlDataSource> CreateAsync()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "flagship_repair_" + Guid.NewGuid().ToString("N");
        await using (var admin = NpgsqlDataSource.Create(connection))
        await using (var create = admin.CreateCommand("CREATE SCHEMA " + schema))
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        var data = NpgsqlDataSource.Create(
            new NpgsqlConnectionStringBuilder(connection) { SearchPath = schema }.ConnectionString);
        await PostgresMigrationRunner.ApplyAllAsync(data, PostgresMigrationRunner.MigrationDirectory,
            CancellationToken.None);
        return data;
    }
}
