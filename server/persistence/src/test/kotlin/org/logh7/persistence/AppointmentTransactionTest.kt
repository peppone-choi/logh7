package org.logh7.persistence

import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.logh7.engine.*
import java.nio.file.Path
import kotlin.test.*

class AppointmentTransactionTest {
    @Test fun capitalAppointmentsCommitTheirAuthorityPaymentAndOfficeTogetherOrKeepAllThree()=runBlocking {
        for(rule in CapitalAppointmentPolicy.rules) for(fail in listOf(false,true)) {
            val base=if(rule.power==2)1049 else 1177
            val power=if(rule.power==2)Power.EMPIRE else Power.ALLIANCE
            val g=Galaxy(listOf(GalaxySystem(3,rule.capital.grid,4,"Home",rule.power)),
                listOf(GalaxyBase(base,rule.capital.grid,80,0,rule.capital.baseName,0,20f,86400,0,0f,2f)),
                mapOf(Power.EMPIRE to rule.capital.grid,Power.ALLIANCE to rule.capital.grid))
            val root=directory.resolve("capital-${rule.card}-$fail")
            val store=FileWorldStateStore(root)
            val initial=PlanetStocks(1,0,mapOf(base to PlanetStock(base,rule.power,0,100,200,300)),
                deploymentInitialized=true,nationalOffices=mapOf(rule.authority to 10000))
            store.saveWorld(mapOf("pilot" to pilot.copy(power=power,grid=rule.capital.grid,baseId=base)),initial)
            val engine=WorldEngine(this,rules,ticking=false,galaxy=g,navigationStore=store.navigationStore,
                stockStore=store.stockStore,worldStateCommitter=store)
            try {
                val joined=CompletableDeferred<Admission>()
                engine.submit(WorldCommand.Join("pilot",power,false,joined,"c",1));assertEquals(Admission.ACCEPTED,joined.await())
                val request=query.copy(power=power,base=base,targetCard=rule.card,targetPower=rule.power,
                    actorRank=when(rule.card){192->1;193->5;else->2},targetRank=rule.lowest)
                val before=engine.snapshot()
                if(fail) {
                    store.beforeSwitch={throw java.io.IOException("capital appointment commit failed")}
                    assertFailsWith<java.io.IOException> {appoint(engine,request)}
                    assertEquals(before.navigators,engine.snapshot().navigators)
                    assertEquals(before.planetStocks,engine.snapshot().planetStocks)
                    assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
                } else {
                    assertNull(appoint(engine,request).rejection)
                    assertEquals(840,engine.snapshot().navigators.getValue("pilot").mcp)
                    assertEquals(10001,rule.holder(engine.snapshot().planetStocks!!.bases.getValue(base)))
                    assertFalse(appoint(engine,request).changed)
                }
            } finally {engine.drainAndClose()}
            FileWorldStateStore(root).use {
                assertEquals(if(fail)1000 else 840,it.loadNavigation().getValue("pilot").mcp)
                assertEquals(if(fail)0 else 10001,rule.holder(it.loadStocks()!!.bases.getValue(base)))
                assertEquals(initial.nationalOffices,it.loadStocks()!!.nationalOffices)
            }
        }
    }
    @TempDir lateinit var directory: Path
    private val rules = SessionRules.load()
    private val galaxy = Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),
        listOf(GalaxyBase(1049,2487,80,0,"Home",0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val pilot = Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,cpRecoveryAt=rules.startSeconds)
    private fun inventory() = PlanetStocks(1,0,galaxy.conditions.mapValues { (_,c) ->
        PlanetStock(c.baseId,c.power,c.camp,100,200,300,
            PlanetEconomy(c.population,c.adultPopulation,c.approval,c.peace),governorId=10000) },deploymentInitialized=true)
    private val query = GarrisonAppointmentRequest("pilot","c",10000,Power.EMPIRE,1,1049,10001,55,7,10,2)
    private suspend fun join(engine: WorldEngine) {
        val result = CompletableDeferred<Admission>()
        engine.submit(WorldCommand.Join("pilot",Power.EMPIRE,false,result,"c",1))
        assertEquals(Admission.ACCEPTED,result.await())
    }
    private suspend fun appoint(engine: WorldEngine, q: GarrisonAppointmentRequest = query): GarrisonAppointmentResult {
        val result = CompletableDeferred<GarrisonAppointmentResult>(); engine.submit(WorldCommand.AppointGarrison(q,result)); return result.await()
    }
    @Test fun actorPublishesPaidAppointmentAfterOneCommitAndReopeningKeepsIt() = runBlocking {
        val root = directory.resolve("world")
        val store = FileWorldStateStore(root)
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            val changed = appoint(engine)
            assertNull(changed.rejection); assertTrue(changed.changed)
            assertEquals(1000,engine.snapshot().navigators.getValue("pilot").pcp)
            assertEquals(840,engine.snapshot().navigators.getValue("pilot").mcp)
            assertEquals(10001,engine.snapshot().planetStocks!!.bases.getValue(1049).garrisonCommanderId)
            assertEquals(changed.player,store.loadNavigation()["pilot"])
            assertEquals(changed.stocks,store.loadStocks())
            val repeated = appoint(engine)
            assertNull(repeated.rejection); assertFalse(repeated.changed)
            assertEquals(1000,repeated.player!!.pcp); assertEquals(840,repeated.player!!.mcp)
        } finally { engine.drainAndClose() }
        FileWorldStateStore(root).use {
            assertEquals(1000,it.loadNavigation().getValue("pilot").pcp)
            assertEquals(840,it.loadNavigation().getValue("pilot").mcp)
            assertEquals(10001,it.loadStocks()!!.bases.getValue(1049).garrisonCommanderId)
            assertEquals(10000,it.loadStocks()!!.bases.getValue(1049).governorId)
        }
    }
    @Test fun failedCommitPublishesNeitherPaymentNorAppointmentAndBlocksFurtherCommands() = runBlocking {
        val root = directory.resolve("world")
        val store = FileWorldStateStore(root)
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,navigationStore=store.navigationStore,
            stockStore=store.stockStore,worldStateCommitter=store)
        try {
            join(engine)
            assertEquals(AppointmentRejection.RANK,appoint(engine,query.copy(targetRank=20)).rejection)
            val before = engine.snapshot()
            store.beforeSwitch = { throw java.io.IOException("commit failed") }
            assertFailsWith<java.io.IOException> { appoint(engine) }
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(before.navigators,engine.snapshot().navigators)
            assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(1000,store.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertEquals(0,store.loadStocks()!!.bases.getValue(1049).garrisonCommanderId)
            assertFailsWith<IllegalStateException> { appoint(engine) }
        } finally { engine.drainAndClose() }
        FileWorldStateStore(root).use {
            assertEquals(1000,it.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,it.loadNavigation().getValue("pilot").mcp)
            assertEquals(0,it.loadStocks()!!.bases.getValue(1049).garrisonCommanderId)
        }
    }
    @Test fun missingPairedCommitterRejectsWithoutPaymentOrAppointmentChange() = runBlocking {
        val store = FileWorldStateStore(directory.resolve("world"))
        store.saveWorld(mapOf("pilot" to pilot),inventory())
        val engine = WorldEngine(this,rules,ticking=false,galaxy=galaxy,
            navigationStore=store.navigationStore,stockStore=store.stockStore)
        try {
            join(engine)
            val result = appoint(engine)
            assertEquals(AppointmentRejection.STORAGE,result.rejection)
            assertEquals(1000,result.player!!.pcp)
            assertEquals(1000,result.player!!.mcp)
            assertEquals(0,result.stocks!!.bases.getValue(1049).garrisonCommanderId)
            assertEquals(1000,store.loadNavigation().getValue("pilot").pcp)
            assertEquals(1000,store.loadNavigation().getValue("pilot").mcp)
            assertEquals(0,store.loadStocks()!!.bases.getValue(1049).garrisonCommanderId)
        } finally { engine.drainAndClose() }
    }
}
