using System.Buffers.Binary;
using System.Threading.Channels;
using Logh7.Server.Compatibility;
using Logh7.Server.OriginalGateway;
using Logh7.Server.Storage;
using Npgsql;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

/// <summary>
/// 任務 = application type 0x0421, the same body shape as 射撃 with the mission
/// byte where the weapon selection sits. ORIGINAL_OBSERVED: the shipped client
/// put that frame on the wire on 2026-09-09 after the palette's six-icon mission
/// sub-panel and a click on an enemy fleet.
/// </summary>
public sealed class OriginalMissionTests
{
    public static bool HasTestDatabase => OriginalReturnBasePostgresTests.HasTestDatabase;

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(2, "MISSION_TARGET_NOT_HOSTILE")]
    [InlineData(3, "mission-accepted")]
    public async Task Interception_requires_an_enemy_squadron(byte targetPower, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(66, targetPower, 9));
        var (session, key, unit, _) = await SessionAsync(data, battles, ct);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0, 0, unit, [unit], 3, 1, 66)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Contains(expected, answer.ResponseMetadata);
        if (targetPower == 2) Assert.DoesNotContain("mission-accepted", answer.ResponseMetadata);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(1, 2, 0, 1u, "mission-accepted")]
    [InlineData(1, 3, 0, 1u, "MISSION_BASE_NOT_FRIENDLY")]
    [InlineData(1, 2, 1, 1u, "MISSION_BASE_NOT_FRIENDLY")]
    [InlineData(2, 3, 0, 1u, "mission-accepted")]
    [InlineData(2, 2, 1, 1u, "mission-accepted")]
    [InlineData(2, 2, 0, 1u, "MISSION_BASE_NOT_HOSTILE")]
    [InlineData(1, 2, 0, 66u, "MISSION_TARGET_BASE_NOT_IN_FIELD")]
    [InlineData(2, 3, 0, 66u, "MISSION_TARGET_BASE_NOT_IN_FIELD")]
    [InlineData(1, 2, 0, 2u, "MISSION_TARGET_BASE_NOT_IN_FIELD")]
    public async Task Defence_and_occupation_resolve_a_local_base_not_a_ship(
        byte mission, byte basePower, byte baseCamp, uint target, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var json = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "catalog.json")))!;
        var information = json["templates"]![0]!["baseInformation"]![0]!;
        information["power"] = basePower;
        information["camp"] = baseCamp;
        var catalog = OriginalBattlefieldCatalog.Parse(json.ToJsonString());
        var battles = new OriginalTacticalBattleRegistry();
        // A real living ship with this ID must not satisfy a base mission.
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(66, 3, 9));
        var (session, key, unit, _) = await SessionAsync(data, battles, ct, catalog);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(new(0, 0,
            unit, [unit], mission, mission == 1 ? (byte)1 : (byte)0, target)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Contains(expected, answer.ResponseMetadata);
        if (expected == "mission-accepted")
        {
            var echo = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0).Payload!;
            Assert.True(OriginalTacticalCommandCodec.TryDecodeMissionCommand(echo.AsSpan(4), out var decoded));
            Assert.Equal(target, decoded.TargetId);
            Assert.Equal(mission, decoded.Mission);
        }
        else Assert.DoesNotContain("mission-accepted", answer.ResponseMetadata);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0x0704, 0u)]
    [InlineData(0x0705, 333u)] // Preserve current special-promotion policy; not a recovered reset rule.
    [InlineData(0x0706, 100u)]
    public async Task Rank_response_projects_authoritative_achievement_not_the_request(ushort type, uint expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        var (session, key, unit, owner) = await SessionAsync(data, battles, ct);
        await using (var setup = data.CreateCommand("UPDATE character SET achievement=333,rank=10 WHERE account_id=$1"))
        {
            setup.Parameters.AddWithValue(owner);
            await setup.ExecuteNonQueryAsync(ct);
        }
        var request = new byte[type == 0x0704 ? 28 : type == 0x0705 ? 34 : 36];
        BinaryPrimitives.WriteUInt16BigEndian(request, type);
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(6), unit);
        if (type == 0x0705)
        {
            BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(18), unit);
            request[22] = 10;
        }
        else
        {
            request[18] = 10;
            if (type == 0x0706) BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(19), unit);
        }
        BinaryPrimitives.WriteUInt32BigEndian(request.AsSpan(type == 0x0704 ? 19 : 23), 999);
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Equal(NaturalAuthoritySessionStatus.Success, answer.Status);
        var echo = OriginalClientInnerFrameCodec.Decode(answer.ResponsePayload!, key, 0).Payload!;
        Assert.Equal(expected, BinaryPrimitives.ReadUInt32BigEndian(echo.AsSpan(type == 0x0704 ? 26 : 30, 4)));
        var push = Assert.Single(answer.AdditionalResponses!);
        var publicFrame = OriginalClientInnerFrameCodec.Decode(push.Payload, key, 0).Payload!;
        Assert.Equal(expected, BinaryPrimitives.ReadUInt32BigEndian(publicFrame.AsSpan(publicFrame.Length - 47, 4)));
        var participant = Assert.Single(battles.OtherParticipants(101, 0), p => p.Unit.Id == unit);
        Assert.Equal(new OriginalTacticalCommanderMerit(unit, type == 0x0706 ? (byte)11 : (byte)9, expected),
            participant.CommanderMerit);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false, 0u)]
    [InlineData(true, 100u)]
    public async Task Ordinary_rank_change_resets_achievement_once(bool demotion, uint expectedAchievement)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (owner, character) = await SeedAsync(data);
        await using (var setup = data.CreateCommand("UPDATE character SET rank=10,achievement=333 WHERE account_id=$1"))
        {
            setup.Parameters.AddWithValue(owner);
            await setup.ExecuteNonQueryAsync(ct);
        }
        var store = new PostgresAccountStore(data);
        var write = new CharacterRankUpWrite(new string('a', 64), character, 10,
            demotion ? (short)11 : (short)9,
            demotion ? "CharacterDemoted" : "CharacterRankPromoted", character);
        var firstResult = await store.PromoteCharacterAsync(owner, write, ct);
        Assert.True(firstResult.Updated);
        Assert.Equal(expectedAchievement, firstResult.Achievement);
        var changed = Assert.Single(await store.ListCharactersAsync(owner, ct));
        Assert.Equal(write.PromotedRank, changed.Rank);
        Assert.Equal(expectedAchievement, changed.Achievement);
        await using (var readEvent = data.CreateCommand("SELECT payload::text FROM domain_event WHERE account_id=$1 AND event_type=$2"))
        {
            readEvent.Parameters.AddWithValue(owner);
            readEvent.Parameters.AddWithValue(write.EventType);
            using var payload = System.Text.Json.JsonDocument.Parse((string)(await readEvent.ExecuteScalarAsync(ct))!);
            Assert.True(payload.RootElement.TryGetProperty("sourceAchievement", out var sourceAchievement));
            Assert.Equal(333u, sourceAchievement.GetUInt32());
            Assert.True(payload.RootElement.TryGetProperty("achievement", out var resultingAchievement));
            Assert.Equal(expectedAchievement, resultingAchievement.GetUInt32());
        }
        await using (var later = data.CreateCommand("UPDATE character SET achievement=42,rank=8 WHERE account_id=$1"))
        {
            later.Parameters.AddWithValue(owner);
            await later.ExecuteNonQueryAsync(ct);
        }
        var replayed = await store.PromoteCharacterAsync(owner, write, ct);
        Assert.False(replayed.Updated);
        Assert.Equal((short)8, replayed.Rank);
        Assert.Equal(42u, Assert.Single(await store.ListCharactersAsync(owner, ct)).Achievement);
        Assert.Equal(42u, replayed.Achievement);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0x11223344u)]
    [InlineData(uint.MaxValue)]
    public async Task Stored_character_achievement_survives_restore_and_public_projection(uint achievement)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        await using var column = data.CreateCommand("""
            SELECT count(*) FROM information_schema.columns
            WHERE table_schema=current_schema() AND table_name='character' AND column_name='achievement'
            """);
        Assert.Equal(1L, (long)(await column.ExecuteScalarAsync(ct))!);
        var (owner, character) = await SeedAsync(data);
        var store = new PostgresAccountStore(data);
        var initial = Assert.Single(await store.ListCharactersAsync(owner, ct));
        await using var update = data.CreateCommand("UPDATE character SET achievement=$1 WHERE account_id=$2");
        update.Parameters.AddWithValue((long)achievement);
        update.Parameters.AddWithValue(owner);
        Assert.Equal(1, await update.ExecuteNonQueryAsync(ct));
        var saved = Assert.Single(await store.ListCharactersAsync(owner, ct));
        Assert.Equal(initial.Pcp, saved.Pcp);
        Assert.Equal(initial.Mcp, saved.Mcp);
        Assert.Equal(initial.Rank, saved.Rank);
        Assert.Equal(0u, initial.Achievement);
        Assert.Equal(achievement, saved.Achievement);
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System,
            new byte[16], store: store, battles: new OriginalTacticalBattleRegistry());
        var restore = typeof(NaturalAuthoritySession).GetMethod("RestoreCharacter",
            System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!;
        var restored = Assert.IsType<OriginalCreateCharacterCommand>(restore.Invoke(session, [saved]));
        var frame = OriginalWorldEntryCodec.EncodeCharacter((uint)character, (uint)character, 39, restored);
        // Original parser: achievement ends the parentage record; then 8 pairs
        // of u16 ability fields and the 11-byte trailer. Names are variable length.
        Assert.Equal(achievement, BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(frame.Length - 47, 4)));
    }

    /// <summary>
    /// The captured frame, byte for byte. The mission sub-panel's command state
    /// 0x19 (0x0050EFA4)
    /// builds it through FUN_004B4850, which calls the request dispatcher with
    /// selector 0x7F, and selector 0x7F's arm at 0x004B80EC sets request and
    /// expected response alike to 0x0421.
    /// </summary>
    [Fact]
    public void The_command_body_is_the_frame_the_client_sent()
    {
        const string captured = "04213F8000003F21E2AE00000002010000000200017E000106";

        Assert.True(OriginalTacticalCommandCodec.TryDecodeMissionCommand(
            Convert.FromHexString(captured), out var decoded));
        Assert.Equal(0x3F800000u, decoded.Time);
        Assert.Equal(0x3F21E2AEu, decoded.Wait);
        Assert.Equal(2u, decoded.Order);
        Assert.Equal([2u], decoded.UnitIds);
        // The two bytes the palette had just set: mission icon 0, target kind 1.
        Assert.Equal(0, decoded.Mission);
        Assert.Equal(1, decoded.TargetKind);
        Assert.Equal(0x7E000106u, decoded.TargetId);

        var encoded = OriginalTacticalCommandCodec.EncodeMissionCommand(decoded)
            .AsSpan(OriginalLoginCodec.MessageCodeSize).ToArray();
        Assert.Equal(captured, Convert.ToHexString(encoded));
        Assert.Equal(25, encoded.Length);
        // A body one byte short, and a different type of the same length, are not 任務.
        Assert.False(OriginalTacticalCommandCodec.TryDecodeMissionCommand(
            encoded.AsSpan(0, 24), out _));
        encoded[1] = 0x06;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeMissionCommand(encoded, out _));
    }

    /// <summary>
    /// The six mission icons the palette offers, and the one of them that sends
    /// without a target pick.
    /// </summary>
    [Fact]
    public void The_client_offers_six_missions_and_one_of_them_has_no_target()
    {
        Assert.Equal(5, OriginalTacticalCommandCodec.HighestMission);
        Assert.Equal(5, OriginalTacticalCommandCodec.MissionWithoutTarget);
        Assert.Equal(0x0421, OriginalTacticalCommandCodec.MissionCommandType);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    // A unit that is not a living friendly participant is refused visibly.
    [InlineData(0u, 4242u, 3, 0u, "MISSION_UNIT_NOT_FRIENDLY")]
    // The client offers six mission icons (0x25..0x2A -> 0..5) and no seventh.
    [InlineData(0u, 0u, 6, 0u, "MISSION_KIND_UNKNOWN")]
    // Missions 0..4 reach the sender only with an object the hit list resolved.
    [InlineData(0u, 0u, 3, 0u, "MISSION_TARGET_NOT_IN_FIELD")]
    [InlineData(0u, 0u, 3, 4242u, "MISSION_TARGET_NOT_IN_FIELD")]
    // Mission 5 is the branch that sends with no pick at all, so it carries none.
    [InlineData(0u, 0u, 5, 4242u, "MISSION_TARGET_NOT_ALLOWED")]
    public async Task A_mission_that_cannot_be_delivered_is_refused_visibly(
        uint actorOverride, uint unit, byte mission, uint target, string expected)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var (session, key, unitId, _) = await SessionAsync(data, new OriginalTacticalBattleRegistry(), ct);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(new(0, 0,
            actorOverride == 0 ? unitId : actorOverride,
            [unit == 0 ? unitId : unit], mission, mission is 2 or 5 ? (byte)0 : (byte)1, target)).AsSpan(4).ToArray();

        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);

        Assert.Contains(expected, answer.ResponseMetadata);
        Assert.DoesNotContain("mission-accepted", answer.ResponseMetadata);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0, 0)]
    [InlineData(1, 0)]
    [InlineData(2, 1)]
    [InlineData(3, 0)]
    [InlineData(4, 0)]
    [InlineData(5, 1)]
    [InlineData(3, 255)]
    public async Task Mission_rejects_a_target_kind_the_original_sender_cannot_produce(byte mission, byte kind)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        const uint target = 66;
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(target, 0, 9));
        var (session, key, unitId, _) = await SessionAsync(data, battles, ct);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0, 0, unitId, [unitId], mission, kind, mission == 5 ? 0u : target)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Contains("MISSION_TARGET_KIND_INVALID", answer.ResponseMetadata);
        Assert.DoesNotContain("mission-accepted", answer.ResponseMetadata);
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0u)]
    [InlineData(999u)]
    public async Task Mission_with_a_foreign_order_is_not_accepted_or_delivered(uint order)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        var recipient = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        battles.UpdateParticipant(recipient.Writer, OriginalNpcAiTests.Actor(77, 2, 4));
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(66, 0, 9));
        using var subscription = battles.Subscribe(101,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, recipient.Writer, 77);
        var (session, key, _, _) = await SessionAsync(data, battles, ct);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0, 0, order, [77u], 3, 1, 66)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Contains("MISSION_ACTOR_NOT_CONTROLLED", answer.ResponseMetadata);
        Assert.DoesNotContain("mission-accepted", answer.ResponseMetadata);
        Assert.False(recipient.Reader.TryRead(out _));
    }

    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Mission_order_is_the_character_not_the_selected_unit(bool useUnitAsOrder)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        const uint unit = 900;
        var battles = new OriginalTacticalBattleRegistry();
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(unit, 2, 4));
        var (session, key, _, owner) = await SessionAsync(data, battles, ct);
        await using var read = data.CreateCommand("SELECT character_id FROM character WHERE account_id=$1");
        read.Parameters.AddWithValue(owner);
        var character = checked((uint)(long)(await read.ExecuteScalarAsync(ct))!);
        Assert.NotEqual(unit, character);
        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0, 0, useUnitAsOrder ? unit : character, [unit], 5, 0, 0)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);
        Assert.Contains(useUnitAsOrder ? "MISSION_ACTOR_NOT_CONTROLLED" : "mission-accepted",
            answer.ResponseMetadata);
    }

    /// <summary>
    /// The normal case the shipped client produces: the player drag-selects his
    /// own fleet, so state 0x19's frame builder copies his own unit out of the
    /// selection array at 0x02215128 and `unit` is that unit. An authority that
    /// refuses 「自分自身には…」 refuses every 任務 the client can send - this
    /// authority's first draft did, and this test is why it no longer does.
    /// </summary>
    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(0, 1, 66u)]
    [InlineData(5, 0, 0u)]
    public async Task A_mission_may_name_the_players_own_fleet(byte mission, byte kind, uint target)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        const uint enemyUnit = 66;
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(enemyUnit, 0, 9));
        var (session, key, unitId, _) = await SessionAsync(data, battles, ct);

        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0x11223344, 0, unitId, [unitId], mission, kind, target)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);

        Assert.Contains("mission-accepted", answer.ResponseMetadata);
        Assert.Contains(FormattableString.Invariant($"units={unitId}"), answer.ResponseMetadata);
        Assert.Contains(FormattableString.Invariant($"target={target}"), answer.ResponseMetadata);
    }

    /// <summary>
    /// The delivered case: the recipient's own connection receives the
    /// 0x0421 frame, and other same-side tactical participants also receive it.
    /// Enemy, other-grid and strategic observers must not receive the mission.
    /// The
    /// sender gets the same body back as its response.
    /// </summary>
    [Theory(Skip = "Requires isolated PostgreSQL", SkipUnless = nameof(HasTestDatabase))]
    [InlineData(2, 101u, true, true)]
    [InlineData(0, 101u, true, false)]
    [InlineData(2, 102u, true, false)]
    [InlineData(2, 101u, false, false)]
    public async Task A_mission_reaches_all_same_side_tactical_participants(
        byte bystanderPower, uint bystanderGrid, bool tacticalActive, bool receives)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var data = await CreateAsync();
        var battles = new OriginalTacticalBattleRegistry();
        const uint recipientUnit = 77;
        const uint bystanderUnit = 88;
        var recipient = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        var bystander = Channel.CreateUnbounded<OriginalTacticalNotificationBatch>();
        // Same power as the seeded character (faction 2) = friendly.
        battles.UpdateParticipant(recipient.Writer, OriginalNpcAiTests.Actor(recipientUnit, 2, 4));
        var other = OriginalNpcAiTests.Actor(bystanderUnit, bystanderPower, 6);
        battles.UpdateParticipant(bystander.Writer, new(other.Unit with { Grid = bystanderGrid },
            other.Ship, other.Corps, other.CharacterFrame.ToArray(), other.Power));
        // Missions 0..4 name an object the client picked out of the hit list.
        const uint targetUnit = 66;
        battles.UpdateParticipant(Channel.CreateUnbounded<OriginalTacticalNotificationBatch>().Writer,
            OriginalNpcAiTests.Actor(targetUnit, 0, 9));
        var (session, key, unitId, _) = await SessionAsync(data, battles, ct);
        // Subscribe after scene setup so combat-start notifications cannot
        // change the observer mode whose mission eligibility is under test.
        using var recipientLease = battles.Subscribe(101,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, recipient.Writer, recipientUnit);
        using var bystanderLease = battles.Subscribe(bystanderGrid,
            OriginalAuthoredPlayableCatalog.TacticalShipCapabilities.Number, bystander.Writer, bystanderUnit,
            tacticalActive: tacticalActive);

        var request = OriginalTacticalCommandCodec.EncodeMissionCommand(
            new(0x11223344, 0, unitId, [recipientUnit], 3, 1, targetUnit)).AsSpan(4).ToArray();
        var answer = await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(request, key, 3), ct);

        Assert.Contains("mission-accepted", answer.ResponseMetadata);
        Assert.Contains("units=77", answer.ResponseMetadata);
        Assert.True(recipient.Reader.TryRead(out var delivered));
        var frame = delivered!.Frames.Single().ToArray();
        Assert.True(OriginalTacticalCommandCodec.TryDecodeMissionCommand(frame.AsSpan(4), out var decoded));
        Assert.Equal([recipientUnit], decoded.UnitIds);
        Assert.Equal(3, decoded.Mission);
        Assert.Equal(targetUnit, decoded.TargetId);
        Assert.Equal(receives, bystander.Reader.TryRead(out var broadcast));
        if (receives)
            Assert.Equal(frame, Assert.Single(broadcast!.Frames).ToArray());
        Assert.False(bystander.Reader.TryRead(out _));
    }


    /// <summary>
    /// The shipped skirmish catalog carries an allied picket in the rear grid,
    /// so a tactical order has a friendly fleet to address without a second human
    /// player.
    /// </summary>
    [Fact]
    public void The_rear_grid_carries_a_friendly_fleet_for_an_order_to_reach()
    {
        var catalog = OriginalBattlefieldCatalog.LoadConfigured(
            Path.Combine(AppContext.BaseDirectory, "battlefields", "fleet-skirmish.json"));
        var rear = catalog.Resolve(102);

        // The two support vessels are also power 2; the picket is the one with no role.
        var allied = Assert.Single(rear.Fleets ?? [], f => f.Power == 2 && f.Role is null);
        var ship = Assert.Single(allied.Ships);
        var projected = Assert.Single(allied.Project(102));
        Assert.Equal(ship.Id, projected.Unit.Id);
        // Same power and camp as the player: a suggestion may be addressed to it,
        // and the NPC target selector will not treat it as hostile.
        Assert.False(projected.IsHostileTo(2, 0));
        Assert.Equal(102u, projected.Unit.Grid);
    }

    private static async Task<(NaturalAuthoritySession Session, byte[] Key, uint UnitId, Guid Owner)> SessionAsync(
        NpgsqlDataSource data, OriginalTacticalBattleRegistry battles, CancellationToken ct,
        OriginalBattlefieldCatalog? catalog = null)
    {
        IAccountStore store = new PostgresAccountStore(data);
        var (owner, character) = await SeedAsync(data);
        var unitId = checked((uint)character);
        await using (var stage = data.CreateCommand("UPDATE original_grid_unit SET current_cell_id=101"))
            await stage.ExecuteNonQueryAsync(ct);
        var key = new byte[16];
        var session = OriginalWarpSessionClockTests.CreateWorldEnteredSession(TimeProvider.System, key,
            store: store, battles: battles, catalog: catalog);
        OriginalWarpSessionClockTests.SetField(session, "_accountId", owner);
        OriginalWarpSessionClockTests.SetField(session, "_worldCharacterId", checked((uint)character));
        OriginalWarpSessionClockTests.SetField(session, "_worldGridUnitId", unitId);
        OriginalWarpSessionClockTests.SetField(session, "_worldGridCellId", 101u);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0205"), key, 1), ct);
        await session.ProcessAsync(0x30,
            OriginalClientInnerFrameCodec.Encode(Convert.FromHexString("0F02"), key, 2), ct);
        return (session, key, unitId, owner);
    }

    private static async Task<(Guid Owner, long Character)> SeedAsync(NpgsqlDataSource data)
    {
        var ct = TestContext.Current.CancellationToken;
        var owner = Guid.NewGuid();
        await using (var seed = data.CreateCommand("""
            INSERT INTO account(account_id,normalized_login,password_hash,password_salt,
                argon_memory_kib,argon_iterations,argon_parallelism,status,authority_version,authority_state_hash)
            VALUES($1,'mission',decode(repeat('00',32),'hex'),decode(repeat('00',16),'hex'),8,1,1,'active',1,repeat('0',64))
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

    private static async Task<NpgsqlDataSource> CreateAsync()
    {
        var connection = Environment.GetEnvironmentVariable("LOGH7_RETURN_BASE_TEST_DB")!;
        var schema = "mission_" + Guid.NewGuid().ToString("N");
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
