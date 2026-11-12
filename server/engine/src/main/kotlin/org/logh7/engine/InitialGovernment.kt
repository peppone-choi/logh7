package org.logh7.engine

/** Original starting office holders (web manual pp.60, 66). Local IDs are below the lottery range. */
object InitialGovernment {
    data class Official(val id: Int, val country: CountryId, val rank: Int,
        val surname: String, val givenName: String, val cards: List<Int>)

    private val officials = listOf(
        Official(901, Power.EMPIRE.country, 0, "프리드리히", "4세", listOf(1, 2)),
        Official(902, Power.EMPIRE.country, 1, "리히텐라데", "클라우스 폰", listOf(5, 6)),
        Official(903, Power.ALLIANCE.country, 1, "샌포드", "로열", listOf(132)),
    )
    val baselineAbilities: List<Int> = List(8) { 50 }

    fun roster(stocks: PlanetStocks?): List<Official> =
        officials.filter { stocks?.strategic?.people?.containsKey(it.id) == true }

    internal fun seed(galaxy: Galaxy, stocks: PlanetStocks, startSeconds: Long): PlanetStocks {
        if (!galaxy.initialGovernment) return stocks
        val catalog = CountryCatalogLoader.current()
        val offices = OrganizationalOfficePolicy(galaxy, catalog)
        var next = stocks
        for (official in officials) {
            val country = official.country
            val definition = catalog.countries[country.value] ?: continue
            val ranks = catalog.families.getValue(definition.family).ranks
            if (ranks != if (country == Power.EMPIRE.country) "empire" else "alliance") continue
            val capital = offices.capital(next, country) ?: continue
            if (official.cards.any { card ->
                offices.role(card, country)?.let { offices.active(next, it, capital) } != true
            }) continue
            // One merit keeps the initial politicians eligible at the first monthly settlement.
            val person = StrategicPerson(rank = official.rank, merit = if (official.rank == 1) 1 else 0)
            next = next.copy(strategic = next.strategic.copy(people = next.strategic.people + (official.id to person)))
            for (card in official.cards) next = offices.set(next, OfficeHolding(card, capital, official.id, country))
            if (1 in official.cards) next = next.copy(social = next.social.copy(
                succession = next.social.succession.with(country, ThroneState(emperor = official.id, at = startSeconds))))
        }
        return next
    }
}
