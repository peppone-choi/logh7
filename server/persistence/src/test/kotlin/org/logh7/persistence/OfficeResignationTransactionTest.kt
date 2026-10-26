package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class OfficeResignationTransactionTest {
    @TempDir lateinit var directory:Path
    private val rules=SessionRules.load()
    private val base=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base,base.copy(id=1048,name="Other")),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpRecoveryAt=rules.startSeconds)
    private fun stock(card:Int):PlanetStocks {
        val ordinary=PlanetStock(1048,2,0,100,200,300,governorId=if(card==54)10000 else 10002)
        val capital=PlanetStock(1049,2,0,100,200,300,guardInspectorId=if(card==57)10000 else 10003)
        return PlanetStocks(1,0,mapOf(1048 to ordinary,1049 to capital),deploymentInitialized=true,
            nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to if(card==17)10000 else 10004))
    }
    private suspend fun resign(engine:WorldEngine,card:Int):OfficeResignationResult {
        val result=CompletableDeferred<OfficeResignationResult>()
        engine.submit(WorldCommand.ResignOffice(OfficeResignationRequest("pilot","c",10000,Power.EMPIRE,1,card),result))
        return result.await()
    }
    @Test fun paymentAndEveryStoredOfficeFamilyPublishTogetherOrNotAtAll()=runBlocking {
        for(card in listOf(54,57,17)) for(fail in listOf(false,true)) {
            val root=directory.resolve("$card-$fail");val store=FileWorldStateStore(root)
            val initial=stock(card);store.saveWorld(mapOf("pilot" to pilot),initial)
            val engine=WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            var saved:PlanetStocks?=null
            try {
                val joined=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,joined,"c",1));assertEquals(Admission.ACCEPTED,joined.await())
                val before=engine.snapshot()
                if(fail) {
                    store.beforeSwitch={throw java.io.IOException("commit failed")}
                    assertFailsWith<java.io.IOException> {resign(engine,card)}
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                    assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                    assertFailsWith<IllegalStateException> {resign(engine,card)}
                } else {
                    val result=resign(engine,card);assertNull(result.rejection);assertTrue(result.changed)
                    assertEquals(920,result.player!!.mcp);saved=result.stocks
                    assertEquals(result.player,engine.snapshot().navigators["pilot"]);assertEquals(saved,store.loadStocks())
                    val replay=resign(engine,card);assertEquals(ResignationRejection.AUTHORITY,replay.rejection)
                    assertEquals(920,replay.player!!.mcp);assertEquals(saved,replay.stocks)
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use {reopened->
                assertEquals(if(fail)1000 else 920,reopened.loadNavigation().getValue("pilot").mcp)
                assertEquals(if(fail)initial else saved,reopened.loadStocks())
                if(!fail) {
                    val actual=reopened.loadStocks()!!
                    assertEquals(if(card==54)0 else 10002,actual.bases.getValue(1048).governorId)
                    assertEquals(if(card==57)0 else 10003,actual.bases.getValue(1049).guardInspectorId)
                    assertEquals(if(card==17)null else 10004,actual.nationalOffices[NationalOffice.MILITARY_MINISTER])
                }
            }
        }
    }
}
