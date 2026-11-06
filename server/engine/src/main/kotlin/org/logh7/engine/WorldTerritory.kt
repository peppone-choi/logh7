package org.logh7.engine

/** Manual W p.12's original defeat triggers; 2026-11-06 user decision applies them per country.
 * A system is controlled only when every base belongs to the same regular country (revival policy).
 * Author: Peppone Choi. */
internal class WorldTerritory(private val galaxy:Galaxy) {
    private val systems=galaxy.bases.groupBy {it.grid}
    private val initial=Power.entries.associateWith {side->systems.values.count {bases->
        bases.all {base->galaxy.initialOwners.getValue(base.id).country==side.country && galaxy.conditions.getValue(base.id).camp==0}
    }}
    fun controlled(stocks:PlanetStocks, countries:Set<CountryId> = galaxy.countryStarts.keys+
        stocks.strategic.capitals.keys+stocks.bases.values.mapNotNull {it.country}):Map<CountryId,Int> = countries.associateWith {country->
        systems.values.count {bases->bases.all {base->stocks.bases.getValue(base.id).let {it.country==country && it.camp==0}}}
    }
    fun ending(stocks:PlanetStocks, registered:Set<CountryId> = emptySet(),
        eliminated:Set<CountryId> = emptySet()):SessionAction.CountryTerritory? {
        val defaults=galaxy.bases.mapNotNull {base->Capital.of(base)?.takeIf {
            galaxy.initialOwners.getValue(base.id).country==CountryId(it.power)}?.let {CountryId(it.power) to base.id}}.toMap()
        val capitals=defaults+stocks.strategic.capitals
        val countries=registered+galaxy.countryStarts.keys+capitals.keys+
            stocks.bases.values.filter {it.camp==0}.mapNotNull {it.country}
        if(countries.size<2)return null
        val counts=controlled(stocks,countries)
        val captured=capitals.filterKeys {it !in eliminated}.mapNotNull {(country,id)->
            val owner=checkNotNull(stocks.bases[id]) {"Capital is missing from the world"}
            // A coup/neutral takeover still has its own rules; it is not a foreign country's capital capture.
            if(owner.camp==0 && owner.country!=null && owner.country!=country)country to owner.country else null
        }.toMap()
        // The original threshold is not applied to a newly founded, one-base country or a small scenario.
        val eligible=if(initial.values.all {it>3})initial.keys.map {it.country}.toSet() else emptySet()
        val reduced=eligible.any {it !in eliminated && counts.getValue(it)<=3}
        val register=countries.size>2 && countries!=registered
        return if(captured.isNotEmpty() || reduced || register)SessionAction.CountryTerritory(counts,captured,eligible) else null
    }
}
