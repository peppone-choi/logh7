package org.logh7.gateway

import org.logh7.engine.*
import org.logh7.protocol.SupplementDirectoryMessages

/** Read-only supplement/supply choices for the authenticated actual country. Author: Peppone Choi. */
internal class SupplyDirectoryScope(private val galaxy:Galaxy,country:CountryId?,
    countries:CountryCatalog=CountryCatalogLoader.current()) {
    private val country=country?.takeIf {it.value in countries.countries}
    private fun home(stocks:PlanetStocks,outfit:OutfitStock):Boolean = country?.let {own->
        outfit.country==own && stocks.bases[outfit.baseId]?.let {it.belongsTo(own) && it.camp==0}==true
    }==true

    fun supplement(stocks:PlanetStocks,actor:Int,kind:Int):List<SupplementDirectoryMessages.Unit> {
        val source=stocks.outfits.values.singleOrNull {it.commanderId==actor && country!=null && it.country==country}
            ?.takeIf {home(stocks,it)} ?: return emptyList()
        return source.activeShips?.values.orEmpty().filter {it.stack.kind==kind}.sortedBy {it.id}.map {
            SupplementDirectoryMessages.Unit(it.id,it.stack.kind,UnitSpecifications.ships.single {spec->spec.kind==kind}.number-it.boats)
        }.filter {it.destroyed>0}
    }

    fun supply(stocks:PlanetStocks,actor:Int,grid:Int):List<OutfitStock> {
        val source=stocks.outfits.values.singleOrNull {it.commanderId==actor && it.kind==OutfitKind.TRANSPORT &&
            country!=null && it.country==country && galaxy.bases.single {base->base.id==it.baseId}.grid==grid}
            ?.takeIf {home(stocks,it)} ?: return emptyList()
        return stocks.outfits.values.filter {it.id!=source.id && it.baseId==source.baseId && it.country==country}.sortedBy {it.id}
    }
}
