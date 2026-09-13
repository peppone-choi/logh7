using System.Collections.Concurrent;

namespace Logh7.Server.OriginalGateway;

// Registry-owned casualties for NPC and player units in the authored field.
// No original damage balance or durable persistence is implied.
public sealed class OriginalTacticalEncounter(ushort enemyNumber)
{
    // Server authority identity for idempotent processing of this encounter's loss.
    public Guid Id { get; } = Guid.NewGuid();
    private sealed record Snapshot(OriginalTacticalDamageState Damage, bool Completed);
    private readonly object _gate = new();
    private Snapshot _snapshot = new(default, false);
    private readonly ConcurrentDictionary<uint, OriginalTacticalDamageState> _unitDamage = new();
    private readonly ConcurrentDictionary<uint, ushort> _unitNumbers = new();
    public ushort UnitNumber(uint unit) => _unitNumbers.GetValueOrDefault(unit, EnemyNumber);
    public void RegisterUnitNumber(uint unit, ushort number)
    {
        if (unit == 0 || number == 0 || GetUnitDamage(unit).Damaged > number)
            throw new ArgumentOutOfRangeException(nameof(number));
        if (_unitNumbers.GetOrAdd(unit, number) != number)
            throw new InvalidOperationException("TACTICAL_UNIT_COMPLEMENT_CHANGED");
    }
    private readonly Dictionary<uint, long> _shipGenerations = new();

    // Caller holds this grid's command lease. A repeated observation merges
    // casualties; only a newer persisted incarnation can replace them.
    public bool ObserveShipGeneration(uint unit, long generation, OriginalTacticalDamageState persisted)
    {
        if (unit == 0 || unit == OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId || generation < 0 ||
            persisted.Damaged > UnitNumber(unit) || persisted.Destroyed > persisted.Damaged)
            throw new ArgumentOutOfRangeException(nameof(generation));
        lock (_gate)
        {
            var currentGeneration=_shipGenerations.GetValueOrDefault(unit);
            if (generation < currentGeneration) return false;
            var current=GetUnitDamage(unit);
            _unitDamage[unit]=generation > currentGeneration
                ? persisted
                : new(Math.Max(current.Damaged,persisted.Damaged),Math.Max(current.Destroyed,persisted.Destroyed));
            _shipGenerations[unit]=generation;
            return true;
        }
    }
    public ushort EnemyNumber { get; } = enemyNumber;
    public OriginalTacticalDamageState EnemyDamage => Volatile.Read(ref _snapshot).Damage;
    public bool IsCompleted => Volatile.Read(ref _snapshot).Completed;
    public bool EnemyHasSurvivors => EnemyDamage.Destroyed < UnitNumber(OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId);
    public OriginalTacticalDamageState GetUnitDamage(uint unit) =>
        unit == OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId ? EnemyDamage : _unitDamage.GetValueOrDefault(unit);

    public void ValidateUnitDamage(uint unit, OriginalTacticalDamageState damage)
    {
        if (unit == 0 || damage.Damaged > UnitNumber(unit) || damage.Destroyed > damage.Damaged)
            throw new ArgumentOutOfRangeException(nameof(damage));
    }

    public void RecordUnitDamage(uint unit, OriginalTacticalDamageState damage)
    {
        ValidateUnitDamage(unit, damage);
        if (unit == OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId) RecordEnemyDamage(damage);
        else _unitDamage[unit] = damage;
    }

    public bool HasSurvivors(uint unit) => GetUnitDamage(unit).Destroyed < UnitNumber(unit);

    public OriginalInformationUnitProjection ProjectUnit(OriginalInformationUnitProjection unit)
    {
        var damage = GetUnitDamage(unit.Id);
        return unit with { Damaged = damage.Damaged, Destroyed = damage.Destroyed };
    }
    public void RecordEnemyDamage(OriginalTacticalDamageState damage)
    {
        if (damage.Damaged > UnitNumber(OriginalAuthoredPlayableCatalog.TacticalEnemyUnitId) || damage.Destroyed > damage.Damaged)
            throw new ArgumentOutOfRangeException(nameof(damage));
        lock (_gate) _snapshot = _snapshot with { Damage = damage };
    }
    public IReadOnlyList<T> ProjectParticipants<T>(T friendly, T enemy, bool includeDefeated = false) =>
        EnemyHasSurvivors || includeDefeated ? [friendly, enemy] : [friendly];
    public IReadOnlyList<byte[]> TryComplete(uint grid, byte friendlyPower, byte friendlyCamp,
        IReadOnlyList<OriginalInformationBaseRecord>? bases, bool npcIsHostile = true)
    {
        // Original manual chapter4: no hostile units and all grid objectives
        // controlled. Null ownership must not be confused with no objectives.
        lock (_gate)
        {
            if (IsCompleted || (npcIsHostile && EnemyHasSurvivors) || bases is null ||
                bases.Any(value => value.Grid == grid &&
                    (value.Power != friendlyPower || value.Camp != friendlyCamp))) return [];
            var frames = new[]
            {
                OriginalWorldBootstrapCodec.EncodeInformationGrid(checked((ushort)grid), 0),
                OriginalTacticalCommandCodec.EncodeNotifyTactics(0, grid),
            };
            _snapshot = _snapshot with { Completed = true };
            return frames;
        }
    }
    public byte[] EncodeGridState(ushort requestedGrid, uint encounterGrid) =>
        OriginalWorldBootstrapCodec.EncodeInformationGrid(requestedGrid,
            !IsCompleted && requestedGrid == encounterGrid ? (byte)1 : (byte)0);
}
