package org.logh7.engine

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Test
import kotlin.test.*

class BaseCountryOwnershipTest {
    @Test fun startupUsesTheCheckedSnapshotInsteadOfLoadingADifferentOwnerAfterRecovery() = runBlocking {
        val rules=SessionRules.load()
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        val before=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),deploymentInitialized=true)
        var loads=0
        val store=object:PlanetStockStore {
            override fun load():PlanetStocks {
                loads++
                return if(loads==1)before else before.copy(bases=mapOf(1049 to
                    before.bases.getValue(1049).copy(owner=Allegiance.Country(CountryId(42)))))
            }
            override fun save(stocks:PlanetStocks) {}
        }
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,stockStore=store)
        try {
            val result=CompletableDeferred<PlanetStocks>()
            engine.submit(WorldCommand.Stock(1,result))
            assertEquals(Allegiance.Country(CountryId(2)),result.await().bases.getValue(1049).owner)
            assertEquals(1,loads)
        } finally {engine.drainAndClose()}
    }
    @Test fun legacyNonStateValuesStayDistinctFromCountryIdsWithTheSameNumber() {
        for(code in 0..4) {
            val owner=Allegiance.fromLegacy(code)
            assertEquals(code,owner.legacyPower())
            assertEquals(if(code in 2..3)CountryId(code) else null,owner.country)
            for(camp in 0..1) {
                val base=PlanetStock(1000,code,camp,100,200,300)
                assertEquals(owner,base.owner)
                assertEquals(camp,base.camp)
            }
        }
        assertNotEquals<Allegiance>(Allegiance.Neutral,Allegiance.Country(CountryId(1)))
        assertNotEquals<Allegiance>(Allegiance.Pirates,Allegiance.Country(CountryId(4)))
        listOf(-1,5).forEach {assertFailsWith<IllegalArgumentException> {Allegiance.fromLegacy(it)}}
        val base=PlanetStock(1000,Allegiance.Country(CountryId(42)),0,100,200,300)
        assertTrue(base.belongsTo(CountryId(42)))
        assertFalse(base.belongsTo(CountryId(2)))
        assertFailsWith<IllegalStateException> {base.power}
    }

    @Test fun countryAppearanceIsAProjectionAndNeverChangesTheOwnerOrNeutralStatus() {
        val original=CountryCatalogLoader.load()
        val other=original.countries.getValue(2).copy(id=42,key="other_empire")
        val catalog=CountryCatalog(original.calendars.values.toList(),original.families.values.toList(),
            original.countries.values.toList()+other)
        val owner=Allegiance.Country(CountryId(42))
        assertEquals(2,catalog.nativePower(owner))
        assertEquals(CountryId(42),owner.country)
        assertEquals(1,catalog.nativePower(Allegiance.Neutral))
        assertEquals(4,catalog.nativePower(Allegiance.Pirates))
        assertEquals(0,catalog.nativePower(Allegiance.Unspecified))
        assertFailsWith<IllegalArgumentException> {catalog.nativePower(Allegiance.Country(CountryId(99)))}
    }

    @Test fun unsupportedBaseOwnerStopsBeforeSessionRecoveryOrNavigationCanLoadOrWrite() = runBlocking {
        val rules=SessionRules.load()
        var sessionLoads=0;var commits=0
        var sessionClosed=false;var stocksClosed=false;var navigationClosed=false
        val durability=object:SessionDurability {
            override fun load():DurableWorldState {sessionLoads++;error("Must not reach session recovery")}
            override fun commit(action:SessionAction,transition:SessionTransition) {commits++}
            override fun close() {sessionClosed=true}
        }
        val inventory=PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,Allegiance.Country(CountryId(42)),0,1,2,3)))
        val stockStore=object:PlanetStockStore {
            override fun load()=inventory
            override fun save(stocks:PlanetStocks) {commits++}
            override fun close() {stocksClosed=true}
        }
        val navigation=object:NavigationStore {
            override fun load():Map<String,Navigator> = error("Must not load navigation")
            override fun save(players:Map<String,Navigator>) {commits++}
            override fun close() {navigationClosed=true}
        }
        assertFailsWith<IllegalStateException> {WorldEngine(this,rules,ticking=false,durability=durability,
            stockStore=stockStore,navigationStore=navigation)}
        assertEquals(0,sessionLoads)
        assertEquals(0,commits)
        assertTrue(sessionClosed && stocksClosed && navigationClosed)
    }
}
