package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class CompletenessSupplyTransactionTest {
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487, Power.ALLIANCE to 2487))
    private val pilot = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,
        baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun stocks() = ActiveShips.initialize(PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
        outfits=mapOf(1 to OutfitStock(1,2,OutfitKind.TRANSPORT,1049,101,10000,
            UnitInventory(mapOf(UnitStack(32) to 1)),supplies=1000),
            2 to OutfitStock(2,2,OutfitKind.FLEET,1049,102,10001,UnitInventory(mapOf(UnitStack(32) to 2)))),
        warehouses=mapOf(101 to OutfitWarehouse(101,2,OutfitKind.TRANSPORT,1049,1),
            102 to OutfitWarehouse(102,2,OutfitKind.FLEET,1049,2)),deploymentInitialized=true))
    private suspend fun supply(engine: WorldEngine): CompletenessSupplyResult {
        val result = CompletableDeferred<CompletenessSupplyResult>()
        engine.submit(WorldCommand.SupplyOutfit(CompletenessSupplyRequest("pilot","c",10000,Power.EMPIRE,1,1,2),result))
        return result.await()
    }

    @Test fun bothCargoBalancesAndCpCommitTogetherAndRemainAfterReopening() = runBlocking {
        for (fail in listOf(false,true)) {
            val root=directory.resolve("supply-$fail")
            val store=FileWorldStateStore(root)
            val initial=stocks();store.saveWorld(mapOf("pilot" to pilot),initial)
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            var saved: PlanetStocks?=null
            try {
                val joined=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"c",1))
                assertEquals(Admission.ACCEPTED,joined.await())
                val before=engine.snapshot()
                if(fail) {
                    store.beforeSwitch={throw java.io.IOException("supply commit failed")}
                    assertFailsWith<java.io.IOException> {supply(engine)}
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                    assertEquals(before.navigators,engine.snapshot().navigators)
                    assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                    assertFailsWith<IllegalStateException> {supply(engine)}
                } else {
                    val result=supply(engine);assertNull(result.rejection);assertEquals(1000,result.transferred)
                    assertEquals(840,result.player!!.mcp);saved=result.stocks
                    assertEquals(result.player,engine.snapshot().navigators["pilot"])
                    val replay=supply(engine);assertNull(replay.rejection);assertFalse(replay.changed)
                    assertEquals(saved,replay.stocks);assertEquals(840,replay.player!!.mcp)
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use { reopened ->
                assertEquals(if(fail)1000 else 840,reopened.loadNavigation().getValue("pilot").mcp)
                assertEquals(if(fail)initial else saved,reopened.loadStocks())
                assertEquals(if(fail)1000 else 0,reopened.loadStocks()!!.outfits.getValue(1).supplies)
                assertEquals(if(fail)0 else 1000,reopened.loadStocks()!!.outfits.getValue(2).supplies)
            }
        }
    }
}
