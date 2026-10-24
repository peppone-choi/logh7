package org.logh7.engine

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class PlanetProductionTest {
    private val galaxy = Galaxy(listOf(GalaxySystem(3, 2487, 4, "Home", 2), GalaxySystem(4, 2015, 4, "Neutral", 0)),
        listOf(GalaxyBase(1000, 2487, 60, 0, "Home", 0, 20f, 86400, 0, 0f, 2f),
            GalaxyBase(1001, 2015, 61, 0, "Neutral", 0, 20f, 86400, 0, 0f, 2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2015))
    private class Store : PlanetStockStore {
        var saved: PlanetStocks? = null
        var writes = 0
        var fail = false
        override fun load() = saved
        override fun save(stocks: PlanetStocks) {
            if (fail) throw java.io.IOException("disk failure")
            writes++; saved = stocks
        }
    }
    @Test fun oneDayBoundaryOfflineCatchupReopeningAndRepeatedQueriesNeverDuplicateProduction() {
        val store = Store()
        val production = PlanetProduction(galaxy, store, 0, PlanetProductionPolicy())
        val start = production.settle(1, 0)
        assertEquals(start, production.settle(1, 86399))
        assertEquals(101000, production.settle(1, 86400).bases.getValue(1000).supplies)
        val offline = production.settle(1, 5 * 86400L + 1)
        assertEquals(105000, offline.bases.getValue(1000).supplies)
        assertEquals(100000, offline.bases.getValue(1001).supplies)
        assertEquals(start.bases.getValue(1000).food, offline.bases.getValue(1000).food)
        val reopened = PlanetProduction(galaxy, store, 0, PlanetProductionPolicy())
        assertEquals(offline, reopened.settle(1, 5 * 86400L + 1))
        assertEquals(offline, reopened.settle(1, 4 * 86400L))
        assertEquals(3, store.writes)
        assertEquals(106000, reopened.settle(1, 6 * 86400L).bases.getValue(1000).supplies)
        assertFailsWith<UnsupportedOperationException> { (offline.bases as MutableMap).clear() }
    }
    @Test fun failedStorageDoesNotPublishAndNewSessionResetsOnlyOnce() {
        val store = Store()
        val production = PlanetProduction(galaxy, store, 0, PlanetProductionPolicy(maximumStock = 100100))
        val start = production.settle(1, 0)
        store.fail = true
        assertFailsWith<java.io.IOException> { production.settle(1, 86400) }
        assertEquals(start, production.snapshot(1))
        store.fail = false
        assertEquals(100100, production.settle(1, 100 * 86400L).bases.getValue(1000).supplies)
        val fresh = production.settle(2, 0)
        assertEquals(100000, fresh.bases.getValue(1000).supplies)
        assertFailsWith<IllegalArgumentException> { production.settle(1, 86400) }
        assertEquals(fresh, production.settle(2, 0))
        assertFailsWith<IllegalArgumentException> { PlanetProduction(galaxy, store, 0, PlanetProductionPolicy(), generation = 1) }
    }
    @Test fun actorSerializesMultipleReadersAndLatchesStorageFailure() = runBlocking {
        val store = Store()
        val engine = WorldEngine(this, ticking = false, galaxy = galaxy, stockStore = store)
        suspend fun stock(generation: Long = 1): PlanetStocks {
            val result = CompletableDeferred<PlanetStocks>()
            engine.submit(WorldCommand.Stock(generation, result)); return result.await()
        }
        val start = stock()
        engine.submit(WorldCommand.AdvanceTo(3600000))
        val results = coroutineScope { List(20) { async { stock() } }.awaitAll() }
        assertTrue(results.all { it.bases.getValue(1000).supplies == 101000 })
        assertEquals(2, store.writes)
        assertFailsWith<IllegalArgumentException> { stock(2) }
        store.fail = true
        engine.submit(WorldCommand.AdvanceTo(7200000))
        assertFailsWith<java.io.IOException> { stock() }
        assertEquals(101000, engine.snapshot().planetStocks!!.bases.getValue(1000).supplies)
        assertEquals(EngineHealth.STORAGE_FAILED, engine.health())
        assertFailsWith<IllegalStateException> { stock() }
        assertEquals(100000, start.bases.getValue(1000).supplies)
        engine.drainAndClose()
    }
}
