package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** 블랙홀 중력권(체비쇼프 1) 조석·끌림 손실. 작성: Peppone Choi. */
class BlackHoleTidesTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Odin", 2), GalaxySystem(4, 2015, 4, "Heinessen", 3)),
        emptyList(), mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015), terrain = Galaxy.manualTerrain())
    private val stack = UnitStack(32)
    private fun outfit(id: Int, grid: Int) = OutfitStock(id, 2, OutfitKind.FLEET, 1000, 9 + id,
        units = UnitInventory(mapOf(stack to 2)), activeShips = ActiveShipUnits(listOf(ActiveShipUnit(2 * id - 1, stack), ActiveShipUnit(2 * id, stack))),
        position = OutfitPosition(grid))
    private fun boats(o: OutfitStock) = o.activeShips!!.values.map { it.boats }

    @Test fun onlyTheEightNeighboursOfABlackHoleAreTheGravityZone() {
        assertTrue(BlackHoleTides.inGravity(galaxy, 3061)); assertTrue(BlackHoleTides.inGravity(galaxy, 3161))
        assertFalse(BlackHoleTides.inGravity(galaxy, 3060)); assertFalse(BlackHoleTides.inGravity(galaxy, 3062))
        assertFalse(BlackHoleTides.inGravity(galaxy, 2487))
    }
    @Test fun lossIsAFlooredPercentOfEachUnitAndSmallUnitsAreSpared() {
        val pulled = BlackHoleTides.loss(outfit(1, 3061), BlackHoleTides.PULL_PERCENT)
        assertEquals(listOf(291, 291), boats(pulled))
        val small = outfit(1, 3061).let { TacticalLosses.apply(it, 1, 210) }
        assertEquals(listOf(90, 297), boats(BlackHoleTides.loss(small, BlackHoleTides.TIDAL_PERCENT)))
    }
    @Test fun eachGameHourInTheZoneCostsOnePercentWithoutBacklogBeforeTheFirstSettlement() {
        val stocks = PlanetStocks(1, 0, mapOf(1000 to PlanetStock(1000, 2, 0, 100, 200, 300)),
            outfits = mapOf(1 to outfit(1, 3061), 2 to outfit(2, 2487)),
            warehouses = mapOf(10 to OutfitWarehouse(10, 2, OutfitKind.FLEET, 1000, 1), 11 to OutfitWarehouse(11, 2, OutfitKind.FLEET, 1000, 2)),
            nextShipId = 5)
        val first = BlackHoleTides.settle(galaxy, stocks, 100 * 3600L + 5)
        assertEquals(100L, first.tidalHour); assertEquals(stocks.outfits, first.outfits)
        assertSame(first, BlackHoleTides.settle(galaxy, first, 100 * 3600L + 3599))
        val later = BlackHoleTides.settle(galaxy, first, 102 * 3600L)
        assertEquals(102L, later.tidalHour)
        assertEquals(listOf(295, 295), boats(later.outfits.getValue(1)))
        assertEquals(stocks.outfits.getValue(2), later.outfits.getValue(2))
        val closed = BlackHoleTides.settle(galaxy, first, 1000 * 3600L)
        assertEquals(listOf(251, 251), boats(closed.outfits.getValue(1))) // at most 24 hours are applied
        val away = later.copy(outfits = later.outfits - 1, warehouses = later.warehouses - 10)
        assertEquals(0L, BlackHoleTides.settle(galaxy, away, 103 * 3600L).tidalHour)
        val untouched = away.copy(tidalHour = 0)
        assertSame(untouched, BlackHoleTides.settle(galaxy, untouched, 104 * 3600L))
    }
}
