package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class SupplementTransactionTest {
    @TempDir lateinit var directory: Path
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun stocks()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300,
        economy=PlanetEconomy(100000000,60000000,70,80))),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,UnitInventory(mapOf(UnitStack(32) to 1)),
            activeShips=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32),290))))),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,
            UnitInventory(mapOf(UnitStack(32) to 1),mapOf(UnitStack(3) to 100)),supplies=1000)),
        deploymentInitialized=true,nextShipId=2)
    private suspend fun supplement(engine: WorldEngine,mode: Int=1,supplies: Int=100): SupplementResult {
        val result=CompletableDeferred<SupplementResult>()
        engine.submit(WorldCommand.SupplementOutfit(SupplementRequest("pilot","c",10000,Power.EMPIRE,1,1049,1,0,mode,
            mapOf(32 to listOf(1)),supplies),result));return result.await()
    }
    @Test fun boatsCrewCargoAndCpAreOneCommitAndStorageFailureNeverPublishesHalf()=runBlocking {
        for(fail in listOf(false,true)) {
            val root=directory.resolve("supplement-$fail");val store=FileWorldStateStore(root)
            store.saveWorld(mapOf("pilot" to pilot),stocks())
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            var original: PlanetStocks?=null;var saved: PlanetStocks?=null
            try {
                val joined=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"c",1))
                assertEquals(Admission.ACCEPTED,joined.await());val before=engine.snapshot();original=before.planetStocks
                val preview=supplement(engine,mode=0);assertNull(preview.rejection);assertFalse(preview.changed)
                assertEquals(before.planetStocks,engine.snapshot().planetStocks);assertEquals(1000,preview.player!!.mcp)
                if(fail) {
                    store.beforeSwitch={throw java.io.IOException("supplement commit failed")}
                    assertFailsWith<java.io.IOException> {supplement(engine)}
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                    assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                } else {
                    val result=supplement(engine);assertNull(result.rejection);assertTrue(result.changed);saved=result.stocks
                    assertEquals(840,result.player!!.mcp);assertEquals(300,saved!!.outfits.getValue(1).activeShips!!.values.single().boats)
                    assertEquals(290,saved!!.warehouses.getValue(101).spareShips.values[UnitStack(32)])
                    val replay=supplement(engine,supplies=0);assertFalse(replay.changed);assertEquals(saved,replay.stocks)
                    assertEquals(840,replay.player!!.mcp)
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use { reopened ->
                assertEquals(if(fail)1000 else 840,reopened.loadNavigation().getValue("pilot").mcp)
                assertEquals(if(fail)original else saved,reopened.loadStocks())
            }
        }
    }
}
