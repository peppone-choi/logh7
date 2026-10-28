package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class GroundTransferTransactionTest {
    @TempDir lateinit var directory: Path
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun stocks():PlanetStocks {
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,UnitStack(32))))
        val troops=ActiveGroundUnits(listOf(ActiveGroundUnit(2,UnitStack(0,2),base=1049),ActiveGroundUnit(3,UnitStack(1),base=1049)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10000,UnitInventory(ships.quantities(),troops.quantities()),
                activeShips=ships,activeGround=troops)),mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1)),
            deploymentInitialized=true,nextShipId=4)
    }
    private suspend fun transfer(engine:WorldEngine,action:GroundTransferAction=GroundTransferAction.LOAD):GroundTransferResult {
        val result=CompletableDeferred<GroundTransferResult>()
        engine.submit(WorldCommand.TransferGround(GroundTransferRequest("pilot","c",10000,Power.EMPIRE,1,1,action),result))
        return result.await()
    }
    @Test fun troopLocationsAndCpCommitTogetherAndFailureKeepsBothBeforeReopen()=runBlocking {
        for(fail in listOf(false,true)) {
            val root=directory.resolve("transfer-$fail");val store=FileWorldStateStore(root)
            store.saveWorld(mapOf("pilot" to pilot),stocks())
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            var expected:PlanetStocks?=null
            try {
                val joined=CompletableDeferred<Admission>();engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"c",1))
                assertEquals(Admission.ACCEPTED,joined.await());val before=engine.snapshot();expected=before.planetStocks
                if(fail) {
                    store.beforeSwitch={throw java.io.IOException("ground transfer commit failed")}
                    assertFailsWith<java.io.IOException> {transfer(engine)}
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                    assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                } else {
                    val loaded=transfer(engine);assertNull(loaded.rejection);assertTrue(loaded.changed)
                    assertEquals(920,loaded.player!!.mcp)
                    assertTrue(loaded.stocks!!.outfits.getValue(1).activeGround!!.values.all {it.carrier==1 && it.base==0})
                    val retry=transfer(engine);assertFalse(retry.changed);assertEquals(920,retry.player!!.mcp)
                    expected=loaded.stocks
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use {reopened->
                assertEquals(if(fail)1000 else 920,reopened.loadNavigation().getValue("pilot").mcp)
                assertEquals(expected,reopened.loadStocks())
                if(!fail) {
                    val restarted=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=reopened.navigationStore,
                        stockStore=reopened.stockStore,worldStateCommitter=reopened)
                    try {
                        val joined=CompletableDeferred<Admission>();restarted.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"c",1))
                        assertEquals(Admission.ACCEPTED,joined.await())
                        val unloaded=transfer(restarted,GroundTransferAction.UNLOAD);assertNull(unloaded.rejection)
                        assertEquals(840,unloaded.player!!.mcp);expected=unloaded.stocks
                        assertTrue(expected!!.outfits.getValue(1).activeGround!!.values.all {it.base==1049 && it.carrier==0})
                        assertEquals(listOf(2,3),expected!!.outfits.getValue(1).activeGround!!.values.map {it.id})
                    } finally {restarted.drainAndClose()}
                }
            }
            if(!fail)FileWorldStateStore(root).use {final->
                assertEquals(840,final.loadNavigation().getValue("pilot").mcp)
                assertEquals(expected,final.loadStocks())
            }
        }
    }
}
