package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class CountryTacticalDeploymentTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Here",2)),listOf(
        GalaxyBase(1000,2487,80,0,"Base",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val catalog=CountryCatalogLoader.load().let {old -> CountryCatalog(old.calendars.values.toList(),
        old.families.values.toList(),old.countries.values.toList()+listOf(42,43).map {id ->
            old.countries.getValue(2).copy(id=id,key="country_$id",name="Country $id")})}
    private fun player(id:Int,country:Int?)=Navigator(1,id,Power.EMPIRE,2487,1000,1000,1000,cpPaused=true)
        .copy(country=country?.let(::CountryId))
    private fun stocks():PlanetStocks {
        val outfits=listOf(42,43).mapIndexed {index,country ->
            val ships=ActiveShipUnits(listOf(ActiveShipUnit(index+1,UnitStack(32))))
            index+1 to OutfitStock(index+1,CountryId(country),OutfitKind.FLEET,1000,101+index,
                units=UnitInventory(ships.quantities()),activeShips=ships,position=OutfitPosition(2487),shipStyle=ShipStyleId("empire"))
        }.toMap()
        return PlanetStocks(1,0,mapOf(1000 to PlanetStock(1000,2,0,0,0,0)),outfits,
            outfits.values.associate {o -> o.warehouseId to OutfitWarehouse(o.warehouseId,o.country,
                OutfitKind.FLEET,1000,o.id,shipStyle=o.shipStyle)},deploymentInitialized=true,nextShipId=3)
    }

    @Test fun countriesWithTheSameDisplayHaveSeparateEdgesAndShareOnlyTheirOwnUnitColumn() {
        val players=listOf(player(10000,42),player(10001,43))
        val stocks=stocks()
        fun at(unit:Int,people:List<Navigator> = players,s:PlanetStocks=stocks)=
            TacticalDeployment.initial(unit,2487,people,s,galaxy,catalog)
        assertTrue(at(1).position.x>0);assertTrue(at(2).position.x<0)
        assertEquals(at(1).position.x,at(players[0].unitId).position.x)
        assertEquals(at(2).position.x,at(players[1].unitId).position.x)
        assertEquals(10f,at(players[0].unitId).position.y-at(1).position.y)
        assertEquals(10f,at(players[1].unitId).position.y-at(2).position.y)
        assertEquals(at(1),at(1,players.reversed(),stocks.copy(outfits=stocks.outfits.toList().reversed().toMap())))
        assertFailsWith<NoSuchElementException> {at(99)}
    }

    @Test fun independentCharactersNeverMergeIntoOneCountryOrEachOthersCamp() {
        val players=listOf(player(10000,null),player(10001,null))
        val first=TacticalDeployment.initial(players[0].unitId,2487,players,null,galaxy,catalog)
        val second=TacticalDeployment.initial(players[1].unitId,2487,players,null,galaxy,catalog)
        assertTrue(first.position.x>0 && second.position.x<0)
        assertEquals(first.position.y,second.position.y)
    }

    @Test fun changingShipStyleDoesNotChangeACountrysCampOrBorrowItsNeighborsColumn() {
        val players=listOf(player(10000,42),player(10001,43))
        val before=TacticalDeployment.initial(players[0].unitId,2487,players,null,galaxy,catalog)
        val changed=players.map {it.copy(shipStyle=ShipStyleId("alliance"))}
        assertEquals(before,TacticalDeployment.initial(changed[0].unitId,2487,changed,null,galaxy,catalog))
        val projected=CountryCatalog(catalog.calendars.values.toList(),catalog.families.values.toList(),
            catalog.countries.values.map {if(it.id==42)it.copy(nativePower=3) else it})
        assertTrue(TacticalDeployment.initial(players[0].unitId,2487,players,null,galaxy,projected).position.x<0)
        assertEquals(CountryId(42),players[0].country)
    }
}
