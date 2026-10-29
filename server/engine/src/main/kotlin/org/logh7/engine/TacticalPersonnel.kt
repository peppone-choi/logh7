package org.logh7.engine

/** Tactical reward ledger -> strategic personnel, once per durable after-image. Author: 최병호. */
object TacticalPersonnel {
    private const val MAXIMUM_MERIT=0xffffffffL
    fun rank(stocks:PlanetStocks,character:Int)=stocks.strategic.people[character]?.rank
        ?: stocks.tacticalCommands.ranks[character] ?: 20
    fun merit(stocks:PlanetStocks,character:Int):Long {
        val state=stocks.tacticalCommands
        val pending=((state.merits[character] ?: 0)-(state.creditedMerits[character] ?: 0)).coerceAtLeast(0)
        val current=stocks.strategic.people[character]?.merit ?: 0
        return current+minOf(pending,MAXIMUM_MERIT-current)
    }
    internal fun credit(stocks:PlanetStocks):PlanetStocks {
        val state=stocks.tacticalCommands
        val credits=state.creditedMerits.toMutableMap()
        val people=stocks.strategic.people.toMutableMap()
        for((id,total) in state.merits) if(total>(credits[id] ?: 0)) {
            val person=people[id] ?: StrategicPerson()
            people[id]=person.copy(merit=merit(stocks,id))
            // Mark even a saturated reward consumed; later spending cannot reclaim it.
            credits[id]=total
        }
        return stocks.copy(strategic=stocks.strategic.copy(people=people),
            tacticalCommands=state.copy(creditedMerits=credits))
    }
}
