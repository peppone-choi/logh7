package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountrySessionStatisticsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),(1000..1002).map {id->
        GalaxyBase(id,2487,80,if(id==1001)1 else 0,"Base $id",0,20f,86400,0,0f,2f)
    },Power.entries.associateWith {2487})

    @Test fun identicalModelsNeverMergeCountriesAndIndependentFlagshipsCountOnlyInTheTotal() {
        val bases=(1000..1002).associate {id->id to PlanetStock(id,when(id) {
            1000->Allegiance.Country(CountryId(42));1001->Allegiance.Country(CountryId(43));else->Allegiance.Neutral
        },0,0,0,0,PlanetEconomy(if(id==1001)20000 else 10000,5000,50,50))}
        val outfits=listOf(42,43).mapIndexed {i,id->
            val ships=ActiveShipUnits(listOf(ActiveShipUnit(i+1,UnitStack(32))))
            i+1 to OutfitStock(i+1,CountryId(id),OutfitKind.FLEET,1000,10+i,
                units=UnitInventory(ships.quantities()),activeShips=ships,shipStyle=ShipStyleId("empire"))
        }.toMap()
        val warehouses=outfits.values.associate {o->o.warehouseId to OutfitWarehouse(o.warehouseId,o.country,
            OutfitKind.FLEET,1000,o.id,units=if(o.country==CountryId(42))UnitInventory(ships=mapOf(UnitStack(32) to 2),
                troops=mapOf(UnitStack(0) to 3)) else UnitInventory(),shipStyle=ShipStyleId("empire"))}
        val stock=PlanetStocks(1,0,bases,outfits,warehouses,nextShipId=3)
        fun player(id:Int,country:CountryId?,generation:Long=1)=Navigator(generation,id,country,2487,1000,1000,1000,
            shipStyle=ShipStyleId("empire"))
        val players=mapOf("a" to player(10000,CountryId(42)),"b" to player(10001,CountryId(43)),
            "independent" to player(10002,null),"another-generation" to player(10003,CountryId(43),2))
        val session=SessionSimulation(SessionRules.load()).snapshot()
        val snapshot=WorldSnapshot(0,0,session,navigators=players,planetStocks=stock)
        val result=SessionResults.statistics(galaxy,snapshot)
        assertEquals(setOf(CountryId(42),CountryId(43)),result.countries.keys)
        assertEquals(SessionStatistics(1,0,10000,4,3),result.countries[CountryId(42)])
        assertEquals(SessionStatistics(0,1,20000,2,0),result.countries[CountryId(43)])
        assertEquals(SessionStatistics(2,1,40000,7,3),result.total)
        assertFailsWith<UnsupportedOperationException>{(result.countries as MutableMap).clear()}
    }
}
