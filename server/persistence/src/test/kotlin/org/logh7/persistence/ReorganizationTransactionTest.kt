package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class ReorganizationTransactionTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun inventory() = PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c) ->
        PlanetStock(c.baseId,c.power,c.camp,100,200,300,PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace)) },
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
            UnitInventory(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 1),mapOf(UnitStack(3) to 10)))),deploymentInitialized=true)
    private fun query(units: Int = 2, crew: Int = 10, boats: Int = 0, id: Int = 10000) =
        ReorganizationRequest("pilot","c",id,Power.EMPIRE,1,1049,1,0,1,
            listOf(ReorganizationShip(32,units,boats)),listOf(ReorganizationTroop(1003,0,crew)))
    private suspend fun join(engine: WorldEngine) {
        val result = CompletableDeferred<Admission>(); engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,result,"c",1))
        assertEquals(Admission.ACCEPTED,result.await())
    }
    private suspend fun act(engine: WorldEngine, request: ReorganizationRequest = query()): ReorganizationResult {
        val result=CompletableDeferred<ReorganizationResult>(); engine.submit(WorldCommand.Reorganize(request,result)); return result.await()
    }
    @Test fun remainderStrengthAndRetiredIdentitySurviveDiskReopenAndReturn() = runBlocking<Unit> {
        val root=directory.resolve("partial-world")
        val store=FileWorldStateStore(root)
        val initial=inventory();val warehouse=initial.warehouses.getValue(101)
        store.saveWorld(mapOf("pilot" to pilot),initial.copy(warehouses=mapOf(101 to warehouse.copy(
            units=UnitInventory(troops=mapOf(UnitStack(3) to 10)),
            spareShips=SpareShipBoats(mapOf(UnitStack(32) to 123))))))
        var engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine);val result=act(engine,query(1,3));assertNull(result.rejection)
            assertEquals(123,store.loadStocks()!!.outfits.getValue(1).activeShips!!.values.single().boats)
        } finally {engine.drainAndClose()}
        val reopened=FileWorldStateStore(root)
        engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=reopened.navigationStore,
            stockStore=reopened.stockStore,worldStateCommitter=reopened)
        try {
            join(engine);val result=act(engine,query(-1,-3));assertNull(result.rejection)
            assertEquals(680,result.player!!.mcp)
            val saved=assertNotNull(reopened.loadStocks())
            assertTrue(saved.outfits.getValue(1).activeShips!!.values.isEmpty())
            assertTrue(saved.warehouses.getValue(101).units.ships.isEmpty())
            assertEquals(123,saved.warehouses.getValue(101).spareShips.values[UnitStack(32)])
            assertEquals(2,saved.nextShipId)
        } finally {engine.drainAndClose()}
    }
    @Test fun mixedShipLevelsCrewAndPaymentCommitTogetherAndSurviveReopen() = runBlocking<Unit> {
        val root=directory.resolve("world"); val store=FileWorldStateStore(root)
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            val unchanged=act(engine,ReorganizationRequest("pilot","c",10000,Power.EMPIRE,1,1049,1,0,1,emptyList(),emptyList()))
            assertNull(unchanged.rejection); assertFalse(unchanged.changed); assertEquals(1000,unchanged.player!!.mcp)
            val result=act(engine); assertNull(result.rejection); assertTrue(result.changed)
            assertEquals(840,result.player!!.mcp); assertEquals(1000,result.player!!.pcp)
            val stocks=result.stocks!!; val outfit=stocks.outfits.getValue(1)
            assertEquals(mapOf(UnitStack(32,0) to 1,UnitStack(32,1) to 1),outfit.units.ships)
            assertEquals(10,outfit.units.troops[UnitStack(3)]); assertTrue(stocks.warehouses.getValue(101).units.ships.isEmpty())
            assertEquals(setOf(1,2),outfit.activeShips!!.values.map { it.id }.toSet())
            assertEquals(stocks,store.loadStocks()); assertEquals(result.player,store.loadNavigation()["pilot"])
            val returned=act(engine,query(-2,-10)); assertNull(returned.rejection)
            assertEquals(680,returned.player!!.mcp); assertTrue(returned.stocks!!.outfits.getValue(1).units.ships.isEmpty())
            val again=act(engine); assertNull(again.rejection)
            assertEquals(setOf(3,4),again.stocks!!.outfits.getValue(1).activeShips!!.values.map { it.id }.toSet())
        } finally { engine.drainAndClose() }
        FileWorldStateStore(root).use {
            assertEquals(520,it.loadNavigation().getValue("pilot").mcp)
            assertEquals(setOf(3,4),it.loadStocks()!!.outfits.getValue(1).activeShips!!.values.map { it.id }.toSet())
        }
    }
    @Test fun invalidCrewOverdrawPartialBoatsAndIdentityDoNotMoveUnitsOrPay() = runBlocking<Unit> {
        val store=FileWorldStateStore(directory.resolve("world")); store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            assertEquals(LogisticsRejection.CREW,act(engine,query(crew=9)).rejection)
            val before=engine.snapshot()
            assertEquals(LogisticsRejection.STOCK,act(engine,query(units=3)).rejection)
            assertEquals(LogisticsRejection.STOCK,act(engine,query(boats=1)).rejection)
            assertEquals(LogisticsRejection.IDENTITY,act(engine,query(id=10001)).rejection)
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertNull(act(engine).rejection) // Failed commands released their reservations.
        } finally { engine.drainAndClose() }
    }
    @Test fun commitFailurePublishesNeitherPaymentNorUnitsAndBlocksWrites() = runBlocking<Unit> {
        val store=FileWorldStateStore(directory.resolve("world")); store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            assertEquals(LogisticsRejection.STOCK,act(engine,query(boats=1)).rejection)
            val before=engine.snapshot(); store.beforeSwitch={ throw java.io.IOException("disk") }
            assertFailsWith<java.io.IOException> { act(engine) }
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(before.navigators,engine.snapshot().navigators); assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertTrue(store.loadStocks()!!.outfits.getValue(1).units.ships.isEmpty())
            assertFailsWith<IllegalStateException> { act(engine) }
        } finally { engine.drainAndClose() }
    }
    @Test fun cpCannotBeMixedAndFullAlternativePaymentUsesPcp320() = runBlocking<Unit> {
        for ((index,balance) in listOf(319 to 159,320 to 0).withIndex()) {
            val store=FileWorldStateStore(directory.resolve("cp-$index"))
            store.saveWorld(mapOf("pilot" to pilot.copy(pcp=balance.first,mcp=balance.second)),inventory())
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            try {
                join(engine)
                val result=act(engine)
                if (index == 0) {
                    assertEquals(LogisticsRejection.CP,result.rejection)
                    assertEquals(319,result.player!!.pcp); assertEquals(159,result.player!!.mcp)
                    assertTrue(result.stocks!!.outfits.getValue(1).units.ships.isEmpty())
                    assertEquals(1,result.stocks!!.nextShipId)
                } else {
                    assertNull(result.rejection); assertEquals(0,result.player!!.pcp); assertEquals(0,result.player!!.mcp)
                    assertEquals(2,result.stocks!!.outfits.getValue(1).activeShips!!.values.size)
                }
            } finally { engine.drainAndClose() }
        }
    }
    @Test fun missingCommonCommitterRejectsWithoutPaymentOrInventoryChange() = runBlocking<Unit> {
        val store=FileWorldStateStore(directory.resolve("world")); store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,stockStore=store.stockStore)
        try {
            join(engine)
            val result=act(engine); assertEquals(LogisticsRejection.STORAGE,result.rejection)
            assertEquals(1000,result.player!!.mcp); assertTrue(result.stocks!!.outfits.getValue(1).units.ships.isEmpty())
            assertEquals(1,result.stocks!!.nextShipId)
        } finally { engine.drainAndClose() }
    }
}
