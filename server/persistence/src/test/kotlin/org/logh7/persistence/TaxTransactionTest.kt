package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class TaxTransactionTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun inventory() = PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c) ->
        PlanetStock(c.baseId,c.power,c.camp,100,200,300,
            PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace),governorId=10000) },deploymentInitialized=true)
    private val query = TaxRequest("pilot","c",10000,Power.EMPIRE,1,1049,35,54)
    private suspend fun join(engine: WorldEngine) {
        val result = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,result,"c",1))
        assertEquals(Admission.ACCEPTED,result.await())
    }
    private suspend fun tax(engine: WorldEngine, q: TaxRequest = query): TaxResult {
        val result = CompletableDeferred<TaxResult>(); engine.submit(WorldCommand.Tax(q,result)); return result.await()
    }
    @Test fun actorPublishesPaidSettingAfterOneCommitAndReopeningKeepsIt() = runBlocking {
        val root = directory.resolve("world")
        val store = FileWorldStateStore(root)
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            val changed = tax(engine)
            assertNull(changed.rejection); assertTrue(changed.changed)
            assertEquals(1000,engine.snapshot().navigators.getValue("pilot").pcp)
            assertEquals(840,engine.snapshot().navigators.getValue("pilot").mcp)
            assertEquals(35,engine.snapshot().planetStocks!!.bases.getValue(1049).taxRate)
            assertEquals(changed.player,store.loadNavigation()["pilot"])
            assertEquals(changed.stocks,store.loadStocks())
            val repeated = tax(engine)
            assertNull(repeated.rejection); assertFalse(repeated.changed)
            assertEquals(1000,repeated.player!!.pcp); assertEquals(840,repeated.player!!.mcp)
        } finally { engine.drainAndClose() }
        FileWorldStateStore(root).use {
            assertEquals(1000,it.loadNavigation().getValue("pilot").pcp)
            assertEquals(840,it.loadNavigation().getValue("pilot").mcp)
            assertEquals(35,it.loadStocks()!!.bases.getValue(1049).taxRate)
            assertEquals(10000,it.loadStocks()!!.bases.getValue(1049).governorId)
        }
    }
    @Test fun failedCommitPublishesNeitherPaymentNorRateAndBlocksFurtherCommands() = runBlocking {
        val root = directory.resolve("world")
        val store = FileWorldStateStore(root)
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            assertNull(tax(engine,query.copy(rate=20)).rejection)
            val before = engine.snapshot()
            store.beforeSwitch = { throw java.io.IOException("commit failed") }
            assertFailsWith<java.io.IOException> { tax(engine) }
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(1000,store.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertNull(store.loadStocks()!!.bases.getValue(1049).taxRate)
            assertFailsWith<IllegalStateException> { tax(engine) }
        } finally { engine.drainAndClose() }
        FileWorldStateStore(root).use {
            assertEquals(1000,it.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,it.loadNavigation().getValue("pilot").mcp)
            assertNull(it.loadStocks()!!.bases.getValue(1049).taxRate)
        }
    }
    @Test fun missingPairedCommitterRejectsWithoutPaymentOrSettingChange() = runBlocking {
        val store = FileWorldStateStore(directory.resolve("world"))
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,
            navigationStore=store.navigationStore,stockStore=store.stockStore)
        try {
            join(engine)
            val result = tax(engine)
            assertEquals(TaxRejection.STORAGE,result.rejection)
            assertEquals(1000,result.player!!.pcp)
            assertEquals(1000,result.player!!.mcp)
            assertNull(result.stocks!!.bases.getValue(1049).taxRate)
            assertEquals(1000,store.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertNull(store.loadStocks()!!.bases.getValue(1049).taxRate)
        } finally { engine.drainAndClose() }
    }
    @Test fun oldSixImportsWithoutGrantingOfficeAndSevenValidatesAdministrativeFields() {
        val path = directory.resolve("stocks")
        val old = "LOGH7-STOCK-6\t1\t0\t1\t0\t0\t0\t1\t1\nP\t1049\t2\t0\t100\t200\t300\t-\t-\t-\t-\t;;\n"
        java.nio.file.Files.writeString(path,old)
        FilePlanetStockStore(path).use {
            assertEquals(0,it.load()!!.bases.getValue(1049).governorId)
            assertNull(it.load()!!.bases.getValue(1049).taxRate)
            it.save(inventory().copy(bases=inventory().bases.mapValues { (_,s) -> s.copy(taxRate=35) }))
        }
        FilePlanetStockStore(path).use { assertEquals(35,it.load()!!.bases.getValue(1049).taxRate) }
        val valid = java.nio.file.Files.readString(path)
        for (bad in listOf(valid.replace("\t35\t10000","\t101\t10000"),valid.replace("\t35\t10000","\t35\t-1"))) {
            java.nio.file.Files.writeString(path,bad)
            assertFails { FilePlanetStockStore(path).use { it.load() } }
            assertEquals(bad,java.nio.file.Files.readString(path))
        }
    }
}
