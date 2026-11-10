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
        assertEquals(emptyList(), BlackHoleTides.losses(stocks, first, 5), "no loss before the first settled hour")
        assertEquals(emptyList(), BlackHoleTides.losses(first, later, 5), "an outfit without members tells no one")
        val led = { s: PlanetStocks -> s.copy(outfits = s.outfits + (1 to s.outfits.getValue(1).copy(commanderId = 10002))) }
        assertEquals(listOf(TidalLoss(1, 1, listOf(10002), 10, 7)),
            BlackHoleTides.losses(led(first), led(later), 7))
        val outside = led(first).let { s -> s.copy(outfits = s.outfits + (1 to s.outfits.getValue(1).copy(position = OutfitPosition(2487)))) }
        val arrived = led(first).let { s -> s.copy(outfits = s.outfits + (1 to BlackHoleTides.loss(s.outfits.getValue(1), BlackHoleTides.PULL_PERCENT))) }
        assertEquals(listOf(TidalLoss(1, 1, listOf(10002), 18, 9, pulled = true)), BlackHoleTides.losses(outside, arrived, 9, galaxy))
        assertEquals(emptyList(), BlackHoleTides.losses(outside, arrived, 9), "without the galaxy only tides count")
        val closed = BlackHoleTides.settle(galaxy, first, 1000 * 3600L)
        assertEquals(listOf(251, 251), boats(closed.outfits.getValue(1))) // at most 24 hours are applied
        val away = later.copy(outfits = later.outfits - 1, warehouses = later.warehouses - 10)
        assertEquals(0L, BlackHoleTides.settle(galaxy, away, 103 * 3600L).tidalHour)
        val untouched = away.copy(tidalHour = 0)
        assertSame(untouched, BlackHoleTides.settle(galaxy, untouched, 104 * 3600L))
    }
    @Test fun anAdjacentFleetWarpLosesBoatsOnlyWhenGravityPullsItOffCourse() {
        val base = GalaxyBase(1000, 2487, 80, 0, "Odin", 0, 20f, 86400, 0, 0f, 2f)
        val world = Galaxy(galaxy.systems, listOf(base), galaxy.starts, terrain = Galaxy.manualTerrain())
        val rules = SessionRules.load()
        val session = SessionSimulation(rules).also { it.join("pilot", Power.EMPIRE, false, "c") }.exportState()
        val origin = 3062
        val target = 3061
        // The same 100 ly route drifts on move 0 and lands on target on move 1.
        for ((moves, pulled) in listOf(0L to true, 1L to false)) {
            val pilot = Navigator(1, 10000, Power.EMPIRE, origin, 1000, 1000, 1000).copy(moves = moves)
            val fleet = outfit(1, origin).copy(commanderId = pilot.characterId)
            val initial = PlanetStocks(1, 0, mapOf(base.id to PlanetStock(base.id, 2, 0, 0, 0, 0)),
                outfits = mapOf(fleet.id to fleet), warehouses = mapOf(10 to OutfitWarehouse(10, 2, OutfitKind.FLEET, base.id, fleet.id)),
                deploymentInitialized = true, nextShipId = 3)
            val navigation = Navigation(world, object : NavigationStore {
                override fun load() = mapOf("pilot" to pilot)
                override fun save(players: Map<String, Navigator>) = error("expected a world save")
            }, NavigationPolicy())
            val production = PlanetProduction(world, object : PlanetStockStore {
                override fun load() = initial
                override fun save(stocks: PlanetStocks) = error("expected a world save")
            }, rules.startSeconds, PlanetProductionPolicy())
            var saved: PlanetStocks? = null
            val move = NavigationRequest("pilot", pilot.characterId, Power.EMPIRE, "c", 1,
                destination = target, unitId = pilot.unitId, captainCard = TacticalAuthority.captainCard(pilot))
            val result = FleetNavigation.apply(move, session, world, navigation, production, assertNotNull(production.snapshot(1)),
                WorldStateCommitter { _, stocks -> saved = stocks }) { false }

            assertNull(result.rejection)
            val arrived = assertNotNull(result.player)
            assertNull(arrived.warpTarget)
            assertEquals(900, arrived.cruise)
            assertEquals(960, arrived.mcp)
            assertEquals(pulled, arrived.grid != target)
            assertTrue(BlackHoleTides.inGravity(world, arrived.grid))
            val moved = assertNotNull(saved).outfits.getValue(fleet.id)
            assertEquals(OutfitPosition(arrived.grid), moved.position)
            assertEquals(List(2) { if (pulled) 291 else 300 }, boats(moved))
        }
    }

    @Test fun stillUnitsDriftToTheEdgeFacingTheBlackHoleWhereTidalLossDoubles() {
        val still = TacticalMotion(3061, 100, TacticalPose(TacticalPoint(0f, 0f), 1f), 0f, 1f, listOf(TacticalPoint(0f, 0f)))
        assertEquals(TacticalPoint(-250f, 0f), BlackHoleTides.edge(galaxy, 3061))
        assertNull(BlackHoleTides.edge(galaxy, 2487))
        val drift = assertNotNull(BlackHoleTides.drift(galaxy, still, 200))
        assertEquals(listOf(TacticalPoint(-250f, 0f)), drift.waypoints); assertEquals(0.05f, drift.velocity)
        assertEquals(1f, drift.direction); assertTrue(drift.parallel)
        assertEquals(-5f, drift.pose(300).position.x, 1e-3f)
        assertNull(BlackHoleTides.drift(galaxy, drift, 300))
        // A flagship drifts no faster than its own parallel-move limit, and not at all with no engine output.
        assertEquals(0.02f, BlackHoleTides.drift(galaxy, still, 200, 0.02f)!!.velocity)
        assertNull(BlackHoleTides.drift(galaxy, still, 200, 0f))
        assertNull(BlackHoleTides.drift(galaxy, still.copy(grid = 2487), 200))
        val held = TacticalMotion(3061, 100, TacticalPose(TacticalPoint(-250f, 0f), 1f), 0f, 1f, listOf(TacticalPoint(-250f, 0f)))
        assertNull(BlackHoleTides.drift(galaxy, held, 200))
        val o = outfit(1, 3061).let { it.copy(activeShips = ActiveShipUnits(it.activeShips!!.values.map { u ->
            if (u.id == 1) u.copy(tactical = NpcTacticalState(held, 100, 0)) else u.copy(tactical = NpcTacticalState(still, 100, 0)) })) }
        assertEquals(listOf(294, 297), boats(BlackHoleTides.loss(o, BlackHoleTides.TIDAL_PERCENT, galaxy, 200)))
        assertEquals(listOf(297, 297), boats(BlackHoleTides.loss(o, BlackHoleTides.TIDAL_PERCENT)))
    }
}
