package org.logh7.engine

/** Manual organization pp.56/62/63, native group-3 cards and group-73 institutions. */
enum class NationalOffice(val card:Int,val power:Int,val capital:Capital,val facility:Int,val ranks:Set<Int>) {
    IMPERIAL_SUPREME_COMMANDER(2,2,Capital.EMPIRE,22,setOf(0,2)),
    IMPERIAL_STAFF_DIRECTOR(3,2,Capital.EMPIRE,22,setOf(2)),
    MILITARY_MINISTER(17,2,Capital.EMPIRE,31,setOf(2)),
    NATIONAL_AFFAIRS_CHAIR(134,3,Capital.ALLIANCE,24,setOf(1)),
    DEFENSE_HEAD(161,3,Capital.ALLIANCE,34,setOf(2,4,5)),
    ALLIANCE_FLEET_COMMANDER(169,3,Capital.ALLIANCE,29,setOf(2)),
    ALLIANCE_FLEET_CHIEF(171,3,Capital.ALLIANCE,29,setOf(2,4,5));
    fun seat(galaxy:Galaxy):Int?=galaxy.bases.singleOrNull { Capital.of(it)==capital }?.let { base ->
        galaxy.facilities[base.id]?.singleOrNull { it.kind==facility }?.id
    }
    fun active(galaxy:Galaxy,stocks:PlanetStocks):Boolean=galaxy.bases.singleOrNull { Capital.of(it)==capital }?.let {
        val base=stocks.bases[it.id]
        base?.power==power && base.camp==0 && seat(galaxy)!=null
    }==true
    companion object { fun of(card:Int)=entries.singleOrNull { it.card==card } }
}

/** These national offices are independently stored, not inferred from rank or a planetary office. */
class NationalOfficePolicy(private val galaxy:Galaxy) {
    fun cards(stocks:PlanetStocks,character:Int,power:Int):List<Pair<Int,Int>> = NationalOffice.entries.filter {
        it.power==power && stocks.nationalOffices[it]==character && it.active(galaxy,stocks)
    }.map { it.card to checkNotNull(it.seat(galaxy)) }
}
