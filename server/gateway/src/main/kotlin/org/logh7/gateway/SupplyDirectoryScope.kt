package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.SupplementDirectoryMessages

/** Read-only supplement/supply choices for the authenticated actual country. Author: Peppone Choi. */
internal class SupplyDirectoryScope(private val galaxy:Galaxy,country:CountryId?,
    countries:CountryCatalog=CountryCatalogLoader.current()) {
    private val country=country?.takeIf {it.value in countries.countries}
    fun supplement(stocks:PlanetStocks,actor:Int,kind:Int):List<SupplementDirectoryMessages.Unit> {
        val own=country ?: return emptyList()
        val source=stocks.outfits.values.singleOrNull {it.commanderId==actor &&
            TacticalAuthority.canAccessOutfitWarehouse(stocks,it,actor,own)} ?: return emptyList()
        return source.activeShips?.values.orEmpty().filter {it.stack.kind==kind}.sortedBy {it.id}.map {
            SupplementDirectoryMessages.Unit(it.id,it.stack.kind,UnitSpecifications.ships.single {spec->spec.kind==kind}.number-it.boats)
        }.filter {it.destroyed>0}
    }

    fun supplySource(stocks:PlanetStocks,actor:Int,grid:Int,port:Int?=null):OutfitStock? {
        val own=country ?: return null
        val camp=Camp(own,CoupActions.rebel(stocks,actor,own))
        return stocks.outfits.values.singleOrNull {it.commanderId==actor && it.kind==OutfitKind.TRANSPORT &&
            TacticalAuthority.camp(it,stocks)==camp && it.positionIn(galaxy).let { position ->
                position.grid==grid && (port==null || position.baseId==port) &&
                    stocks.bases[position.baseId]?.let(TacticalAuthority::camp)==camp
            }}
    }

    fun supply(stocks:PlanetStocks,actor:Int,grid:Int):List<OutfitStock> =
        supplySource(stocks,actor,grid)?.let { supplyTargets(stocks,it) }.orEmpty()

    fun supplyTargets(stocks:PlanetStocks,source:OutfitStock):List<OutfitStock> {
        if(source.country!=country)return emptyList()
        val port=source.positionIn(galaxy).baseId
        val camp=TacticalAuthority.camp(source,stocks)
        return stocks.outfits.values.filter {it.id!=source.id && TacticalAuthority.camp(it,stocks)==camp &&
            (it.position?.baseId ?: it.baseId)==port}.sortedBy {it.id}
    }
}
