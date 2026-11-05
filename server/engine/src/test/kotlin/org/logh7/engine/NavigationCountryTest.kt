package org.logh7.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class NavigationCountryTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,800,700,1000,baseId=1049,mode=4)
    private val stock=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),deploymentInitialized=true)

    @Test fun countryAndAbsenceOfMembershipDoNotChangeTheShipsActualStyle() {
        val hull=FlagshipHull(Flagships.forPower(2).first().kind,0)
        val ship=pilot.copy(hull=hull)
        for(country in listOf(CountryId(42),null)) {
            val moved=ship.copy(country=country)
            assertEquals(country,moved.country)
            assertEquals(ShipStyleId("empire"),moved.shipStyle)
            assertEquals(hull,moved.hull)
            assertFailsWith<IllegalStateException> {moved.power}
        }
        val alliance=pilot.copy(shipStyle=ShipStyleId("alliance"),
            hull=FlagshipHull(Flagships.forPower(3).first().kind,0))
        assertEquals(CountryId(2),alliance.country)
        assertEquals(Power.EMPIRE,alliance.power)
        assertFailsWith<IllegalArgumentException> {ship.copy(shipStyle=ShipStyleId("alliance"))}
    }

    @Test fun anotherCountryWithTheSameStyleCannotUseTheExistingNavigatorsIdentity() {
        var saves=0
        val other=pilot.copy(country=CountryId(42))
        val store=object:NavigationStore {
            override fun load()=mapOf("pilot" to other)
            override fun save(players:Map<String,Navigator>) {saves++}
        }
        val navigation=Navigation(galaxy,store,NavigationPolicy())
        val session=SessionSimulation(SessionRules.load()).also {it.join("pilot",Power.EMPIRE,false,"c")}.exportState()
        val result=navigation.apply(NavigationRequest("pilot",10000,Power.EMPIRE,"c",1),session)
        assertEquals(NavigationRejection.IDENTITY,result.rejection)
        assertEquals(other,navigation.snapshot(1).getValue("pilot"))
        assertEquals(0,saves)
    }

    @Test fun unsupportedMembershipOrStyleStopsBeforeSessionRecoveryAndClosesAllStores()=runBlocking {
        for(player in listOf(pilot.copy(country=CountryId(42)),pilot.copy(country=null),
            pilot.copy(shipStyle=ShipStyleId("alliance")),pilot.copy(shipStyle=ShipStyleId("custom_style")))) {
            var loads=0;var commits=0;var closed=0
            val session=object:SessionDurability {
                override fun load():DurableWorldState {loads++;error("No recovery may begin")}
                override fun commit(action:SessionAction,transition:SessionTransition) {commits++}
                override fun close() {closed++}
            }
            val inventory=object:PlanetStockStore {
                override fun load()=stock
                override fun save(stocks:PlanetStocks) {commits++}
                override fun close() {closed++}
            }
            val navigation=object:NavigationStore {
                override fun load()=mapOf("pilot" to player)
                override fun save(players:Map<String,Navigator>) {commits++}
                override fun close() {closed++}
            }
            assertFailsWith<IllegalStateException> {WorldEngine(this,ticking=false,galaxy=galaxy,
                durability=session,stockStore=inventory,navigationStore=navigation)}
            assertEquals(0,loads);assertEquals(0,commits);assertEquals(3,closed)
        }
    }

    @Test fun theActorUsesTheNavigationSnapshotThatPassedTheStartupGuard()=runBlocking {
        var loads=0
        val navigation=object:NavigationStore {
            override fun load():Map<String,Navigator> {
                loads++
                return mapOf("pilot" to if(loads==1)pilot else pilot.copy(country=CountryId(42)))
            }
            override fun save(players:Map<String,Navigator>) {}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stock
            override fun save(stocks:PlanetStocks) {}
        }
        val engine=WorldEngine(this,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory)
        try {
            engine.synchronizeTime()
            assertEquals(CountryId(2),engine.snapshot().navigators.getValue("pilot").country)
            assertEquals(1,loads)
        } finally {engine.drainAndClose()}
    }

    @Test fun theCatalogStyleDoesNotDependOnTheCountrysIdentityOrNativeDisplayCode() {
        val original=CountryCatalogLoader.load()
        val country=original.countries.getValue(2).copy(id=42,key="other_empire")
        val families=original.families.values.map {if(it.key=="empire")it.copy(shipStyle="alliance") else it}
        val catalog=CountryCatalog(original.calendars.values.toList(),families,original.countries.values.toList()+country)
        assertEquals(ShipStyleId("alliance"),catalog.shipStyle(CountryId(42)))
        assertEquals(2,catalog.countries.getValue(42).nativePower)
        assertFailsWith<IllegalArgumentException> {catalog.shipStyle(CountryId(99))}
    }
}
