using Logh7.Server.OriginalGateway;
using Xunit;

namespace Logh7.Server.ProtocolTests.OriginalGateway;

public sealed class OriginalUnitShipCodecTests
{
    [Fact]
    public void Equipped_gun_and_missile_data_reaches_the_original_static_ship_record()
    {
        var capabilities = new OriginalStaticUnitShipCapabilities(
            100, 1, 1, 100, 100, 100, 100, 100, 1, 25, 63,
            GunArms: 12, GunPower: 10, GunAngleMask: 63,
            MissileArms: 8, MissilePower: 40, MissileAngleMask: 63, MissileConsumption: 5);
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [new OriginalStaticUnitShipTemplate(0, 0, 0, 0, 0, string.Empty, capabilities)]);
        // Original 004109A0 compact wire, empty name + explicit NUL.
        Assert.Equal("0C000A3F0800283F0005", Convert.ToHexString(frame.AsSpan(92, 10)));
    }

    [Fact]
    public void Tactical_scene_requests_preserve_original_count_widths()
    {
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalSceneRequest(
            Convert.FromHexString("03360001"),
            out var characters));
        Assert.Equal(1u, characters.Qualifier);
        Assert.Empty(characters.Ids);

        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalSceneRequest(
            Convert.FromHexString("033E0002000000027F000001"),
            out var corps));
        Assert.Equal(new uint[] { 2, 0x7f000001 }, corps.Ids);

        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalSceneRequest(
            Convert.FromHexString("03440100000001"),
            out var bases));
        Assert.Equal(new uint[] { 1 }, bases.Ids);

        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalSceneRequest(
            Convert.FromHexString("034600000065"),
            out var obstacles));
        Assert.Equal(101u, obstacles.Qualifier);
    }

    [Fact]
    public void Tactical_scene_bootstrap_encodes_characters_positions_and_empty_obstacles()
    {
        var characters = OriginalSystemSceneCodec.EncodeTacticalCharacters(
            new uint[] { 7, 8 });
        var positions = OriginalSystemSceneCodec.EncodeUnitPositions(
            OriginalSystemSceneCodec.CreateTacticalBattlefield(
                2,
                7,
                OriginalBattlefieldCatalog.LoadDefault().Resolve(101)));
        var obstacles = OriginalSystemSceneCodec.EncodeEmptyObstacles(101);

        Assert.Equal("00000000033700020000000700000008", Convert.ToHexString(characters));
        Assert.Equal(48, positions.Length);
        Assert.Equal("0000000003490002", Convert.ToHexString(positions.AsSpan(0, 8)));
        Assert.Equal(2u, ReadUInt32(positions, 8));
        Assert.Equal(0x7f000001u, ReadUInt32(positions, 28));
        Assert.Equal("000000000347000000650000000000", Convert.ToHexString(obstacles));
    }

    [Fact]
    public void Tactics_corps_response_encodes_the_reverse_traced_fifty_five_byte_record()
    {
        var frame = OriginalSystemSceneCodec.EncodeTacticalCorps(
            new OriginalTacticalCorpsResponse(
            [
                new OriginalTacticalCorpsRecord(
                    Id: 0x01020304,
                    Mission: 0x05,
                    TargetKind: 0x06,
                    Target: 0x0708090a,
                    CommandRange: 1.0f,
                    TacticsChief: 0x0b,
                    File: 0x0c,
                    PowerMove: 0x0d,
                    PowerWarp: 0x0e,
                    PowerSensor: 0x0f,
                    PowerBeam: 0x10,
                    PowerGun: 0x11,
                    PowerShield: [0x12, 0x13, 0x14, 0x15, 0x16, 0x17],
                    FillBeam: 0x1819,
                    FillGun: 0x1a1b,
                    FillShield: [0x1c1d, 0x1e1f, 0x2021, 0x2223, 0x2425, 0x2627],
                    DamagedShield: [0x2829, 0x2a2b, 0x2c2d, 0x2e2f, 0x3031, 0x3233]),
            ]));

        Assert.Equal(
            "00000000" +
            "033F" +
            "0001" +
            "01020304" +
            "05" +
            "06" +
            "0708090A" +
            "3F800000" +
            "0B" +
            "0C" +
            "0D" +
            "0E" +
            "0F" +
            "10" +
            "11" +
            "121314151617" +
            "1819" +
            "1A1B" +
            "1C1D1E1F2021222324252627" +
            "28292A2B2C2D2E2F30313233",
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Information_obstacle_round_trips_every_original_array_shape()
    {
        var response = new OriginalTacticalObstacleResponse(
            Grid: 101,
            BlackHoles:
            [
                new OriginalBlackHoleObstacle(1, 2, 3, 1, 2),
            ],
            AsteroidBelts:
            [
                new OriginalAsteroidBeltObstacle(4, 5, 6, 3, 4),
            ],
            GasClouds:
            [
                new OriginalGasCloudObstacle(7, 8, 9, 5, 6, 1, 6, 7),
            ],
            AbnormalGravities:
            [
                new OriginalAbnormalGravityObstacle(10, 11, 12, 8, 9),
            ],
            Circles:
            [
                new OriginalCircleObstacle(13, 14, 15, 10, 11, 12, 13),
            ]);

        var frame = OriginalSystemSceneCodec.EncodeObstacles(response);

        Assert.Equal(107, frame.Length);
        Assert.Equal("0000000003470000006501", Convert.ToHexString(frame.AsSpan(0, 11)));
        Assert.True(OriginalSystemSceneCodec.TryDecodeObstacles(
            frame.AsSpan(OriginalLoginCodec.MessageCodeSize),
            out var decoded));
        Assert.Equal(response.Grid, decoded.Grid);
        Assert.Equal(response.BlackHoles, decoded.BlackHoles);
        Assert.Equal(response.AsteroidBelts, decoded.AsteroidBelts);
        Assert.Equal(response.GasClouds, decoded.GasClouds);
        Assert.Equal(response.AbnormalGravities, decoded.AbnormalGravities);
        Assert.Equal(response.Circles, decoded.Circles);
    }

    [Fact]
    public void Battlefield_catalog_resolves_exact_grid_then_external_default()
    {
        const string json = """
        {
          "schemaVersion": 1,
          "templates": [
            {
              "id": "open-space-default-v1",
              "grid": 0,
              "evidenceStatus": "NEW_DESIGN",
              "enemyPower": 3,
              "playerSpawn": { "x": -10, "y": 0, "z": 0, "direction": 0 },
              "enemySpawn": { "x": 10, "y": 0, "z": 0, "direction": 3.1415927 },
              "baseSpawn": { "x": 0, "y": 30, "z": 0, "direction": 0 },
              "blackHoles": [],
              "asteroidBelts": [],
              "gasClouds": [],
              "abnormalGravities": [],
              "circles": []
            },
            {
              "id": "grid-101-observed-later",
              "grid": 101,
              "evidenceStatus": "NEW_DESIGN",
              "enemyPower": 3,
              "playerSpawn": { "x": -20, "y": 0, "z": 0, "direction": 0 },
              "enemySpawn": { "x": 20, "y": 0, "z": 0, "direction": 3.1415927 },
              "baseSpawn": { "x": 0, "y": 40, "z": 0, "direction": 0 },
              "blackHoles": [],
              "asteroidBelts": [],
              "gasClouds": [],
              "abnormalGravities": [],
              "circles": []
            }
          ]
        }
        """;

        var catalog = OriginalBattlefieldCatalog.Parse(json);

        Assert.Equal("grid-101-observed-later", catalog.Resolve(101).Id);
        Assert.Equal(-20, catalog.Resolve(101).PlayerSpawn.X);
        Assert.Equal("open-space-default-v1", catalog.Resolve(999).Id);
    }

    [Fact]
    public void Authored_tactical_battlefield_contains_player_and_opposing_ship()
    {
        var battlefield = OriginalSystemSceneCodec.CreateTacticalBattlefield(
            playerUnitId: 2,
            playerCharacterId: 7,
            template: OriginalBattlefieldCatalog.LoadDefault().Resolve(101));

        Assert.Equal(2, battlefield.Records.Count);
        var player = battlefield.Records[0];
        var opponent = battlefield.Records[1];
        Assert.Equal(2u, player.Id);
        Assert.Equal(7u, player.Character);
        Assert.Equal(-10, player.X);
        Assert.NotEqual(player.Id, opponent.Id);
        Assert.NotEqual(player.Character, opponent.Character);
        Assert.Equal(10, opponent.X);
    }

    [Fact]
    public void Information_unit_batch_projects_two_units_and_damage_state()
    {
        var frame = OriginalWorldEntryCodec.EncodeUnits(new[]
        {
            new OriginalInformationUnitProjection(
                Id: 2,
                Grid: 101,
                Base: 1,
                MoraleMax: 100,
                Damaged: 0,
                Destroyed: 0,
                Supplies: 100,
                Mobilization: 100,
                Cruising: 10),
            new OriginalInformationUnitProjection(
                Id: 99,
                Grid: 101,
                Base: 0,
                MoraleMax: 100,
                Damaged: 25,
                Destroyed: 1,
                Supplies: 50,
                Mobilization: 50,
                Cruising: 8),
        });

        Assert.Equal(92, frame.Length);
        Assert.Equal(0x0325, ReadUInt16(frame, 4));
        Assert.Equal(2, ReadUInt16(frame, 6));
        Assert.Equal(2u, ReadUInt32(frame, 8));
        Assert.Equal((ushort)0, ReadUInt16(frame, 34));
        Assert.Equal(99u, ReadUInt32(frame, 50));
        Assert.Equal((ushort)25, ReadUInt16(frame, 76));
        Assert.Equal((ushort)1, ReadUInt16(frame, 78));
        Assert.Equal(BitConverter.SingleToUInt32Bits(8), ReadUInt32(frame, 88));
    }

    [Fact]
    public void Information_unit_default_selects_static_unitship_template_zero()
    {
        var frame = OriginalWorldEntryCodec.EncodeUnit(2);

        // FUN_004C32A0 passes InformationUnit.kind directly to FUN_004C46A0,
        // which stores it at tactical entity +0x8BC. The first and only
        // StaticInformationUnitShip template therefore requires index 0.
        Assert.Equal((ushort)0, ReadUInt16(frame, 12));
    }

    public static TheoryData<string> MalformedTacticsUnitShipRequests => new()
    {
        "FFFF0000",
        "033A0001010203",
        "033A000001",
    };

    public static TheoryData<string> MalformedTacticsUnitShipResponses => new()
    {
        "FFFF0000",
        "033B000101020304",
        "033B000001",
    };

    [Fact]
    public void Static_unit_ship_bootstrap_keeps_playable_capabilities_with_regular_and_recovery_models()
    {
        Assert.True(OriginalWorldBootstrapCodec.TryEncodeResponse(
            Convert.FromHexString("030A"),
            out var frame));

        Assert.Equal(1087, frame.Length);
        Assert.Equal("00000000030B0A", Convert.ToHexString(frame.AsSpan(0, 7)));
        // FUN_004C4C50 expands the wire `number` into tactical template +0x218.
        // FUN_004B2740 arms entity destruction when the remaining count at
        // entity +0x8D8 reaches zero, so a playable template must be nonzero.
        Assert.Equal((byte)1, frame[15]); // name contains a NUL even when empty
        Assert.Equal((ushort)0, ReadUInt16(frame, 16));
        Assert.Equal((ushort)100, ReadUInt16(frame, 18)); // formation ship count
        Assert.Equal((ushort)100, ReadUInt16(frame, 30)); // existence / hull maximum
        Assert.Equal((ushort)100, ReadUInt16(frame, 34)); // total system power / HUD divisor
        Assert.Equal(BitConverter.SingleToUInt32Bits(100), ReadUInt32(frame, 36)); // communication / selectable range
        Assert.Equal(BitConverter.SingleToUInt32Bits(100), ReadUInt32(frame, 40)); // searching range
        Assert.Equal((ushort)100, ReadUInt16(frame, 68)); // navigation
        Assert.Equal(BitConverter.SingleToUInt32Bits(1), ReadUInt32(frame, 70)); // speed
        Assert.Equal(BitConverter.SingleToUInt32Bits(1), ReadUInt32(frame, 74)); // turn
        Assert.Equal((ushort)100, ReadUInt16(frame, 78)); // front armor
        Assert.Equal((ushort)90, ReadUInt16(frame, 84)); // shield recovery grade, not capacity
        Assert.Equal((ushort)100, ReadUInt16(frame, 86)); // shield capacity
        Assert.Equal((byte)1, frame[88]); // beam arms
        Assert.Equal((ushort)25, ReadUInt16(frame, 89)); // beam power
        // Four empty-name records have identical108-byte packed width.
        // Every supplied model needs nonzero count/power, not just record0.
        for(var record=1;record<4;record++)
        {
            Assert.Equal((ushort)100,ReadUInt16(frame,18+record*108));
            Assert.Equal((ushort)100,ReadUInt16(frame,34+record*108));
        }
        // Records4-6 are the ordinary vessel templates (manual: 300 hulls);
        // record7 is the authored flagship-class template, which the manual
        // gives a single hull. Every record still needs nonzero system power.
        for(var record=4;record<7;record++)
        {
            Assert.Equal((ushort)300,ReadUInt16(frame,18+record*108));
            Assert.Equal((ushort)100,ReadUInt16(frame,34+record*108));
        }
        Assert.Equal((ushort)1,ReadUInt16(frame,18+7*108));
        Assert.Equal((ushort)100,ReadUInt16(frame,34+7*108));
    }

    [Fact]
    public void Static_unit_ship_template_encodes_big_endian_identity_and_packed_name()
    {
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(new[]
        {
            new OriginalStaticUnitShipTemplate(
                Kind: 0x0102,
                Type: 0x03,
                Category: 0x04,
                Achievement: 0x0506,
                ModelFile: 0x0708,
                Name: "A"),
        });

        Assert.Equal(117, frame.Length);
        Assert.Equal(
            "00000000030B01" +
            "01020304050607080200410000" +
            new string('0', 97 * 2),
            Convert.ToHexString(frame));
    }

    [Fact]
    public void Static_unit_ship_templates_reject_more_than_two_hundred_records()
    {
        var template = new OriginalStaticUnitShipTemplate(0, 0, 0, 0, 0, string.Empty);

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
                Enumerable.Repeat(template, 201).ToArray()));
    }

    [Fact]
    public void Static_unit_ship_template_rejects_name_without_space_for_terminator()
    {
        var template = new OriginalStaticUnitShipTemplate(
            0,
            0,
            0,
            0,
            0,
            "ABCDEFGHIJKLM");

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalWorldBootstrapCodec.EncodeStaticUnitShips([template]));
    }

    [Theory]
    [InlineData("")]
    [InlineData("艦名")]
    [InlineData("ABCDEFGHIJKL")]
    public void Static_ship_name_overwrites_a_poisoned_client_buffer_with_a_terminated_string(string name)
    {
        var frame = OriginalWorldBootstrapCodec.EncodeStaticUnitShips(
            [new OriginalStaticUnitShipTemplate(0, 0, 0, 0, 0, name)]);
        var expandedName = Enumerable.Repeat((ushort)0x00FF, 13).ToArray();
        var count = frame[15];
        Assert.InRange(count, (byte)1, (byte)13);
        for (var i = 0; i < count; i++) expandedName[i] = ReadUInt16(frame, 16 + i * 2);
        var rendered = new string(expandedName.TakeWhile(value => value != 0).Select(value => (char)value).ToArray());
        Assert.Equal(name, rendered);
        Assert.Equal(0, expandedName[name.Length]);
    }

    [Fact]
    public void Tactics_unit_ship_id_request_uses_u16_count_and_big_endian_ids()
    {
        var request = new OriginalTacticalUnitShipIdRequest(
            new uint[] { 0x01020304, 0xa1a2a3a4 });

        var payload = OriginalSystemSceneCodec.EncodeTacticalUnitShipIdRequest(request);

        Assert.Equal("033A000201020304A1A2A3A4", Convert.ToHexString(payload));
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShipIdRequest(
            payload,
            out var decoded));
        Assert.Equal(request.Ids, decoded.Ids);
    }

    [Fact]
    public void Tactics_unit_ship_id_request_encoder_rejects_more_than_six_hundred_ids()
    {
        var request = new OriginalTacticalUnitShipIdRequest(
            Enumerable.Range(0, 601).Select(index => checked((uint)index)).ToArray());

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalSystemSceneCodec.EncodeTacticalUnitShipIdRequest(request));
    }

    [Fact]
    public void Tactics_unit_ship_id_request_decoder_rejects_count_over_six_hundred()
    {
        var payload = new byte[sizeof(ushort) + sizeof(ushort) + 601 * sizeof(uint)];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(payload, 0x033a);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(
            payload.AsSpan(sizeof(ushort)),
            601);

        Assert.False(OriginalSystemSceneCodec.TryDecodeTacticalUnitShipIdRequest(
            payload,
            out _));
    }

    [Theory]
    [MemberData(nameof(MalformedTacticsUnitShipRequests))]
    public void Tactics_unit_ship_id_request_rejects_wrong_type_truncation_and_trailing_bytes(
        string hex)
    {
        Assert.False(OriginalSystemSceneCodec.TryDecodeTacticalUnitShipIdRequest(
            Convert.FromHexString(hex),
            out _));
    }

    [Fact]
    public void Tactics_unit_ship_response_encodes_exact_forty_seven_byte_record_and_round_trips()
    {
        var response = new OriginalTacticalUnitShipResponse(new[]
        {
            new OriginalTacticalUnitShipRecord(
                Id: 0x01020304,
                Morale: 0x05,
                Confusion: 0x06,
                Character: 0x0708090a,
                X: 1.0f,
                Y: -2.0f,
                Z: 3.5f,
                Direction: -0.5f,
                DetachmentLeader: 0x11121314,
                DetachmentX: 4.0f,
                DetachmentY: -4.0f,
                DetachmentZ: 0.25f,
                DetachmentDirection: 2.0f,
                Search: 0x15),
        });

        var frame = OriginalSystemSceneCodec.EncodeTacticalUnitShips(response);

        Assert.Equal(
            "00000000" +
            "033B" +
            "0001" +
            "01020304" +
            "05" +
            "06" +
            "0708090A" +
            "3F800000" +
            "C0000000" +
            "40600000" +
            "BF000000" +
            "11121314" +
            "40800000" +
            "C0800000" +
            "3E800000" +
            "40000000" +
            "15",
            Convert.ToHexString(frame));
        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(
            frame.AsSpan(OriginalLoginCodec.MessageCodeSize),
            out var decoded));
        Assert.Equal(response.Records, decoded.Records);
    }

    [Fact]
    public void Tactics_unit_ship_response_encoder_rejects_more_than_six_hundred_records()
    {
        var records = Enumerable.Repeat(default(OriginalTacticalUnitShipRecord), 601)
            .ToArray();

        Assert.Throws<ArgumentOutOfRangeException>(() =>
            OriginalSystemSceneCodec.EncodeTacticalUnitShips(
                new OriginalTacticalUnitShipResponse(records)));
    }

    [Fact]
    public void Tactics_unit_ship_response_decoder_rejects_count_over_six_hundred()
    {
        var payload = new byte[sizeof(ushort) + sizeof(ushort) + 601 * 47];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(payload, 0x033b);
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(
            payload.AsSpan(sizeof(ushort)),
            601);

        Assert.False(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(
            payload,
            out _));
    }

    [Theory]
    [MemberData(nameof(MalformedTacticsUnitShipResponses))]
    public void Tactics_unit_ship_response_rejects_wrong_type_truncation_and_trailing_bytes(
        string hex)
    {
        Assert.False(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(
            Convert.FromHexString(hex),
            out _));
    }

    [Fact]
    public void Tactics_unit_ship_projection_returns_only_requested_matching_ids()
    {
        var available = new[]
        {
            UnitShip(1),
            UnitShip(2),
        };

        var projected = OriginalSystemSceneCodec.ProjectTacticalUnitShips(
            new OriginalTacticalUnitShipIdRequest(new uint[] { 2, 3 }),
            available);
        var empty = OriginalSystemSceneCodec.ProjectTacticalUnitShips(
            new OriginalTacticalUnitShipIdRequest(Array.Empty<uint>()),
            available);

        Assert.Equal(new uint[] { 2 }, projected.Records.Select(record => record.Id));
        Assert.Empty(empty.Records);
    }

    [Fact]
    public void Authored_tactics_unit_ship_projection_binds_persisted_unit_and_character_ids()
    {
        var record = OriginalSystemSceneCodec.CreateAuthoredTacticalUnitShip(
            unitId: 0x01020304,
            characterId: 0xa1a2a3a4);

        Assert.Equal(0x01020304u, record.Id);
        Assert.Equal(0xa1a2a3a4u, record.Character);
        Assert.Equal(default, record with { Id = 0, Character = 0 });
    }

    [Fact]
    public void World_entry_tactics_unit_ship_frame_uses_the_same_authoritative_ids()
    {
        var frame = OriginalWorldEntryCodec.EncodeTacticalUnitShip(
            unitId: 0x01020304,
            characterId: 0xa1a2a3a4);

        Assert.True(OriginalSystemSceneCodec.TryDecodeTacticalUnitShips(
            frame.AsSpan(OriginalLoginCodec.MessageCodeSize),
            out var decoded));
        var record = Assert.Single(decoded.Records);
        Assert.Equal(0x01020304u, record.Id);
        Assert.Equal(0xa1a2a3a4u, record.Character);
    }

    [Fact]
    public void Unit_command_prefetch_selector_resolves_to_the_unit_list_notify()
    {
        Assert.Equal(
            0x1207,
            OriginalSimpleRankCodec.KnownListKind(
                OriginalSimpleRankCodec.UnitCommandPrefetchSelector));
        Assert.Equal(0, OriginalSimpleRankCodec.KnownListKind(0xffff));
    }

    [Fact]
    public void Unit_command_prefetch_transaction_carries_the_authoritative_unit_id()
    {
        var beginRequestBody = new byte[OriginalSimpleCharacterRosterCodec.BeginWireBodySize];
        System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(
            beginRequestBody,
            OriginalSimpleRankCodec.UnitCommandPrefetchSelector);

        var frames = OriginalSimpleRankCodec.EncodeUnitListTransaction(
            beginRequestBody,
            new (uint, byte, ushort)[] { (0x01020304, 0, 0) });

        Assert.Equal(3, frames.Count);
        Assert.Equal(0x1200, ReadType(frames[0]));
        Assert.Equal(0x1207, ReadType(frames[1]));
        Assert.Equal(0x1201, ReadType(frames[2]));
        Assert.Equal(
            OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + 4804,
            frames[1].Length);
        Assert.Equal(
            "000101020304000000",
            Convert.ToHexString(frames[1].AsSpan(6, 9)));
    }

    private static OriginalTacticalUnitShipRecord UnitShip(uint id) =>
        new(
            id,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0);

    private static ushort ReadType(byte[] frame) =>
        System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(
            frame.AsSpan(OriginalLoginCodec.MessageCodeSize));

    private static ushort ReadUInt16(byte[] frame, int offset) =>
        System.Buffers.Binary.BinaryPrimitives.ReadUInt16BigEndian(frame.AsSpan(offset));

    private static uint ReadUInt32(byte[] frame, int offset) =>
        System.Buffers.Binary.BinaryPrimitives.ReadUInt32BigEndian(frame.AsSpan(offset));
}
