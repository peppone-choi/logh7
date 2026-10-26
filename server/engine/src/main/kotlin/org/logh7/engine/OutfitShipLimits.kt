package org.logh7.engine

/** Manual appendix pp.57-58,63-64: physical ships, not native party row counts. Author: 최병호. */
internal object OutfitShipLimits {
    fun maximum(kind: OutfitKind): Long = when (kind) {
        OutfitKind.FLEET -> 18_000L
        OutfitKind.TRANSPORT -> 6_900L
        OutfitKind.PATROL, OutfitKind.GROUND -> 900L
    }

    fun validate(kind: OutfitKind, units: UnitInventory) {
        val boats = units.ships.entries.sumOf { (stack, quantity) ->
            quantity.toLong() * UnitSpecifications.ships.single { it.kind == stack.kind }.number
        }
        require(boats <= maximum(kind)) { "Outfit exceeds its physical ship limit" }
    }
}
