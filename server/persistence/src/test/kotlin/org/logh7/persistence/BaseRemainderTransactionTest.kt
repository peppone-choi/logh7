package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

/** Assignment persists both physical stock balances and payment in one world switch. Author: Peppone Choi. */
class BaseRemainderTransactionTest {
    @TempDir lateinit var directory:Path
    private val rules=SessionRules.load()
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val stack=UnitStack(32,2)
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
        cpRecoveryAt=rules.startSeconds)
    private fun initial()=PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,1000,1000,1000)),
        mapOf(1 to OutfitStock(1,2,OutfitKind.FLEET,1049,101,10001)),
        mapOf(101 to OutfitWarehouse(101,2,OutfitKind.FLEET,1049,1,spareShips=SpareShipBoats(mapOf(stack to 123)))),
        mapOf(10000 to LogisticsOffice.EMPIRE_FIRST),deploymentInitialized=true)
    private fun request(n:Int)=StockAssignmentRequest("pilot","c",10000,Power.EMPIRE,1,1049,1,0,
        listOf(ReorganizationShip(32,n)),emptyList(),0)
    private suspend fun join(e:WorldEngine) {
        val result=CompletableDeferred<Admission>();e.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,result,"c",1))
        assertEquals(Admission.ACCEPTED,result.await())
    }
    private suspend fun act(e:WorldEngine,n:Int):StockAssignmentResult {
        val result=CompletableDeferred<StockAssignmentResult>();e.submit(WorldCommand.AssignStock(request(n),result))
        return result.await()
    }
    @Test fun returnAndReassignmentSurviveDiskReopenAndFailedSwitchNeverPublishesHalf() = runBlocking<Unit> {
        for(fail in listOf(false,true)) {
            val root=directory.resolve("world-$fail");val store=FileWorldStateStore(root)
            store.saveWorld(mapOf("pilot" to pilot),initial())
            val e=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            try {
                join(e)
                if(fail) {
                    val before=store.loadStocks();val players=store.loadNavigation()
                    store.beforeSwitch={throw java.io.IOException("disk")}
                    assertFailsWith<java.io.IOException> {act(e,-1)}
                    assertEquals(EngineHealth.STORAGE_FAILED,e.health())
                    assertEquals(before,store.loadStocks());assertEquals(players,store.loadNavigation())
                    assertEquals(before,e.snapshot().planetStocks)
                } else {
                    val result=act(e,-1);assertNull(result.rejection)
                    assertEquals(840,store.loadNavigation().getValue("pilot").mcp)
                    assertEquals(123,store.loadStocks()!!.bases.getValue(1049).spareShips.values[stack])
                    assertTrue(store.loadStocks()!!.warehouses.getValue(101).spareShips.values.isEmpty())
                }
            } finally {e.drainAndClose()}
            if(!fail) {
                val reopened=FileWorldStateStore(root)
                val next=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=reopened.navigationStore,
                    stockStore=reopened.stockStore,worldStateCommitter=reopened)
                try {
                    join(next);val result=act(next,1);assertNull(result.rejection)
                    assertEquals(680,result.player!!.mcp)
                    val saved=reopened.loadStocks()!!
                    assertTrue(saved.bases.getValue(1049).spareShips.values.isEmpty())
                    assertTrue(saved.warehouses.getValue(101).units.ships.isEmpty())
                    assertEquals(123,saved.warehouses.getValue(101).spareShips.values[stack])
                } finally {next.drainAndClose()}
            }
        }
    }
}
