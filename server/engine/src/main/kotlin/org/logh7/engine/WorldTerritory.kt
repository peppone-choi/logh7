package org.logh7.engine

/** Manual W p.12: capital capture or regular territory reduced to three systems ends the session.
 * A system is controlled only when every base belongs to the same regular camp (revival policy).
 * Author: Peppone Choi. */
internal class WorldTerritory(private val galaxy:Galaxy) {
    private val systems=galaxy.bases.groupBy {it.grid}
    private fun power(wire:Int)=when(wire) {2->Power.EMPIRE;3->Power.ALLIANCE;else->null}
    private val initial=Power.entries.associateWith {side->systems.values.count {bases->
        bases.all {base->galaxy.conditions.getValue(base.id).let {power(it.power)==side && it.camp==0}}
    }}
    fun controlled(stocks:PlanetStocks):Map<Power,Int> = Power.entries.associateWith {side->
        systems.values.count {bases->bases.all {base->stocks.bases.getValue(base.id).let {power(it.power)==side && it.camp==0}}}
    }
    fun ending(stocks:PlanetStocks):SessionAction.Territory? {
        val capitals=galaxy.bases.mapNotNull {base->
            val capital=Capital.of(base) ?: return@mapNotNull null
            val owner=stocks.bases.getValue(base.id)
            // Neutral/insurgent takeover rules remain separate; do not invent their winner.
            if(owner.camp==0 && owner.power in 2..3 && owner.power!=capital.power) power(capital.power) else null
        }.toSet()
        val counts=controlled(stocks)
        // Small local scenarios can start below the manual defeat threshold. They still support
        // capital capture, but do not immediately end before any play because of their map size.
        val reduced=initial.values.all {it>3} && counts.values.any {it<=3}
        return if(capitals.isNotEmpty() || reduced)SessionAction.Territory(counts,capitals) else null
    }
}
