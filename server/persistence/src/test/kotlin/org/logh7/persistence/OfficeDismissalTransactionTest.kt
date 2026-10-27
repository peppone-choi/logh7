package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class OfficeDismissalTransactionTest {
    @TempDir lateinit var directory:Path
    private val rules=SessionRules.load()
    private val base=GalaxyBase(1049,2487,80,0,"オーディン",0,20f,86400,0,0f,2f)
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(base,base.copy(id=1048,name="Other")),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,cpRecoveryAt=rules.startSeconds)
    private fun stock(card:Int):PlanetStocks {
        val ordinary=PlanetStock(1048,2,0,100,200,300,governorId=10000,garrisonCommanderId=if(card==55)10001 else 10003)
        val capital=PlanetStock(1049,2,0,100,200,300,guardInspectorId=if(card==57)10001 else 10004)
        return PlanetStocks(1,0,mapOf(1048 to ordinary,1049 to capital),deploymentInitialized=true,
            nationalOffices=mapOf(NationalOffice.MILITARY_MINISTER to 10000),
            outfits=mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,commanderId=10000,
                staff=FleetStaff(officers=if(card==42)listOf(10001,10002) else listOf(10002)))),
            warehouses=mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)))
    }
    private suspend fun dismiss(engine:WorldEngine,card:Int):OfficeDismissalResult {
        val result=CompletableDeferred<OfficeDismissalResult>()
        engine.submit(WorldCommand.DismissOffice(OfficeDismissalRequest("pilot","c",10000,Power.EMPIRE,1,10001,card,2,2),result))
        return result.await()
    }
    @Test fun paymentAndEveryStoredOfficeFamilyPublishTogetherOrNotAtAll()=runBlocking {
        for(card in listOf(55,57,42)) for(fail in listOf(false,true)) {
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
                    assertFailsWith<java.io.IOException> {dismiss(engine,card)}
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                    assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                    assertFailsWith<IllegalStateException> {dismiss(engine,card)}
                } else {
                    val result=dismiss(engine,card);assertNull(result.rejection);assertTrue(result.changed)
                    assertEquals(840,result.player!!.mcp);saved=result.stocks
                    assertEquals(result.player,engine.snapshot().navigators["pilot"]);assertEquals(saved,store.loadStocks())
                    val replay=dismiss(engine,card);assertEquals(DismissalRejection.AUTHORITY,replay.rejection)
                    assertEquals(840,replay.player!!.mcp);assertEquals(saved,replay.stocks)
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use {reopened->
                assertEquals(if(fail)1000 else 840,reopened.loadNavigation().getValue("pilot").mcp)
                assertEquals(if(fail)initial else saved,reopened.loadStocks())
                if(!fail) {
                    val actual=reopened.loadStocks()!!
                    assertEquals(10000,actual.bases.getValue(1048).governorId)
                    assertEquals(if(card==55)0 else 10003,actual.bases.getValue(1048).garrisonCommanderId)
                    assertEquals(if(card==57)0 else 10004,actual.bases.getValue(1049).guardInspectorId)
                    assertEquals(10000,actual.nationalOffices[NationalOffice.MILITARY_MINISTER])
                    assertEquals(listOf(10002),actual.outfits.getValue(200).staff.officers)
                    assertEquals(10000,actual.outfits.getValue(200).commanderId)
                }
            }
        }
    }
}
