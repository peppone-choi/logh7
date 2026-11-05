package org.logh7.engine

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.*

class OutfitCountryTest {
    private val base=PlanetStock(1000,2,0,100,200,300)
    private val fleet=OutfitStock(1,2,OutfitKind.FLEET,1000,1)
    private val warehouse=OutfitWarehouse(1,2,OutfitKind.FLEET,1000,1)
    private fun state(outfit:OutfitStock=fleet,depot:OutfitWarehouse=warehouse)=
        PlanetStocks(1,0,mapOf(1000 to base),mapOf(outfit.id to outfit),mapOf(depot.id to depot))

    @Test fun identicalStylesNeverLetAnotherCountryUseTheFleetsWarehouse() {
        for(id in listOf(1,4,42)) {
            val foreign=fleet.copy(country=CountryId(id))
            assertEquals(ShipStyleId("empire"),foreign.shipStyle)
            assertFailsWith<IllegalStateException> {foreign.power}
            assertFailsWith<IllegalArgumentException> {state(foreign)}
            val own=warehouse.copy(country=CountryId(id))
            assertEquals(CountryId(id),state(foreign,own).outfits.getValue(1).country)
            assertFailsWith<IllegalArgumentException> {state(foreign,own.copy(shipStyle=ShipStyleId("alliance")))}
        }
    }

    @Test fun sharedWarehousesAreGroupedByActualCountryAndModelStyle() {
        val patrol=fleet.copy(kind=OutfitKind.PATROL)
        val depot=warehouse.copy(kind=OutfitKind.PATROL,outfitId=0)
        val foreign=patrol.copy(id=2,country=CountryId(42),warehouseId=2)
        val otherStyle=patrol.copy(id=3,shipStyle=ShipStyleId("alliance"),warehouseId=3)
        val stocks=PlanetStocks(1,0,mapOf(1000 to base),mapOf(1 to patrol,2 to foreign,3 to otherStyle),
            mapOf(1 to depot,2 to depot.copy(id=2,country=CountryId(42)),
                3 to depot.copy(id=3,shipStyle=ShipStyleId("alliance"))))
        assertEquals(3,stocks.warehouses.size)
        assertFailsWith<IllegalArgumentException> {stocks.copy(outfits=stocks.outfits+(2 to foreign.copy(warehouseId=1)))}
    }

    @Test fun guardModelRulesUseStyleWithoutChangingTheOwner() {
        val option=GuardShips.options.first {it.power==2}
        val stack=UnitStack(option.baseKind)
        val guarded=fleet.copy(country=CountryId(42),kind=OutfitKind.PATROL,
            units=UnitInventory(ships=mapOf(stack to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(101,stack))),guard=mapOf(101 to option.index))
        assertEquals(CountryId(42),guarded.country)
        assertFailsWith<IllegalArgumentException> {guarded.copy(shipStyle=ShipStyleId("alliance"))}
    }

    @Test fun upkeepBillsOnlyTheActualOwnerEvenWhenMultipleCountriesUseEmpireShips() {
        val option=GuardShips.options.first {it.power==2}
        val stack=UnitStack(option.baseKind)
        val first=fleet.copy(units=UnitInventory(ships=mapOf(stack to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(101,stack,boats=200))))
        val second=first.copy(id=2,country=CountryId(42),warehouseId=2,
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(102,stack,boats=100))))
        val stocks=PlanetStocks(1,0,mapOf(1000 to base),mapOf(1 to first,2 to second),
            mapOf(1 to warehouse,2 to warehouse.copy(id=2,country=CountryId(42),outfitId=2)),nextShipId=103)
        val accounts=mutableMapOf(CountryId(2) to 500000L,CountryId(42) to 800000L,CountryId(3) to 77L)
        FleetUpkeep.pay(stocks,accounts,10)
        assertEquals(498000L,accounts[CountryId(2)])
        assertEquals(799000L,accounts[CountryId(42)])
        assertEquals(77L,accounts[CountryId(3)])
    }

    @Test fun unsupportedOutfitOwnersStopBeforeNavigationOrRecoveryAndReleaseEveryStore()=runBlocking {
        val stocks=state(fleet.copy(country=CountryId(42)),warehouse.copy(country=CountryId(42)))
        val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
            listOf(GalaxyBase(1000,2487,80,0,"Home",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
        var recovery=0;var writes=0;var closed=0
        val session=object:SessionDurability {
            override fun load():DurableWorldState {recovery++;error("No recovery may begin")}
            override fun commit(action:SessionAction,transition:SessionTransition) {writes++}
            override fun close() {closed++}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=stocks
            override fun save(stocks:PlanetStocks) {writes++}
            override fun close() {closed++}
        }
        val navigation=object:NavigationStore {
            override fun load():Map<String,Navigator> = error("Must reject before navigation load")
            override fun save(players:Map<String,Navigator>) {writes++}
            override fun close() {closed++}
        }
        assertFailsWith<IllegalStateException> {WorldEngine(this,ticking=false,galaxy=galaxy,
            durability=session,stockStore=inventory,navigationStore=navigation)}
        assertEquals(0,recovery);assertEquals(0,writes);assertEquals(3,closed)
    }
}
