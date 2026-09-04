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

public static class OriginalWorldBootstrapCodec
{
    private const int StaticGridBodySize = 0x138c;
    private const int MaximumMailAddresses = 100;
    private const int MaximumMailAddressNameCharacters = 13;
    private const int MaximumMessengerCharacters = 101;
    private const int MaximumMessengerNameCharacters = 13;
    private const int MaximumMessengerFlagshipCharacters = 16;

    public static bool TryEncodeResponse(ReadOnlySpan<byte> request, out byte[] response)
    {
        response = [];
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
            0x0300 => EncodeResponseTime(),
            0x0304 => EncodeStaticCards(),
            0x0306 => EncodeStaticCardCommands(),
            0x0308 => EncodeZeroFilled(0x0309, 0x055c),
            0x030a => EncodeZeroFilled(0x030b, 0x6d64),
            0x030c => EncodeZeroFilled(0x030d, 0x0184),
            0x030e => EncodeZeroFilled(0x030f, 0x0034),
            0x0310 => EncodeZeroFilled(0x0311, 0x01b0),
            0x0312 => EncodeStaticGridTypes(),
            0x0314 => EncodeStaticGrid(),
            0x031c => EncodeStaticBases(),
            0x0f00 => EncodeStatus(0x0f01),
            0x0f02 => EncodeStatus(0x0f03),
            0x0f04 => EncodeMailAddresses([]),
            0x1000 => EncodeZeroFilled(0x1001, 0x1c0),
            _ => []
        };
        return response.Length != 0;
    }

    private static byte[] EncodeResponseTime()
    {
        var response = Allocate(0x0301, sizeof(uint));
        BinaryPrimitives.WriteUInt32LittleEndian(response.AsSpan(6), 0x40000000);
        return response;
    }

    public static byte[] EncodeStatus(ushort type)
    {
        var response = Allocate(type, 1);
        response[6] = 1;
        return response;
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
        var ids = new List<ushort> { 5 };
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

    public static byte[] EncodeStaticBases()
    {
        // Input_ResponseStaticInformationBase::input_from_stream
        // (0x004142e0) accepts at most 350 packed records. The former fixed
        // 0x520c zero body represented count=0 and therefore resolved every
        // base-name lookup as NO DATA.
        var body = new WireWriter();
        // EXPERIMENT (condition 5, 2026-09-03): with LOGH7_CELESTIAL_CLASS_SWEEP=1 emit one Base per class_
        // value 0..13 at distinct grid cells, so a single in-system (星系内宇宙) capture reveals which class_
        // renders which celestial family (planet / fortress / sun / black hole). Default = the single Base 1.
        var classSweep = Environment.GetEnvironmentVariable("LOGH7_CELESTIAL_CLASS_SWEEP") == "1";
        if (classSweep)
        {
            const int classCount = 14; // class_ range 0..13 (client FUN_004142E0 bounds class_ <= 0xD)
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
                body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRadius);
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
        // PROBE (2026-09-04): BASE-target commands (部隊結成/部隊解散/発令/演説) reject the authored base with
        // 「選択可能な拠点が存在しません」 even though the character and the base share grid cell 101, so position is
        // not the gate. The client's wording is 「惑星／要塞軌道上」, and klass 3 is the known planet family
        // (render path FUN_004D3BD0 accepts klass 3, variants 0..6) while the authored base ships klass 1.
        // LOGH7_BASE_KLASS sweeps the class byte to find which value the client accepts as a usable 拠点.
        body.WriteByte(TryByteEnv("LOGH7_BASE_KLASS", OriginalAuthoredPlayableCatalog.BaseKlass));
        // ORIGINAL_STATIC: FUN_004142E0 reads f32/u32/u8/f32/f32 here.
        // FUN_00425C20 labels the matching slots revolution radius, cycle,
        // direction, initial angle, and radius. Float IEEE754 bits use network
        // byte order. Concrete values are bounded NEW DESIGN placeholders.
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionRadius);
        body.WriteUInt32(OriginalAuthoredPlayableCatalog.BaseRevolutionCycle);
        body.WriteByte(OriginalAuthoredPlayableCatalog.BaseRevolutionDirection);
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRevolutionInitAngle);
        body.WriteSingle(OriginalAuthoredPlayableCatalog.BaseRadius);
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
