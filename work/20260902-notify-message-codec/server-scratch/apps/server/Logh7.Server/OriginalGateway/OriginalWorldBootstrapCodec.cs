using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public readonly record struct OriginalMailAddressRecord(
    uint CharacterId,
    string DisplayName);

public readonly record struct OriginalMessengerInformationRecord(
    uint CharacterId,
    string DisplayName,
    string FlagshipName,
    ushort Rank);

public readonly record struct OriginalStaticUnitShipTemplate(
    ushort Kind,
    byte Type,
    byte Category,
    ushort Achievement,
    ushort ModelFile,
    string Name,
    OriginalStaticUnitShipCapabilities? Capabilities = null,
    OriginalStaticUnitShipLogistics? Logistics = null);

// Original static template fields, not warehouse quantities or a recovery policy.
// Null retains the legacy zero fixture; zero is not evidence of free ships or
// crew-free operation. Callers must supply independently sourced catalog data.
public readonly record struct OriginalStaticUnitShipLogistics(
    uint Price, ushort Resources, ushort Cost, ushort Term, ushort Crew);

public readonly record struct OriginalStaticUnitShipCapabilities(
    ushort Navigation,
    float Speed,
    float Turn,
    ushort ArmorFront,
    ushort ArmorBack,
    ushort ArmorSide,
    ushort Shield,
    ushort ShieldCapacity,
    byte BeamArms,
    ushort BeamPower,
    byte BeamAngleMask,
    ushort Number = 0,
    ushort Existence = 0,
    ushort TotalPower = 0,
    float CommunicationRange = 0,
    float SearchingRange = 0,
    byte GunArms = 0,
    ushort GunPower = 0,
    byte GunAngleMask = 0,
    byte MissileArms = 0,
    ushort MissilePower = 0,
    byte MissileAngleMask = 0,
    ushort MissileConsumption = 0);

public static class OriginalWorldBootstrapCodec
{
    private const int StaticGridBodySize = 0x138c;
    private const int MaximumMailAddresses = 100;
    private const int MaximumMailAddressNameCharacters = 13;
    private const int MaximumMessengerCharacters = 101;
    private const int MaximumMessengerNameCharacters = 13;
    private const int MaximumMessengerFlagshipCharacters = 16;
    private const int MaximumStaticUnitShipTemplates = 200;
    // Input_ResponseStaticInformationUnitShip allows 13 u16 elements,
    // including the NUL consumed by the direct name reader in 0x0054D886.
    private const int MaximumStaticUnitShipNameCharacters = 12;

    public static bool TryEncodeResponse(ReadOnlySpan<byte> request, out byte[] response,
        IReadOnlyList<OriginalStaticBaseRecord>? staticBases = null,
        OriginalStaticArmsTable? staticArms = null)
    {
        response = [];
        if (request.Length == sizeof(ushort) * 2 &&
            BinaryPrimitives.ReadUInt16BigEndian(request) == 0x0316)
        {
            response = EncodeInformationGrid(
                BinaryPrimitives.ReadUInt16BigEndian(request[sizeof(ushort)..]),
                tacticsState: 1);
            return true;
        }

        if (request.Length == 0x1b &&
            BinaryPrimitives.ReadUInt16BigEndian(request) == 0x0f0d)
        {
            response = EncodeCompactCommandEcho(request);
            return true;
        }

        if (request.Length != sizeof(ushort))
        {
            return false;
        }

        var requestType = BinaryPrimitives.ReadUInt16BigEndian(request);
        response = requestType switch
        {
            0x0304 => EncodeStaticCards(),
            0x0306 => EncodeStaticCardCommands(),
            0x0308 => EncodeStaticPowerDistribution(),
            0x030a => EncodeStaticUnitShips(),
            0x030c => EncodeZeroFilled(0x030d, 0x0184),
            0x030e => EncodeZeroFilled(0x030f, 0x0034),
            0x0310 => (staticArms ?? OriginalAuthoredPlayableCatalog.TacticalArms).EncodeResponse(),
            0x0312 => EncodeStaticGridTypes(),
            0x0314 => EncodeStaticGrid(),
            0x031c => staticBases is null ? EncodeStaticBases() : EncodeStaticBases(staticBases),
            0x0f00 => EncodeStatus(0x0f01),
            0x0f02 => EncodeStatus(0x0f03),
            0x0f04 => EncodeMailAddresses([]),
            0x1000 => EncodeZeroFilled(0x1001, 0x1c0),
            _ => []
        };
        return response.Length != 0;
    }

    // Original input 004A7670: u32/u32/u32/u16, NOT the padded 16-byte object.
    // Completion scheduling and authoritative unit updates are the caller's responsibility.
    public static byte[] EncodeNotifyRepairFleet(
        uint maneuverUnit, uint target, uint maneuverSupplies, ushort targetDamage)
    {
        var response = Allocate(0x042d, 14);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), maneuverUnit);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(10), target);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(14), maneuverSupplies);
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(18), targetDamage);
        return response;
    }

    // Original 042E input object uses shared reader 0043E9E0: four u32 values.
    public static byte[] EncodeNotifySupplyFleet(
        uint transportUnit, uint target, uint transportSupplies, uint targetSupplies)
    {
        var response = Allocate(0x042e, 16);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), transportUnit);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(10), target);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(14), transportSupplies);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(18), targetSupplies);
        return response;
    }

    // 043C input reader004A9AD0: u32,u32,u8,u8,u32 (14 wire bytes).
    // Logger004A9C20 names these time,id,kind,mission,achievement.
    // Preserve raw kind/id: their gameplay domains are not yet recovered.
    public static byte[] EncodeNotifyMissionResult(
        uint tick, uint id, byte kind, byte mission, uint achievement)
    {
        var response = Allocate(0x043c, 14);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), tick);
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(10), id);
        response[14] = kind;
        response[15] = mission;
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(16), achievement);
        return response;
    }

    // 004A8120 reads power/camp as bytes, then a network-order character ID.
    // The expanded object is eight bytes; its padding is not on the wire.
    public static byte[] EncodeNotifyTacticsChiefCommander(byte power, byte camp, uint characterId)
    {
        var response = Allocate(0x0431, 6);
        response[6] = power;
        response[7] = camp;
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(8), characterId);
        return response;
    }

    public static byte[] EncodeResponseTime(uint tick)
    {
        // Input_ResponseTime::input_from_stream (004AA250): one network-order u32.
        var response = Allocate(0x0301, sizeof(uint));
        BinaryPrimitives.WriteUInt32BigEndian(response.AsSpan(6), tick);
        return response;
    }

    public static byte[] EncodeStatus(ushort type)
    {
        var response = Allocate(type, 1);
        response[6] = 1;
        return response;
    }

    public static byte[] EncodeInformationGrid(ushort index, byte tacticsState)
    {
        if (tacticsState > 2)
        {
            throw new ArgumentOutOfRangeException(
                nameof(tacticsState),
                tacticsState,
                "The original parser accepts tactics_state values from 0 through 2.");
        }

        // Input_ResponseInformationGrid::input_from_stream (0x00413950):
        // index:u16 followed by tactics_state:u8 constrained to 0..2.
        var response = Allocate(0x0317, sizeof(ushort) + sizeof(byte));
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(6), index);
        response[8] = tacticsState;
        return response;
    }

    public static byte[] EncodeStaticPowerDistribution()
    {
        // ORIGINAL_STATIC: Input_ResponseStaticInformationPowerDistribution::
        // input_from_stream at 0x00410370 consumes this exact compact order.
        // Its expanded object is 0x55c bytes because the client inserts two
        // alignment bytes after warp[2]; those bytes are not present on wire.
        var body = new WireWriter();

        // NEW DESIGN: the original authority values have not been recovered.
        // Use bounded nonzero defaults so all parser-proven tactical curves and
        // HUD denominators are defined until original data is found.
        for (var index = 0; index < 11; index++)
        {
            body.WriteSingle(1); // move[11]
        }

        body.WriteByte(1); // warp[0]
        body.WriteByte(1); // warp[1]

        for (var index = 0; index < 4; index++)
        {
            body.WriteSingle(1); // sensor[4]
        }

        for (var shield = 0; shield < 11; shield++)
        {
            for (var fillup = 0; fillup < 9; fillup++)
            {
                body.WriteUInt32(OriginalAuthoredPlayableCatalog.TacticalShieldRecoveryPeriod); // shield[11][9].fillup.time
            }
        }

        for (var beam = 0; beam < 14; beam++)
        {
            for (var fillup = 0; fillup < 20; fillup++)
            {
                body.WriteUInt16(100); // beam[14][20].fillup.value
            }
        }

        for (var gun = 0; gun < 11; gun++)
        {
            for (var fillup = 0; fillup < 16; fillup++)
            {
                body.WriteUInt16(100); // gun[11][16].fillup.value
            }
        }

        return Wrap(0x0309, body);
    }

    public static byte[] EncodeStaticUnitShips() =>
        EncodeStaticUnitShips(PlayableUnitShipTemplates());

    public static byte[] EncodeStaticUnitShipsWithComplements(IReadOnlyDictionary<ushort, ushort> numbers) =>
        EncodeStaticUnitShips(PlayableUnitShipTemplates().Select(t => numbers.TryGetValue(t.Kind, out var number)
            ? t with { Capabilities = t.Capabilities.GetValueOrDefault() with { Number = number } } : t).ToArray());

    private static IReadOnlyList<OriginalStaticUnitShipTemplate> PlayableUnitShipTemplates() =>
        [
            new OriginalStaticUnitShipTemplate(
                Kind: 0,
                Type: 0,
                Category: 0,
                Achievement: 0,
                // VISUAL_CANDIDATE E075: original GE/EM012; validate against
                // the ordinary-hull thumbnail, not constmsg row=model index.
                ModelFile: 12,
                Name: string.Empty,
                Capabilities: OriginalAuthoredPlayableCatalog.TacticalShipCapabilities),
            new OriginalStaticUnitShipTemplate(
                Kind: 89,
                Type: 0,
                Category: 0,
                Achievement: 0,
                // Separate FP/ FM003 candidate. model_file /1000 selects
                // faction table; 004C4290 normalizes by Kind, not packet order.
                ModelFile: 1003,
                Name: string.Empty,
                Capabilities: OriginalAuthoredPlayableCatalog.TacticalShipCapabilities),
            // E102 VISUAL_CANDIDATE: original MDX geometry compared with iu003
            // and iu093. Class IDs are not model IDs; native identity/rendering
            // remains unverified. Preserve the approved temporary capabilities.
            // Without these normalized slots,004F3D80 reads zero -> EM001.
            new OriginalStaticUnitShipTemplate(
                Kind: 3, Type: 0, Category: 0, Achievement: 0,
                ModelFile: 18, Name: string.Empty,
                Capabilities: OriginalAuthoredPlayableCatalog.TacticalShipCapabilities),
            new OriginalStaticUnitShipTemplate(
                Kind: 93, Type: 0, Category: 0, Achievement: 0,
                ModelFile: 1014, Name: string.Empty,
                Capabilities: OriginalAuthoredPlayableCatalog.TacticalShipCapabilities),
            ..OriginalSubordinateShipCatalog.Templates,
        ];

    public static byte[] EncodeStaticUnitShips(
        IReadOnlyList<OriginalStaticUnitShipTemplate> templates)
    {
        ArgumentNullException.ThrowIfNull(templates);
        if (templates.Count > MaximumStaticUnitShipTemplates)
        {
            throw new ArgumentOutOfRangeException(
                nameof(templates),
                templates.Count,
                $"At most {MaximumStaticUnitShipTemplates} static unit-ship templates are supported.");
        }

        var body = new WireWriter();
        body.WriteByte(checked((byte)templates.Count));
        foreach (var template in templates)
        {
            if (template.Name.Length > MaximumStaticUnitShipNameCharacters)
            {
                throw new ArgumentOutOfRangeException(
                    nameof(templates),
                    template.Name.Length,
                    $"Static unit-ship names are limited to {MaximumStaticUnitShipNameCharacters} characters.");
            }

            body.WriteUInt16(template.Kind);
            body.WriteByte(template.Type);
            body.WriteByte(template.Category);
            body.WriteUInt16(template.Achievement);
            body.WriteUInt16(template.ModelFile);
            // With count=0 the parser skips its name buffer entirely. The
            // flagship sheet still reads that buffer as a NUL-terminated
            // string, exposing stale heap data (live: repeated 0x00FF).
            body.WriteByte(checked((byte)(template.Name.Length + 1)));
            foreach (var character in template.Name)
            {
                body.WriteUInt16(character);
            }
            body.WriteUInt16(0);

            // NEW DESIGN: the first playable probe uses bounded authored combat
            // values in parser-proven fields. Callers that omit Capabilities
            // retain an all-zero template for exact codec fixtures.
            var capabilities = template.Capabilities.GetValueOrDefault();
            // 00411A60 prints exactly six direction bits for each *_angle.
            // These are sector masks, not degrees; reject silently ignored bits.
            ArgumentOutOfRangeException.ThrowIfGreaterThan(capabilities.BeamAngleMask, (byte)0x3f);
            ArgumentOutOfRangeException.ThrowIfGreaterThan(capabilities.GunAngleMask, (byte)0x3f);
            ArgumentOutOfRangeException.ThrowIfGreaterThan(capabilities.MissileAngleMask, (byte)0x3f);
            // ORIGINAL_STATIC + LIVE ORIGINAL CLIENT (2026-09-05):
            // FUN_004C4C50 expands this `number` into tactical template
            // +0x218. FUN_004C32A0 derives entity +0x8D8 (remaining ships)
            // from it; FUN_004B2740 arms destruction at zero and
            // FUN_004C94E0 removes the entity after 30 update frames.
            body.WriteUInt16(capabilities.Number);
            var logistics = template.Logistics.GetValueOrDefault();
            body.WriteUInt32(logistics.Price);
            body.WriteUInt16(logistics.Resources);
            body.WriteUInt16(logistics.Cost);
            body.WriteUInt16(logistics.Term);
            // ORIGINAL_STATIC: FUN_00411A60 names expanded UnitShip+0x32
            // `existence`; FUN_004C4C50 projects it to tactical +0x24e.
            body.WriteUInt16(capabilities.Existence);
            body.WriteUInt16(logistics.Crew);
            // ORIGINAL_STATIC: FUN_00411A60 names expanded UnitShip+0x36
            // `power`. FUN_004C4C50 projects it to tactical template +0x252,
            // which FUN_0050D230 uses as the total-system-power divisor.
            body.WriteUInt16(capabilities.TotalPower);
            // ORIGINAL_STATIC + LIVE ORIGINAL CLIENT (2026-09-05):
            // StaticInformationUnitShip.communication_range is projected by
            // FUN_004C4C50 into tactical template +0x254, then copied by
            // FUN_004C32A0 to entity +0x8C8. FUN_0050D230 passes it to
            // FUN_004EC600 as the pick manager's far/selectable threshold.
            // Zero makes every non-origin ship alternate-only (0x20000).
            body.WriteSingle(capabilities.CommunicationRange);
            body.WriteSingle(capabilities.SearchingRange);
            body.WriteUInt16s(11);
            body.WriteUInt16(0); // searching evasion
            body.WriteUInt16(capabilities.Navigation);
            body.WriteSingle(capabilities.Speed);
            body.WriteSingle(capabilities.Turn);
            body.WriteUInt16(capabilities.ArmorFront);
            body.WriteUInt16(capabilities.ArmorBack);
            body.WriteUInt16(capabilities.ArmorSide);
            body.WriteUInt16(capabilities.Shield);
            body.WriteUInt16(capabilities.ShieldCapacity);
            body.WriteByte(capabilities.BeamArms);
            body.WriteUInt16(capabilities.BeamPower);
            body.WriteByte(capabilities.BeamAngleMask);
            body.WriteByte(capabilities.GunArms);
            body.WriteUInt16(capabilities.GunPower);
            body.WriteByte(capabilities.GunAngleMask);
            body.WriteByte(capabilities.MissileArms);
            body.WriteUInt16(capabilities.MissilePower);
            body.WriteByte(capabilities.MissileAngleMask);
            body.WriteUInt16(capabilities.MissileConsumption);
            body.WriteByte(0);   // antiaircraft arms
            body.WriteUInt16s(6);
        }

        return Wrap(0x030b, body);
    }

    public static byte[] EncodeMailAddresses(IReadOnlyList<OriginalMailAddressRecord> addresses)
    {
        ArgumentNullException.ThrowIfNull(addresses);
        if (addresses.Count > MaximumMailAddresses)
        {
            throw new ArgumentOutOfRangeException(
                nameof(addresses),
                addresses.Count,
                $"At most {MaximumMailAddresses} original mail addresses are supported.");
        }

        var body = new WireWriter();
        body.WriteByte(checked((byte)addresses.Count));
        foreach (var address in addresses)
        {
            body.WriteUInt32(address.CharacterId);
            var name = address.DisplayName.AsSpan(
                0,
                Math.Min(address.DisplayName.Length, MaximumMailAddressNameCharacters));
            body.WriteByte(checked((byte)name.Length));
            foreach (var character in name)
            {
                body.WriteUInt16(character);
            }

            // Input_ResponseInformationMailAddress::input_from_stream
            // materializes a fixed 0x124-byte client record from this compact
            // stream. These zero counts omit the optional affiliation/status
            // collections while the three tail dwords keep the record valid.
            body.WriteUInt16(0);
            body.WriteUInt16(0);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteUInt32(0);
            body.WriteUInt32(0);
            body.WriteUInt32(0);
        }

        return Wrap(0x0f05, body);
    }

    public static byte[] EncodeMessengerInformation(
        IReadOnlyList<OriginalMessengerInformationRecord> characters)
    {
        ArgumentNullException.ThrowIfNull(characters);
        if (characters.Count > MaximumMessengerCharacters)
        {
            throw new ArgumentOutOfRangeException(
                nameof(characters),
                characters.Count,
                $"At most {MaximumMessengerCharacters} original messenger characters are supported.");
        }

        // Input_ResponseInformationMessengerStatus::input_from_stream
        // (0x00484280) reads a compact count followed by variable-length
        // records into fixed 0x128-byte client slots. The parser first reads
        // an outer character identifier into slot -4 (0x0048439b-0x004843ab),
        // then reads the embedded SimpleInformationCharacter. Empty
        // outfit/status collections are valid; the trailing dwords and
        // connection flag remain present on the wire.
        var body = new WireWriter();
        body.WriteByte(checked((byte)characters.Count));
        foreach (var character in characters)
        {
            body.WriteUInt32(character.CharacterId);
            body.WritePstr16(character.DisplayName, MaximumMessengerNameCharacters);
            body.WriteUInt16(character.Rank);
            body.WriteUInt16(0);
            body.WritePstr16(character.FlagshipName, MaximumMessengerFlagshipCharacters);
            // NEW DESIGN: expose the authoritative owned character as one
            // connected messenger endpoint. The original parser permits one
            // connection group and one nested character (0x0048442d-0x00484524).
            // An original-server capture for the semantic labels is still
            // unavailable, so keep the unknown scalar at zero.
            body.WriteByte(1); // connection group count
            body.WriteUInt32(character.CharacterId);
            body.WriteByte(0); // disconnected flag
            body.WriteByte(1); // server-healthy flag
            body.WriteByte(1); // nested character count
            body.WriteUInt32(character.CharacterId);
            body.WriteUInt16(character.Rank);
            body.WriteUInt16(0); // UNKNOWN scalar
            body.WritePstr16(character.DisplayName, MaximumMessengerNameCharacters);
            // NEW DESIGN: one visible status/card row for the owned character.
            // The parser accepts up to four 0x28-byte entries
            // (0x00484524-0x004845c7).
            body.WriteByte(1); // status/base count
            body.WriteUInt16(0); // UNKNOWN status kind
            body.WriteUInt32(character.CharacterId);
            body.WriteUInt16(character.Rank);
            body.WriteUInt16(0); // UNKNOWN scalar
            body.WritePstr16(character.DisplayName, MaximumMessengerNameCharacters);
            body.WriteUInt32(character.CharacterId);
            body.WriteUInt32(0);
            body.WriteUInt32(0);
            // ORIGINAL_STATIC: FUN_00544B20 partitions presence 0/1 into
            // the online list, appends the away suffix only for 1, and puts
            // presence 2 into the offline list. The authoritative live
            // endpoint is therefore state 0, not state 1.
            body.WriteByte(0); // online
        }

        // The original message object is fixed at 0x74cc bytes even though
        // input_from_stream consumes only the compact prefix. Preserve that
        // envelope so the native message pipeline continues its 0x0f06 poll.
        var response = Allocate(0x0f07, 0x74cc);
        body.CopyTo(response.AsSpan(6));
        return response;
    }

    public static byte[] EncodeStaticGridTypes()
    {
        var response = Allocate(0x0313, StaticGridBodySize);
        var body = response.AsSpan(6);
        // EXPERIMENT (condition 5, 2026-09-03): when LOGH7_CELESTIAL_TWO_DISTINCT=1, publish a second
        // planet palette record (marker 4, klass 3, variant 1) so the two starting cells can render as two
        // DISTINCT planet models instead of one replicated entry. Default build behavior is unchanged.
        var twoDistinct = Environment.GetEnvironmentVariable("LOGH7_CELESTIAL_TWO_DISTINCT") == "1";
        body[0] = (byte)(OriginalAuthoredPlayableCatalog.PlanetMarker + 1 + (twoDistinct ? 1 : 0));
        for (var value = 0; value < 3; value++)
        {
            var offset = 1 + value * 3;
            body[offset] = checked((byte)value);
        }

        // ORIGINAL_STATIC: FUN_004131E0 materializes each 0x0313 palette
        // record as [contentId, klass, variant]. FUN_004C8B70 indexes it with
        // the 0x0315 cell marker; FUN_004D3BD0 accepts klass 3 and variants
        // 0..6 for the selected-system planet path.
        // AUTHORED_PLACEHOLDER / NEW DESIGN: marker 3 uses an independent
        // content ID and the first valid original renderer variant. No
        // content-ID-to-Base-ID join is claimed. This is one scene only.
        var planetOffset = 1 + OriginalAuthoredPlayableCatalog.PlanetMarker * 3;
        body[planetOffset] = OriginalAuthoredPlayableCatalog.PlanetContentId;
        // EXPERIMENT (condition 5 klass/variant sweep, 2026-09-03): let a run override the marker-3 palette
        // klass/variant so a live capture reveals which klass renders which celestial family (planet vs
        // fortress/black-hole via space/planets/strategy model paths). Default is the current planet values.
        body[planetOffset + 1] = TryByteEnv("LOGH7_CELESTIAL_KLASS", OriginalAuthoredPlayableCatalog.PlanetKlass);
        body[planetOffset + 2] = TryByteEnv("LOGH7_CELESTIAL_VARIANT", OriginalAuthoredPlayableCatalog.PlanetVariant);

        if (twoDistinct)
        {
            var secondMarker = OriginalAuthoredPlayableCatalog.PlanetMarker + 1; // marker 4
            var secondOffset = 1 + secondMarker * 3;
            body[secondOffset] = 2;     // independent content id (distinct from marker 3)
            body[secondOffset + 1] = 3; // klass 3 = planet render path (FUN_004D3BD0)
            body[secondOffset + 2] = 1; // variant 1 = a different planet model than marker 3's variant 0
        }

        return response;
    }

    // EXPERIMENT (condition 11/16, 2026-09-03): LOGH7_EXTRA_CARD_COMMANDS="62,61" appends extra original command
    // ids (constmsg group 18 rows: 62 完全補給, 61 完全修理, ...) to the authority card's command list so the
    // unmodified client reveals which request type each command sends. Default (unset) = no change.
    private static byte UnitCategoryMask()
    {
        var raw = Environment.GetEnvironmentVariable("LOGH7_UNIT_CATEGORY_MASK");
        return !string.IsNullOrWhiteSpace(raw) && byte.TryParse(raw, System.Globalization.NumberStyles.HexNumber, null, out var mask) ? mask : (byte)0x3F;
    }

    private static ushort[] ExtraCardCommandIds()
    {
        // NEW_DESIGN default (2026-09-03): 任命 (5, CommandCardAppointment) is served on the authored card by default so
        // the verified appointment vertical reproduces without LOGH7_EXTRA_CARD_COMMANDS; env ids are appended.
        // NEW_DESIGN: expose own-ship departure60 now that its authoritative
        // handler exists. Both0305 and0307 consume this list; defining only
        // the handler leaves the original command panel unable to select it.
        // This does not claim original card39 assignment or mode5 support.
        var ids = new List<ushort> { 5, 60 };
        var raw = Environment.GetEnvironmentVariable("LOGH7_EXTRA_CARD_COMMANDS");
        if (string.IsNullOrWhiteSpace(raw))
        {
            return ids.ToArray();
        }

        foreach (var part in raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (ushort.TryParse(part, out var id) && id is > 0 and < 97 && id != OriginalAuthoredPlayableCatalog.StrategicWarpCommandId && !ids.Contains(id))
            {
                ids.Add(id);
            }
        }

        return ids.Count > 22 ? ids.GetRange(0, 22).ToArray() : ids.ToArray();
    }

    private static byte TryByteEnv(string name, byte fallback)
    {
        var value = Environment.GetEnvironmentVariable(name);
        return value is not null && byte.TryParse(value, out var parsed) ? parsed : fallback;
    }

    public static byte[] EncodeStaticGrid()
    {
        var response = Allocate(0x0315, StaticGridBodySize);
        var body = response.AsSpan(6);
        body[0] = 100;
        body[1] = 50;
        var authoredSceneCellCount =
            OriginalAuthoredPlayableCatalog.DestinationGridCell -
            OriginalAuthoredPlayableCatalog.CurrentGridCell + 1;
        var remaining = 5000 - OriginalAuthoredPlayableCatalog.DestinationGridCell - 1;
        // EXPERIMENT (condition 5, 2026-09-03): split the two authored cells into distinct markers when
        // LOGH7_CELESTIAL_TWO_DISTINCT=1, adding one RLE pair. Default build behavior is unchanged.
        var twoDistinct = Environment.GetEnvironmentVariable("LOGH7_CELESTIAL_TWO_DISTINCT") == "1"
            && authoredSceneCellCount == 2;
        var pairCount = 2 + (remaining + byte.MaxValue - 1) / byte.MaxValue + (twoDistinct ? 1 : 0);
        BinaryPrimitives.WriteUInt16BigEndian(body[2..], checked((ushort)(pairCount * 2)));
        var cursor = 4;

        // AUTHORED_PLACEHOLDER / NEW DESIGN: exactly two adjacent grid cells
        // select the same authored planet palette entry. They are distinct
        // source/destination cells only; no route or movement readiness is
        // asserted. The rest of the 100x50 board remains marker 0.
        body[cursor] = checked((byte)OriginalAuthoredPlayableCatalog.CurrentGridCell);
        body[cursor + 1] = 0;
        cursor += 2;
        if (twoDistinct)
        {
            body[cursor] = 1;
            body[cursor + 1] = OriginalAuthoredPlayableCatalog.PlanetMarker; // cell 101 -> marker 3
            cursor += 2;
            body[cursor] = 1;
            body[cursor + 1] = (byte)(OriginalAuthoredPlayableCatalog.PlanetMarker + 1); // cell 102 -> marker 4
            cursor += 2;
        }
        else
        {
            body[cursor] = checked((byte)authoredSceneCellCount);
            body[cursor + 1] = OriginalAuthoredPlayableCatalog.PlanetMarker;
            cursor += 2;
        }
        while (remaining > 0)
        {
            var run = Math.Min(remaining, byte.MaxValue);
            body[cursor] = checked((byte)run);
            body[cursor + 1] = 0;
            cursor += 2;
            remaining -= run;
        }

        return response;
    }

    public static byte[] EncodeStaticCards()
    {
        // Input_ResponseStaticInformationCard::input_from_stream
        // (0x0040ee80) reads a u16 count followed by packed live fields for
        // each 0x46-byte in-memory record. The client derives the visible card
        // wording from its original resources; no literal card name is on wire.
        // FUN_004f68f0 indexes the 0x46-byte cache directly by the character's
        // card ID, then reads the record's leading u16 through original
        // constmsg group 3 and record byte 5 through original group 4. Populate
        // every preceding slot so AUTHORED_PLACEHOLDER card 39 resolves to the
        // original 艦隊司令官（艦隊） strings without modifying client resources.
        // PROBE mode 10 (2026-09-03): client FUN_0057CC85 (command panel state 12, TARGET_SELECT_S_CARD) lists a
        // 0x1208 card record only if staticCard[cardId].u16@+6 (the first u16 after byte 5) == [0xC9EAC0].
        // LOGH7_STATIC_CARD_APPOINTER="40:39,41:0" sets that u16 for card ids (also extends the served card count).
        var appointer = StaticCardAppointerOverrides();
        var maxCard = appointer.Count == 0 ? OriginalAuthoredPlayableCatalog.AuthorityCardId : Math.Max(OriginalAuthoredPlayableCatalog.AuthorityCardId, appointer.Keys.Max());
        var body = new WireWriter();
        body.WriteUInt16((ushort)(maxCard + 1));
        for (ushort cardId = 0; cardId <= maxCard; cardId++)
        {
            body.WriteUInt16(cardId);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteByte((byte)(cardId == OriginalAuthoredPlayableCatalog.AuthorityCardId ? 11 : 0));
            body.WriteUInt16(appointer.TryGetValue(cardId, out var ap) ? ap : (ushort)0);
            body.WriteUInt16(0);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteByte(0);
            body.WriteUInt16(0);
            body.WriteByte(0);
            body.WriteUInt16(0);
            var extraCommands = cardId == OriginalAuthoredPlayableCatalog.AuthorityCardId ? ExtraCardCommandIds() : [];
            var commandCount = cardId == OriginalAuthoredPlayableCatalog.AuthorityCardId ? 2 + extraCommands.Length : 0;
            body.WriteByte((byte)commandCount);
            if (commandCount != 0)
            {
                // AUTHORED_PLACEHOLDER / NEW DESIGN: retain command 0 for the
                // existing promotion path and expose strategic WARP exactly
                // once. FUN_0040EE80 permits up to 24 u16 action IDs.
                body.WriteUInt16(0);
                body.WriteUInt16(OriginalAuthoredPlayableCatalog.StrategicWarpCommandId);
                foreach (var extra in extraCommands)
                {
                    body.WriteUInt16(extra);
                }
            }
        }
        return Wrap(0x0305, body);
    }

    // NEW_DESIGN (2026-09-03, documented in docs/handoffs): the client keeps a 0x1208 post as a 任命 candidate only if
    // staticCard[post].u16@+6 == the player's card id (FUN_004C9140), so the authority declares the appointing
    // authority of the subordinate posts of the authored fleet commander card 39 (constmsg group 3 names:
    // 40 艦隊副司令官, 41 艦隊参謀長, 42 艦隊参謀, 43 艦隊司令官副官). LOGH7_STATIC_CARD_APPOINTER still overrides.
    public static readonly IReadOnlyDictionary<ushort, ushort> DefaultCardAppointer = new Dictionary<ushort, ushort>
    {
        [40] = OriginalAuthoredPlayableCatalog.AuthorityCardId,
        [41] = OriginalAuthoredPlayableCatalog.AuthorityCardId,
        [42] = OriginalAuthoredPlayableCatalog.AuthorityCardId,
        [43] = OriginalAuthoredPlayableCatalog.AuthorityCardId,
    };

    public static IReadOnlyDictionary<ushort, ushort> StaticCardAppointerOverrides()
    {
        var raw = Environment.GetEnvironmentVariable("LOGH7_STATIC_CARD_APPOINTER");
        var map = new Dictionary<ushort, ushort>(DefaultCardAppointer);
        if (string.IsNullOrWhiteSpace(raw))
        {
            return map;
        }

        foreach (var part in raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var kv = part.Split(':');
            map[ushort.Parse(kv[0])] = ushort.Parse(kv[1]);
        }

        return map;
    }

    public static byte[] EncodeStaticCardCommands()
    {
        // Input_ResponseStaticInformationCardCommand::input_from_stream
        // (0x0040f9f0) reads a u16 card-record count, then each card ID, a
        // command count, and packed 8-byte command entries. Keep command 0 as
        // an explicit AUTHORED_PLACEHOLDER until its original definition and
        // authority mutation are recovered independently.
        var appointer = StaticCardAppointerOverrides();
        var maxCard = appointer.Count == 0 ? OriginalAuthoredPlayableCatalog.AuthorityCardId : Math.Max(OriginalAuthoredPlayableCatalog.AuthorityCardId, appointer.Keys.Max());
        var body = new WireWriter();
        body.WriteUInt16((ushort)(maxCard + 1));
        for (ushort cardId = 0; cardId <= maxCard; cardId++)
        {
            body.WriteUInt16(cardId);
            var extraCommands = cardId == OriginalAuthoredPlayableCatalog.AuthorityCardId ? ExtraCardCommandIds() : [];
            var commandCount = cardId == OriginalAuthoredPlayableCatalog.AuthorityCardId ? 2 + extraCommands.Length : 0;
            body.WriteByte((byte)commandCount);
            if (commandCount == 0)
            {
                continue;
            }

            foreach (var commandId in new ushort[]
            {
                0,
                OriginalAuthoredPlayableCatalog.StrategicWarpCommandId
            }.Concat(extraCommands))
            {
                body.WriteUInt16(commandId);
                // ORIGINAL_STATIC: FUN_0040F9F0 reads each command as u16 ID,
                // three gate bytes, two metadata bytes, then one metadata byte.
                // AUTHORED_PLACEHOLDER / NEW DESIGN: reuse command 0's existing
                // all-valid gate and zero unknown metadata for 0x2B; do not
                // invent a role or original card-assignment semantic.
                body.WriteByte(0xff);
                body.WriteByte(0xff);
                body.WriteByte(0x1f);
                body.WriteByte(0);
                body.WriteByte(0);
                // 2026-09-03 (static trace, runs 054448Z/071237Z): the third metadata byte is a POST mask read by the command
                // panel's state-6 sub-menu (部隊解散 / 発令 step 2): bit n set => post row with value n is offered; the rows are
                // constmsg 0x125..0x129 = 艦隊司令官(5) 艦隊副司令官(4) 艦隊参謀長(3) 艦隊参謀(2) 艦隊司令官副官(0). Zero meant
                // 「選択可能な項目が存在しません」. NEW_DESIGN default: all bits (0x3F); LOGH7_UNIT_CATEGORY_MASK overrides (hex byte).
                body.WriteByte(UnitCategoryMask());
            }
        }
        return Wrap(0x0307, body);
    }

    public static byte[] EncodeStaticBases(IReadOnlyList<OriginalStaticBaseRecord> records)
    {
        ArgumentNullException.ThrowIfNull(records);
        OriginalStaticBaseRecord.ValidateAll(records);
        var body = new WireWriter();
        body.WriteUInt16(checked((ushort)records.Count));
        foreach (var record in records)
        {
            body.WriteUInt32(record.Id);
            body.WriteUInt16(record.Grid);
            body.WriteUInt16(record.ModelFile);
            body.WriteUInt16(record.Kind);
            body.WritePstr16(record.Name, 13);
            body.WriteByte(record.Class);
            body.WriteSingle(record.RevolutionRadius);
            body.WriteUInt32(record.RevolutionCycle);
            body.WriteByte(record.RevolutionDirection);
            body.WriteSingle(record.RevolutionInitialAngle);
            body.WriteSingle(record.Diameter);
        }
        return Wrap(0x031d, body);
    }

    public static byte[] EncodeStaticBases()
    {
        // Input_ResponseStaticInformationBase::input_from_stream
        // (0x004142e0) accepts at most 350 packed records. The former fixed
        // 0x520c zero body represented count=0 and therefore resolved every
        // base-name lookup as NO DATA.
        var body = new WireWriter();
        // Legacy opt-in probe only. Explicit catalog definitions use the typed overload above.
        // E013 corrects the old inference: <=13 bounds nameCount, NOT Class.
        // The fourteen-value sweep is an authored experiment, not a proven enum domain.
        var classSweep = Environment.GetEnvironmentVariable("LOGH7_CELESTIAL_CLASS_SWEEP") == "1";
        if (classSweep)
        {
            const int classCount = 14;
            body.WriteUInt16(classCount);
            for (var i = 0; i < classCount; i++)
            {
                body.WriteUInt32(OriginalAuthoredPlayableCatalog.BaseId + (uint)i);
                body.WriteUInt16((ushort)(OriginalAuthoredPlayableCatalog.CurrentGridCell + i));
                body.WriteUInt16(0);
                body.WriteUInt16(0);
                body.WritePstr16(OriginalAuthoredPlayableCatalog.BaseName, 13);
                body.WriteByte((byte)i); // class_ = i
                body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionRadius);
                body.WriteUInt32(OriginalAuthoredPlayableCatalog.BaseRevolutionCycle);
                body.WriteByte(OriginalAuthoredPlayableCatalog.BaseRevolutionDirection);
                body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionInitAngle);
                body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseDiameter);
            }
            return Wrap(0x031d, body);
        }
        body.WriteUInt16(1);
        body.WriteUInt32(OriginalAuthoredPlayableCatalog.BaseId);
        body.WriteUInt16(OriginalAuthoredPlayableCatalog.CurrentGridCell);
        body.WriteUInt16(0);
        body.WriteUInt16(0);
        body.WritePstr16(OriginalAuthoredPlayableCatalog.BaseName, 13);
        // ORIGINAL_STATIC: FUN_004142E0 reads class as the byte immediately
        // after the compact name. The nonzero class is NEW DESIGN for Base 1.
        // Retained legacy opt-in probe; Base Class and strategy GridType klass are distinct.
        body.WriteByte(TryByteEnv("LOGH7_BASE_KLASS", OriginalAuthoredPlayableCatalog.BaseKlass));
        // ORIGINAL_STATIC: FUN_004142E0 reads f32/u32/u8/f32/f32 here.
        // Exact Base logger00414A30 names revolution radius, cycle,
        // direction, initial angle (degrees), and DIAMETER. Float bits use network
        // byte order. Concrete values are bounded NEW DESIGN placeholders.
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionRadius);
        body.WriteUInt32(OriginalAuthoredPlayableCatalog.BaseRevolutionCycle);
        body.WriteByte(OriginalAuthoredPlayableCatalog.BaseRevolutionDirection);
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionInitAngle);
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseDiameter);
        return Wrap(0x031d, body);
    }

    private static byte[] EncodeZeroFilled(ushort type, int bodySize) =>
        Allocate(type, bodySize);

    private static byte[] Wrap(ushort type, WireWriter body)
    {
        var response = Allocate(type, body.Count);
        body.CopyTo(response.AsSpan(6));
        return response;
    }

    private static byte[] EncodeCompactCommandEcho(ReadOnlySpan<byte> command)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + command.Length];
        command.CopyTo(response.AsSpan(OriginalLoginCodec.MessageCodeSize));
        return response;
    }

    private static byte[] Allocate(ushort type, int bodySize)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), type);
        return response;
    }

    private sealed class WireWriter
    {
        private readonly List<byte> _bytes = [];

        public int Count => _bytes.Count;

        public void WriteByte(byte value) => _bytes.Add(value);

        public void WriteBytes(int count)
        {
            for (var index = 0; index < count; index++)
            {
                WriteByte(0);
            }
        }

        public void WriteUInt16(ushort value)
        {
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WriteUInt16s(int count)
        {
            for (var index = 0; index < count; index++)
            {
                WriteUInt16(0);
            }
        }

        public void WriteUInt32(uint value)
        {
            _bytes.Add((byte)(value >> 24));
            _bytes.Add((byte)(value >> 16));
            _bytes.Add((byte)(value >> 8));
            _bytes.Add((byte)value);
        }

        public void WriteSingle(float value) =>
            WriteUInt32(BitConverter.SingleToUInt32Bits(value));

        public void WriteUInt32s(int count)
        {
            for (var index = 0; index < count; index++)
            {
                WriteUInt32(0);
            }
        }

        public void WritePstr16(string value, int maximum)
        {
            var characters = value.AsSpan(0, Math.Min(value.Length, maximum));
            WriteByte(checked((byte)characters.Length));
            foreach (var character in characters)
            {
                WriteUInt16(character);
            }
        }

        public void CopyTo(Span<byte> destination) =>
            _bytes.ToArray().CopyTo(destination);
    }
}
