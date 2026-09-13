namespace Logh7.Server.OriginalGateway;

public static class OriginalSubordinateShipCatalog
{
    // Manual unit/damage sections: ordinary unit=300 hulls, flagship=1.
    // Group55 names distinguish tiered ordinary kinds from generic flagships.
    // Other performance and model joins remain authored visual candidates.
    public static OriginalStaticUnitShipCapabilities Capabilities =>
        OriginalAuthoredPlayableCatalog.TacticalShipCapabilities with { Number = 300 };

    public static bool Supports(ushort kind) =>
        kind is 32 or 56 or 119 or 139 or RepairShipKind or SupplyShipKind;

    /// <summary>
    /// 工作艦. ORIGINAL_STATIC for the *rule*, NEW_DESIGN for the kind id: the
    /// client's 修理 filter FUN_004C76E0 reads
    /// <c>byte [world + kind*0x148 + 0x2C1C96]</c> and requires it to equal **9**
    /// (0x00510F50 <c>push 9</c>), so a 工作艦 is defined by that per-kind class
    /// byte, not by a name or a model. Which served field lands there is being
    /// checked by experiment - every template this authority serves currently has
    /// Type = Category = 0 and the byte reads 0 for every kind, which is why 修理
    /// has never activated.
    /// </summary>
    public const ushort RepairShipKind = 57;

    /// <summary>
    /// 補給艦. The same rule with **10**: 0x00510FF1 <c>push 0xA</c>.
    /// </summary>
    public const ushort SupplyShipKind = 58;

    // Kind139 is the authored flagship-class single-hull picket. The manual's
    // unit section gives a flagship unit one hull, and the client reads the
    // hull count from this per-kind template, so the authority must not carry a
    // different number for the same kind or the two would disagree on death.
    public const ushort FlagshipClassKind = 139;

    public static IReadOnlyList<OriginalStaticUnitShipTemplate> Templates =>
    [
        // ORIGINAL_MANUAL page79: empire battleship I requires five crew units.
        new(32, 0, 0, 0, 12, string.Empty, Capabilities,
            Logistics: new(0, 0, 0, 0, 5)),
        // ORIGINAL_MANUAL: gin7manual.pdf page82, destroyer I-VIII crew=1 unit.
        // Group55 row56 is destroyer I (flagship-name-selectors-v45.json).
        // Other logistics retain their legacy zero placeholders: not free construction.
        // Do not apply this to57/58, which this catalog repurposes as support hulls.
        new(56, 0, 0, 0, 18, string.Empty, Capabilities,
            Logistics: new(0, 0, 0, 0, 1)),
        // ORIGINAL_MANUAL page90: alliance battleship I requires four crew units.
        new(119, 0, 0, 0, 1003, string.Empty, Capabilities,
            Logistics: new(0, 0, 0, 0, 4)),
        // NEW_DESIGN: an unarmed scout picket. Every weapon arc is cleared, so
        // SelectWeapon finds no family and the unit never fires. It exists to be
        // found and destroyed, which is what makes a first finishable battle
        // possible; it is not a claim that the original had an unarmed hull.
        new(FlagshipClassKind, 0, 0, 0, 1014, string.Empty, Capabilities with
            { Number = 1, BeamAngleMask = 0, GunAngleMask = 0, MissileAngleMask = 0 }),
        // EXPERIMENT: Type carries the class the 修理/補給 filters demand - 9 and
        // 10. If the byte at kind*0x148 + 0x2C1C96 turns out to be Category
        // instead, the same values move one field along; nothing else changes.
        // Both are unarmed support hulls, so every weapon arc is cleared.
        new(RepairShipKind, 9, 0, 0, 18, string.Empty, Capabilities with
            { Number = 1, BeamAngleMask = 0, GunAngleMask = 0, MissileAngleMask = 0 }),
        new(SupplyShipKind, 10, 0, 0, 18, string.Empty, Capabilities with
            { Number = 1, BeamAngleMask = 0, GunAngleMask = 0, MissileAngleMask = 0 }),
    ];

    // Single source of truth for both the served template and the authority's
    // simulation: hull count, weapon arcs and everything else come from the
    // same per-kind template the client was given.
    public static OriginalStaticUnitShipCapabilities CapabilitiesFor(ushort kind)
    {
        foreach (var template in Templates)
            if (template.Kind == kind) return template.Capabilities ?? Capabilities;
        return Capabilities;
    }

    public static ushort ComplementFor(ushort kind) => CapabilitiesFor(kind).Number;
}
