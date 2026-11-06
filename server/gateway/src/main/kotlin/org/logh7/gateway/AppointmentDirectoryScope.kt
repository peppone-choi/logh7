package org.logh7.gateway

import org.logh7.engine.*

/**
 * Appointment (0x1200) and dismissal directories act for the actor profile's actual country, its
 * configured office family and rank system, never for the native display code. A country that is
 * not in the catalogue (removed or never registered) gets empty directories. Author: Peppone Choi.
 */
internal class AppointmentDirectoryScope(private val galaxy:Galaxy,country:CountryId?,
    private val countries:CountryCatalog=CountryCatalogLoader.current()) {
    val country:CountryId?=country?.takeIf {it.value in countries.countries}
    val policy=OrganizationalOfficePolicy(galaxy,countries)
    fun rank(stocks:PlanetStocks,character:Int,fallback:Int)=
        country?.let {ProposalActions.rank(stocks,character,it,fallback,countries)} ?: fallback
    fun available(stocks:PlanetStocks,actor:Int,fallbackRank:Int):Map<Int,List<Int>> =
        country?.let {policy.available(stocks,actor,it.value,rank(stocks,actor,fallbackRank))} ?: emptyMap()
    fun dismissals(stocks:PlanetStocks,actor:Int,fallbackRank:Int):List<OfficeDismissalPolicy.Seat> =
        country?.let {OfficeDismissalPolicy(galaxy,countries).available(stocks,actor,it.value,rank(stocks,actor,fallbackRank))}
            ?: emptyList()
    fun role(card:Int)=country?.let {policy.role(card,it)}
    /** Open seats per card, counted against this country's holders only. */
    fun vacancies(stocks:PlanetStocks,available:Map<Int,List<Int>>):Map<Int,Int> = available.mapValues {(card,seats)->
        val definition=checkNotNull(role(card))
        seats.sumOf {seat->definition.seats-policy.holders(stocks,card,checkNotNull(country)).count {it.seat==seat}}.coerceAtMost(255)
    }
    /** The ship captain card shown first for every person, by the country's office family. */
    fun captainCard():Int {
        val family=country?.let {countries.countries[it.value]}?.let {countries.families[it.family]}
        return if(family?.offices=="alliance")195 else 59
    }
}
