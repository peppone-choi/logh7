namespace Logh7.Server.OriginalGateway;

public sealed record OriginalLotteryCandidateTemplate(
    uint CharacterId,
    string DisplayName,
    short Faction,
    short Blood,
    short Sex,
    string LastName,
    string FirstName,
    string FlagshipName,
    int Face,
    short[] AbilityValues);

public static class OriginalLotteryCandidateCatalog
{
    public const string Provenance = "AUTHORED_PLACEHOLDER";

    // NEW DESIGN: the original service database and lottery award rules are
    // unavailable. These IDs, gameplay values, and ship names are editable
    // server-owned placeholders and must not be cited as original LOGH7 data.
    public static IReadOnlyList<OriginalLotteryCandidateTemplate> Templates { get; } =
    [
        new(0x70000001, "ヤン・ウェンリー", 2, 1, 1,
            "ヤン", "ウェンリー", "プレアデス", 1000001,
            [92, 88, 70, 72, 86, 78, 66, 74]),
        new(0x70000002, "ユリアン・ミンツ", 2, 2, 1,
            "ミンツ", "ユリアン", "アルタイル", 1000001,
            [78, 80, 68, 74, 82, 76, 72, 84]),
        new(0x70000003, "アッテンボロー", 2, 3, 1,
            "アッテンボロー", "ダスティ", "リゲル", 1000001,
            [84, 77, 62, 70, 73, 88, 82, 69]),
        new(0x70000004, "シェーンコップ", 2, 4, 1,
            "シェーンコップ", "ワルター", "ベテルギウス", 1000001,
            [76, 83, 60, 66, 68, 91, 90, 75]),
        new(0x70000005, "キャゼルヌ", 2, 1, 1,
            "キャゼルヌ", "アレックス", "スピカ", 1000001,
            [66, 72, 91, 94, 79, 64, 61, 80])
    ];

    public static IReadOnlyList<OriginalSimpleCharacterRosterEntry> Entries { get; } =
        Templates
            .Select(template => new OriginalSimpleCharacterRosterEntry(
                template.CharacterId,
                template.DisplayName,
                checked((byte)template.Faction)))
            .ToArray();

    public static OriginalLotteryCandidateTemplate Get(uint characterId) =>
        Templates.Single(template => template.CharacterId == characterId);
}
