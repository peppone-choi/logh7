package org.logh7.engine

/** Manual organization pp.57/63: one commander aide, lieutenant..marshal, appointed by that commander. */
class FleetAideAppointmentPolicy {
    fun card(power: Int) = if (power == 2) 43 else 179
    fun commanderRank(power:Int,rank:Int) = if(power==2)rank in 2..5 else power==3 && rank in setOf(2,4,5,6)
    fun eligible(actorRank: Int,targetRank: Int,power:Int=2) = commanderRank(power,actorRank) &&
        targetRank in 2..12 && actorRank < targetRank && (power!=3 || targetRank!=3)
    fun available(stocks: PlanetStocks,character: Int,power: Int,rank: Int): List<OutfitStock> =
        if(!commanderRank(power,rank)) emptyList() else stocks.outfits.values.filter {
            it.power==power && it.kind==OutfitKind.FLEET && it.commanderId==character && it.aideId==0 &&
                stocks.bases[it.baseId]?.let { base -> base.power==power && base.camp==0 }==true
        }.sortedBy { it.id }
}
