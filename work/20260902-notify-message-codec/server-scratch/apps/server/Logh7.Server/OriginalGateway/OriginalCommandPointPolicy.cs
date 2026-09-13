using System.Text.Json;

namespace Logh7.Server.OriginalGateway;

/// <summary>
/// Political (PCP) and military (MCP) command points. The manual documents each
/// command's consumed points and that only the deficit is covered by the other
/// pool, and states that substituted consumption does not count toward ability
/// growth. Initial grant, regeneration and cap are NOT recovered original data;
/// they are an explicitly authored, user-approved replacement policy that lives
/// in policies/command-points.json so it can be edited without a code change.
/// </summary>
public enum OriginalCommandPointPool
{
    Political = 0,
    Military = 1,
}

public readonly record struct OriginalCommandPointCharge(
    bool Accepted,
    uint Cost,
    uint Direct,
    uint Substitute,
    uint PoliticalAfter,
    uint MilitaryAfter,
    string? ErrorCode);

public readonly record struct OriginalCommandPointAccrual(
    uint Balance,
    DateTimeOffset AccruedAt,
    uint Granted);

public sealed class OriginalCommandPointPolicy
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };

    private sealed record Document(
        int SchemaVersion,
        string? EvidenceStatus,
        string? Approval,
        uint InitialPolitical,
        uint InitialMilitary,
        uint Cap,
        uint RegenerationAmount,
        uint RegenerationIntervalSeconds,
        uint SubstitutionRatio,
        bool DeficitOnlySubstitution,
        bool ExcludeSubstituteFromGrowth,
        bool SuspendRegenerationInTactics);

    private OriginalCommandPointPolicy(Document document)
    {
        EvidenceStatus = document.EvidenceStatus ?? "UNKNOWN";
        Approval = document.Approval ?? "UNKNOWN";
        InitialPolitical = document.InitialPolitical;
        InitialMilitary = document.InitialMilitary;
        Cap = document.Cap;
        RegenerationAmount = document.RegenerationAmount;
        RegenerationInterval = TimeSpan.FromSeconds(document.RegenerationIntervalSeconds);
        SubstitutionRatio = document.SubstitutionRatio;
        DeficitOnlySubstitution = document.DeficitOnlySubstitution;
        ExcludeSubstituteFromGrowth = document.ExcludeSubstituteFromGrowth;
        SuspendRegenerationInTactics = document.SuspendRegenerationInTactics;
    }

    public string EvidenceStatus { get; }
    public string Approval { get; }
    public uint InitialPolitical { get; }
    public uint InitialMilitary { get; }
    public uint Cap { get; }
    public uint RegenerationAmount { get; }
    public TimeSpan RegenerationInterval { get; }
    public uint SubstitutionRatio { get; }
    public bool DeficitOnlySubstitution { get; }
    public bool ExcludeSubstituteFromGrowth { get; }
    public bool SuspendRegenerationInTactics { get; }

    public uint Initial(OriginalCommandPointPool pool) =>
        pool == OriginalCommandPointPool.Military ? InitialMilitary : InitialPolitical;

    public static OriginalCommandPointPolicy LoadDefault() =>
        Load(Path.Combine(AppContext.BaseDirectory, "policies", "command-points.json"));

    public static OriginalCommandPointPolicy LoadConfigured(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return LoadDefault();
        if (!Path.IsPathFullyQualified(path))
            throw new ArgumentException("Command point policy path must be absolute.", nameof(path));
        return Load(path);
    }

    public static OriginalCommandPointPolicy Load(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        return Parse(File.ReadAllText(path));
    }

    public static OriginalCommandPointPolicy Parse(string json)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(json);
        var document = JsonSerializer.Deserialize<Document>(json, JsonOptions)
            ?? throw new InvalidDataException("command point policy is empty");
        if (document.SchemaVersion != 1)
            throw new InvalidDataException($"unsupported command point policy schema {document.SchemaVersion}");
        if (document.Cap == 0 || document.InitialPolitical > document.Cap || document.InitialMilitary > document.Cap)
            throw new InvalidDataException("command point cap must be positive and cover both initial grants");
        if (document.RegenerationIntervalSeconds == 0)
            throw new InvalidDataException("command point regeneration interval must be positive");
        // Ratio 1 is a legal authored choice; 0 would silently make the other
        // pool free, which is never the manual's substitution intent.
        if (document.SubstitutionRatio == 0)
            throw new InvalidDataException("command point substitution ratio must be positive");
        if (!document.DeficitOnlySubstitution)
            throw new InvalidDataException("only deficit-first substitution is authored");
        return new(document);
    }

    /// <summary>
    /// Grants whole elapsed intervals only, so the remainder is never lost or
    /// double counted. Time spent inside a tactical battle is discarded rather
    /// than banked, matching the manual's exclusion of tactical time.
    /// </summary>
    public OriginalCommandPointAccrual Accrue(uint balance, DateTimeOffset accruedAt, DateTimeOffset now,
        bool inTactics)
    {
        if (now <= accruedAt) return new(balance, accruedAt, 0);
        var intervals = (now - accruedAt).Ticks / RegenerationInterval.Ticks;
        if (intervals <= 0) return new(balance, accruedAt, 0);
        var settled = accruedAt + TimeSpan.FromTicks(intervals * RegenerationInterval.Ticks);
        if (inTactics && SuspendRegenerationInTactics) return new(balance, settled, 0);
        if (balance >= Cap) return new(balance, settled, 0);
        var granted = (uint)Math.Min(Cap - balance, Math.Min(intervals * (long)RegenerationAmount, Cap));
        return new(balance + granted, settled, granted);
    }

    /// <summary>
    /// Spends the command's own pool first and covers only the remaining deficit
    /// from the other pool at the authored ratio. A rejected charge changes
    /// nothing; the caller must not spend a partial amount.
    /// </summary>
    public OriginalCommandPointCharge Charge(uint political, uint military, OriginalCommandPointPool pool, uint cost)
    {
        var primary = pool == OriginalCommandPointPool.Military ? military : political;
        var other = pool == OriginalCommandPointPool.Military ? political : military;
        var direct = Math.Min(primary, cost);
        var deficit = cost - direct;
        var substitute = (ulong)deficit * SubstitutionRatio;
        if (substitute > other)
            return new(false, cost, 0, 0, political, military, "COMMAND_POINTS_INSUFFICIENT");
        var primaryAfter = primary - direct;
        var otherAfter = other - (uint)substitute;
        return pool == OriginalCommandPointPool.Military
            ? new(true, cost, direct, (uint)substitute, otherAfter, primaryAfter, null)
            : new(true, cost, direct, (uint)substitute, primaryAfter, otherAfter, null);
    }
}
