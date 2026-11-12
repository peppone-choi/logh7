package org.logh7.engine

/** Original starting office holders (web manual pp.60, 66). Local IDs are below the lottery range. */
object InitialGovernment {
    private const val EMPIRE_EMPEROR = 901
    internal const val EMPIRE_MILITARY_MINISTER = 17
    internal const val EMPIRE_AUXILIARY_FORMATION = 25
    internal const val EMPIRE_GRENADIER_INSPECTOR = 34
    internal const val ALLIANCE_CHAIR = 903
    internal const val ALLIANCE_RESOURCES_CHAIR = 904
    data class Official(val id: Int, val country: CountryId, val rank: Int,
        val surname: String, val givenName: String, val cards: List<Int>)

    private val militaryOfficials = listOf(
        Official(905, Power.EMPIRE.country, 2, "에렌베르크", "", listOf(EMPIRE_MILITARY_MINISTER)),
        Official(906, Power.EMPIRE.country, 2, "슈타인호프", "", listOf(22)),
        Official(907, Power.EMPIRE.country, 3, "오프레서", "", listOf(EMPIRE_GRENADIER_INSPECTOR)),
    )
    private val officials = listOf(
        Official(EMPIRE_EMPEROR, Power.EMPIRE.country, 0, "프리드리히", "4세", listOf(1, 2)),
        Official(902, Power.EMPIRE.country, 1, "리히텐라데", "클라우스 폰", listOf(5, 6)),
        Official(ALLIANCE_CHAIR, Power.ALLIANCE.country, 1, "샌포드", "로열", listOf(132)),
        Official(ALLIANCE_RESOURCES_CHAIR, Power.ALLIANCE.country, 1, "황", "루이", listOf(139)),
    ) + militaryOfficials
    val baselineAbilities: List<Int> = List(8) { 50 }

    fun roster(stocks: PlanetStocks?): List<Official> =
        officials.filter { stocks?.strategic?.people?.containsKey(it.id) == true }

    /** Add the newly recovered officers to older initial-government saves once, preserving held seats. */
    internal fun restoreMilitaryOffices(galaxy: Galaxy, stocks: PlanetStocks): PlanetStocks {
        if (!galaxy.initialGovernment || militaryOfficials.all { it.id in stocks.strategic.people }) return stocks
        val empire = Power.EMPIRE.country
        val offices = OrganizationalOfficePolicy(galaxy)
        if (offices.holders(stocks, 1, empire).none { it.character == EMPIRE_EMPEROR } ||
            BattleDeath.deceased(stocks, EMPIRE_EMPEROR) || stocks.social.justice.detained(EMPIRE_EMPEROR) ||
            CoupActions.rebel(stocks, EMPIRE_EMPEROR, empire)) return stocks
        val capital = offices.capital(stocks, empire) ?: return stocks
        var next = stocks
        for (official in militaryOfficials) {
            if (official.id in next.strategic.people || next.strategic.people.size >= 4096) continue
            val card = official.cards.single()
            val role = offices.role(card, empire) ?: continue
            if (!offices.active(next, role, capital) ||
                galaxy.facilitiesAt(capital, next, CountryCatalogLoader.current()).none { it.kind == role.facility }) continue
            if (card == EMPIRE_GRENADIER_INSPECTOR) {
                val minister = militaryOfficials.first()
                val rank = ProposalActions.rank(next, minister.id, empire, minister.rank)
                if (BattleDeath.deceased(next, minister.id) || next.social.justice.detained(minister.id) ||
                    CoupActions.rebel(next, minister.id, empire) ||
                    !offices.authorized(next, role, capital, minister.id, rank) ||
                    !offices.eligible(next, role, minister.id, rank, official.id, official.rank)) continue
            }
            next = next.copy(strategic = next.strategic.copy(
                people = next.strategic.people + (official.id to StrategicPerson(rank = official.rank))))
            if (offices.holders(next, card, empire).isEmpty())
                next = offices.set(next, OfficeHolding(card, capital, official.id, empire))
        }
        return next
    }

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
