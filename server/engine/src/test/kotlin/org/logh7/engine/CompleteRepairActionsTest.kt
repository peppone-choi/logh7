package org.logh7.engine

import org.junit.jupiter.api.Test
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.test.*

/** 손상·재고·오프라인 부원 기함과 거부의 원자성. 작성: 최병호. */
class CompleteRepairActionsTest {
    private val galaxy=Galaxy(listOf(GalaxySystem(3,2487,4,"Home",2)),listOf(
        GalaxyBase(1049,2487,80,0,Capital.EMPIRE.baseName,0,20f,86400,0,0f,2f)),
        mapOf(Power.EMPIRE to 2487,Power.ALLIANCE to 2487))
    private val session=SessionSimulation(SessionRules.load()).also {it.join("actor",Power.EMPIRE,false,"c")}.exportState()
    private val now=session.gameMillis/1000
    private val player=Navigator(1,10000,Power.EMPIRE,2487,1000,1000,1000,baseId=1049,mode=4,
        supplies=35,hull=FlagshipHull(0,now,hp=70))
    private val offline=player.copy(characterId=10001,hull=FlagshipHull(2,now,hp=45),supplies=21)
    private val remote=player.copy(characterId=10002,grid=2488,baseId=0,mode=5)
    private val outsider=player.copy(characterId=10003)
    private val players=mapOf("actor" to player,"offline" to offline,"remote" to remote,"outsider" to outsider)
    private fun stocks():PlanetStocks {
        val stack=UnitStack(ProductionUnits.definitions.first {it.ship && it.power==2}.kind,2)
        val maximum=UnitSpecifications.ships.single {it.kind==stack.kind}.number
        val ships=ActiveShipUnits(listOf(ActiveShipUnit(1,stack,boats=maximum-7,damaged=5,supplies=99)))
        return PlanetStocks(1,0,mapOf(1049 to PlanetStock(1049,2,0,100,200,300)),
            mapOf(200 to OutfitStock(200,2,OutfitKind.FLEET,1049,300,10000,UnitInventory(ships=ships.quantities()),
                supplies=99,activeShips=ships,staff=FleetStaff(officers=listOf(10001,10002)))),
            mapOf(300 to OutfitWarehouse(300,2,OutfitKind.FLEET,1049,200)),nextShipId=2)
    }
    private fun request()=StrategyActionRequest("actor","c",10000,Power.EMPIRE,1,61,39,5,outfit=200,navigators=players)
    private fun act(r:StrategyActionRequest=request(),s:PlanetStocks=stocks(),p:Navigator=player)=StrategyActions.evaluate(galaxy,r,session,p,s)
    @Test fun repairsSurvivorsAndNearbyOfflineMembersWithoutRecreatingDestroyedBoatsOrSpendingTheirCargo() {
        val original=stocks();val result=act(s=original)
        assertNull(result.rejection);assertEquals(840,result.player?.mcp);assertEquals(100,result.player?.hull?.hp)
        val repaired=assertNotNull(result.stocks).outfits.getValue(200)
        val before=original.outfits.getValue(200).activeShips!!.values.single();val after=repaired.activeShips!!.values.single()
        assertEquals(before.id,after.id);assertEquals(before.stack,after.stack);assertEquals(before.boats,after.boats)
        assertEquals(0,after.damaged);assertEquals(0,after.supplies);assertEquals(0,repaired.supplies)
        assertEquals(setOf("offline"),result.navigators.keys)
        assertEquals(100,result.navigators.getValue("offline").hull?.hp)
        assertEquals(offline.supplies,result.navigators.getValue("offline").supplies)
        assertEquals(player.supplies,result.player?.supplies);assertEquals(original.warehouses,result.stocks.warehouses)
        val nextPlayers=players+result.navigators+("actor" to assertNotNull(result.player))
        val replay=act(request().copy(navigators=nextPlayers),assertNotNull(result.stocks),assertNotNull(result.player))
        assertNull(replay.rejection);assertFalse(replay.changed);assertEquals(result.player,replay.player)
    }
    @Test fun captainRepairsOnlyTheirFlagshipAndConsumesTheirOwnSupplies() {
        val original=stocks();val result=act(request().copy(card=59,outfit=0),original)
        assertNull(result.rejection);assertEquals(100,result.player?.hull?.hp);assertEquals(0,result.player?.supplies)
        assertEquals(840,result.player?.mcp);assertEquals(original,result.stocks);assertTrue(result.navigators.isEmpty())
    }
    @Test fun wrongOfficeBusyShortageAndInsufficientCpPreserveEveryHullAndBalance() {
        val original=stocks()
        val cases=listOf(request().copy(card=44) to StrategyActionRejection.CARD,
            request().copy(outfit=999) to StrategyActionRejection.AUTHORITY,
            request().copy(rank=1) to StrategyActionRejection.RANK)
        for((r,reason) in cases) {
            val denied=act(r,original);assertEquals(reason,denied.rejection);assertEquals(original,denied.stocks)
            assertEquals(player,denied.player);assertTrue(denied.navigators.isEmpty())
        }
        val poor=player.copy(pcp=319,mcp=159)
        val denied=act(s=original,p=poor);assertEquals(StrategyActionRejection.CP,denied.rejection)
        assertEquals(original,denied.stocks);assertEquals(poor,denied.player);assertTrue(denied.navigators.isEmpty())
        val empty=original.copy(outfits=original.outfits.mapValues {(_,o)->o.copy(supplies=0)})
        assertEquals(StrategyActionRejection.RESOURCE,act(s=empty).rejection)
        assertEquals(StrategyActionRejection.BUSY,act(p=player.copy(cpPaused=true)).rejection)
        val fallback=act(p=player.copy(pcp=320,mcp=159));assertNull(fallback.rejection)
        assertEquals(0,fallback.player?.pcp);assertEquals(159,fallback.player?.mcp)
    }
    @Test fun worldActorHonorsWarehouseReservationAndPublishesNoRepairWhenTheMultiPersonCommitFails() = runBlocking {
        var savedPlayers=players
        var savedStocks=stocks().copy(deploymentInitialized=true)
        var fail=false
        val navigation=object:NavigationStore {
            override fun load()=savedPlayers
            override fun save(value:Map<String,Navigator>){savedPlayers=value}
        }
        val inventory=object:PlanetStockStore {
            override fun load()=savedStocks
            override fun save(value:PlanetStocks){savedStocks=value}
        }
        val committer=WorldStateCommitter {nav,stock->
            if(fail)throw IOException("injected repair commit failure")
            savedPlayers=nav;savedStocks=checkNotNull(stock)
        }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val engine=WorldEngine(scope,ticking=false,galaxy=galaxy,navigationStore=navigation,stockStore=inventory,
            worldStateCommitter=committer,npcPolicy=NpcTacticalPolicy(enabled=false))
        try {
            val join=CompletableDeferred<Admission>()
            engine.submit(WorldCommand.Join("actor",Power.EMPIRE,false,join,"c",1));assertEquals(Admission.ACCEPTED,withTimeout(5000){join.await()})
            val nav=CompletableDeferred<NavigationResult>()
            engine.submit(WorldCommand.Navigate(NavigationRequest("actor",10000,Power.EMPIRE,"c",1),nav));withTimeout(5000){nav.await()}
            val prepare=LogisticsRequest("actor","c",10000,Power.EMPIRE,1,200,LogisticsMode.REORGANIZATION,LogisticsPhase.PREPARE)
            val lock=CompletableDeferred<LogisticsResult>();engine.submit(WorldCommand.Logistics(prepare,lock))
            val held=withTimeout(5000){lock.await()};assertNull(held.rejection)
            val before=engine.snapshot()
            val blocked=CompletableDeferred<StrategyActionResult>();engine.submit(WorldCommand.StrategyAction(request(),blocked))
            assertEquals(StrategyActionRejection.BUSY,withTimeout(5000){blocked.await()}.rejection)
            assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            val cancel=CompletableDeferred<LogisticsResult>()
            engine.submit(WorldCommand.Logistics(prepare.copy(phase=LogisticsPhase.CANCEL,reservation=held.reservation),cancel))
            assertNull(withTimeout(5000){cancel.await()}.rejection)
            fail=true
            val repair=CompletableDeferred<StrategyActionResult>();engine.submit(WorldCommand.StrategyAction(request(),repair))
            assertFailsWith<IOException>{withTimeout(5000){repair.await()}}
            assertEquals(EngineHealth.STORAGE_FAILED,engine.health())
            assertEquals(before.navigators,engine.snapshot().navigators);assertEquals(before.planetStocks,engine.snapshot().planetStocks)
            assertEquals(70,savedPlayers.getValue("actor").hull?.hp);assertEquals(45,savedPlayers.getValue("offline").hull?.hp)
            assertEquals(99,savedStocks.outfits.getValue(200).supplies)
        } finally {withTimeout(5000){engine.drainAndClose()};scope.cancel()}
    }
}
