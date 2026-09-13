using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalTacticalCommandCodecTests
{
    [Fact]
    public void Information_grid_response_sets_tactical_state_for_the_requested_grid()
    {
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("03160065"),
            out var response));

        Assert.Equal(
            "00000000" + "0317" + "0065" + "01",
            Convert.ToHexString(response));
    }

    [Fact]
    public void Tactical_transition_gate_bootstraps_once_then_refreshes_player_context()
    {
        var gate = new OriginalTacticalTransitionGate();

        Assert.Equal(
            OriginalTacticalTransitionStep.WorldBootstrap,
            gate.OnWorldInitializeRequest());
        Assert.Equal(
            OriginalTacticalTransitionStep.WorldRefresh,
            gate.OnWorldInitializeRequest());
        Assert.Equal(
            OriginalTacticalTransitionStep.WorldRefresh,
            gate.OnWorldInitializeRequest());
    }

    [Theory]
    [InlineData(0, 101, "0000000065")]
    [InlineData(1, 101, "0100000065")]
    [InlineData(2, 0x12345678, "0212345678")]
    public void Notify_tactics_encodes_compact_state_byte_and_grid_id(byte state, uint grid, string body)
    {
        // 0048CB80 reads one raw byte followed by one network-order u32.
        // 0048CC40 names them state/grid. The dispatcher copy is padded,
        // not evidence for two wire u32s or for a battle-id field.
        var frame = OriginalTacticalCommandCodec.EncodeNotifyTactics(
            state,
            grid);

        Assert.Equal(
            "00000000" + "0F1F" + body,
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Authored_tactical_damage_accumulates_and_marks_destroyed_at_one_hundred()
    {
        var first = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
            new OriginalTacticalDamageState(0, 0));
        var terminal = OriginalTacticalCommandAuthority.ApplyAuthoredDamage(
            new OriginalTacticalDamageState(75, 0));

        Assert.Equal(new OriginalTacticalDamageState(25, 0), first);
        Assert.Equal(new OriginalTacticalDamageState(100, 100), terminal);
    }

    [Fact]
    public void A_nonlethal_hit_preserves_survivors_and_morale_in_the_original_notification()
    {
        // 004A6400 labels damage/destroy/morale; 004C0DF0 computes
        // normal=100-damage, remaining=100-destroy, and stores morale.
        var notification = OriginalTacticalCommandAuthority.CreateDamageNotification(
            0, 2, 1, 1, 99, new OriginalTacticalDamageState(25, 0), 100);
        var frame = OriginalTacticalCommandCodec.EncodeAttackedNotification(notification);
        Assert.Equal("00000000042600000000000000020101000000630019000000000064",
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Destroyed_count_is_independent_of_damage_and_is_not_a_boolean()
    {
        var notification = OriginalTacticalCommandAuthority.CreateDamageNotification(
            0, 2, 12, 1, 99, new OriginalTacticalDamageState(75, 20), 73);
        var frame = OriginalTacticalCommandCodec.EncodeAttackedNotification(notification);
        Assert.Equal("00000000042600000000000000020C0100000063004B001400000049",
            Convert.ToHexString(frame));
    }

    [Theory]
    [InlineData(0, 1)]
    [InlineData(1, 12)]
    [InlineData(2, 8)]
    public void Shoot_selection_resolves_equipped_weapon_id_not_the_UI_selector(byte selection, byte expectedArms)
    {
        var template = new OriginalStaticUnitShipCapabilities(
            100, 1, 1, 100, 100, 100, 100, 100, 1, 25, 63,
            GunArms: 12, GunPower: 10, MissileArms: 8, MissilePower: 40);
        Assert.Equal(expectedArms, OriginalTacticalCommandAuthority.ResolveShotArms(selection, template));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(2)]
    [InlineData(3)]
    [InlineData(255)]
    public void Unequipped_or_unknown_weapon_does_not_produce_a_damage_weapon(byte selection)
    {
        Assert.Null(OriginalTacticalCommandAuthority.ResolveShotArms(selection, default));
    }

    [Fact]
    public void Attacked_notification_encodes_the_parser_proven_compact_layout()
    {
        var frame = OriginalTacticalCommandCodec.EncodeAttackedNotification(
            new OriginalTacticalAttackedNotification(
                Time: 1,
                AttackerId: 2,
                Arms: 3,
                TargetKind: 4,
                TargetId: 99,
                Damaged: 5,
                Destroyed: 6,
                ShieldDirection: 7,
                DamagedShield: 8,
                Morale: 9));

        Assert.Equal(
            "00000000" + "0426" +
            "00000001" + "00000002" + "03" + "04" + "00000063" +
            "0005" + "0006" + "07" + "0008" + "09",
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Attack_and_shoot_commands_decode_target_fields()
    {
        var attackPayload = Convert.FromHexString(
            "0405" + "00000001" + "00000002" + "00000003" +
            "01" + "00000002" + "04" + "00000063");
        var shootPayload = Convert.FromHexString(
            "0406" + "00000001" + "00000002" + "00000003" +
            "01" + "00000002" + "05" + "06" + "00000063");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeAttackShipCommand(
            attackPayload,
            out var attack));
        Assert.True(OriginalTacticalCommandCodec.TryDecodeShootShipCommand(
            shootPayload,
            out var shoot));
        Assert.Equal(new uint[] { 2 }, attack.UnitIds);
        Assert.Equal((byte)4, attack.Kind);
        Assert.Equal(99u, attack.TargetId);
        Assert.Equal(new uint[] { 2 }, shoot.UnitIds);
        Assert.Equal((byte)5, shoot.Arms);
        Assert.Equal((byte)6, shoot.TargetKind);
        Assert.Equal(99u, shoot.TargetId);
    }

    [Fact]
    public void Stop_command_decodes_separate_unit_and_base_sets()
    {
        var payload = Convert.FromHexString(
            "040A" + "00000001" + "00000002" + "00000003" +
            "01" + "00000002" +
            "01" + "00000063");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeStopCommand(
            payload,
            out var command));
        Assert.Equal(new uint[] { 2 }, command.UnitIds);
        Assert.Equal(new uint[] { 99 }, command.BaseIds);
    }

    [Fact]
    public void Target_command_authority_rejects_foreign_attackers_and_self_targets()
    {
        Assert.True(OriginalTacticalCommandAuthority.AuthorizeTargetCommand(
            new uint[] { 2 },
            controlledUnitId: 2,
            targetId: 99).Accepted);
        Assert.False(OriginalTacticalCommandAuthority.AuthorizeTargetCommand(
            new uint[] { 3 },
            controlledUnitId: 2,
            targetId: 99).Accepted);
        Assert.False(OriginalTacticalCommandAuthority.AuthorizeTargetCommand(
            new uint[] { 2 },
            controlledUnitId: 2,
            targetId: 2).Accepted);
    }

    [Fact]
    public void Automatic_attack_resolves_zero_target_to_the_authoritative_enemy()
    {
        var automatic = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
            new uint[] { 2 },
            controlledUnitId: 2,
            requestedTargetId: 0,
            automaticTargetId: 99);
        var explicitTarget = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
            new uint[] { 2 },
            controlledUnitId: 2,
            requestedTargetId: 77,
            automaticTargetId: 99);

        Assert.True(automatic.Accepted);
        Assert.Equal(99u, automatic.TargetId);
        Assert.True(explicitTarget.Accepted);
        Assert.Equal(77u, explicitTarget.TargetId);
    }

    [Theory]
    [InlineData(0u)]
    [InlineData(99u)]
    public void Cease_fire_is_accepted_without_resolving_a_damage_target(uint enemyId)
    {
        // Original UI mode 3 is serialized as kind 0, not kind 3 (004B4110).
        var decision = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
            new uint[] { 2 }, 2, 0, enemyId, attackKind: 0);

        Assert.True(decision.Accepted);
        Assert.Equal(0u, decision.TargetId);
    }

    [Theory]
    [InlineData((byte)3)]
    [InlineData((byte)4)]
    [InlineData((byte)255)]
    public void Unknown_attack_modes_are_rejected_before_any_damage_target_is_chosen(byte kind)
    {
        var decision = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
            new uint[] { 2 }, 2, 0, 99, attackKind: kind);

        Assert.False(decision.Accepted);
        Assert.Equal("TACTICAL_ATTACK_KIND_INVALID", decision.ErrorCode);
        Assert.Equal(0u, decision.TargetId);
    }

    [Fact]
    public void Cease_fire_cannot_control_another_players_unit()
    {
        var decision = OriginalTacticalCommandAuthority.AuthorizeAttackCommand(
            new uint[] { 3 }, 2, 0, 99, attackKind: 0);

        Assert.False(decision.Accepted);
        Assert.Equal("TACTICAL_UNIT_NOT_CONTROLLED", decision.ErrorCode);
    }

    [Fact]
    public void Move_ship_decodes_current_and_destination_vectors()
    {
        var payload = Convert.FromHexString(
            "0400" +
            "00000001" + "00000002" + "00000003" +
            "01" +
            "00000002" + "3F800000" + "00000000" + "00000000" + "00000000" +
            "40000000" + "3F000000" +
            "01" + "41200000" + "C0000000" + "40600000");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeMoveShipCommand(
            payload,
            out var command));
        Assert.Equal(1u, command.Time);
        Assert.Equal(2u, command.Wait);
        Assert.Equal(3u, command.Order);
        var unit = Assert.Single(command.Units);
        Assert.Equal(2u, unit.UnitId);
        Assert.Equal(1.0f, unit.Direction);
        Assert.Equal(2.0f, command.Velocity);
        Assert.Equal(0.5f, command.ToDirection);
        var destination = Assert.Single(command.Destinations);
        Assert.Equal(new OriginalTacticalVector(10.0f, -2.0f, 3.5f), destination);
    }

    [Fact]
    public void Move_ship_authority_updates_only_the_controlled_unit_position()
    {
        var state = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(2, 7);
        var command = new OriginalTacticalMoveShipCommand(
            1,
            2,
            3,
            new[] { new OriginalTacticalMoveUnit(2, 1, 0, 0, 0) },
            2,
            0.5f,
            new[] { new OriginalTacticalVector(10, -2, 3.5f) });

        var accepted = OriginalTacticalCommandAuthority.AuthorizeMoveShip(
            command,
            controlledUnitId: 2,
            state);
        var rejected = OriginalTacticalCommandAuthority.AuthorizeMoveShip(
            command,
            controlledUnitId: 99,
            state);

        Assert.True(accepted.Accepted);
        var moved = Assert.NotNull(accepted.State);
        Assert.Equal(10, moved.X);
        Assert.Equal(-2, moved.Y);
        Assert.Equal(3.5f, moved.Z);
        Assert.Equal(0.5f, moved.Direction);
        Assert.False(rejected.Accepted);
        Assert.Equal("TACTICAL_UNIT_NOT_CONTROLLED", rejected.ErrorCode);
    }

    [Fact]
    public void Tactical_command_echo_adds_only_the_inner_message_code_prefix()
    {
        var payload = Convert.FromHexString("0404AABB");

        var frame = OriginalTacticalCommandCodec.EncodeCommandEcho(payload);

        Assert.Equal("000000000404AABB", Convert.ToHexString(frame));
    }

    [Fact]
    public void Warp_authority_accepts_only_the_controlled_unit_and_projects_location()
    {
        var accepted = OriginalTacticalCommandAuthority.AuthorizeWarp(
            new OriginalTacticalWarpCommand(10, 20, 30, new uint[] { 2 }),
            controlledUnitId: 2,
            grid: 101,
            @base: 1,
            mode: 4);
        var rejected = OriginalTacticalCommandAuthority.AuthorizeWarp(
            new OriginalTacticalWarpCommand(10, 20, 30, new uint[] { 3 }),
            controlledUnitId: 2,
            grid: 101,
            @base: 1,
            mode: 4);

        Assert.True(accepted.Accepted);
        var notification = Assert.NotNull(accepted.Notification);
        Assert.Equal(0u, notification.Time);
        Assert.Equal(101u, notification.Grid);
        Assert.Equal(1u, notification.Base);
        Assert.Equal((ushort)4, notification.Mode);
        Assert.Equal(new uint[] { 2 }, notification.UnitIds);
        Assert.False(rejected.Accepted);
        Assert.Equal("TACTICAL_UNIT_NOT_CONTROLLED", rejected.ErrorCode);
    }

    [Fact]
    public void Warp_completion_does_not_adopt_uninitialized_request_time()
    {
        // Original004B4500 never initializes the first two outgoing dwords.
        // A future request timestamp must not stall an already-completed warp.
        var decision = OriginalTacticalCommandAuthority.AuthorizeWarp(
            new OriginalTacticalWarpCommand(0xf1234567, 0xdeadbeef, 2, new uint[] { 2 }),
            controlledUnitId: 2, grid: 101, @base: 1, mode: 0);
        Assert.True(decision.Accepted);
        Assert.Equal(0u, Assert.NotNull(decision.Notification).Time);
    }

    [Fact]
    public void Warp_completion_uses_authority_tick_instead_of_request_scheduling()
    {
        var decision = OriginalTacticalCommandAuthority.AuthorizeWarp(
            new OriginalTacticalWarpCommand(0xf1234567, 0xdeadbeef, 2, new uint[] { 2 }),
            controlledUnitId: 2, grid: 102, @base: 0, mode: 0, authorityTick: 321);
        var notification = Assert.NotNull(decision.Notification);
        var frame = OriginalTacticalCommandCodec.EncodeWarpedNotification(notification);
        Assert.Equal(321u, notification.Time);
        Assert.Equal("00000000042500000141000000660000000000000100000002",
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Warp_command_decodes_compact_original_wire_fields()
    {
        var payload = Convert.FromHexString(
            "0404" +
            "00000001" +
            "00000002" +
            "00000003" +
            "02" +
            "01020304" +
            "A1A2A3A4");

        Assert.True(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            payload,
            out var command));
        Assert.Equal(1u, command.Time);
        Assert.Equal(2u, command.Wait);
        Assert.Equal(3u, command.Order);
        Assert.Equal(new uint[] { 0x01020304, 0xa1a2a3a4 }, command.UnitIds);
    }

    [Fact]
    public void Warp_command_rejects_truncation_and_more_than_thirty_two_units()
    {
        Assert.False(OriginalTacticalCommandCodec.TryDecodeWarpCommand(
            Convert.FromHexString("040400000000000000000000000001010203"),
            out _));

        var over = new byte[sizeof(ushort) + 13 + 33 * sizeof(uint)];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(over, 0x0404);
        over[sizeof(ushort) + 12] = 33;
        Assert.False(OriginalTacticalCommandCodec.TryDecodeWarpCommand(over, out _));
    }

    [Fact]
    public void Warped_notification_encodes_time_location_mode_and_unit_ids()
    {
        var frame = OriginalTacticalCommandCodec.EncodeWarpedNotification(
            new OriginalTacticalWarpedNotification(
                Time: 1,
                Grid: 2,
                Base: 3,
                Mode: 4,
                UnitIds: new uint[] { 0x01020304, 0xa1a2a3a4 }));

        Assert.Equal(
            "00000000" +
            "0425" +
            "00000001" +
            "00000002" +
            "00000003" +
            "0004" +
            "02" +
            "01020304" +
            "A1A2A3A4",
            Convert.ToHexString(frame));
    }
}
