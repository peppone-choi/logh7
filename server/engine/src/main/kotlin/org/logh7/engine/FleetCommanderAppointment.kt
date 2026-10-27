package org.logh7.engine

/** Manual organization: Empire fleet commander (lieutenant-general..marshal), appointed by military minister. */
class FleetCommanderAppointmentPolicy(private val galaxy: Galaxy) {
    fun authorized(stocks: PlanetStocks,character: Int,power: Int,rank: Int): Boolean =
        power==2 && rank in NationalOffice.MILITARY_MINISTER.ranks &&
            stocks.nationalOffices[NationalOffice.MILITARY_MINISTER]==character &&
            NationalOffice.MILITARY_MINISTER.active(galaxy,stocks)
    fun eligible(actorRank: Int,targetRank: Int)=actorRank==2 && targetRank in 3..5
    fun available(stocks: PlanetStocks,character: Int,power: Int,rank: Int): List<OutfitStock> =
        if (!authorized(stocks,character,power,rank)) emptyList() else stocks.outfits.values.filter {
            it.power==2 && it.kind==OutfitKind.FLEET && it.commanderId==0 && friendly(stocks,it)
        }.sortedBy { it.id }
    internal fun friendly(stocks: PlanetStocks,outfit: OutfitStock): Boolean =
        stocks.bases[outfit.baseId]?.let { it.power==2 && it.camp==0 }==true
}
