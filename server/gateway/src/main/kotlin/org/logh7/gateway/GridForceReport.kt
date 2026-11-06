package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.GridForceMessages
import kotlin.math.abs

/** Friendly surviving ships, not grid-entry unit slots. Includes offline flagships. Author: Peppone Choi. */
internal object GridForceReport {
    fun message(snapshot: WorldSnapshot, galaxy: Galaxy, viewer: Navigator, seconds: Long,
        countries:CountryCatalog=CountryCatalogLoader.current()): ByteArray? {
        if (snapshot.session?.generation != viewer.generation) return null
        val stocks=snapshot.planetStocks?.takeIf {it.generation==viewer.generation}
        val camp=Camp.of(viewer,stocks?.let {CoupActions.rebel(it,viewer.characterId,viewer.country)}==true)
        val power=viewer.country?.let {countries.nativePower(Allegiance.Country(it))} ?: viewer.shipStyle.nativePower
        val units = sortedMapOf<Int, Long>()
        // One native search has 225 cells: use a 15x15 neighbourhood until the
        // original strategic sensor range formula is restored.
        fun add(grid: Int, count: Long) {
            if (count > 0 && abs(grid % 100 - viewer.grid % 100) <= 7 && abs(grid / 100 - viewer.grid / 100) <= 7)
                units[grid] = (units[grid] ?: 0L) + count
        }
        snapshot.navigators.values.filter {it.generation==viewer.generation &&
            Camp.of(it,stocks?.let {s->CoupActions.rebel(s,it.characterId,it.country)}==true)==camp}
            .forEach {add(it.grid,1)}
        stocks?.outfits?.values
            ?.filter {TacticalAuthority.camp(it,stocks)==camp}?.forEach {outfit ->
                // Damaged surviving ships still exist; destroyed ships and ground units do not.
                val ships=outfit.activeShips?.values?.sumOf {it.boats.toLong()} ?: outfit.units.ships.entries.sumOf {(stack,count)->
                    count.toLong()*UnitSpecifications.ships.single {it.kind==stack.kind}.number
                }
                add(outfit.positionIn(galaxy).grid,ships)
            }
        // The native importer converts a zero count to one. Never send fake units.
        return if (units.isEmpty()) null else GridForceMessages.search(seconds, viewer.characterId, power, if(camp.insurgent)1 else 0,
            units.mapValues { it.value.coerceAtMost(65535).toInt() })
    }
}
