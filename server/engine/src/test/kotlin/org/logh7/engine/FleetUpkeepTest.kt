package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlin.test.*

/** Daily fleet upkeep from the national treasury (FleetUpkeep). Author: Peppone Choi. */
class FleetUpkeepTest {
    private fun outfit(id: Int, power: Int, vararg boats: Int) = OutfitStock(id, power, OutfitKind.FLEET, 1000, id,
        units = UnitInventory(mapOf(UnitStack(89) to boats.size)), activeShips = ActiveShipUnits(boats.mapIndexed { i, b -> ActiveShipUnit(id * 100 + i + 1, UnitStack(89), boats = b) }))
    private val full = UnitSpecifications.ships.single { it.kind == 89 }.number
    private val half = full / 2
    private val stocks = PlanetStocks(1, 0, mapOf(1000 to PlanetStock(1000, 2, 0, 100, 100, 100)),
        mapOf(1 to outfit(1, 2, full, half, 0), 2 to outfit(2, 3, full)),
        mapOf(1 to OutfitWarehouse(1, 2, OutfitKind.FLEET, 1000, 1), 2 to OutfitWarehouse(2, 3, OutfitKind.FLEET, 1000, 2)),
        nextShipId = 1000)

    @Test fun onlyLivingShipsOfEachSideAreCharged() {
        assertEquals((full + half).toLong(), FleetUpkeep.ships(stocks, 2))
        assertEquals(full.toLong(), FleetUpkeep.ships(stocks, 3))
        assertEquals((full + half) * 800L, FleetUpkeep.due(stocks, 2, 800))
        assertEquals(0, FleetUpkeep.due(stocks, 2, 0))
    }

    @Test fun aShortTreasuryPaysWhatItHoldsAndKeepsNoDebt() {
        val treasuries = mutableMapOf(2 to 100_000_000L, 3 to 100L)
        FleetUpkeep.pay(stocks, treasuries, 800)
        assertEquals(100_000_000L - (full + half) * 800L, treasuries[2])
        assertEquals(0L, treasuries[3])
        FleetUpkeep.pay(stocks, treasuries, 800)
        assertEquals(0L, treasuries[3], "never negative, no arrears")
    }

    @Test fun blackHoleLossesGiveTheSameBillWhetherSettledHourlyOrAtOnce() {
        val home = GalaxyBase(1000, 2487, 80, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)), listOf(home), Power.entries.associateWith { 2487 },
            terrain = Galaxy.manualTerrain())
        assertTrue(BlackHoleTides.inGravity(galaxy, 3059))
        val start = SessionRules.load().startSeconds
        // A unit large enough for the 1%/hour tide to remove whole ships (integer losses).
        val big = UnitSpecifications.ships.filter { it.number >= 100 }.maxBy { it.number }
        val caughtFleet = OutfitStock(1, 2, OutfitKind.FLEET, 1000, 1, units = UnitInventory(mapOf(UnitStack(big.kind) to 2)),
            activeShips = ActiveShipUnits(listOf(ActiveShipUnit(101, UnitStack(big.kind)), ActiveShipUnit(102, UnitStack(big.kind)))),
            position = OutfitPosition(3059))
        val initial = stocks.copy(outfits = stocks.outfits + (1 to caughtFleet), deploymentInitialized = true,
            tidalHour = maxOf(1L, start / BlackHoleTides.HOUR), // 0 means "no watermark yet".
            nationalTreasuries = mapOf(2 to 1_000_000_000L, 3 to 1_000_000_000L))
        fun run(times: List<Long>): PlanetStocks {
            var saved: PlanetStocks? = initial
            val store = object : PlanetStockStore {
                override fun load() = saved
                override fun save(stocks: PlanetStocks) { saved = stocks }
            }
            val production = PlanetProduction(galaxy, store, start, PlanetProductionPolicy())
            return times.map { production.settle(1, it) }.last()
        }
        val hourly = run((1..24).map { start + it * BlackHoleTides.HOUR })
        val atOnce = run(listOf(start + 86400))
        assertTrue(FleetUpkeep.ships(atOnce, 2) < FleetUpkeep.ships(initial, 2), "the tide removed ships during the day")
        assertEquals(hourly.outfits, atOnce.outfits)
        assertEquals(hourly.nationalTreasuries, atOnce.nationalTreasuries, "the bill does not depend on settlement frequency")
    }

    @Test fun dailySettlementChargesOncePerDayAfterThatDaysIncome() {
        val home = GalaxyBase(1000, 2487, 80, 0, "Home", 0, 20f, 86400, 0, 0f, 2f)
        val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2)), listOf(home), Power.entries.associateWith { 2487 })
        val start = SessionRules.load().startSeconds
        val opening = 1_000_000_000L
        val initial = stocks.copy(deploymentInitialized = true, nationalTreasuries = mapOf(2 to opening, 3 to opening))
        var saved: PlanetStocks? = initial
        val store = object : PlanetStockStore {
            override fun load() = saved
            override fun save(stocks: PlanetStocks) { saved = stocks }
        }
        fun production(perShip: Long) = PlanetProduction(galaxy, store, start, PlanetProductionPolicy(fleetUpkeepPerShip = perShip))
        val free = production(0).settle(1, start + 86400)
        val income = free.nationalTreasuries.getValue(2) - opening
        // Same day replayed with upkeep enabled from the same opening state.
        saved = initial
        val charged = production(800).settle(1, start + 86400)
        assertEquals(opening + income - (full + half) * 800L, charged.nationalTreasuries[2])
        assertEquals(opening + (free.nationalTreasuries.getValue(3) - opening) - full * 800L, charged.nationalTreasuries[3])
        // Re-settling the same day (restart or repeated query) never charges again.
        assertEquals(charged.nationalTreasuries, production(800).settle(1, start + 86400).nationalTreasuries)
        // Two more days: exactly three days of upkeep in total, compared with the same days settled without it.
        val third = production(800).settle(1, start + 3 * 86400)
        saved = initial
        val freeThird = production(0).settle(1, start + 3 * 86400)
        assertEquals(3 * (full + half) * 800L, freeThird.nationalTreasuries.getValue(2) - third.nationalTreasuries.getValue(2))
    }
}
