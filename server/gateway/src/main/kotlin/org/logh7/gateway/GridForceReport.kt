package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.GridForceMessages
import kotlin.math.abs

/** Friendly surviving ships, not grid-entry unit slots. Includes offline flagships. Author: Peppone Choi. */
internal object GridForceReport {
    fun message(snapshot: WorldSnapshot, galaxy: Galaxy, viewer: Navigator, seconds: Long,
        countries:CountryCatalog=CountryCatalogLoader.current()): ByteArray? =
        reports(snapshot, galaxy, viewer, seconds, countries, search = false).values.singleOrNull()

    fun search(snapshot: WorldSnapshot, galaxy: Galaxy, viewer: Navigator, seconds: Long,
        countries: CountryCatalog = CountryCatalogLoader.current()): Map<Pair<Int, Boolean>, ByteArray> =
        reports(snapshot, galaxy, viewer, seconds, countries, search = true)

    private fun reports(snapshot: WorldSnapshot, galaxy: Galaxy, viewer: Navigator, seconds: Long,
        countries: CountryCatalog, search: Boolean): Map<Pair<Int, Boolean>, ByteArray> {
        val session = snapshot.session?.takeIf { it.generation == viewer.generation } ?: return emptyMap()
        val eliminated = session.eliminated.keys
        val stocks=snapshot.planetStocks?.takeIf {it.generation==viewer.generation}
        val camp=Camp.of(viewer,stocks?.let {CoupActions.rebel(it,viewer.characterId,viewer.country)}==true)
        fun nativePower(camp: Camp, fallback: Int? = null) =
            camp.country?.let { countries.nativePower(Allegiance.Country(it)) } ?: fallback
        val friendly = nativePower(camp, viewer.shipStyle.nativePower) to camp.insurgent
        val groups = linkedMapOf<Pair<Int, Boolean>, MutableMap<Int, Long>>()
        // The native decoder accepts up to 225 reported cells. The 15x15 search
        // window is provisional until the original range is known.
        fun add(owner: Camp, power: Int?, grid: Int, count: Long) {
            if (count <= 0 || abs(grid % 100 - viewer.grid % 100) > 7 || abs(grid / 100 - viewer.grid / 100) > 7) return
            if (power == null || power !in 2..3) return
            val key = power to owner.insurgent
            // An enemy with the same native label would be rendered as friendly.
            if (owner != camp && (!search || key == friendly)) return
            val units = groups.getOrPut(key) { mutableMapOf() }
            units[grid] = (units[grid] ?: 0L) + count
        }
        snapshot.navigators.values.filter { GridForces.countsFlagship(it, viewer.generation, stocks, eliminated) }
            .forEach {
                val owner = Camp.of(it, stocks?.let { s -> CoupActions.rebel(s, it.characterId, it.country) } == true)
                add(owner, nativePower(owner, it.shipStyle.nativePower), it.grid, 1)
            }
        stocks?.outfits?.values?.forEach {outfit ->
                if (outfit.country in eliminated) return@forEach
                val owner = TacticalAuthority.camp(outfit, stocks)
                if (!search && owner != camp) return@forEach
                // Damaged surviving ships still exist; destroyed ships and ground units do not.
                val ships=outfit.activeShips?.values?.sumOf {it.boats.toLong()} ?: outfit.units.ships.entries.sumOf {(stack,count)->
                    count.toLong()*UnitSpecifications.ships.single {it.kind==stack.kind}.number
                }
                add(owner, nativePower(owner), outfit.positionIn(galaxy).grid, ships)
            }
        // The native importer converts a zero count to one. Never send fake units.
        return groups.mapValues { (group, units) ->
            GridForceMessages.search(seconds, viewer.characterId, group.first, if (group.second) 1 else 0,
                units.mapValues { it.value.coerceAtMost(65535).toInt() })
        }
    }
}
