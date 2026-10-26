package org.logh7.engine

/** Manual appendix pp.57-58,63-64: physical ships, not native party row counts. Author: 최병호. */
internal object OutfitShipLimits {
    fun maximum(kind: OutfitKind): Long = when (kind) {
        OutfitKind.FLEET -> 18_000L
        OutfitKind.TRANSPORT -> 6_900L
        OutfitKind.PATROL, OutfitKind.GROUND -> 900L
    }

    fun validate(kind: OutfitKind, units: UnitInventory) {
        // 00534160 groups the shared native class, not the internal hull-role type.
        val groups=LongArray(3)
        for ((stack,quantity) in units.ships) {
            val spec=requireNotNull(UnitSpecifications.ships.singleOrNull { it.kind==stack.kind })
            val group=when (spec.category) {
                0,1,3,4,5,6,7,8,9,13 -> 0
                10,11 -> 1
                12 -> 2
                else -> throw IllegalArgumentException("Ship class is not a military formation class")
            }
            groups[group]+=quantity.toLong()*spec.number
        }
        require(groups.sum() <= maximum(kind)) { "Outfit exceeds its physical ship limit" }
        // The native editor's 005329c0 shows separate 3/20 transport limits.
        // Restrict each formation to its displayed military roles; no silent reclassification.
        require(when (kind) {
            OutfitKind.FLEET, OutfitKind.PATROL -> groups[1]==0L && groups[2]==0L
            OutfitKind.TRANSPORT -> groups[0]<=900L && groups[1]<=6000L && groups[2]==0L
            OutfitKind.GROUND -> groups[0]==0L && groups[1]==0L
        }) { "Ship role or role capacity does not match the outfit" }
    }
}
