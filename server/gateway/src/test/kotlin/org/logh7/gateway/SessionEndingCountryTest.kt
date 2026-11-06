package org.logh7.gateway

import org.junit.jupiter.api.Test
import org.logh7.engine.*
import org.logh7.protocol.*
import kotlin.test.*

/** The ending report and the restored warp/dock captain card use the actual country's display code. Author: Peppone Choi. */
class SessionEndingCountryTest {
    // 42: empire office family displayed as the alliance (native 3). 43: alliance family displayed as the empire (native 2).
    private val catalog=CountryCatalogLoader.load().let {old->CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(
            CountryDefinition(42,"new_empire_country","New Empire","new_empire",3,0,"empire"),
            CountryDefinition(43,"new_alliance_country","New Alliance","alliance",2,0,"alliance")))}
    private val actor=CharacterMessages.Generate(4,10000,2,0,0,"Alpha","Pilot",18,1,1,1000001,
        listOf(60)+List(7){50},0,0,0,20,3,0,"AlphaShip",1)
    private fun player(country:CountryId,style:ShipStyleId=ShipStyleId.fromLegacy(Power.EMPIRE))=
        Navigator(1,10000,country,2487,840,920,1000,shipStyle=style)
    private fun world():WorldSnapshot {
        val end=SessionEnd(EndReason.TIME_LIMIT,setOf(Power.ALLIANCE),123)
        val value=SessionResults.assess(end,SessionStatistics(4,2,100_000_000_001,1010,66),mapOf(
            Power.EMPIRE to SessionStatistics(2,1,90_000_009_999,1000,33),
            Power.ALLIANCE to SessionStatistics(2,1,9_999_990_002,10,33)),VictoryConditions(true,true))
        return WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot().copy(ended=end),result=value)
    }
    private fun message(who:CharacterMessages.Generate,nav:Navigator,countries:CountryCatalog=catalog)=
        SessionEndingReport.message(world(),who,nav,listOf(who),emptyList(),countries=countries)

    @Test fun addedCountriesReceiveTheirEndingReportByDisplayCodeWithoutLegacyAuthority() {
        // 42 displays as 3 regardless of its empire office family or empire ship style; 43 displays as 2.
        assertEquals(0x035a,message(actor.copy(power=3),player(CountryId(42))).let {(it[4].toInt() shl 8 or (it[5].toInt() and 255))})
        assertEquals(0x035a,message(actor.copy(power=2),player(CountryId(43),ShipStyleId.fromLegacy(Power.ALLIANCE))).let {(it[4].toInt() shl 8 or (it[5].toInt() and 255))})
        // A record whose side contradicts the actual country is refused, not reinterpreted.
        assertFailsWith<IllegalArgumentException> {message(actor.copy(power=2),player(CountryId(42)))}
        assertFailsWith<IllegalArgumentException> {message(actor.copy(power=3),player(CountryId(43)))}
    }

    @Test fun removedUnaffiliatedAndOriginalCountriesKeepTheirExplicitRules() {
        assertFailsWith<IllegalArgumentException>("removed country") {
            message(actor.copy(power=3),player(CountryId(42)),CountryCatalogLoader.load())}
        // No membership: the ship style's display code decides, as the captain card rule does.
        assertEquals(0x035a,message(actor,Navigator(1,10000,null,2487,840,920,1000,shipStyle=ShipStyleId.fromLegacy(Power.EMPIRE)))
            .let {(it[4].toInt() shl 8 or (it[5].toInt() and 255))})
        for((power,side) in listOf(2 to Power.EMPIRE,3 to Power.ALLIANCE))
            assertEquals(0x035a,message(actor.copy(power=power),Navigator(1,10000,side,2487,840,920,1000))
                .let {(it[4].toInt() shl 8 or (it[5].toInt() and 255))})
        assertFailsWith<IllegalArgumentException> {message(actor.copy(power=3),Navigator(1,10000,Power.EMPIRE,2487,840,920,1000))}
    }

    @Test fun theRestoredCaptainCardFollowsTheSameDisplayRuleAndUnknownCountriesHaveNone() {
        // pendingNavigation() now echoes TacticalAuthority.captainCard instead of Navigator.power.
        assertEquals(195,TacticalAuthority.captainCard(player(CountryId(42)),catalog),"42 shows the alliance captain card")
        assertEquals(59,TacticalAuthority.captainCard(player(CountryId(43),ShipStyleId.fromLegacy(Power.ALLIANCE)),catalog))
        assertNull(TacticalAuthority.captainCard(player(CountryId(42)),CountryCatalogLoader.load()),"removed country: no card")
        assertEquals(59,TacticalAuthority.captainCard(Navigator(1,10000,Power.EMPIRE,2487,840,920,1000),catalog))
        assertEquals(195,TacticalAuthority.captainCard(Navigator(1,10000,Power.ALLIANCE,2487,840,920,1000),catalog))
    }

    @Test fun lobbyInformationCountsOnlyOriginalCountryBasesWithoutReadingOtherOwners() {
        val stocks=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,0,0,0),1177 to PlanetStock(1177,3,0,0,0,0),
            1050 to PlanetStock(1050,Allegiance.Country(CountryId(42)),0,0,0,0),1051 to PlanetStock(1051,2,1,0,0,0)))
        val info=SessionEndingReport.information(WorldSnapshot(0,0,SessionSimulation(SessionRules.load()).snapshot(),
            planetStocks=stocks),listOf(actor))
        assertEquals(listOf(1L,1L),info.powers.map {it.bases},"42's base is not read as either side; the rebel base is excluded")
    }
}
