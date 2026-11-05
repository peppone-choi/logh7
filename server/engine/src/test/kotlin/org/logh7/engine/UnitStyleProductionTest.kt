package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

class UnitStyleProductionTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"ヴァルハラ",2)),listOf(
        GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2487})
    private val policy=PlanetProductionPolicy()
    private fun producer(countries:CountryCatalog)=UnitProduction(galaxy,policy.units,policy.economy,policy.maximumStock,countries)
    private fun stock()=PlanetStock(1049,2,0,1_000_000,10_000_000,1_000_000,
        PlanetEconomy(1_000_000,600_000,70,80),UnitInventory(ships=mapOf(UnitStack(32) to 2),
            troops=mapOf(UnitStack(3) to 7),work=mapOf("S32" to ProductionUnits.find(2,"戦艦Ⅰ").hours-1,
                "T3" to ProductionUnits.find(2,"艦隊乗組員").hours-1)))

    @Test fun aStyleChangeFinishesOnlyThePendingOldBatchAndPreservesInventoryAcrossASecondChange() {
        val swapped=producer(countriesWithSwappedShipStyles())
        val before=stock()
        var next=swapped.advance(before,before)
        assertEquals(CountryId(2),next.country);assertEquals(CountryId(2),next.productionCountry)
        assertEquals(3,next.units.ships[UnitStack(32)])
        assertEquals(12,next.units.troops[UnitStack(3)])
        assertFalse("S32" in next.units.work || "T3" in next.units.work)
        assertEquals(24,next.units.work["S119"])
        repeat(4) {next=swapped.advance(next,next)}
        assertEquals(3,next.units.ships[UnitStack(32)]);assertEquals(12,next.units.troops[UnitStack(3)])
        val allianceBuilt=next.units.ships[UnitStack(119)] ?: 0
        assertTrue(allianceBuilt>0)
        val normal=producer(CountryCatalogLoader.load())
        repeat(12) {next=normal.advance(next,next)}
        assertEquals(allianceBuilt+1,next.units.ships[UnitStack(119)],"finish the old Alliance batch once")
        assertFalse("S119" in next.units.work)
        assertEquals(CountryId(2),next.productionCountry)
        assertTrue(next.units.ships.getValue(UnitStack(32))>3)
    }

    @Test fun anotherActualOwnerCannotUseProgressEvenWhenItsModelsMatch() {
        val before=stock().copy(productionCountry=CountryId(42))
        val next=producer(CountryCatalogLoader.load()).advance(before,before)
        assertEquals(2,next.units.ships[UnitStack(32)])
        assertEquals(7,next.units.troops[UnitStack(3)])
        assertEquals(24,next.units.work["S32"])
        assertEquals(CountryId(2),next.productionCountry)
    }

    @Test fun aPausedYardKeepsTheStartedOldModelWithoutFinishingIt() {
        val before=stock()
        val next=producer(countriesWithSwappedShipStyles()).advance(before,before,shipyardOpen=false)
        assertEquals(before.units.ships,next.units.ships)
        assertEquals(before.units.work["S32"],next.units.work["S32"])
        assertEquals(CountryId(2),next.productionCountry)
    }

    @Test fun allianceOwnershipMayStartEmpireModelsWithoutChangingTheOwnerOrErasingItsOldOrder() {
        val home=Galaxy(listOf(GalaxySystem(3,2015,4,"バーラト",3)),listOf(
            GalaxyBase(2015,2015,80,0,"ハイネセン",0,20f,86400,0,0f,2f)),Power.entries.associateWith {2015})
        val before=stock().copy(baseId=2015,owner=Allegiance.Country(CountryId(3)),
            units=UnitInventory(work=mapOf("S119" to ProductionUnits.find(3,"戦艦Ⅰ").hours-1)))
        val next=UnitProduction(home,policy.units,policy.economy,policy.maximumStock,countriesWithSwappedShipStyles()).advance(before,before)
        assertEquals(CountryId(3),next.country);assertEquals(CountryId(3),next.productionCountry)
        assertEquals(1,next.units.ships[UnitStack(119)])
        assertEquals(24,next.units.work["S32"]);assertFalse("S119" in next.units.work)
    }
}
