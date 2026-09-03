using System.Buffers.Binary;

namespace Logh7.Server.OriginalGateway;

public static class OriginalSimpleRankCodec
{
    public const ushort PromotionRankSelector = 0x11;
    private const ushort BeginType = 0x1200;
    private const ushort RankType = 0x1209;
    private const ushort EndType = 0x1201;
    private const int EndBodySize = 1;
    public static IReadOnlyList<byte[]> EncodePromotionTransaction(
        ushort currentRank,
        ReadOnlySpan<byte> beginRequestBody)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        if (currentRank is 0 or > OriginalAuthoredPlayableCatalog.StartingRank)
        {
            throw new ArgumentOutOfRangeException(nameof(currentRank));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));

        // ORIGINAL_STATIC + LIVE: NotifySimpleInformationRank (0x1209) is a
        // packed byte count followed by raw little-endian ushort rank pairs.
        // Eligibility is authoritative: expose only the selected character's
        // current ladder step; the client renders current -> current - 1.
        var rank = Allocate(RankType, sizeof(byte) + sizeof(ushort));
        rank[6] = 1;
        BinaryPrimitives.WriteUInt16LittleEndian(rank.AsSpan(7), currentRank);

        return [begin, rank, Allocate(EndType, EndBodySize)];
    }

    // EXPERIMENT (condition 11 任命, 2026-09-03): selector 0x12 list probe. Hypothesis: the client pairs
    // simple-information selector s with notify type 0x11F8 + s (0x11 -> 0x1209 rank ladder, observed), so
    // 0x12 -> 0x120A whose store routine FUN_004C22D0 takes [count u8][3 pad][count x 296-byte records]
    // into world+0x585358 (cap 100). Enabled only with LOGH7_NINMEI_PROBE=1. The record is a discovery
    // pattern (repeated CP932 marker strings) so the live list reveals which record offset is rendered.
    public const ushort NinmeiSelector = 0x12;
    private const ushort NinmeiListType = 0x120a;
    private const int NinmeiRecordSize = 0x128;
    private const int NinmeiRecordCapacity = 100;
    public static bool NinmeiProbeEnabled => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") is "1" or "2" or "3" or "4" or "5" or "6" or "7" or "8" or "9" or "10";
    // mode 8: LOGH7_NINMEI_CARDS="1" or "1,2": count = number of ids, record i = u16 cardId + zeros (count-sensitivity test).
    // mode 9 (2026-09-03): mode 8 ids + non-id bytes filled with 0x01 (count=1 passes the receive gate; test the record flag fields).
    public static bool NinmeiProbeCardIds => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") is "8" or "9" or "10";
    public static bool NinmeiProbeOnePerFrame => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") == "10";
    public static ushort[] NinmeiCardIds()
    {
        var raw = Environment.GetEnvironmentVariable("LOGH7_NINMEI_CARDS") ?? "1";
        return raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(v => ushort.TryParse(v, out var id) ? id : (ushort)0).Where(id => id != 0).Take(300).ToArray();
    }
    // mode 7: reproduce the exact mode-2 0x1208 frame (count byte 1, token records) ALONE to separate count/content effects.
    public static bool NinmeiProbeTokenCardAlone => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") == "7";
    // mode 6: the prefixed 0x1208 with zero-filled records was still rejected while token-filled (all-nonzero) records were
    // accepted in the burst => fill every non-id field with 1 to test a non-zero-field validation in handle_message.
    public static bool NinmeiProbeNonZero => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") is "6" or "9";
    // mode 5: 0x1208 alone was logged unsupported while in the all-types burst it was accepted after 0x1205-0x1207 => test
    // the prefix dependency: send 0x1205, 0x1206, 0x1207 (count=1, zero records) then the 0x1208 card records.
    public static bool NinmeiProbePrefixed => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") == "5";
    // mode 4 (2026-09-03): the client debug log proved 0x120A-0x120D unsupported and 0x1208 NotifySimpleInformationCard
    // accepted; the 任命 controller binds world+0x584510/0x584514 (u16 count, pad2, 12-byte records, cap 300).
    // Send three card records (cardId 1..3, remaining fields 0) so the dialog reveals the record semantics.
    public static bool NinmeiProbeCardList => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") is "4" or "5" or "6" or "7" or "8" or "9" or "10";
    public static bool NinmeiProbeAllTypes => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") is "2" or "3";
    // mode 3: same burst in REVERSE type order (0x120F first) to separate stream-position effects from type effects
    public static bool NinmeiProbeReverse => Environment.GetEnvironmentVariable("LOGH7_NINMEI_PROBE") == "3";
    // Client fixed body sizes (size table 0x4BA23C, FUN_004B8B00) for the 0x1202..0x120F list notifies.
    private static readonly (ushort Type, int Size)[] ListNotifySizes =
    [
        (0x1202, 57604), (0x1203, 8804), (0x1204, 7204), (0x1205, 804), (0x1206, 1604), (0x1207, 4804),
        (0x1208, 3604), (0x1209, 43), (0x120a, 29604), (0x120b, 15604), (0x120c, 8644), (0x120d, 12004),
        (0x120e, 29244), (0x120f, 29604)
    ];
    // 任命 step 2 (2026-09-03, run 041442Z): after the post is chosen the client sends 0x1200 selector 0x0004 and
    // command-panel state 3 lists 0x1202 NotifySimpleInformationCharacter records (parser 0x55BA80, big-endian):
    //   u32 characterId, u8 cardCount(<=13) + u16[cardCount], u16, u16, u8 n2(<=16) + u16[n2], u8 flagA(<=1) [+ nested],
    //   u8 n3(<=4) [+ nested], u32, u32   -> minimal record = 20 bytes (all counts 0). Cell stride 0x120, cap 200.
    public const ushort NinmeiCharacterSelector = 0x0004;
    // PROBE (2026-09-03, LOGH7_LIST_KIND_PROBE="15:1202,0B:1202,1E:1202"): serve a known list kind for a 0x1200
    // selector whose expected notify kind is not known yet (live sweep 054448Z: 抜擢 person picker 0x0015, 発令 0x000B,
    // 部隊解散 0x001E all rejected the 0x120F roster fallback with 選択可能な項目が存在しません). Values: 1202 character
    // list, 1208 post list, 1209 rank ladder. Unlisted selectors keep the roster fallback. Read-only.
    public static ushort ListKindProbe(ushort selector)
    {
        var raw = Environment.GetEnvironmentVariable("LOGH7_LIST_KIND_PROBE") ?? string.Empty;
        foreach (var pair in raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var parts = pair.Split(':');
            if (parts.Length == 2 &&
                ushort.TryParse(parts[0], System.Globalization.NumberStyles.HexNumber, null, out var probeSelector) &&
                probeSelector == selector &&
                ushort.TryParse(parts[1], System.Globalization.NumberStyles.HexNumber, null, out var probeKind))
            {
                return probeKind;
            }
        }

        return 0;
    }
    public static bool NinmeiCharacterProbeEnabled => !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("LOGH7_NINMEI_CHARS"));
    public static bool NinmeiCharacterProbeSelectsIds => NinmeiCharacterProbeEnabled;
    public static uint[] NinmeiCharacterIds()
    {
        var raw = Environment.GetEnvironmentVariable("LOGH7_NINMEI_CHARS") ?? string.Empty;
        return raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Select(uint.Parse).ToArray();
    }

    // Default 任命 post list (no probe env): Begin + one 0x1208 frame per subordinate post of the player's card + End.
    // Record = {u16 cardId, u32 holderCharacterId, u8 flag} packed after the u16 BE count (client parser 0x55F670).
    public static IReadOnlyList<byte[]> EncodeNinmeiPostTransaction(
        ReadOnlySpan<byte> beginRequestBody,
        IReadOnlyList<(ushort CardId, uint HolderCharacterId)> posts)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var (cardId, holder) in posts)
        {
            var f = Allocate(0x1208, 3604);
            var fb = f.AsSpan(6);
            fb[1] = 1;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(2, 2), cardId);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(4, 4), holder);
            fb[8] = 0;
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    // PROBE encoders (2026-09-03) for the picker kinds named by the parser table (client-simple-information-parser-table.json):
    //   0x1205 NotifySimpleInformationGrid  : u8 count(<=200) + u32 gridId[]        fixed body 804  (parser 0x55E940)
    //   0x1204 NotifySimpleInformationBase  : u8 count(<=200) + {u32 baseId, u16, u16, u8 n(<=13) + u16[n]}  fixed body 7204 (parser 0x55E200)
    //   0x1206 NotifySimpleInformationStrategy: u8 count(<=200) + {u32 id, u16, u8(0..2), u8(0..2)}  fixed body 1604 (parser 0x55ED10)
    // One record per frame, all big-endian; used by LOGH7_LIST_KIND_PROBE=<selector>:1204|1205|1206.
    public static IReadOnlyList<byte[]> EncodeGridListTransaction(ReadOnlySpan<byte> beginRequestBody, IReadOnlyList<uint> gridIds)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var gridId in gridIds)
        {
            var f = Allocate(0x1205, 804);
            var fb = f.AsSpan(6);
            fb[0] = 1;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(1, 4), gridId);
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    public static IReadOnlyList<byte[]> EncodeBaseListTransaction(ReadOnlySpan<byte> beginRequestBody, IReadOnlyList<(uint BaseId, ushort A, ushort B)> bases)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var (baseId, a, b) in bases)
        {
            var f = Allocate(0x1204, 7204);
            var fb = f.AsSpan(6);
            fb[0] = 1;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(1, 4), baseId);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(5, 2), a);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(7, 2), b);
            fb[9] = 0;
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    public static IReadOnlyList<byte[]> EncodeStrategyListTransaction(ReadOnlySpan<byte> beginRequestBody, IReadOnlyList<(uint Id, ushort Value, byte A, byte B)> strategies)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var (id, value, a, b) in strategies)
        {
            var f = Allocate(0x1206, 1604);
            var fb = f.AsSpan(6);
            fb[0] = 1;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(1, 4), id);
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(5, 2), value);
            fb[7] = a;
            fb[8] = b;
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    //   0x1207 NotifySimpleInformationUnit: u16 count(<=600) + {u32 unitId, u8 kind(0..2), u16}  fixed body 4804 (parser 0x55F1F0)
    public static IReadOnlyList<byte[]> EncodeUnitListTransaction(ReadOnlySpan<byte> beginRequestBody, IReadOnlyList<(uint UnitId, byte Kind, ushort Value)> units)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var (unitId, kind, value) in units)
        {
            var f = Allocate(0x1207, 4804);
            var fb = f.AsSpan(6);
            fb[1] = 1;                          // u16 BE count = 1
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(2, 4), unitId);
            fb[6] = kind;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(7, 2), value);
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    public static IReadOnlyList<byte[]> EncodeNinmeiCharacterTransaction(
        ReadOnlySpan<byte> beginRequestBody,
        IReadOnlyList<(uint CharacterId, string Name, string FullName, ushort Rank)> characters)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));
        var frames = new List<byte[]> { begin };
        foreach (var (id, name, fullName, rank) in characters)
        {
            // LIVE (run 042254Z) + parser 0x55BA80: count is a u8 (vt+0x24), then per record
            //   u32 id BE, u8 nameLen (pstr16, <= 13 incl. terminator), u16[] name BE, u16, u16,
            //   u8 n2 (<= 16) + u16[n2], u8 flagA (<= 1), u8 n3 (<= 4), u32, u32.
            // State 3 (0x57B9C4) lists every cell using +0 id, +6 name, +0x20 u16.
            var f = Allocate(0x1202, 57604);   // fixed body size (client size table): 4 + 200 x 288
            var fb = f.AsSpan(6);
            fb[0] = 1;                          // count u8 = 1
            var w = 1;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt32BigEndian(fb.Slice(w, 4), id); w += 4;
            var text = name.Length > 12 ? name[..12] : name;
            fb[w++] = checked((byte)(text.Length + 1));
            foreach (var ch in text)
            {
                System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(w, 2), ch); w += 2;
            }
            w += 2;                             // terminator counted in nameLen (pstr16)
            // PROBE (2026-09-03): the confirm dialog showed empty person/post placeholders; try the second string
            // (n2 <= 16, pstr16) = full name and the two u16 fields = rank (the list header offers 階級順 sorting).
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(w, 2), rank); w += 2;
            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(w, 2), rank); w += 2;
            var text2 = fullName.Length > 15 ? fullName[..15] : fullName;
            fb[w++] = checked((byte)(text2.Length + 1));   // n2 (pstr16 incl. terminator)
            foreach (var ch in text2)
            {
                System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(w, 2), ch); w += 2;
            }
            w += 2;
            fb[w++] = 0;                        // flagA
            fb[w++] = 0;                        // n3
            w += 4; w += 4;                     // u32, u32 = 0
            frames.Add(f);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    public static IReadOnlyList<byte[]> EncodeNinmeiProbeTransaction(ReadOnlySpan<byte> beginRequestBody)
    {
        if (beginRequestBody.Length != OriginalSimpleCharacterRosterCodec.BeginWireBodySize)
        {
            throw new ArgumentOutOfRangeException(nameof(beginRequestBody));
        }

        var begin = Allocate(BeginType, beginRequestBody.Length);
        beginRequestBody.CopyTo(begin.AsSpan(6));

        // LIVE (run 20260903T005816Z): a 300-byte 0x120A body never reached world+0x585354 (count stayed 0)
        // while the BEGIN echo landed, and the client's per-type size table (0x4ba23c) pins 0x120A at
        // 0x73A4 = 4 + 100 x 296 bytes. Notify bodies are FIXED-SIZE (cap x record): send the full block.
        var list = Allocate(NinmeiListType, 4 + NinmeiRecordCapacity * NinmeiRecordSize);
        list[6] = 1;
        var record = list.AsSpan(6 + 4, NinmeiRecordSize);
        // marker pattern: "P<offset/8>" ASCII tokens every 8 bytes -> the rendered text tells the offset
        for (var o = 0; o < NinmeiRecordSize; o += 8)
        {
            var token = System.Text.Encoding.ASCII.GetBytes($"P{o:D3}");
            token.CopyTo(record[o..]);
            record[o + 4] = 0x20;
            record[o + 5] = 0x20;
            record[o + 6] = 0x20;
            record[o + 7] = 0x00;
        }

        record[0] = 0x01;   // leading u16 id = 1 (little-endian) in case the first field is an id
        record[1] = 0x00;
        if (NinmeiProbeCardList)
        {
            var cards = Allocate(0x1208, 3604);
            var cbody = cards.AsSpan(6);
            if (NinmeiProbeTokenCardAlone)
            {
                cbody[0] = 1;
                for (var o = 4; o + 8 <= cbody.Length && o < 4 + 296; o += 8)
                {
                    var token = System.Text.Encoding.ASCII.GetBytes($"T08{o:D3}");
                    token.CopyTo(cbody[o..]);
                    cbody[o + 6] = 0x20;
                    cbody[o + 7] = 0x00;
                }

                return [begin, cards, Allocate(EndType, EndBodySize)];
            }

            var ids = NinmeiProbeCardIds ? NinmeiCardIds() : new ushort[] { 1, 2, 3 };
            if (NinmeiProbeOnePerFrame)
            {
                // mode 10: the client accepts 0x1208 only with count==1 (runs 8/8b) and FUN_004C2150 appends
                // records across frames => one full-size frame per card id.
                var multi = new List<byte[]> { begin };
                foreach (var id in ids)
                {
                    var f = Allocate(0x1208, 3604);
                    var fb = f.AsSpan(6);
                    // WIRE LAYOUT (live RPM 2026-09-03, run 040557Z): count u16 BE, then PACKED 7-byte records
                    // {u16 cardId, u32 holder?, u8 flag} — the client parser (0x55F670) reads them sequentially
                    // into 12-byte cells {u16 @0, pad, u32 @4, u8 @8}; state 12 uses @0 as the card id.
                    fb[1] = 1;
                    System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(fb.Slice(2, 2), id);
                    multi.Add(f);
                }

                multi.Add(Allocate(EndType, EndBodySize));
                return multi;
            }

            System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(cbody, (ushort)ids.Length);
            for (var i = 0; i < ids.Length; i++)
            {
                var rec = cbody.Slice(2 + i * 7, 7);   // packed 7-byte wire records after the BE count
                if (NinmeiProbeNonZero)
                {
                    rec.Fill(0x01);
                }

                System.Buffers.Binary.BinaryPrimitives.WriteUInt16BigEndian(rec, ids[i]);
            }

            if (NinmeiProbePrefixed)
            {
                var prefix = new List<byte[]> { begin };
                foreach (var (t, size) in new (ushort, int)[] { (0x1205, 804), (0x1206, 1604), (0x1207, 4804) })
                {
                    var f = Allocate(t, size);
                    f[6] = 1;
                    prefix.Add(f);
                }

                prefix.Add(cards);
                prefix.Add(Allocate(EndType, EndBodySize));
                return prefix;
            }

            return [begin, cards, Allocate(EndType, EndBodySize)];
        }

        if (!NinmeiProbeAllTypes)
        {
            return [begin, list, Allocate(EndType, EndBodySize)];
        }

        // PROBE MODE 2 (2026-09-03): the client accepted a 9-byte 0x1209 push but never dispatched 0x120A at
        // 300 or 29,604 bytes, so acceptance is per TYPE (paired with the selector), not per size. Send every
        // 0x1202..0x120F notify at its fixed size with count=1 and a token pattern; the read-only RPM dump
        // of the 14 count cells then tells which type selector 0x12 actually consumes.
        var frames = new List<byte[]> { begin };
        foreach (var (notifyType, size) in NinmeiProbeReverse ? ListNotifySizes.Reverse().ToArray() : ListNotifySizes)
        {
            var frame = Allocate(notifyType, size);
            frame[6] = 1;
            var body = frame.AsSpan(6 + (notifyType == 0x1209 ? 1 : 4));
            for (var o = 0; o + 8 <= body.Length && o < 296; o += 8)
            {
                var token = System.Text.Encoding.ASCII.GetBytes($"T{notifyType & 0xff:X2}{o:D3}");
                token.CopyTo(body[o..]);
                body[o + 6] = 0x20;
                body[o + 7] = 0x00;
            }

            frames.Add(frame);
        }

        frames.Add(Allocate(EndType, EndBodySize));
        return frames;
    }

    // EXPERIMENT (condition 11/16, 2026-09-03): the authority never serves the session-server 0x02xx
    // ResponseInformation* family. The 任命 dialog manager binds world+0x36A488 (filled by 0x0218
    // ResponseInformationPackage, 340 B) and world+0x35F35C (0x021B ResponseInformationOutfitParty,
    // 8,900 B). With LOGH7_INFO_PROBE=1 push token-filled frames of exactly those sizes right after the
    // game-login acceptance so the dialog reveals whether they are its candidate source.
    public static bool InfoProbeEnabled => Environment.GetEnvironmentVariable("LOGH7_INFO_PROBE") == "1";
    public static IReadOnlyList<byte[]> EncodeInfoProbeFrames()
    {
        var frames = new List<byte[]>();
        foreach (var (type, size) in new (ushort, int)[] { (0x0218, 340), (0x021b, 8900) })
        {
            var frame = Allocate(type, size);
            var body = frame.AsSpan(6);
            body[0] = 1;
            for (var o = 4; o + 8 <= body.Length && o < 4 + 296; o += 8)
            {
                var token = System.Text.Encoding.ASCII.GetBytes($"I{type & 0xff:X2}{o:D3}");
                token.CopyTo(body[o..]);
                body[o + 6] = 0x20;
                body[o + 7] = 0x00;
            }

            frames.Add(frame);
        }

        return frames;
    }

    private static byte[] Allocate(ushort type, int bodySize)
    {
        var response = new byte[OriginalLoginCodec.MessageCodeSize + sizeof(ushort) + bodySize];
        BinaryPrimitives.WriteUInt16BigEndian(response.AsSpan(4), type);
        return response;
    }
}
