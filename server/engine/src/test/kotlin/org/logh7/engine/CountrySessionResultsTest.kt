package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountrySessionResultsTest {
    private val a=CountryId(42); private val b=CountryId(43); private val c=CountryId(44)
    private val catalog=CountryCatalogLoader.load().let {seed->CountryCatalog(seed.calendars.values.toList(),seed.families.values.toList(),
        seed.countries.values.toList()+listOf(seed.countries.getValue(2).copy(id=42,key="a",name="A",shipStyleOverride="alliance"),
            seed.countries.getValue(3).copy(id=43,key="b",name="B",nativePower=2),
            seed.countries.getValue(2).copy(id=44,key="c",name="C",nativePower=3)))}
    private val end=SessionEnd(EndReason.CAPITAL_CAPTURED,emptySet(),123,setOf(b,c),setOf(a))
    private fun facts(leader:Boolean?=true)=VictoryConditions(countries=mapOf(a to CountryVictoryConditions(true,leader),
        b to CountryVictoryConditions(true),c to CountryVictoryConditions(true,true)))
    private fun assess(ships:Long=110,conditions:VictoryConditions=facts())=SessionResults.assessCountries(end,
        SessionStatistics(population=100),mapOf(a to SessionStatistics(population=90,shipUnits=ships),
            b to SessionStatistics(population=5,shipUnits=6),c to SessionStatistics(population=5,shipUnits=5)),conditions,catalog)

    @Test fun fleetDominanceUsesEveryOtherCountryAndConfiguredOfficeRulesIgnoreModels() {
        val small=assess(100)
        assertFalse(small.countries.getValue(a).fleetDominance)
        assertEquals(SessionGrade.LIMITED,small.countries.getValue(a).grade)
        assertEquals(SessionGrade.LIMITED,assess(conditions=facts(false)).countries.getValue(a).grade)
        val full=assess()
        assertEquals(SessionGrade.DECISIVE,full.countries.getValue(a).grade)
        assertEquals(true,full.countries.getValue(a).leaderRequired)
        assertEquals(false,full.countries.getValue(b).leaderRequired)
        assertEquals(SessionGrade.DEFEAT,full.countries.getValue(b).grade)
        assertEquals(SessionGrade.DEFEAT,full.countries.getValue(c).grade)
        assertFailsWith<IllegalArgumentException> {full.powers}
    }

    @Test fun missingPoliticalFactsDoNotBorrowAnotherCountrysFactsOrGrantDecisiveVictory() {
        val legacy=VictoryConditions(true,true)
        assertEquals(SessionGrade.UNRESOLVED,assess(conditions=legacy).countries.getValue(a).grade)
        assertEquals(SessionGrade.UNRESOLVED,assess(conditions=facts(null)).countries.getValue(a).grade)
        assertEquals(SessionGrade.UNRESOLVED,assess(conditions=VictoryConditions(countries=mapOf(
            CountryId(2) to CountryVictoryConditions(true,true)))).countries.getValue(a).grade)
    }

    @Test fun eliminatedCountryWithMostPopulationCannotWinAtTheTimeLimitAndMultiCountryTiesStayUnresolved() {
        val timeout=SessionEnd(EndReason.TIME_LIMIT,emptySet(),123,emptySet(),setOf(a,c))
        val result=SessionResults.assessCountries(timeout,SessionStatistics(population=100),mapOf(
            a to SessionStatistics(population=6),b to SessionStatistics(population=90),c to SessionStatistics(population=4)),catalog=catalog)
        assertEquals(SessionGrade.LOCAL,result.countries.getValue(a).grade)
        assertEquals(SessionGrade.DEFEAT,result.countries.getValue(b).grade)
        val tied=SessionResults.assessCountries(timeout,SessionStatistics(population=100),mapOf(
            a to SessionStatistics(population=5),b to SessionStatistics(population=90),c to SessionStatistics(population=5)),catalog=catalog)
        assertEquals(SessionGrade.UNRESOLVED,tied.countries.getValue(a).grade)
        assertEquals(SessionGrade.UNRESOLVED,tied.countries.getValue(c).grade)
        assertEquals(SessionGrade.DEFEAT,tied.countries.getValue(b).grade)
    }

    @Test fun defeatedCountriesWithNoAssetsKeepZeroRowsAndCollectionsAreDetached() {
        val input=mutableMapOf(a to CountryVictoryConditions(true,true))
        val result=SessionResults.assessCountries(end,SessionStatistics(population=1),mapOf(a to SessionStatistics(population=1,shipUnits=1)),
            VictoryConditions(countries=input),catalog)
        input.clear()
        assertEquals(setOf(a,b,c),result.countries.keys)
        assertEquals(SessionStatistics(),result.countries.getValue(b).statistics)
        assertEquals(true,result.conditions.noCoup(a))
        assertFailsWith<UnsupportedOperationException> {(result.countries as MutableMap).clear()}
    }

    private val galaxy=Galaxy((0..2).map {GalaxySystem(3+it,2487+it,4,"S$it",2)},
        (0..2).map {GalaxyBase(1000+it,2487+it,80,0,"Renamed $it",0,20f,86400,0,0f,2f)},Power.entries.associateWith {2487})
    private fun stocks()=PlanetStocks(1,0,galaxy.bases.associate {base->base.id to PlanetStock(base.id,Allegiance.Country(a),0,0,0,0,
        PlanetEconomy(10,0,50,50))},strategic=StrategicState(capitals=mapOf(a to 1000,b to 1001,c to 1002),
        offices=listOf(OfficeHolding(2,1000,10000,a))))
    private fun player(country:CountryId=a,generation:Long=1)=Navigator(generation,10000,country,2489,0,0,0,shipStyle=ShipStyleId("alliance"))

    @Test fun leaderConditionUsesActualCountryActiveOfficeRegisteredCapitalAndGeneration() {
        assertEquals(true,SessionResults.imperialLeaderAtEnemyCapital(stocks(),listOf(player()),a,galaxy,catalog))
        assertNull(SessionResults.imperialLeaderAtEnemyCapital(stocks(),emptyList(),a,galaxy,catalog))
        assertNull(SessionResults.imperialLeaderAtEnemyCapital(stocks(),listOf(player(b)),a,galaxy,catalog))
        assertNull(SessionResults.imperialLeaderAtEnemyCapital(stocks(),listOf(player(generation=2)),a,galaxy,catalog))
        val foreign=stocks().copy(strategic=stocks().strategic.copy(offices=listOf(OfficeHolding(2,1002,10000,c))))
        assertEquals(false,SessionResults.imperialLeaderAtEnemyCapital(foreign,listOf(player()),a,galaxy,catalog))
        val wrongSeat=stocks().copy(strategic=stocks().strategic.copy(offices=listOf(OfficeHolding(2,1001,10000,a))))
        assertEquals(false,SessionResults.imperialLeaderAtEnemyCapital(wrongSeat,listOf(player()),a,galaxy,catalog))
        val dead=stocks().copy(strategic=stocks().strategic.copy(people=mapOf(10000 to StrategicPerson(deceasedAt=1))))
        assertEquals(false,SessionResults.imperialLeaderAtEnemyCapital(dead,listOf(player()),a,galaxy,catalog))
    }

    @Test fun worldResultKeepsEliminatedRowsAndDoesNotInventCoupFactsForAnUnmigratedCountry() {
        val session=SessionSimulation(SessionRules.load()).snapshot().copy(ended=end,countries=setOf(a,b,c))
        val result=SessionResults.fromWorld(galaxy,WorldSnapshot(0,0,session,navigators=mapOf("offline" to player()),planetStocks=stocks()),catalog=catalog)
        assertEquals(setOf(a,b,c),result.countries.keys)
        assertEquals(SessionStatistics(),result.countries.getValue(b).statistics)
        assertEquals(30,result.total.population)
        assertNull(result.conditions.noCoup(a))
        assertEquals(true,result.conditions.leader(a))
        assertEquals(SessionGrade.UNRESOLVED,result.countries.getValue(a).grade)
    }
}
